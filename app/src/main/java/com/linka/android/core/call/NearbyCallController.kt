package com.linka.android.core.call

import android.content.Context
import com.linka.android.core.audio.LinkaVoiceForegroundService
import com.linka.android.core.audio.NearbyVoiceSession
import com.linka.android.core.audio.RoutedVoiceSession
import com.linka.android.core.audio.VoiceSessionState
import com.linka.android.core.nearby.NearbyDirectLink
import com.linka.android.core.identity.LocalDeviceIdentity
import com.linka.android.core.identity.TrustedDeviceStore
import com.linka.android.core.mesh.MeshPacketCodec
import com.linka.android.core.mesh.MeshPacketRouter
import com.linka.android.core.mesh.RoutedPayloadCodec
import com.linka.android.core.mesh.RoutedPayloadType
import com.linka.android.core.mesh.MeshRouterAction
import com.linka.android.core.mesh.RelayConsentStore
import com.linka.android.core.security.PeerSessionSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class NearbyCallUiState(
    val call: CallSnapshot = CallSnapshot(),
    val status: String = "No active call",
    val error: String? = null
)

/**
 * Coordinates call control and a two-way nearby voice session.
 *
 * The microphone foreground service is started from explicit, visible user
 * actions (place or accept) before an ongoing call can be backgrounded.
 */
class NearbyCallController(
    context: Context,
    private val localIdentity: LocalDeviceIdentity,
    private val trustedDevices: TrustedDeviceStore,
    private val relayConsent: RelayConsentStore,
    private val nearbyLink: NearbyDirectLink
) {
    private val localDeviceId = localIdentity.deviceId
    private val appContext = context.applicationContext
    private val machine = CallStateMachine()
    private val peerSecurity = PeerSessionSecurity(localIdentity, trustedDevices) { relayConsent.isEnabled() }
    private val routedPeerSecurity = PeerSessionSecurity(localIdentity, trustedDevices) { relayConsent.isEnabled() }
    private val meshRouter = MeshPacketRouter(
        localDeviceId = localDeviceId,
        relayEnabled = { relayConsent.isEnabled() },
        signOrigin = { bytes -> localIdentity.sign(bytes) },
        verifyOrigin = { sourceDeviceId, body, signature ->
            trustedDevices.get(sourceDeviceId)?.let { trusted ->
                LocalDeviceIdentity.verify(trusted.publicKeyBase64, body, signature)
            } ?: false
        }
    )
    private val trackedEndpoints = mutableSetOf<String>()
    private val voiceSession = NearbyVoiceSession(appContext, nearbyLink)
    private val routedVoiceSession = RoutedVoiceSession(appContext)
    private var routedIncomingVoiceChannel: Channel<ByteArray>? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _voiceState = MutableStateFlow(VoiceSessionState())
    val voiceState: StateFlow<VoiceSessionState> = _voiceState.asStateFlow()
    private val _state = MutableStateFlow(NearbyCallUiState())
    val state: StateFlow<NearbyCallUiState> = _state.asStateFlow()

    init {
        require(localDeviceId.isNotBlank() && localDeviceId.length <= 64)
        scope.launch {
            combine(voiceSession.state, routedVoiceSession.state) { direct, routed ->
                when {
                    routed.isStarting || routed.isRunning || routed.error != null -> routed
                    else -> direct
                }
            }.collect { _voiceState.value = it }
        }
        scope.launch {
            nearbyLink.incomingBytes.collect { incoming ->
                if (PeerSessionSecurity.isHandshakePacket(incoming.bytes)) {
                    val result = peerSecurity.handle(incoming.endpointId, incoming.bytes)
                    result.responses.forEach { response ->
                        nearbyLink.sendBytes(incoming.endpointId, response) { error ->
                            if (error != null) publishError(error)
                        }
                    }
                    if (result.error != null) {
                        publishError(result.error)
                    } else if (result.isAuthenticated) {
                        val authenticatedPeer = peerSecurity.session(incoming.endpointId)
                        if (authenticatedPeer != null) {
                            meshRouter.registerNeighbor(
                                endpointId = incoming.endpointId,
                                deviceId = authenticatedPeer.remoteDeviceId,
                                relayOptIn = authenticatedPeer.remoteRelayOptIn
                            )
                        }
                        _state.value = _state.value.copy(
                            status = if (authenticatedPeer?.remoteRelayOptIn == true) {
                                "Secure identity verified; peer permits relay participation"
                            } else {
                                "Secure identity verified; peer is not a relay"
                            },
                            error = null
                        )
                    }
                    return@collect
                }

                val session = peerSecurity.session(incoming.endpointId)
                if (session == null) {
                    publishError("Ignored nearby data before trusted identity verification")
                    return@collect
                }
                val plaintext = try {
                    session.decryptControl(incoming.bytes)
                } catch (error: Exception) {
                    publishError(error.localizedMessage ?: "Secure nearby packet authentication failed")
                    return@collect
                }

                if (MeshPacketCodec.isMeshPacket(plaintext)) {
                    dispatchMeshActions(meshRouter.receive(plaintext, incoming.endpointId))
                    return@collect
                }

                if (incoming.endpointId != nearbyLink.state.value.connectedPeer?.endpointId) {
                    return@collect
                }
                handleIncoming(plaintext, incoming.endpointId, session.remoteDeviceId)
            }
        }
        scope.launch {
            nearbyLink.state.collect { linkState ->
                val connectedEndpoints = (
                    linkState.connectedPeers.map { it.endpointId } +
                        listOfNotNull(linkState.connectedPeer?.endpointId)
                    ).toSet()

                val disconnected = trackedEndpoints - connectedEndpoints
                disconnected.forEach { endpoint ->
                    peerSecurity.remove(endpoint)
                    meshRouter.unregisterNeighbor(endpoint)
                    trackedEndpoints.remove(endpoint)
                }

                connectedEndpoints.forEach { endpoint ->
                    if (trackedEndpoints.add(endpoint)) {
                        val hello = runCatching { peerSecurity.begin(endpoint) }.getOrNull()
                        if (hello != null) {
                            nearbyLink.sendBytes(endpoint, hello) { error ->
                                if (error != null) publishError(error)
                            }
                            _state.value = _state.value.copy(
                                status = "Verifying trusted LINKA identity",
                                error = null
                            )
                        } else if (peerSecurity.session(endpoint) == null) {
                            publishError("Could not start secure identity verification")
                        }
                    }
                }

                val current = machine.snapshot
                if (current.phase in setOf(
                        CallPhase.OUTGOING_RINGING,
                        CallPhase.INCOMING_RINGING,
                        CallPhase.ACTIVE
                    )
                ) {
                    val peer = current.peerDeviceId.orEmpty()
                    val isRouted = peer.startsWith(ROUTE_ENDPOINT_PREFIX)
                    val routeLost = isRouted &&
                        meshRouter.routeTo(peer.removePrefix(ROUTE_ENDPOINT_PREFIX)) == null
                    val directLost = !isRouted &&
                        linkState.connectedPeers.none { it.endpointId == peer } &&
                        linkState.connectedPeer?.endpointId != peer
                    if (routeLost || directLost) {
                        stopVoice("Connection route lost")
                        LinkaVoiceForegroundService.stop(appContext)
                        _state.value = NearbyCallUiState(
                            call = machine.connectionLost(),
                            status = "Connection route lost",
                            error = "LINKA could not keep a connected path to the other phone"
                        )
                    }
                }
            }
        }
    }

    fun startOutgoingCall() {
        val peer = nearbyLink.state.value.connectedPeer
            ?: return publishError("Connect to a nearby LINKA phone first")
        if (peerSecurity.session(peer.endpointId) == null) {
            return publishError("Secure pairing is not complete. Scan this phone's LINKA identity QR on the other phone and retry after verification.")
        }
        val next = try {
            machine.beginOutgoing(UUID.randomUUID().toString(), peer.endpointId)
        } catch (error: IllegalStateException) {
            return publishError(error.message ?: "A call is already in progress")
        } catch (error: IllegalArgumentException) {
            return publishError(error.message ?: "Invalid call details")
        }

        val securePeer = peerSecurity.session(peer.endpointId) ?: run {
            publishError("Secure peer identity verification was lost")
            return
        }

        try {
            LinkaVoiceForegroundService.start(appContext)
        } catch (error: Exception) {
            machine.end("foreground_service_unavailable")
            return publishError(
                error.localizedMessage ?: "Android could not start the call foreground service"
            )
        }

        _state.value = NearbyCallUiState(next, "Calling ${peer.displayName}…")
        send(CallControlType.INVITE, next.callId!!, peer.endpointId)
        scope.launch {
            delay(CALL_RING_TIMEOUT_MS)
            if (machine.snapshot.callId == next.callId &&
                machine.snapshot.phase == CallPhase.OUTGOING_RINGING
            ) {
                val timedOut = machine.end("no_answer")
                _state.value = NearbyCallUiState(timedOut, "No answer")
                send(CallControlType.END, timedOut.callId!!, timedOut.peerDeviceId!!)
                stopVoice("No answer")
                LinkaVoiceForegroundService.stop(appContext)
            }
        }
    }

    fun acceptIncomingCall() {
        val call = machine.snapshot
        if (call.phase != CallPhase.INCOMING_RINGING) {
            publishError("There is no incoming call to accept")
            return
        }

        val endpoint = call.peerDeviceId ?: run {
            publishError("Incoming call has no verified peer connection")
            return
        }
        val securePeer = securePeer(endpoint) ?: run {
            publishError("Secure peer identity verification was lost")
            return
        }
        try {
            LinkaVoiceForegroundService.start(appContext)
        } catch (error: Exception) {
            publishError(
                error.localizedMessage ?: "Android could not start the call foreground service"
            )
            return
        }

        val mediaCipher = try {
            securePeer.forCall(call.callId!!)
        } catch (error: Exception) {
            LinkaVoiceForegroundService.stop(appContext)
            val ended = machine.rejectOrDecline("secure_media_key_unavailable")
            _state.value = NearbyCallUiState(ended, "Could not establish secure voice keys")
            send(CallControlType.REJECT, ended.callId!!, ended.peerDeviceId!!)
            publishError(error.localizedMessage ?: "Could not create unique voice encryption keys")
            return
        }

        val active = try {
            machine.acceptIncoming(System.currentTimeMillis())
        } catch (error: IllegalStateException) {
            mediaCipher.destroy()
            LinkaVoiceForegroundService.stop(appContext)
            publishError(error.message ?: "Unable to accept call")
            return
        }
        _state.value = NearbyCallUiState(active, "Secure call accepted; starting encrypted voice")
        send(CallControlType.ACCEPT, active.callId!!, active.peerDeviceId!!)
        startVoiceForPeer(active.peerDeviceId!!, active.callId!!, mediaCipher)
    }

    fun rejectIncomingCall() {
        val call = machine.snapshot
        if (call.phase != CallPhase.INCOMING_RINGING) return
        val ended = machine.rejectOrDecline("rejected")
        _state.value = NearbyCallUiState(ended, "Incoming call rejected")
        send(CallControlType.REJECT, ended.callId!!, ended.peerDeviceId!!)
    }

    fun endCall() {
        val call = machine.snapshot
        if (call.phase !in setOf(CallPhase.OUTGOING_RINGING, CallPhase.INCOMING_RINGING, CallPhase.ACTIVE)) return
        val ended = machine.end("local_hangup")
        stopVoice("Call ended")
        LinkaVoiceForegroundService.stop(appContext)
        _state.value = NearbyCallUiState(ended, "Call ended")
        send(CallControlType.END, ended.callId!!, ended.peerDeviceId!!)
    }

    fun isRoutedPeerAuthenticated(destinationDeviceId: String): Boolean =
        meshRouter.routeTo(destinationDeviceId) != null &&
            routedPeerSecurity.session(routeEndpoint(destinationDeviceId)) != null

    fun startRoutedCall(destinationDeviceId: String) {
        val endpoint = routeEndpoint(destinationDeviceId)
        if (meshRouter.routeTo(destinationDeviceId) == null) {
            publishError("The offline route has expired. Discover a fresh route first.")
            return
        }
        if (routedPeerSecurity.session(endpoint) == null) {
            publishError("The offline route is not securely paired yet")
            return
        }
        val next = try {
            machine.beginOutgoing(UUID.randomUUID().toString(), endpoint)
        } catch (error: IllegalStateException) {
            return publishError(error.message ?: "A call is already in progress")
        } catch (error: IllegalArgumentException) {
            return publishError(error.message ?: "Invalid routed call details")
        }

        try {
            LinkaVoiceForegroundService.start(appContext)
        } catch (error: Exception) {
            machine.end("foreground_service_unavailable")
            return publishError(
                error.localizedMessage ?: "Android could not start the call foreground service"
            )
        }

        val displayName = trustedDevices.get(destinationDeviceId)?.displayName ?: "LINKA destination"
        _state.value = NearbyCallUiState(next, "Calling $displayName over the encrypted offline route…")
        send(CallControlType.INVITE, next.callId!!, endpoint)
        scope.launch {
            delay(CALL_RING_TIMEOUT_MS)
            if (machine.snapshot.callId == next.callId &&
                machine.snapshot.phase == CallPhase.OUTGOING_RINGING
            ) {
                val timedOut = machine.end("no_answer")
                _state.value = NearbyCallUiState(timedOut, "No answer")
                send(CallControlType.END, timedOut.callId!!, timedOut.peerDeviceId!!)
                stopVoice("No answer")
                LinkaVoiceForegroundService.stop(appContext)
            }
        }
    }

    private fun routeEndpoint(deviceId: String): String = "route:$deviceId"

    private fun securePeer(endpointId: String) =
        if (endpointId.startsWith(ROUTE_ENDPOINT_PREFIX)) {
            routedPeerSecurity.session(endpointId)
        } else {
            peerSecurity.session(endpointId)
        }

    private fun beginRoutedPeerHandshake(destinationDeviceId: String) {
        val endpoint = routeEndpoint(destinationDeviceId)
        if (routedPeerSecurity.session(endpoint) != null) return
        val hello = runCatching { routedPeerSecurity.begin(endpoint) }.getOrNull()
        if (hello != null) {
            sendRoutedPayload(destinationDeviceId, RoutedPayloadType.PEER_HANDSHAKE, hello)
        }
    }

    private fun sendRoutedPayload(
        destinationDeviceId: String,
        type: RoutedPayloadType,
        payload: ByteArray
    ) {
        val wrapped = try {
            RoutedPayloadCodec.encode(type, payload)
        } catch (error: Exception) {
            publishError(error.localizedMessage ?: "Routed payload is invalid")
            return
        }
        dispatchMeshActions(meshRouter.sendOpaqueData(destinationDeviceId, wrapped))
    }

    private fun handleRoutedPayload(sourceDeviceId: String, encoded: ByteArray) {
        val payload = try {
            RoutedPayloadCodec.decode(encoded)
        } catch (error: Exception) {
            publishError(error.localizedMessage ?: "Received an invalid routed payload")
            return
        }
        val endpoint = routeEndpoint(sourceDeviceId)
        when (payload.type) {
            RoutedPayloadType.PEER_HANDSHAKE -> {
                val result = routedPeerSecurity.handle(endpoint, payload.bytes)
                result.responses.forEach { response ->
                    sendRoutedPayload(sourceDeviceId, RoutedPayloadType.PEER_HANDSHAKE, response)
                }
                if (result.error != null) {
                    publishError(result.error)
                } else if (result.isAuthenticated) {
                    _state.value = _state.value.copy(
                        status = "Secure end-to-end identity verified for offline route",
                        error = null
                    )
                }
            }

            RoutedPayloadType.CALL_CONTROL -> {
                val session = routedPeerSecurity.session(endpoint) ?: run {
                    publishError("Ignored routed call control before end-to-end identity verification")
                    return
                }
                val plaintext = try {
                    session.decryptControl(payload.bytes)
                } catch (error: Exception) {
                    publishError(error.localizedMessage ?: "Routed call-control authentication failed")
                    return
                }
                handleIncoming(plaintext, endpoint, session.remoteDeviceId)
            }

            RoutedPayloadType.VOICE_FRAME -> {
                val current = machine.snapshot
                if (current.phase != CallPhase.ACTIVE || current.peerDeviceId != endpoint) return
                val channel = routedIncomingVoiceChannel ?: return
                if (channel.trySend(payload.bytes).isFailure) {
                    _state.value = _state.value.copy(
                        status = "Mesh voice queue is full; network congestion is affecting audio",
                        error = "Routed voice frame was not queued"
                    )
                }
            }
        }
    }

    private fun startVoiceForPeer(
        endpointId: String,
        callId: String,
        mediaCipher: com.linka.android.core.security.SecureCallCipher
    ) {
        if (endpointId.startsWith(ROUTE_ENDPOINT_PREFIX)) {
            val destinationDeviceId = endpointId.removePrefix(ROUTE_ENDPOINT_PREFIX)
            routedIncomingVoiceChannel?.close()
            val channel = Channel<ByteArray>(capacity = ROUTED_VOICE_QUEUE_CAPACITY)
            routedIncomingVoiceChannel = channel
            routedVoiceSession.start(
                secureCipher = mediaCipher,
                sendEncryptedFrame = { encrypted ->
                    sendRoutedPayload(destinationDeviceId, RoutedPayloadType.VOICE_FRAME, encrypted)
                },
                incomingEncryptedFrames = channel.receiveAsFlow()
            )
        } else {
            voiceSession.start(endpointId, callId, mediaCipher)
        }
    }

    private fun stopVoice(reason: String) {
        voiceSession.stop(reason)
        routedVoiceSession.stop(reason)
        routedIncomingVoiceChannel?.close()
        routedIncomingVoiceChannel = null
    }

    fun discoverMeshRoute(destinationDeviceId: String) {
        if (trustedDevices.get(destinationDeviceId) == null) {
            publishError("Trust the destination LINKA identity before discovering a route")
            return
        }
        dispatchMeshActions(meshRouter.startRouteDiscovery(destinationDeviceId))
    }

    private fun dispatchMeshActions(actions: List<MeshRouterAction>) {
        actions.forEach { action ->
            when (action) {
                is MeshRouterAction.SendTo -> {
                    val nextHop = peerSecurity.session(action.endpointId)
                    if (nextHop == null) {
                        _state.value = _state.value.copy(
                            status = "Mesh route is waiting for an authenticated next hop",
                            error = null
                        )
                        return@forEach
                    }
                    val encrypted = try {
                        nextHop.encryptControl(action.packet)
                    } catch (error: Exception) {
                        publishError(error.localizedMessage ?: "Could not protect mesh packet for next hop")
                        return@forEach
                    }
                    nearbyLink.sendBytes(action.endpointId, encrypted) { error ->
                        if (error != null) publishError(error)
                    }
                }

                is MeshRouterAction.RouteEstablished -> {
                    _state.value = _state.value.copy(
                        status = "Offline route discovered to ${action.route.destinationDeviceId.take(8)} via ${action.route.hopCount} hop(s); securing recipient identity",
                        error = null
                    )
                    beginRoutedPeerHandshake(action.route.destinationDeviceId)
                }

                is MeshRouterAction.Dropped -> {
                    _state.value = _state.value.copy(
                        status = "Mesh packet not forwarded: ${action.reason}",
                        error = null
                    )
                }

                is MeshRouterAction.DeliverOpaqueData -> handleRoutedPayload(
                    action.sourceDeviceId,
                    action.payload
                )
            }
        }
    }

    fun refreshSecurePairings() {
        val current = machine.snapshot
        if (current.phase in setOf(CallPhase.OUTGOING_RINGING, CallPhase.INCOMING_RINGING, CallPhase.ACTIVE)) {
            endCall()
        }
        routedPeerSecurity.close()
        meshRouter.clearRoutesAndPendingDiscovery()
        val endpoints = (
            nearbyLink.state.value.connectedPeers.map { it.endpointId } +
                listOfNotNull(nearbyLink.state.value.connectedPeer?.endpointId)
            ).toSet()
        endpoints.forEach { endpoint ->
            peerSecurity.remove(endpoint)
            meshRouter.unregisterNeighbor(endpoint)
            trackedEndpoints.add(endpoint)
            val hello = runCatching { peerSecurity.begin(endpoint) }.getOrNull()
            if (hello != null) {
                nearbyLink.sendBytes(endpoint, hello) { error ->
                    if (error != null) publishError(error)
                }
            }
        }
        _state.value = _state.value.copy(
            status = "Rechecking trusted LINKA devices",
            error = null
        )
    }

    fun reset() {
        try {
            machine.reset()
            _state.value = NearbyCallUiState()
        } catch (error: IllegalStateException) {
            publishError(error.message ?: "Call cannot be reset")
        }
    }

    private fun handleIncoming(bytes: ByteArray, endpointId: String, verifiedSenderId: String) {
        val message = try {
            CallControlCodec.decode(bytes)
        } catch (error: IllegalArgumentException) {
            publishError("Ignored malformed call-control message")
            return
        }

        if (message.senderDeviceId != verifiedSenderId) {
            publishError("Call-control sender does not match the QR-trusted LINKA identity")
            return
        }

        val call = machine.snapshot
        when (message.type) {
            CallControlType.INVITE -> {
                if (call.phase !in setOf(CallPhase.IDLE, CallPhase.ENDED, CallPhase.FAILED)) {
                    if (message.callId != call.callId) {
                        send(CallControlType.BUSY, message.callId, endpointId)
                    }
                    return
                }
                val incoming = machine.receiveInvite(message.callId, endpointId)
                val name = if (endpointId.startsWith(ROUTE_ENDPOINT_PREFIX)) {
                    trustedDevices.get(verifiedSenderId)?.displayName ?: "Trusted offline LINKA device"
                } else {
                    nearbyLink.state.value.connectedPeer?.displayName ?: "Nearby LINKA phone"
                }
                _state.value = NearbyCallUiState(incoming, "Incoming call from $name")
                scope.launch {
                    delay(CALL_RING_TIMEOUT_MS)
                    if (machine.snapshot.callId == incoming.callId &&
                        machine.snapshot.phase == CallPhase.INCOMING_RINGING
                    ) {
                        val expired = machine.rejectOrDecline("no_answer")
                        _state.value = NearbyCallUiState(expired, "Incoming call timed out")
                        send(CallControlType.REJECT, expired.callId!!, expired.peerDeviceId!!)
                    }
                }
            }

            CallControlType.ACCEPT -> {
                if (message.callId != call.callId || call.phase != CallPhase.OUTGOING_RINGING) return
                val securePeer = securePeer(endpointId) ?: run {
                    publishError("Secure session disappeared before voice media started")
                    return
                }
                val mediaCipher = try {
                    securePeer.forCall(call.callId!!)
                } catch (error: Exception) {
                    val ended = machine.end("secure_media_key_unavailable")
                    LinkaVoiceForegroundService.stop(appContext)
                    _state.value = NearbyCallUiState(ended, "Could not establish secure voice keys")
                    send(CallControlType.END, ended.callId!!, ended.peerDeviceId!!)
                    return
                }
                val active = machine.outgoingAccepted(System.currentTimeMillis())
                _state.value = NearbyCallUiState(active, "Secure call accepted; starting encrypted voice")
                startVoiceForPeer(active.peerDeviceId!!, active.callId!!, mediaCipher)
            }

            CallControlType.REJECT -> {
                if (message.callId != call.callId ||
                    call.phase !in setOf(CallPhase.OUTGOING_RINGING, CallPhase.INCOMING_RINGING)
                ) return
                val ended = machine.rejectOrDecline("remote_rejected")
                stopVoice("The other phone rejected the call")
                LinkaVoiceForegroundService.stop(appContext)
                _state.value = NearbyCallUiState(ended, "The other phone rejected the call")
            }

            CallControlType.END -> {
                if (message.callId != call.callId ||
                    call.phase !in setOf(CallPhase.OUTGOING_RINGING, CallPhase.INCOMING_RINGING, CallPhase.ACTIVE)
                ) return
                val ended = machine.end("remote_ended")
                stopVoice("The other phone ended the call")
                LinkaVoiceForegroundService.stop(appContext)
                _state.value = NearbyCallUiState(ended, "The other phone ended the call")
            }

            CallControlType.BUSY -> {
                if (message.callId != call.callId || call.phase != CallPhase.OUTGOING_RINGING) return
                val ended = machine.rejectOrDecline("peer_busy")
                stopVoice("The other phone is busy")
                LinkaVoiceForegroundService.stop(appContext)
                _state.value = NearbyCallUiState(ended, "The other phone is busy")
            }
        }
    }

    private fun send(type: CallControlType, callId: String, endpointId: String) {
        val bytes = try {
            CallControlCodec.encode(
                CallControlMessage(
                    type = type,
                    callId = callId,
                    senderDeviceId = localDeviceId
                )
            )
        } catch (error: IllegalArgumentException) {
            publishError(error.message ?: "Could not encode call-control message")
            return
        }

        if (endpointId.startsWith(ROUTE_ENDPOINT_PREFIX)) {
            val destinationDeviceId = endpointId.removePrefix(ROUTE_ENDPOINT_PREFIX)
            val session = routedPeerSecurity.session(endpointId) ?: run {
                publishError("Cannot send routed call controls before end-to-end verification")
                return
            }
            val encrypted = try {
                session.encryptControl(bytes)
            } catch (error: Exception) {
                publishError(error.localizedMessage ?: "Could not encrypt routed call-control message")
                return
            }
            sendRoutedPayload(destinationDeviceId, RoutedPayloadType.CALL_CONTROL, encrypted)
            return
        }

        val session = peerSecurity.session(endpointId) ?: run {
            publishError("Cannot send call controls before secure identity verification")
            return
        }
        val encrypted = try {
            session.encryptControl(bytes)
        } catch (error: Exception) {
            publishError(error.localizedMessage ?: "Could not encrypt call-control message")
            return
        }
        nearbyLink.sendBytes(endpointId, encrypted) { error ->
            if (error != null) publishError(error)
        }
    }

    private fun publishError(message: String) {
        _state.value = _state.value.copy(error = message, status = "Call control error")
    }

    fun close() {
        stopVoice("LINKA is closing")
        voiceSession.close()
        routedVoiceSession.close()
        peerSecurity.close()
        routedPeerSecurity.close()
        trackedEndpoints.clear()
        LinkaVoiceForegroundService.stop(appContext)
        scope.cancel()
    }

    companion object {
        private const val CALL_RING_TIMEOUT_MS = 45_000L
        private const val ROUTE_ENDPOINT_PREFIX = "route:"
        private const val ROUTED_VOICE_QUEUE_CAPACITY = 128
    }
}
