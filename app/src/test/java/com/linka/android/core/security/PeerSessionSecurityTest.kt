package com.linka.android.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

class PeerSessionSecurityTest {
    private data class TestIdentity(
        val deviceId: String,
        val keyPair: KeyPair
    ) {
        val publicKeyBytes: ByteArray get() = keyPair.public.encoded
        val publicKeyBase64: String get() = Base64.getEncoder().encodeToString(publicKeyBytes)
        val fingerprint: String get() = MessageDigest.getInstance("SHA-256")
            .digest(publicKeyBytes)
            .joinToString("") { "%02X".format(it) }

        fun sign(bytes: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(bytes)
                sign()
            }

        fun trusted(): TrustedPeerIdentity =
            TrustedPeerIdentity(deviceId, publicKeyBase64, fingerprint)
    }

    private fun newIdentity(): TestIdentity {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return TestIdentity(UUID.randomUUID().toString(), generator.generateKeyPair())
    }

    private fun session(
        local: TestIdentity,
        trusted: TestIdentity?,
        relayOptIn: Boolean = false
    ): PeerSessionSecurity =
        PeerSessionSecurity(
            localDeviceId = local.deviceId,
            localPublicKeyBase64 = local.publicKeyBase64,
            signIdentity = local::sign,
            trustedIdentity = { id -> trusted?.takeIf { it.deviceId == id }?.trusted() },
            localRelayOptIn = { relayOptIn }
        )

    @Test
    fun twoQrTrustedPeersCompleteMutualIdentityHandshakeAndShareSecureSession() {
        val alice = newIdentity()
        val bob = newIdentity()
        val aliceSession = session(alice, bob, relayOptIn = true)
        val bobSession = session(bob, alice, relayOptIn = false)

        val aliceHello = aliceSession.begin("endpoint-b")!!
        val bobHello = bobSession.begin("endpoint-a")!!

        val aliceReply = aliceSession.handle("endpoint-b", bobHello)
        val bobReply = bobSession.handle("endpoint-a", aliceHello)
        assertNull(aliceReply.error)
        assertNull(bobReply.error)
        assertFalse(aliceReply.isAuthenticated)
        assertFalse(bobReply.isAuthenticated)

        val aliceComplete = aliceSession.handle("endpoint-b", bobReply.responses.single())
        val bobComplete = bobSession.handle("endpoint-a", aliceReply.responses.single())
        assertTrue(aliceComplete.isAuthenticated)
        assertTrue(bobComplete.isAuthenticated)
        assertEquals(false, aliceComplete.remoteRelayOptIn)
        assertEquals(true, bobComplete.remoteRelayOptIn)
        assertNotNull(aliceSession.session("endpoint-b"))
        assertNotNull(bobSession.session("endpoint-a"))

        val plaintext = "LINKA authenticated call control".toByteArray()
        val encrypted = aliceSession.session("endpoint-b")!!.encryptControl(plaintext)
        assertArrayEquals(
            plaintext,
            bobSession.session("endpoint-a")!!.decryptControl(encrypted)
        )
    }

    @Test
    fun rejectsPeerThatWasNotExplicitlyTrusted() {
        val alice = newIdentity()
        val bob = newIdentity()
        val aliceSession = session(alice, bob)
        val bobWithoutTrust = session(bob, null)

        val aliceHello = aliceSession.begin("endpoint-b")!!
        bobWithoutTrust.begin("endpoint-a")
        val result = bobWithoutTrust.handle("endpoint-a", aliceHello)

        assertFalse(result.isAuthenticated)
        assertNotNull(result.error)
        assertNull(bobWithoutTrust.session("endpoint-a"))
    }

    @Test
    fun rejectsTamperedIdentitySignature() {
        val alice = newIdentity()
        val bob = newIdentity()
        val aliceSession = session(alice, bob)
        val bobSession = session(bob, alice)

        val tampered = aliceSession.begin("endpoint-b")!!.also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        bobSession.begin("endpoint-a")
        val result = bobSession.handle("endpoint-a", tampered)

        assertFalse(result.isAuthenticated)
        assertNotNull(result.error)
        assertNull(bobSession.session("endpoint-a"))
    }
}
