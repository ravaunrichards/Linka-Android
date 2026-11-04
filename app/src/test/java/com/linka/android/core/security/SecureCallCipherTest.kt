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
    fun encryptedVoicePacketHasFixedBoundedSize() {
        val alice = SecureCallCipher(keyA, keyB, "call-size")
        val frame = ByteArray(176) { it.toByte() }
        val encrypted = alice.encryptFrame(frame)

        assertEquals(SecureCallCipher.ENCRYPTED_FRAME_BYTES, encrypted.size)
    }
}
