package com.linka.android.core.call

import android.content.Context
import com.linka.android.core.audio.LinkaVoiceForegroundService
import com.linka.android.core.audio.NearbyVoiceSession
import com.linka.android.core.audio.VoiceSessionState
import com.linka.android.core.nearby.NearbyDirectLink
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
    private val localDeviceId: String,
    private val nearbyLink: NearbyDirectLink
) {
    private val appContext = context.applicationContext
    private val machine = CallStateMachine()
    private val voiceSession = NearbyVoiceSession(appContext, nearbyLink)
    val voiceState: StateFlow<VoiceSessionState> = voiceSession.state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(NearbyCallUiState())
    val state: StateFlow<NearbyCallUiState> = _state.asStateFlow()

    init {
        require(localDeviceId.isNotBlank() && localDeviceId.length <= 64)
        scope.launch {
            nearbyLink.incomingBytes.collect { incoming ->
                val currentEndpoint = nearbyLink.state.value.connectedPeer?.endpointId
                if (incoming.endpointId != currentEndpoint) return@collect
                handleIncoming(incoming.bytes, incoming.endpointId)
            }
        }
        scope.launch {
            nearbyLink.state.collect { linkState ->
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
        val next = try {
            machine.beginOutgoing(UUID.randomUUID().toString(), peer.endpointId)
        } catch (error: IllegalStateException) {
            return publishError(error.message ?: "A call is already in progress")
        } catch (error: IllegalArgumentException) {
            return publishError(error.message ?: "Invalid call details")
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
        _state.value = NearbyCallUiState(active, "Call accepted; starting voice media")
        send(CallControlType.ACCEPT, active.callId!!, active.peerDeviceId!!)
        voiceSession.start(active.peerDeviceId)
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

    private fun handleIncoming(bytes: ByteArray, endpointId: String) {
        val message = try {
            CallControlCodec.decode(bytes)
        } catch (error: IllegalArgumentException) {
            publishError("Ignored malformed call-control message")
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
                val active = machine.outgoingAccepted(System.currentTimeMillis())
                _state.value = NearbyCallUiState(active, "Call accepted; starting voice media")
                voiceSession.start(active.peerDeviceId!!)
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

        nearbyLink.sendBytes(endpointId, bytes) { error ->
            if (error != null) publishError(error)
        }
    }

    private fun publishError(message: String) {
        _state.value = _state.value.copy(error = message, status = "Call control error")
    }

    fun close() {
        voiceSession.close()
        LinkaVoiceForegroundService.stop(appContext)
        scope.cancel()
    }

    companion object {
        private const val CALL_RING_TIMEOUT_MS = 45_000L
    }
}
