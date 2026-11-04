package com.linka.android.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException

class SecureCallCipherTest {
    private val keyA = ByteArray(32) { (it + 1).toByte() }
    private val keyB = ByteArray(32) { (it + 65).toByte() }

    @Test
    fun encryptsAndDecryptsInBothDirections() {
        val alice = SecureCallCipher(keyA, keyB, "call-123")
        val bob = SecureCallCipher(keyB, keyA, "call-123")
        val messageFromAlice = "encrypted voice frame from alice".toByteArray()
        val messageFromBob = "encrypted voice frame from bob".toByteArray()

        assertArrayEquals(messageFromAlice, bob.decryptFrame(alice.encryptFrame(messageFromAlice)))
        assertArrayEquals(messageFromBob, alice.decryptFrame(bob.encryptFrame(messageFromBob)))
    }

    @Test
    fun rejectsReplayedFrame() {
        val alice = SecureCallCipher(keyA, keyB, "call-replay")
        val bob = SecureCallCipher(keyB, keyA, "call-replay")
        val encrypted = alice.encryptFrame(byteArrayOf(1, 2, 3))

        assertArrayEquals(byteArrayOf(1, 2, 3), bob.decryptFrame(encrypted))
        assertThrows(IllegalArgumentException::class.java) {
            bob.decryptFrame(encrypted)
        }
    }


    @Test
    fun acceptsOutOfOrderFramesInsideReplayWindow() {
        val alice = SecureCallCipher(keyA, keyB, "call-reorder")
        val bob = SecureCallCipher(keyB, keyA, "call-reorder")
        val frame0 = alice.encryptFrame(byteArrayOf(0))
        val frame1 = alice.encryptFrame(byteArrayOf(1))
        val frame2 = alice.encryptFrame(byteArrayOf(2))

        assertArrayEquals(byteArrayOf(2), bob.decryptFrame(frame2))
        assertArrayEquals(byteArrayOf(0), bob.decryptFrame(frame0))
        assertArrayEquals(byteArrayOf(1), bob.decryptFrame(frame1))
    }

    @Test
    fun rejectsFramesOutsideReplayWindow() {
        val alice = SecureCallCipher(keyA, keyB, "call-window")
        val bob = SecureCallCipher(keyB, keyA, "call-window")
        val frames = (0 until 66).map { alice.encryptFrame(byteArrayOf(it.toByte())) }

        assertArrayEquals(byteArrayOf(65), bob.decryptFrame(frames.last()))
        assertThrows(IllegalArgumentException::class.java) {
            bob.decryptFrame(frames.first())
        }
    }

    @Test
    fun rejectsTamperedCiphertext() {
        val alice = SecureCallCipher(keyA, keyB, "call-tamper")
        val bob = SecureCallCipher(keyB, keyA, "call-tamper")
        val encrypted = alice.encryptFrame(byteArrayOf(7, 8, 9)).also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }

        assertThrows(GeneralSecurityException::class.java) {
            bob.decryptFrame(encrypted)
        }
    }

    @Test
    fun bindsCiphertextToCallIdentity() {
        val alice = SecureCallCipher(keyA, keyB, "call-one")
        val wrongCall = SecureCallCipher(keyB, keyA, "call-two")
        val encrypted = alice.encryptFrame(byteArrayOf(4, 5, 6))

        assertThrows(GeneralSecurityException::class.java) {
            wrongCall.decryptFrame(encrypted)
        }
    }


    @Test
    fun preventsCallIdReuseAndOperationsAfterSessionDestruction() {
        val peer = SecurePeerSession(
            localDeviceId = "device-a",
            remoteDeviceId = "device-b",
            remoteFingerprint = "fingerprint-b",
            remoteRelayOptIn = false,
            rootKey = ByteArray(32) { (it + 3).toByte() }
        )
        val firstCall = peer.forCall("unique-call")
        assertThrows(IllegalStateException::class.java) {
            peer.forCall("unique-call")
        }

        peer.destroy()
        firstCall.destroy()
        assertThrows(IllegalStateException::class.java) {
            peer.forCall("another-call")
        }
        assertThrows(IllegalStateException::class.java) {
            peer.encryptControl(byteArrayOf(1))
        }
    }

    @Test
    fun encryptedVoicePacketHasFixedBoundedSize() {
        val alice = SecureCallCipher(keyA, keyB, "call-size")
        val frame = ByteArray(176) { it.toByte() }
        val encrypted = alice.encryptFrame(frame)

        assertEquals(SecureCallCipher.ENCRYPTED_FRAME_BYTES, encrypted.size)
    }
}
