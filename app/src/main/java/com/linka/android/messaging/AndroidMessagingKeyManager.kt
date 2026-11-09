package com.linka.android.messaging

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.linka.android.core.identity.LocalDeviceIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class MessagingDevicePublicKey(
    val deviceId: String,
    val accountId: String,
    val keyVersion: Int?,
    val algorithm: String?,
    val publicKeyBase64: String?
)

internal data class MessagingDecryptedMessage(
    val plaintext: String,
    val senderAccountId: String,
    val messageId: String
)

/**
 * Device-bound LINKA E2EE-2 implementation.
 *
 * The long-lived ECDH private key is generated and retained by Android Keystore.
 * Per-message content keys and ephemeral ECDH private keys exist only in memory.
 * Android API 31+ is required for Android Keystore PURPOSE_AGREE_KEY.
 */
internal class AndroidMessagingKeyManager(
    context: Context,
    private val deviceIdentity: LocalDeviceIdentity
) {
    private val appContext = context.applicationContext

    fun createRegistrationBody(): JSONObject {
        ensureSupported()
        val pair = getOrCreateKeyPair()
        val publicKeyDer = pair.public.encoded
        val publicKeyBase64 = encodeBase64(publicKeyDer)
        val fingerprint = sha256Hex(publicKeyDer)
        val statement = listOf(
            "LINKA-MESSAGING-KEY-V1",
            deviceIdentity.deviceId,
            ALGORITHM,
            fingerprint
        ).joinToString("\n").toByteArray(StandardCharsets.UTF_8)
        val signature = deviceIdentity.sign(statement)
        return JSONObject()
            .put("algorithm", ALGORITHM)
            .put("publicKeyBase64", publicKeyBase64)
            .put("signatureBase64", encodeBase64(signature))
    }

    fun encryptForDevices(
        plaintext: String,
        conversationId: String,
        senderAccountId: String,
        devices: List<MessagingDevicePublicKey>
    ): JSONObject {
        ensureSupported()
        require(plaintext.isNotEmpty() && plaintext.length <= MAX_MESSAGE_CHARS) {
            "Enter a message between 1 and $MAX_MESSAGE_CHARS characters."
        }
        require(conversationId.isNotBlank() && senderAccountId.isNotBlank())
        require(devices.isNotEmpty() && devices.size <= MAX_DEVICES) {
            "The active conversation device list is invalid."
        }
        require(devices.map { it.deviceId }.distinct().size == devices.size) {
            "The conversation contains duplicate device identities."
        }
        devices.forEach { device ->
            require(device.algorithm == ALGORITHM && (device.keyVersion ?: 0) > 0 &&
                device.deviceId.isNotBlank() && !device.publicKeyBase64.isNullOrBlank()) {
                "Every active conversation device must register an ECDH P-256 key before sending."
            }
        }

        val contentKey = randomBytes(CONTENT_KEY_BYTES)
        try {
            val messageNonce = randomBytes(GCM_NONCE_BYTES)
            val messageInfo = messageInfo(conversationId, senderAccountId)
            val messageCipher = Cipher.getInstance(AES_GCM)
            messageCipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(contentKey, AES),
                GCMParameterSpec(GCM_TAG_BITS, messageNonce)
            )
            messageCipher.updateAAD(messageInfo)
            val encryptedMessage = messageCipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
            val ciphertext = ByteArray(messageNonce.size + encryptedMessage.size)
            System.arraycopy(messageNonce, 0, ciphertext, 0, messageNonce.size)
            System.arraycopy(encryptedMessage, 0, ciphertext, messageNonce.size, encryptedMessage.size)

            val envelopes = JSONArray()
            devices.forEach { device ->
                val recipientPublic = parseP256PublicKey(decodeBase64(device.publicKeyBase64!!))
                val ephemeralPair = generateEphemeralPair()
                val salt = randomBytes(HKDF_SALT_BYTES)
                val wrapNonce = randomBytes(GCM_NONCE_BYTES)
                val info = envelopeInfo(conversationId, device.deviceId, device.keyVersion)
                val sharedSecret = deriveSharedSecret(ephemeralPair.private, recipientPublic)
                val wrappingKey = hkdfSha256(sharedSecret, salt, info, CONTENT_KEY_BYTES)
                sharedSecret.fill(0)
                val wrapCipher = Cipher.getInstance(AES_GCM)
                wrapCipher.init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(wrappingKey, AES),
                    GCMParameterSpec(GCM_TAG_BITS, wrapNonce)
                )
                wrapCipher.updateAAD(info)
                val wrappedContentKey = wrapCipher.doFinal(contentKey)
                wrappingKey.fill(0)

                envelopes.put(
                    JSONObject()
                        .put("recipientDeviceId", device.deviceId)
                        .put("recipientKeyVersion", device.keyVersion)
                        .put("ephemeralPublicKeyBase64", encodeBase64(ephemeralPair.public.encoded))
                        .put("hkdfSaltBase64", encodeBase64(salt))
                        .put("wrapNonceBase64", encodeBase64(wrapNonce))
                        .put("wrappedContentKeyBase64", encodeBase64(wrappedContentKey))
                )
            }

            return JSONObject()
                .put("ciphertext", encodeBase64(ciphertext))
                .put("encryptionVersion", ENCRYPTION_VERSION)
                .put("keyEnvelopes", envelopes)
        } finally {
            contentKey.fill(0)
        }
    }

    fun decryptMessage(
        ciphertextBase64: String,
        encryptionVersion: String,
        conversationId: String,
        senderAccountId: String,
        keyEnvelope: JSONObject
    ): String {
        ensureSupported()
        require(encryptionVersion == ENCRYPTION_VERSION) {
            "This message uses an unsupported or legacy encryption version."
        }
        require(keyEnvelope.optString("recipientDeviceId") == deviceIdentity.deviceId) {
            "No message-key envelope exists for this Android device."
        }
        val keyVersion = keyEnvelope.optInt("recipientKeyVersion", -1)
        require(keyVersion > 0) { "The message-key envelope has an invalid key version." }

        val packed = decodeBase64(ciphertextBase64)
        require(packed.size in 28..MAX_CIPHERTEXT_BYTES) {
            "Encrypted message has an invalid size."
        }
        val ephemeralPublic = parseP256PublicKey(
            decodeBase64(keyEnvelope.getString("ephemeralPublicKeyBase64"))
        )
        val salt = decodeBase64(keyEnvelope.getString("hkdfSaltBase64"))
        val wrapNonce = decodeBase64(keyEnvelope.getString("wrapNonceBase64"))
        val wrappedContentKey = decodeBase64(keyEnvelope.getString("wrappedContentKeyBase64"))
        require(salt.size == HKDF_SALT_BYTES && wrapNonce.size == GCM_NONCE_BYTES &&
            wrappedContentKey.size == CONTENT_KEY_BYTES + GCM_TAG_BYTES) {
            "Encrypted message-key envelope has an invalid size."
        }

        val pair = getOrCreateKeyPair()
        val info = envelopeInfo(conversationId, deviceIdentity.deviceId, keyVersion)
        val sharedSecret = deriveSharedSecret(pair.private, ephemeralPublic)
        val wrappingKey = hkdfSha256(sharedSecret, salt, info, CONTENT_KEY_BYTES)
        sharedSecret.fill(0)
        val unwrapCipher = Cipher.getInstance(AES_GCM)
        unwrapCipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(wrappingKey, AES),
            GCMParameterSpec(GCM_TAG_BITS, wrapNonce)
        )
        unwrapCipher.updateAAD(info)
        val contentKey = unwrapCipher.doFinal(wrappedContentKey)
        wrappingKey.fill(0)
        require(contentKey.size == CONTENT_KEY_BYTES) { "Invalid LINKA message content key." }

        try {
            val messageNonce = packed.copyOfRange(0, GCM_NONCE_BYTES)
            val encryptedMessage = packed.copyOfRange(GCM_NONCE_BYTES, packed.size)
            val messageCipher = Cipher.getInstance(AES_GCM)
            messageCipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(contentKey, AES),
                GCMParameterSpec(GCM_TAG_BITS, messageNonce)
            )
            messageCipher.updateAAD(messageInfo(conversationId, senderAccountId))
            return messageCipher.doFinal(encryptedMessage).toString(StandardCharsets.UTF_8)
        } finally {
            contentKey.fill(0)
        }
    }

    private fun getOrCreateKeyPair(): KeyPair {
        ensureSupported()
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) return KeyPair(existing.certificate.publicKey, existing.privateKey)

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .build()
        )
        return generator.generateKeyPair()
    }

    private fun generateEphemeralPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            generateKeyPair()
        }

    private fun deriveSharedSecret(privateKey: java.security.PrivateKey, publicKey: java.security.PublicKey): ByteArray {
        return KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(publicKey, true)
            generateSecret()
        }
    }

    private fun parseP256PublicKey(encoded: ByteArray): java.security.PublicKey {
        require(encoded.size in 64..512) { "Invalid ECDH public key size." }
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
        val parameters = (key as? java.security.interfaces.ECPublicKey)?.params
        require(parameters?.curve?.field?.fieldSize == 256) { "Only ECDH P-256 public keys are supported." }
        require(key.encoded.contentEquals(encoded)) { "Non-canonical ECDH public key encoding." }
        return key
    }

    private fun hkdfSha256(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int
    ): ByteArray {
        val extract = Mac.getInstance(HMAC_SHA256).apply {
            init(SecretKeySpec(salt, HMAC_SHA256))
        }.doFinal(inputKeyMaterial)
        val output = ByteArrayOutputStreamCompat(length)
        var previous = ByteArray(0)
        var counter = 1
        while (output.size() < length) {
            val mac = Mac.getInstance(HMAC_SHA256)
            mac.init(SecretKeySpec(extract, HMAC_SHA256))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            output.write(previous)
            counter += 1
        }
        extract.fill(0)
        previous.fill(0)
        return output.toByteArray().copyOf(length)
    }

    private fun envelopeInfo(conversationId: String, deviceId: String, keyVersion: Int): ByteArray =
        listOf("LINKA-MESSAGE-KEY-V1", conversationId, deviceId, keyVersion.toString())
            .joinToString("\n").toByteArray(StandardCharsets.UTF_8)

    private fun messageInfo(conversationId: String, senderAccountId: String): ByteArray =
        listOf("LINKA-MESSAGE-V2", conversationId, senderAccountId)
            .joinToString("\n").toByteArray(StandardCharsets.UTF_8)

    private fun ensureSupported() {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "Android 12 or later is required for Android Keystore-backed E2EE messaging on this device."
        }
    }

    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also { SecureRandom().nextBytes(it) }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02X".format(byte) }

    private fun encodeBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decodeBase64(value: String): ByteArray {
        require(value.isNotBlank() && value.none { it.isWhitespace() }) { "Invalid LINKA encrypted-message encoding." }
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(encodeBase64(bytes) == value) { "Invalid LINKA encrypted-message encoding." }
        return bytes
    }

    private class ByteArrayOutputStreamCompat(private val maxSize: Int) {
        private val chunks = ArrayList<ByteArray>()
        private var length = 0
        fun size(): Int = length
        fun write(value: ByteArray) {
            require(length + value.size <= maxSize + 32) { "HKDF output exceeded its configured size." }
            chunks += value
            length += value.size
        }
        fun toByteArray(): ByteArray {
            val output = ByteArray(length)
            var offset = 0
            chunks.forEach { chunk ->
                System.arraycopy(chunk, 0, output, offset, chunk.size)
                offset += chunk.size
            }
            return output
        }
    }

    companion object {
        const val ALGORITHM = "ECDH-P256"
        const val ENCRYPTION_VERSION = "LINKA-E2EE-2"
        private const val KEY_ALIAS = "linka.messaging.ecdh.p256.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES = "AES"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val HMAC_SHA256 = "HmacSHA256"
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = 16
        private const val GCM_NONCE_BYTES = 12
        private const val CONTENT_KEY_BYTES = 32
        private const val HKDF_SALT_BYTES = 16
        private const val MAX_MESSAGE_CHARS = 6000
        private const val MAX_DEVICES = 100
        private const val MAX_CIPHERTEXT_BYTES = 12 * 1024
    }
}
