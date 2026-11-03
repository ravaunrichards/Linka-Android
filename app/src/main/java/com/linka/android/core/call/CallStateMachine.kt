package com.linka.android.core.call

enum class CallPhase {
    IDLE,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    ACTIVE,
    ENDED,
    FAILED
}

enum class CallDirection { INCOMING, OUTGOING }

data class CallSnapshot(
    val phase: CallPhase = CallPhase.IDLE,
    val callId: String? = null,
    val peerDeviceId: String? = null,
    val direction: CallDirection? = null,
    val startedAtEpochMs: Long? = null,
    val endReason: String? = null
)

/**
 * Pure call lifecycle rules. The caller is responsible for persisting UI state
 * and sending/receiving CallControlMessage objects over an authenticated link.
 */
class CallStateMachine {
    var snapshot: CallSnapshot = CallSnapshot()
        private set

    fun beginOutgoing(callId: String, peerDeviceId: String): CallSnapshot {
        requireValidIdentifiers(callId, peerDeviceId)
        check(snapshot.phase in setOf(CallPhase.IDLE, CallPhase.ENDED, CallPhase.FAILED)) {
            "A call is already in progress"
        }
        snapshot = CallSnapshot(
            phase = CallPhase.OUTGOING_RINGING,
            callId = callId,
            peerDeviceId = peerDeviceId,
            direction = CallDirection.OUTGOING
        )
        return snapshot
    }

    fun receiveInvite(callId: String, peerDeviceId: String): CallSnapshot {
        requireValidIdentifiers(callId, peerDeviceId)
        check(snapshot.phase in setOf(CallPhase.IDLE, CallPhase.ENDED, CallPhase.FAILED)) {
            "Device is busy"
        }
        snapshot = CallSnapshot(
            phase = CallPhase.INCOMING_RINGING,
            callId = callId,
            peerDeviceId = peerDeviceId,
            direction = CallDirection.INCOMING
        )
        return snapshot
    }

    fun acceptIncoming(nowEpochMs: Long): CallSnapshot {
        check(snapshot.phase == CallPhase.INCOMING_RINGING) {
            "There is no incoming call to accept"
        }
        require(nowEpochMs > 0)
        snapshot = snapshot.copy(
            phase = CallPhase.ACTIVE,
            startedAtEpochMs = nowEpochMs,
            endReason = null
        )
        return snapshot
    }

    fun outgoingAccepted(nowEpochMs: Long): CallSnapshot {
        check(snapshot.phase == CallPhase.OUTGOING_RINGING) {
            "There is no outgoing call awaiting acceptance"
        }
        require(nowEpochMs > 0)
        snapshot = snapshot.copy(
            phase = CallPhase.ACTIVE,
            startedAtEpochMs = nowEpochMs,
            endReason = null
        )
        return snapshot
    }

    fun rejectOrDecline(reason: String = "rejected"): CallSnapshot {
        check(snapshot.phase == CallPhase.OUTGOING_RINGING || snapshot.phase == CallPhase.INCOMING_RINGING) {
            "Only a ringing call can be rejected"
        }
        snapshot = snapshot.copy(
            phase = CallPhase.ENDED,
            endReason = reason.take(80)
        )
        return snapshot
    }

    fun end(reason: String = "ended"): CallSnapshot {
        check(snapshot.phase == CallPhase.ACTIVE ||
            snapshot.phase == CallPhase.OUTGOING_RINGING ||
            snapshot.phase == CallPhase.INCOMING_RINGING
        ) { "There is no call to end" }
        snapshot = snapshot.copy(
            phase = CallPhase.ENDED,
            endReason = reason.take(80)
        )
        return snapshot
    }

    fun connectionLost(): CallSnapshot {
        if (snapshot.phase == CallPhase.ACTIVE ||
            snapshot.phase == CallPhase.OUTGOING_RINGING ||
            snapshot.phase == CallPhase.INCOMING_RINGING
        ) {
            snapshot = snapshot.copy(
                phase = CallPhase.FAILED,
                endReason = "connection_lost"
            )
        }
        return snapshot
    }

    fun reset(): CallSnapshot {
        check(snapshot.phase == CallPhase.ENDED || snapshot.phase == CallPhase.FAILED || snapshot.phase == CallPhase.IDLE) {
            "An active call cannot be reset"
        }
        snapshot = CallSnapshot()
        return snapshot
    }

    private fun requireValidIdentifiers(callId: String, peerDeviceId: String) {
        require(callId.isNotBlank() && callId.length <= 64)
        require(peerDeviceId.isNotBlank() && peerDeviceId.length <= 64)
    }
}
