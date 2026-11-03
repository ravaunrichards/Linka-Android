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

data class NearbyLinkState(
    val isAdvertising: Boolean = false,
    val isDiscovering: Boolean = false,
    val peers: List<NearbyPeer> = emptyList(),
    val pendingPairing: PendingPairing? = null,
    val connectedPeer: NearbyPeer? = null,
    val status: String = "Nearby calling is stopped",
    val error: String? = null
)

/**
 * Direct peer discovery and consent pairing over Nearby Connections.
 *
 * The SDK supplies an encrypted direct transport. LINKA still has to bind
 * trusted device identities to this endpoint and implement its own call
 * lifecycle and media protections before public release.
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

    private val strategy = Strategy.P2P_POINT_TO_POINT
    private val serviceId = "com.linka.android"
    private val maxControlPayloadBytes = 1024

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
            if (payload.type != Payload.Type.BYTES) {
                payload.close()
                return
            }
            val bytes = payload.asBytes() ?: return
            if (bytes.isEmpty() || bytes.size > maxControlPayloadBytes) return
            _incomingBytes.tryEmit(NearbyBytesMessage(endpointId, bytes.copyOf()))
        }

        override fun onPayloadTransferUpdate(
            endpointId: String,
            update: PayloadTransferUpdate
        ) = Unit
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val peerName = info.endpointName.ifBlank { "Nearby LINKA device" }
            _state.update {
                it.copy(
                    pendingPairing = PendingPairing(
                        endpointId = endpointId,
                        peerName = peerName,
                        authenticationDigits = info.authenticationDigits
                    ),
                    status = "Confirm the same security code on both phones",
                    error = null
                )
            }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            when (resolution.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    val pending = _state.value.pendingPairing
                    val peer = _state.value.peers.firstOrNull { it.endpointId == endpointId }
                        ?: NearbyPeer(endpointId, pending?.peerName ?: "LINKA device")
                    _state.update {
                        it.copy(
                            pendingPairing = null,
                            connectedPeer = peer,
                            status = "Secure nearby connection established",
                            error = null
                        )
                    }
                    client.stopAdvertising()
                    client.stopDiscovery()
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    _state.update {
                        it.copy(
                            pendingPairing = null,
                            status = "Connection was rejected"
                        )
                    }
                }

                else -> {
                    _state.update {
                        it.copy(
                            pendingPairing = null,
                            status = "Nearby connection failed",
                            error = "Connection status code: ${resolution.status.statusCode}"
                        )
                    }
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            _state.update { current ->
                if (current.connectedPeer?.endpointId == endpointId) {
                    current.copy(
                        connectedPeer = null,
                        status = "Nearby device disconnected"
                    )
                } else {
                    current
                }
            }
        }
    }

    fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        client.startAdvertising(
            localDeviceName,
            serviceId,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            _state.update {
                it.copy(isAdvertising = true, status = "This phone is discoverable nearby", error = null)
            }
        }.addOnFailureListener { exception ->
            _state.update {
                it.copy(
                    isAdvertising = false,
                    status = "Could not make this phone discoverable",
                    error = exception.localizedMessage ?: "Nearby advertising failed"
                )
            }
        }
    }

    fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        client.startDiscovery(
            serviceId,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            _state.update {
                it.copy(isDiscovering = true, peers = emptyList(), status = "Searching for LINKA phones nearby", error = null)
            }
        }.addOnFailureListener { exception ->
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

    fun acceptPairing() {
        val pending = _state.value.pendingPairing ?: return
        client.acceptConnection(pending.endpointId, payloadCallback)
            .addOnFailureListener { exception ->
                _state.update {
                    it.copy(error = exception.localizedMessage ?: "Could not accept connection")
                }
            }
    }

    fun rejectPairing() {
        val pending = _state.value.pendingPairing ?: return
        client.rejectConnection(pending.endpointId)
        _state.update {
            it.copy(
                pendingPairing = null,
                status = "Pairing rejected"
            )
        }
    }

    /**
     * Submits a bounded binary message to the already authenticated Nearby
     * endpoint. Success means the SDK accepted the payload for delivery; it
     * does not claim that the remote app processed it.
     */
    fun sendBytes(endpointId: String, bytes: ByteArray, onResult: (String?) -> Unit = {}) {
        if (_state.value.connectedPeer?.endpointId != endpointId) {
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

    fun disconnect() {
        val endpointId = _state.value.connectedPeer?.endpointId ?: return
        client.disconnectFromEndpoint(endpointId)
        _state.update {
            it.copy(
                connectedPeer = null,
                pendingPairing = null,
                status = "Disconnected"
            )
        }
    }

    fun stopDiscoveryAndAdvertising() {
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
