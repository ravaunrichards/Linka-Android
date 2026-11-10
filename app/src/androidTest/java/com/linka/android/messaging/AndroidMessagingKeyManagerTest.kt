package com.linka.android.messaging

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linka.android.core.identity.LocalDeviceIdentity
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class AndroidMessagingKeyManagerTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var identity: LocalDeviceIdentity
    private lateinit var manager: AndroidMessagingKeyManager

    @Before
    fun prepareDeviceKeys() {
        deleteMessagingKey()
        identity = LocalDeviceIdentity.load(context)
        manager = AndroidMessagingKeyManager(context, identity)
    }

    @After
    fun clearMessagingKey() {
        deleteMessagingKey()
    }

    @Test
    fun createsNonExportableAndroidKeystoreEcdhKeyAndSignedRegistrationProof() {
        val registration = manager.createRegistrationBody()
        assertEquals(AndroidMessagingKeyManager.ALGORITHM, registration.getString("algorithm"))

        val publicKey = Base64.decode(registration.getString("publicKeyBase64"), Base64.NO_WRAP)
        assertTrue(publicKey.size in 64..512)
        assertEquals(0x30, publicKey[0].toInt() and 0xff)

        val fingerprint = MessageDigest.getInstance("SHA-256").digest(publicKey)
            .joinToString("") { byte -> "%02X".format(byte) }
        val statement = listOf(
            "LINKA-MESSAGING-KEY-V1",
            identity.deviceId,
            AndroidMessagingKeyManager.ALGORITHM,
            fingerprint
        ).joinToString("\n").toByteArray(StandardCharsets.UTF_8)
        val signature = Base64.decode(registration.getString("signatureBase64"), Base64.NO_WRAP)
        assertTrue(
            "The existing LINKA device identity must sign the ECDH public key",
            LocalDeviceIdentity.verify(identity.publicKeyBase64, statement, signature)
        )

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(keyStore.containsAlias(KEY_ALIAS))
        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        assertNotNull(entry.certificate.publicKey)
        assertFalse("The Android Keystore ECDH private key must not be exportable", entry.privateKey.encoded != null)
    }

    @Test
    fun registrationKeepsTheSameDevicePublicKeyAcrossRepeatedAppRestores() {
        val first = manager.createRegistrationBody()
        val second = manager.createRegistrationBody()
        assertEquals(first.getString("publicKeyBase64"), second.getString("publicKeyBase64"))

        val publicKey = Base64.decode(first.getString("publicKeyBase64"), Base64.NO_WRAP)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(publicKey)
            .joinToString("") { byte -> "%02X".format(byte) }
        val statement = listOf(
            "LINKA-MESSAGING-KEY-V1",
            identity.deviceId,
            AndroidMessagingKeyManager.ALGORITHM,
            fingerprint
        ).joinToString("\n").toByteArray(StandardCharsets.UTF_8)
        assertTrue(
            LocalDeviceIdentity.verify(
                identity.publicKeyBase64,
                statement,
                Base64.decode(first.getString("signatureBase64"), Base64.NO_WRAP)
            )
        )
        assertTrue(
            LocalDeviceIdentity.verify(
                identity.publicKeyBase64,
                statement,
                Base64.decode(second.getString("signatureBase64"), Base64.NO_WRAP)
            )
        )
    }

    @Test
    fun encryptsAndDecryptsE2ee2MessageUsingItsDeviceEnvelope() {
        val registration = manager.createRegistrationBody()
        val recipient = MessagingDevicePublicKey(
            deviceId = identity.deviceId,
            accountId = ACCOUNT_ID,
            keyVersion = 1,
            algorithm = registration.getString("algorithm"),
            publicKeyBase64 = registration.getString("publicKeyBase64")
        )
        val plaintext = "LINKA Android encrypted messaging round-trip."
        val encrypted = manager.encryptForDevices(
            plaintext = plaintext,
            conversationId = CONVERSATION_ID,
            senderAccountId = ACCOUNT_ID,
            devices = listOf(recipient)
        )

        assertEquals(AndroidMessagingKeyManager.ENCRYPTION_VERSION, encrypted.getString("encryptionVersion"))
        val packedCiphertext = Base64.decode(encrypted.getString("ciphertext"), Base64.NO_WRAP)
        assertTrue(packedCiphertext.size > 28)
        assertFalse(
            "The ciphertext must not contain the plaintext",
            encrypted.getString("ciphertext").contains(
                Base64.encodeToString(plaintext.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            )
        )

        val envelopes = encrypted.getJSONArray("keyEnvelopes")
        assertEquals(1, envelopes.length())
        val envelope = envelopes.getJSONObject(0)
        assertEquals(identity.deviceId, envelope.getString("recipientDeviceId"))
        assertEquals(1, envelope.getInt("recipientKeyVersion"))

        val decrypted = manager.decryptMessage(
            ciphertextBase64 = encrypted.getString("ciphertext"),
            encryptionVersion = encrypted.getString("encryptionVersion"),
            conversationId = CONVERSATION_ID,
            senderAccountId = ACCOUNT_ID,
            keyEnvelope = envelope
        )
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun rejectsTamperedCiphertextAndWrongRecipientDevice() {
        val registration = manager.createRegistrationBody()
        val recipient = MessagingDevicePublicKey(
            deviceId = identity.deviceId,
            accountId = ACCOUNT_ID,
            keyVersion = 1,
            algorithm = registration.getString("algorithm"),
            publicKeyBase64 = registration.getString("publicKeyBase64")
        )
        val encrypted = manager.encryptForDevices(
            plaintext = "Detect message tampering.",
            conversationId = CONVERSATION_ID,
            senderAccountId = ACCOUNT_ID,
            devices = listOf(recipient)
        )
        val envelope = encrypted.getJSONArray("keyEnvelopes").getJSONObject(0)
        val ciphertext = Base64.decode(encrypted.getString("ciphertext"), Base64.NO_WRAP)
        ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 0x01).toByte()

        assertThrows(Exception::class.java) {
            manager.decryptMessage(
                Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                AndroidMessagingKeyManager.ENCRYPTION_VERSION,
                CONVERSATION_ID,
                ACCOUNT_ID,
                envelope
            )
        }

        val wrongDeviceEnvelope = JSONObject(envelope.toString())
            .put("recipientDeviceId", "00000000-0000-4000-8000-000000000099")
        assertThrows(IllegalArgumentException::class.java) {
            manager.decryptMessage(
                encrypted.getString("ciphertext"),
                AndroidMessagingKeyManager.ENCRYPTION_VERSION,
                CONVERSATION_ID,
                ACCOUNT_ID,
                wrongDeviceEnvelope
            )
        }
    }

    @Test
    fun refusesToEncryptWhenAnyActiveDeviceHasNoRegisteredKey() {
        val missingKeyDevice = MessagingDevicePublicKey(
            deviceId = "00000000-0000-4000-8000-000000000099",
            accountId = ACCOUNT_ID,
            keyVersion = null,
            algorithm = null,
            publicKeyBase64 = null
        )
        assertThrows(IllegalArgumentException::class.java) {
            manager.encryptForDevices(
                plaintext = "Never downgrade this message.",
                conversationId = CONVERSATION_ID,
                senderAccountId = ACCOUNT_ID,
                devices = listOf(missingKeyDevice)
            )
        }
    }

    private fun deleteMessagingKey() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private companion object {
        const val KEY_ALIAS = "linka.messaging.ecdh.p256.v1"
        const val ACCOUNT_ID = "00000000-0000-4000-8000-000000000001"
        const val CONVERSATION_ID = "00000000-0000-4000-8000-000000000010"
    }
}
