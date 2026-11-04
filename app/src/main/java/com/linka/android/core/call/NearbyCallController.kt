package com.linka.android.core.call

import android.content.Context
import com.linka.android.core.audio.LinkaVoiceForegroundService
import com.linka.android.core.audio.NearbyVoiceSession
import com.linka.android.core.audio.VoiceSessionState
import com.linka.android.core.nearby.NearbyDirectLink
import com.linka.android.core.identity.LocalDeviceIdentity
import com.linka.android.core.identity.TrustedDeviceStore
import com.linka.android.core.security.PeerSessionSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    trustedDevices: TrustedDeviceStore,
    private val nearbyLink: NearbyDirectLink
) {
    private val localDeviceId = localIdentity.deviceId
    private val appContext = context.applicationContext
    private val machine = CallStateMachine()
    private val peerSecurity = PeerSessionSecurity(localIdentity, trustedDevices)
    private val trackedEndpoints = mutableSetOf<String>()
    private val voiceSession = NearbyVoiceSession(appContext, nearbyLink)
    val voiceState: StateFlow<VoiceSessionState> = voiceSession.state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(NearbyCallUiState())
    val state: StateFlow<NearbyCallUiState> = _state.asStateFlow()

    init {
        require(localDeviceId.isNotBlank() && localDeviceId.length <= 64)
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
                        _state.value = _state.value.copy(
                            status = "Secure identity verified",
                            error = null
                        )
                    }
                    return@collect
                }

                if (incoming.endpointId != nearbyLink.state.value.connectedPeer?.endpointId) {
                    return@collect
                }
                val session = peerSecurity.session(incoming.endpointId)
                if (session == null) {
                    publishError("Ignored call data before trusted identity verification")
                    return@collect
                }
                val plaintext = try {
                    session.decryptControl(incoming.bytes)
                } catch (error: Exception) {
                    publishError(error.localizedMessage ?: "Secure call-control authentication failed")
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
                if (linkState.connectedPeer == null &&
                    current.phase in setOf(
                        CallPhase.OUTGOING_RINGING,
                        CallPhase.INCOMING_RINGING,
                        CallPhase.ACTIVE
                    )
                ) {
                    voiceSession.stop("Nearby connection lost")
                    LinkaVoiceForegroundService.stop(appContext)
                    _state.value = NearbyCallUiState(
                        call = machine.connectionLost(),
                        status = "Nearby connection lost",
                        error = "The paired phone disconnected"
                    )
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
                voiceSession.stop("No answer")
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
        val securePeer = peerSecurity.session(endpoint) ?: run {
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

        val active = try {
            machine.acceptIncoming(System.currentTimeMillis())
        } catch (error: IllegalStateException) {
            LinkaVoiceForegroundService.stop(appContext)
            publishError(error.message ?: "Unable to accept call")
            return
        }
        _state.value = NearbyCallUiState(active, "Secure call accepted; starting encrypted voice")
        send(CallControlType.ACCEPT, active.callId!!, active.peerDeviceId!!)
        voiceSession.start(active.peerDeviceId!!, active.callId!!, securePeer.forCall(active.callId!!))
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
        voiceSession.stop("Call ended")
        LinkaVoiceForegroundService.stop(appContext)
        _state.value = NearbyCallUiState(ended, "Call ended")
        send(CallControlType.END, ended.callId!!, ended.peerDeviceId!!)
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
                val name = nearbyLink.state.value.connectedPeer?.displayName ?: "Nearby LINKA phone"
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
                val securePeer = peerSecurity.session(endpointId) ?: run {
                    publishError("Secure session disappeared before voice media started")
                    return
                }
                val active = machine.outgoingAccepted(System.currentTimeMillis())
                _state.value = NearbyCallUiState(active, "Secure call accepted; starting encrypted voice")
                voiceSession.start(
                    active.peerDeviceId!!,
                    active.callId!!,
                    securePeer.forCall(active.callId!!)
                )
            }

            CallControlType.REJECT -> {
                if (message.callId != call.callId ||
                    call.phase !in setOf(CallPhase.OUTGOING_RINGING, CallPhase.INCOMING_RINGING)
                ) return
                val ended = machine.rejectOrDecline("remote_rejected")
                voiceSession.stop("The other phone rejected the call")
                LinkaVoiceForegroundService.stop(appContext)
                _state.value = NearbyCallUiState(ended, "The other phone rejected the call")
            }

            CallControlType.END -> {
                if (message.callId != call.callId ||
                    call.phase !in setOf(CallPhase.OUTGOING_RINGING, CallPhase.INCOMING_RINGING, CallPhase.ACTIVE)
                ) return
                val ended = machine.end("remote_ended")
                voiceSession.stop("The other phone ended the call")
                LinkaVoiceForegroundService.stop(appContext)
                _state.value = NearbyCallUiState(ended, "The other phone ended the call")
            }

            CallControlType.BUSY -> {
                if (message.callId != call.callId || call.phase != CallPhase.OUTGOING_RINGING) return
                val ended = machine.rejectOrDecline("peer_busy")
                voiceSession.stop("The other phone is busy")
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
        voiceSession.close()
        peerSecurity.close()
        trackedEndpoints.clear()
        LinkaVoiceForegroundService.stop(appContext)
        scope.cancel()
    }

    companion object {
        private const val CALL_RING_TIMEOUT_MS = 45_000L
    }
}
