package com.linka.android.core.nearby

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

data class NearbyPeer(
    val endpointId: String,
    val displayName: String
)

data class PendingPairing(
    val endpointId: String,
    val peerName: String,
    val authenticationDigits: String
)

data class NearbyBytesMessage(
    val endpointId: String,
    val bytes: ByteArray
)

class NearbyIncomingStream internal constructor(
    val endpointId: String,
    val input: InputStream,
    private val payload: Payload
) : Closeable {
    override fun close() {
        runCatching { input.close() }
        runCatching { payload.close() }
    }
}

class NearbyOutgoingStream internal constructor(
    private val input: PipedInputStream,
    private val output: PipedOutputStream
) : Closeable {
    @Synchronized
    fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun close() {
        runCatching { output.close() }
        runCatching { input.close() }
    }
}

data class NearbyLinkState(
    val isAdvertising: Boolean = false,
    val isDiscovering: Boolean = false,
    val peers: List<NearbyPeer> = emptyList(),
    val pendingPairings: List<PendingPairing> = emptyList(),
    val connectedPeer: NearbyPeer? = null,
    val connectedPeers: List<NearbyPeer> = emptyList(),
    val status: String = "Nearby calling is stopped",
    val error: String? = null
)

/**
 * Direct peer discovery, consent pairing, control messages and full-duplex
 * byte-stream transport over Nearby Connections.
 *
 * Google Nearby Connections protects each direct radio link. LINKA's call
 * controller layers QR-trusted identity authentication, directional
 * application encryption and ciphertext-only mesh routing over this transport.
 * This adapter is transport-only; it does not implement those security rules.
 */
class NearbyDirectLink(
    context: Context,
    private val localDeviceName: String
) {
    private val client = Nearby.getConnectionsClient(context.applicationContext)
    private val _state = MutableStateFlow(NearbyLinkState())
    val state: StateFlow<NearbyLinkState> = _state.asStateFlow()

    private val _incomingBytes = MutableSharedFlow<NearbyBytesMessage>(
        extraBufferCapacity = 64
    )
    val incomingBytes: SharedFlow<NearbyBytesMessage> = _incomingBytes.asSharedFlow()

    private val _incomingStreams = MutableSharedFlow<NearbyIncomingStream>(
        extraBufferCapacity = 4
    )
    val incomingStreams: SharedFlow<NearbyIncomingStream> = _incomingStreams.asSharedFlow()

    // Cluster topology is required to keep multiple nearby relay neighbours connected.
    // Direct calls still select one peer for their high-level call session.
    private val strategy = Strategy.P2P_CLUSTER
    private val serviceId = "com.linka.android"
    private val maxControlPayloadBytes = 1024
    private val streamPipeBufferBytes = 64 * 1024
    @Volatile private var advertisingStartPending = false
    @Volatile private var discoveryStartPending = false

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val peer = NearbyPeer(endpointId, info.endpointName.ifBlank { "Nearby LINKA device" })
            _state.update { current ->
                current.copy(
                    peers = (current.peers.filterNot { it.endpointId == endpointId } + peer)
                        .sortedBy { it.displayName.lowercase() },
                    status = "Nearby LINKA devices found"
                )
            }
        }

        override fun onEndpointLost(endpointId: String) {
            _state.update { current ->
                current.copy(peers = current.peers.filterNot { it.endpointId == endpointId })
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    if (bytes.isNotEmpty() && bytes.size <= maxControlPayloadBytes) {
                        _incomingBytes.tryEmit(NearbyBytesMessage(endpointId, bytes.copyOf()))
                    }
                }

                Payload.Type.STREAM -> {
                    val stream = payload.asStream()
                    if (stream == null) {
                        payload.close()
                        return
                    }
                    val incoming = NearbyIncomingStream(
                        endpointId = endpointId,
                        input = stream.asInputStream(),
                        payload = payload
                    )
                    if (!_incomingStreams.tryEmit(incoming)) incoming.close()
                }

                else -> payload.close()
            }
        }

        override fun onPayloadTransferUpdate(
            endpointId: String,
            update: PayloadTransferUpdate
        ) = Unit
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val pending = PendingPairing(
                endpointId = endpointId,
                peerName = info.endpointName.ifBlank { "Nearby LINKA device" },
                authenticationDigits = info.authenticationDigits
            )
            _state.update { current ->
                val requests = current.pendingPairings
                    .filterNot { it.endpointId == endpointId } + pending
                current.copy(
                    pendingPairings = requests,
                    status = if (requests.size == 1) {
                        "Confirm the same security code on both phones"
                    } else {
                        "Review ${requests.size} nearby connection requests"
                    },
                    error = null
                )
            }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            when (resolution.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    val pending = _state.value.pendingPairings.firstOrNull { it.endpointId == endpointId }
                    val peer = _state.value.peers.firstOrNull { it.endpointId == endpointId }
                        ?: NearbyPeer(endpointId, pending?.peerName ?: "LINKA device")
                    _state.update { current ->
                        val connected = current.connectedPeers
                            .filterNot { it.endpointId == endpointId } + peer
                        current.copy(
                            pendingPairings = current.pendingPairings.filterNot { it.endpointId == endpointId },
                            connectedPeers = connected,
                            connectedPeer = current.connectedPeer ?: peer,
                            status = "Nearby peer connected (${connected.size} link(s))",
                            error = null
                        )
                    }
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    _state.update { current ->
                        current.copy(
                            pendingPairings = current.pendingPairings.filterNot { it.endpointId == endpointId },
                            status = "Connection was rejected"
                        )
                    }
                }

                else -> {
                    _state.update { current ->
                        current.copy(
                            pendingPairings = current.pendingPairings.filterNot { it.endpointId == endpointId },
                            status = "Nearby connection failed",
                            error = "Connection status code: ${resolution.status.statusCode}"
                        )
                    }
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            _state.update { current ->
                val remaining = current.connectedPeers.filterNot { it.endpointId == endpointId }
                val activeWasDisconnected = current.connectedPeer?.endpointId == endpointId
                current.copy(
                    connectedPeers = remaining,
                    connectedPeer = if (activeWasDisconnected) null else current.connectedPeer,
                    status = if (activeWasDisconnected) {
                        "Selected nearby device disconnected"
                    } else {
                        "Nearby peer disconnected (${remaining.size} link(s) remain)"
                    }
                )
            }
        }
    }

    /**
     * Activates this phone as a full cluster participant: it is both discoverable
     * by nearby LINKA phones and actively discovers neighbours for relay paths.
     */
    fun startNetwork() {
        startAdvertising()
        startDiscovery()
    }

    @Synchronized
    fun startAdvertising() {
        if (_state.value.isAdvertising || advertisingStartPending) return
        advertisingStartPending = true
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        client.startAdvertising(
            localDeviceName,
            serviceId,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            advertisingStartPending = false
            _state.update { current ->
                current.copy(
                    isAdvertising = true,
                    status = if (current.isDiscovering) {
                        "LINKA network active: discoverable and searching for nearby phones"
                    } else {
                        "This phone is discoverable nearby"
                    },
                    error = null
                )
            }
        }.addOnFailureListener { exception ->
            advertisingStartPending = false
            _state.update {
                it.copy(
                    isAdvertising = false,
                    status = "Could not make this phone discoverable",
                    error = exception.localizedMessage ?: "Nearby advertising failed"
                )
            }
        }
    }

    @Synchronized
    fun startDiscovery() {
        if (_state.value.isDiscovering || discoveryStartPending) return
        discoveryStartPending = true
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        client.startDiscovery(
            serviceId,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            discoveryStartPending = false
            _state.update { current ->
                current.copy(
                    isDiscovering = true,
                    peers = emptyList(),
                    status = if (current.isAdvertising) {
                        "LINKA network active: discoverable and searching for nearby phones"
                    } else {
                        "Searching for LINKA phones nearby"
                    },
                    error = null
                )
            }
        }.addOnFailureListener { exception ->
            discoveryStartPending = false
            _state.update {
                it.copy(
                    isDiscovering = false,
                    status = "Could not search for nearby phones",
                    error = exception.localizedMessage ?: "Nearby discovery failed"
                )
            }
        }
    }

    fun requestConnection(peer: NearbyPeer) {
        client.requestConnection(localDeviceName, peer.endpointId, connectionLifecycleCallback)
            .addOnSuccessListener {
                _state.update { it.copy(status = "Connection requested; waiting for security confirmation", error = null) }
            }
            .addOnFailureListener { exception ->
                _state.update {
                    it.copy(
                        status = "Could not request connection",
                        error = exception.localizedMessage ?: "Connection request failed"
                    )
                }
            }
    }

    fun acceptPairing(endpointId: String) {
        val pending = _state.value.pendingPairings.firstOrNull { it.endpointId == endpointId } ?: return
        client.acceptConnection(pending.endpointId, payloadCallback)
            .addOnFailureListener { exception ->
                _state.update {
                    it.copy(error = exception.localizedMessage ?: "Could not accept connection")
                }
            }
    }

    fun rejectPairing(endpointId: String) {
        val pending = _state.value.pendingPairings.firstOrNull { it.endpointId == endpointId } ?: return
        client.rejectConnection(pending.endpointId)
        _state.update { current ->
            current.copy(
                pendingPairings = current.pendingPairings.filterNot { it.endpointId == endpointId },
                status = "Pairing rejected"
            )
        }
    }

    fun sendBytes(endpointId: String, bytes: ByteArray, onResult: (String?) -> Unit = {}) {
        if (_state.value.connectedPeers.none { it.endpointId == endpointId } &&
            _state.value.connectedPeer?.endpointId != endpointId
        ) {
            onResult("The requested LINKA peer is not connected")
            return
        }
        if (bytes.isEmpty() || bytes.size > maxControlPayloadBytes) {
            onResult("Invalid LINKA control payload size")
            return
        }
        client.sendPayload(endpointId, Payload.fromBytes(bytes.copyOf()))
            .addOnSuccessListener { onResult(null) }
            .addOnFailureListener { exception ->
                onResult(exception.localizedMessage ?: "Nearby payload send failed")
            }
    }

    /**
     * Opens one continuous outbound stream to the active peer. The returned
     * writer must be used from a worker thread and closed when the call ends.
     */
    fun openOutgoingStream(
        endpointId: String,
        onReady: (NearbyOutgoingStream?, String?) -> Unit
    ) {
        if (_state.value.connectedPeers.none { it.endpointId == endpointId } &&
            _state.value.connectedPeer?.endpointId != endpointId
        ) {
            onReady(null, "The requested LINKA peer is not connected")
            return
        }

        try {
            val pipeInput = PipedInputStream(streamPipeBufferBytes)
            val pipeOutput = PipedOutputStream(pipeInput)
            val writer = NearbyOutgoingStream(pipeInput, pipeOutput)
            client.sendPayload(endpointId, Payload.fromStream(pipeInput))
                .addOnSuccessListener { onReady(writer, null) }
                .addOnFailureListener { exception ->
                    writer.close()
                    onReady(null, exception.localizedMessage ?: "Could not open nearby stream")
                }
        } catch (exception: Exception) {
            onReady(null, exception.localizedMessage ?: "Could not create nearby stream")
        }
    }

    /** Selects one already-connected neighbour for a direct voice call. */
    fun selectPeer(endpointId: String) {
        val peer = _state.value.connectedPeers.firstOrNull { it.endpointId == endpointId }
            ?: return
        _state.update {
            it.copy(connectedPeer = peer, status = "Selected ${peer.displayName} for direct calling")
        }
    }

    /** Disconnects one neighbour without dropping the other mesh-capable links. */
    fun disconnectPeer(endpointId: String) {
        client.disconnectFromEndpoint(endpointId)
        _state.update { current ->
            val remaining = current.connectedPeers.filterNot { it.endpointId == endpointId }
            val activeWasDisconnected = current.connectedPeer?.endpointId == endpointId
            current.copy(
                connectedPeers = remaining,
                connectedPeer = if (activeWasDisconnected) null else current.connectedPeer,
                pendingPairings = current.pendingPairings.filterNot { it.endpointId == endpointId },
                status = "Disconnected from one nearby device (${remaining.size} link(s) remain)"
            )
        }
    }

    fun disconnect() {
        val endpointId = _state.value.connectedPeer?.endpointId ?: return
        disconnectPeer(endpointId)
    }

    fun stopDiscoveryAndAdvertising() {
        discoveryStartPending = false
        advertisingStartPending = false
        client.stopDiscovery()
        client.stopAdvertising()
        _state.update {
            it.copy(
                isDiscovering = false,
                isAdvertising = false,
                peers = emptyList(),
                status = "Nearby calling is stopped"
            )
        }
    }

    fun close() {
        client.stopAllEndpoints()
        stopDiscoveryAndAdvertising()
    }
}
