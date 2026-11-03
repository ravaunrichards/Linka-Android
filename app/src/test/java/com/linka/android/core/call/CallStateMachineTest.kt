package com.linka.android.core.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CallStateMachineTest {
    @Test
    fun outgoingCallTransitionsToActiveAndEnds() {
        val machine = CallStateMachine()

        val ringing = machine.beginOutgoing("call-1", "peer-1")
        assertEquals(CallPhase.OUTGOING_RINGING, ringing.phase)
        assertEquals(CallDirection.OUTGOING, ringing.direction)

        val active = machine.outgoingAccepted(1_800_000_000_000)
        assertEquals(CallPhase.ACTIVE, active.phase)
        assertEquals(1_800_000_000_000, active.startedAtEpochMs)

        val ended = machine.end("local_hangup")
        assertEquals(CallPhase.ENDED, ended.phase)
        assertEquals("local_hangup", ended.endReason)
    }

    @Test
    fun incomingCallCanBeAcceptedOrRejected() {
        val accepted = CallStateMachine()
        assertEquals(
            CallPhase.INCOMING_RINGING,
            accepted.receiveInvite("call-2", "peer-2").phase
        )
        assertEquals(CallPhase.ACTIVE, accepted.acceptIncoming(1_800_000_000_001).phase)

        val rejected = CallStateMachine()
        rejected.receiveInvite("call-3", "peer-3")
        assertEquals(CallPhase.ENDED, rejected.rejectOrDecline().phase)
    }

    @Test
    fun connectionLossFailsAnActiveCall() {
        val machine = CallStateMachine()
        machine.beginOutgoing("call-4", "peer-4")
        machine.outgoingAccepted(1_800_000_000_002)

        val failed = machine.connectionLost()
        assertEquals(CallPhase.FAILED, failed.phase)
        assertEquals("connection_lost", failed.endReason)
    }

    @Test
    fun activeCallCannotBeReplacedOrReset() {
        val machine = CallStateMachine()
        machine.receiveInvite("call-5", "peer-5")
        machine.acceptIncoming(1_800_000_000_003)

        assertThrows(IllegalStateException::class.java) {
            machine.beginOutgoing("call-6", "peer-6")
        }
        assertThrows(IllegalStateException::class.java) {
            machine.reset()
        }
    }

    @Test
    fun invalidIdentifiersAreRejected() {
        val machine = CallStateMachine()
        assertThrows(IllegalArgumentException::class.java) {
            machine.beginOutgoing("", "peer")
        }
        assertThrows(IllegalArgumentException::class.java) {
            machine.beginOutgoing("call", "x".repeat(65))
        }
    }
}
