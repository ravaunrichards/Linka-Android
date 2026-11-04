package com.linka.android.core.security

import com.linka.android.core.audio.VoiceFrameCodec
import com.linka.android.core.identity.LocalDeviceIdentity
import com.linka.android.core.identity.TrustedDeviceStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticates an Android Nearby endpoint against an identity explicitly
 * trusted by scanning its signed LINKA QR code.
 *
 * Uses fresh P-256 ECDH keys, ECDSA identity signatures, transcript-bound
 * HMAC confirmation, HKDF-SHA-256 key separation and AES-256-GCM.
 *
 * This implementation still requires independent cryptographic review and
 * physical-device verification before a production security claim.
 */
data class TrustedPeerIdentity(
    val deviceId: String,
    val publicKeyBase64: String,
    val fingerprint: String
)

class PeerSessionSecurity internal constructor(
    private val localDeviceId: String,
    private val localPublicKeyBase64: String,
    private val signIdentity: (ByteArray) -> ByteArray,
    private val trustedIdentity: (String) -> TrustedPeerIdentity?
) {
    constructor(
        localIdentity: LocalDeviceIdentity,
        trustedDevices: TrustedDeviceStore
    ) : this(
        localIdentity.deviceId,
        localIdentity.publicKeyBase64,
        { bytes -> localIdentity.sign(bytes) },
        { deviceId ->
            trustedDevices.get(deviceId)?.let {
                TrustedPeerIdentity(it.deviceId, it.publicKeyBase64, it.fingerprint)
            }
        }
    )

    private data class Hello(
        val deviceId: String,
        val signingPublicKey: ByteArray,
        val ephemeralPublicKey: ByteArray,
        val nonce: ByteArray,
        val signature: ByteArray
    ) {
        fun signedBody(): ByteArray =
            canonicalHello(deviceId, signingPublicKey, ephemeralPublicKey, nonce)
    }

    private data class Ack(val senderDeviceId: String, val transcriptHash: ByteArray, val mac: ByteArray)

    private data class Exchange(
        val localKeyPair: KeyPair,
        val localHello: Hello,
        val localPacket: ByteArray,
        var remoteHello: Hello? = null,
        var rootKey: ByteArray? = null,
        var transcriptHash: ByteArray? = null,
        var acknowledgementSent: Boolean = false,
        var acknowledgementVerified: Boolean = false,
        var pendingAcknowledgement: Ack? = null
    )

    data class Result(
        val responses: List<ByteArray> = emptyList(),
        val isAuthenticated: Boolean = false,
        val remoteDeviceId: String? = null,
        val error: String? = null
    )

    private val exchanges = mutableMapOf<String, Exchange>()
    private val secureSessions = mutableMapOf<String, SecurePeerSession>()

    @Synchronized
    fun begin(endpointId: String): ByteArray? {
        require(endpointId.isNotBlank())
        if (exchanges.containsKey(endpointId)) return null
        val exchange = createExchange()
        exchanges[endpointId] = exchange
        return exchange.localPacket
    }

    @Synchronized
    fun handle(endpointId: String, packet: ByteArray): Result {
        if (endpointId.isBlank() || packet.size !in HEADER_SIZE..MAX_PACKET_BYTES) {
            return Result(error = "Invalid secure-pairing packet")
        }
        if (!isHandshakePacket(packet)) return Result(error = "Not a LINKA secure-pairing packet")

        val responses = mutableListOf<ByteArray>()
        var exchange = exchanges[endpointId] ?: try {
            createExchange().also {
                exchanges[endpointId] = it
                responses += it.localPacket
            }
        } catch (error: Exception) {
            return Result(error = error.localizedMessage ?: "Could not create secure identity handshake")
        }

        return try {
            when (val message = decodePacket(packet)) {
                is HandshakePacket.HelloPacket -> {
                    validateRemoteHello(message.hello)
                    val previous = exchange.remoteHello
                    if (previous != null && !sameHello(previous, message.hello)) {
                        // A previously authenticated peer may have restarted its
                        // ephemeral exchange after a trust update or reconnect.
                        // The new hello is already signature-checked above.
                        secureSessions.remove(endpointId)?.destroy()
                        exchange.rootKey?.fill(0)
                        exchange = createExchange()
                        exchanges[endpointId] = exchange
                        responses += exchange.localPacket
                    }
                    exchange.remoteHello = message.hello
                    establishRootKey(exchange)

                    val ack = createAcknowledgement(
                        exchange.rootKey!!,
                        exchange.transcriptHash!!,
                        localDeviceId
                    )
                    exchange.acknowledgementSent = true
                    responses += encodeAck(ack)

                    val pending = exchange.pendingAcknowledgement
                    if (pending != null) {
                        verifyAcknowledgement(exchange, pending)
                        exchange.pendingAcknowledgement = null
                    }
                    val authenticated = completeIfReady(endpointId, exchange)
                    Result(
                        responses = responses,
                        isAuthenticated = authenticated,
                        remoteDeviceId = exchange.remoteHello?.deviceId
                    )
                }

                is HandshakePacket.AckPacket -> {
                    if (exchange.rootKey == null) {
                        // ACKs can overtake HELLOs on the radio callbacks. Keep
                        // the newest candidate; the transcript hash binds it to
                        // this exact ephemeral exchange when the peer HELLO arrives.
                        exchange.pendingAcknowledgement = message.ack
                    } else {
                        verifyAcknowledgement(exchange, message.ack)
                    }
                    val authenticated = completeIfReady(endpointId, exchange)
                    Result(
                        responses = responses,
                        isAuthenticated = authenticated,
                        remoteDeviceId = exchange.remoteHello?.deviceId
                    )
                }
            }
        } catch (error: Exception) {
            exchanges.remove(endpointId)?.rootKey?.fill(0)
            secureSessions.remove(endpointId)?.destroy()
            Result(
                responses = emptyList(),
                isAuthenticated = false,
                error = error.localizedMessage ?: "Secure identity verification failed"
            )
        }
    }

    @Synchronized
    fun session(endpointId: String): SecurePeerSession? {
        val session = secureSessions[endpointId] ?: return null
        val currentTrust = trustedIdentity(session.remoteDeviceId)
        if (currentTrust == null ||
            !currentTrust.fingerprint.equals(session.remoteFingerprint, ignoreCase = true)
        ) {
            remove(endpointId)
            return null
        }
        return session
    }

    @Synchronized
    fun remove(endpointId: String) {
        exchanges.remove(endpointId)?.rootKey?.fill(0)
        secureSessions.remove(endpointId)?.destroy()
    }

    @Synchronized
    fun close() {
        exchanges.values.forEach { it.rootKey?.fill(0) }
        secureSessions.values.forEach { it.destroy() }
        exchanges.clear()
        secureSessions.clear()
    }

    private fun createExchange(): Exchange {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val ephemeral = generator.generateKeyPair()
        val nonce = ByteArray(NONCE_BYTES).also(java.security.SecureRandom()::nextBytes)
        val publicKey = Base64.getDecoder().decode(localPublicKeyBase64)
        val unsigned = Hello(
            deviceId = localDeviceId,
            signingPublicKey = publicKey,
            ephemeralPublicKey = ephemeral.public.encoded,
            nonce = nonce,
            signature = ByteArray(0)
        )
        val signed = unsigned.copy(signature = signIdentity(unsigned.signedBody()))
        return Exchange(ephemeral, signed, encodeHello(signed))
    }

    private fun validateRemoteHello(hello: Hello) {
        require(hello.deviceId != localDeviceId) { "A phone cannot pair with itself" }
        require(hello.deviceId.matches(Regex("[0-9a-fA-F-]{36}"))) {
            "The peer LINKA device ID is invalid"
        }

        val trusted = trustedIdentity(hello.deviceId)
            ?: throw SecurityException("This phone is not trusted yet. Scan its LINKA identity QR first.")
        val trustedPublicKey = Base64.getDecoder().decode(trusted.publicKeyBase64)
        require(MessageDigest.isEqual(trustedPublicKey, hello.signingPublicKey)) {
            "The nearby phone's identity key does not match the QR-trusted device"
        }
        val fingerprint = sha256(hello.signingPublicKey).toHex()
        require(fingerprint.equals(trusted.fingerprint, ignoreCase = true)) {
            "The nearby phone's identity fingerprint does not match the saved trust record"
        }

        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(hello.signingPublicKey))
        val valid = Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(hello.signedBody())
            verify(hello.signature)
        }
        require(valid) { "The nearby phone could not prove ownership of its trusted identity" }
    }

    private fun establishRootKey(exchange: Exchange) {
        if (exchange.rootKey != null) return
        val remote = exchange.remoteHello ?: return
        val remoteKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(remote.ephemeralPublicKey))
        val sharedSecret = KeyAgreement.getInstance("ECDH").run {
            init(exchange.localKeyPair.private)
            doPhase(remoteKey, true)
            generateSecret()
        }

        val transcript = canonicalTranscript(exchange.localHello, remote)
        val transcriptHash = sha256(transcript)
        val root = hkdf(
            inputKeyMaterial = sharedSecret,
            salt = transcriptHash,
            info = "LINKA-PEER-SESSION-ROOT-v1".toByteArray(StandardCharsets.UTF_8),
            length = 32
        )
        sharedSecret.fill(0)
        exchange.transcriptHash = transcriptHash
        exchange.rootKey = root

        val pending = exchange.pendingAcknowledgement
        if (pending != null) {
            verifyAcknowledgement(exchange, pending)
            exchange.pendingAcknowledgement = null
        }
    }

    private fun createAcknowledgement(rootKey: ByteArray, transcriptHash: ByteArray, senderId: String): Ack {
        val key = hkdf(
            inputKeyMaterial = rootKey,
            salt = transcriptHash,
            info = "LINKA-HANDSHAKE-ACK-KEY-v1".toByteArray(StandardCharsets.UTF_8),
            length = 32
        )
        val body = ACK_DOMAIN + transcriptHash + senderId.toByteArray(StandardCharsets.UTF_8)
        val mac = hmacSha256(key, body)
        key.fill(0)
        return Ack(senderId, transcriptHash.copyOf(), mac)
    }

    private fun verifyAcknowledgement(exchange: Exchange, ack: Ack) {
        val remote = exchange.remoteHello
            ?: throw SecurityException("Secure peer identity has not been verified")
        require(ack.senderDeviceId == remote.deviceId) {
            "Secure-pairing confirmation came from the wrong device"
        }
        val transcriptHash = exchange.transcriptHash
            ?: throw SecurityException("Secure handshake transcript is unavailable")
        if (!MessageDigest.isEqual(transcriptHash, ack.transcriptHash)) {
            // This is an ACK for an earlier ephemeral exchange. Ignore it;
            // the peer will send a confirmation for the current transcript.
            return
        }
        val expected = createAcknowledgement(
            exchange.rootKey ?: throw SecurityException("Secure session key is unavailable"),
            transcriptHash,
            remote.deviceId
        )
        require(MessageDigest.isEqual(expected.mac, ack.mac)) {
            "The peer failed secure key confirmation"
        }
        exchange.acknowledgementVerified = true
    }

    private fun completeIfReady(endpointId: String, exchange: Exchange): Boolean {
        if (!exchange.acknowledgementSent || !exchange.acknowledgementVerified) return false
        if (secureSessions.containsKey(endpointId)) return true
        val remote = exchange.remoteHello ?: return false
        val root = exchange.rootKey ?: return false
        secureSessions[endpointId] = SecurePeerSession(
            localDeviceId = localDeviceId,
            remoteDeviceId = remote.deviceId,
            remoteFingerprint = sha256(remote.signingPublicKey).toHex(),
            rootKey = root.copyOf()
        )
        return true
    }

    private fun sameHello(first: Hello, second: Hello): Boolean =
        MessageDigest.isEqual(first.signedBody(), second.signedBody()) &&
            MessageDigest.isEqual(first.signature, second.signature)

    private fun sameAck(first: Ack, second: Ack): Boolean =
        first.senderDeviceId == second.senderDeviceId &&
            MessageDigest.isEqual(first.transcriptHash, second.transcriptHash) &&
            MessageDigest.isEqual(first.mac, second.mac)

    private sealed interface HandshakePacket {
        data class HelloPacket(val hello: Hello) : HandshakePacket
        data class AckPacket(val ack: Ack) : HandshakePacket
    }

    companion object {
        private const val MAGIC = 0x4C4E4B48 // "LNKH"
        private const val VERSION = 1
        private const val HELLO = 1
        private const val ACK = 2
        private const val HEADER_SIZE = 6
        private const val MAX_PACKET_BYTES = 1024
        private const val NONCE_BYTES = 32
        private const val ACK_BYTES = 32
        private val ACK_DOMAIN = "LINKA-HANDSHAKE-ACK-v1".toByteArray(StandardCharsets.UTF_8)

        fun isHandshakePacket(bytes: ByteArray): Boolean =
            bytes.size >= HEADER_SIZE && ByteBuffer.wrap(bytes, 0, 4).int == MAGIC

        private fun encodeHello(hello: Hello): ByteArray = packet(HELLO) { output ->
            writeField(output, hello.deviceId.toByteArray(StandardCharsets.UTF_8), 64)
            writeField(output, hello.signingPublicKey, 256)
            writeField(output, hello.ephemeralPublicKey, 256)
            writeField(output, hello.nonce, NONCE_BYTES)
            writeField(output, hello.signature, 96)
        }

        private fun encodeAck(ack: Ack): ByteArray = packet(ACK) { output ->
            writeField(output, ack.senderDeviceId.toByteArray(StandardCharsets.UTF_8), 64)
            writeField(output, ack.transcriptHash, ACK_BYTES)
            writeField(output, ack.mac, ACK_BYTES)
        }

        private fun decodePacket(bytes: ByteArray): HandshakePacket {
            require(bytes.size in HEADER_SIZE..MAX_PACKET_BYTES) { "Secure-pairing packet size is invalid" }
            val input = DataInputStream(ByteArrayInputStream(bytes))
            require(input.readInt() == MAGIC) { "Secure-pairing packet signature is invalid" }
            require(input.readUnsignedByte() == VERSION) { "Secure-pairing protocol version is unsupported" }
            val type = input.readUnsignedByte()
            val message = when (type) {
                HELLO -> {
                    val id = String(readField(input, 64), StandardCharsets.UTF_8)
                    val signingKey = readField(input, 256)
                    val ephemeral = readField(input, 256)
                    val nonce = readField(input, NONCE_BYTES)
                    val signature = readField(input, 96)
                    require(id.isNotBlank() && signingKey.size in 64..256 &&
                        ephemeral.size in 64..256 && nonce.size == NONCE_BYTES &&
                        signature.size in 64..96
                    ) { "Secure identity announcement fields are invalid" }
                    HandshakePacket.HelloPacket(Hello(id, signingKey, ephemeral, nonce, signature))
                }
                ACK -> {
                    val sender = String(readField(input, 64), StandardCharsets.UTF_8)
                    val transcriptHash = readField(input, ACK_BYTES)
                    val mac = readField(input, ACK_BYTES)
                    require(sender.isNotBlank() && transcriptHash.size == ACK_BYTES && mac.size == ACK_BYTES) {
                        "Secure key confirmation fields are invalid"
                    }
                    HandshakePacket.AckPacket(Ack(sender, transcriptHash, mac))
                }
                else -> throw IllegalArgumentException("Unknown secure-pairing message type")
            }
            require(input.available() == 0) { "Secure-pairing packet contains trailing data" }
            return message
        }

        private fun packet(type: Int, write: (DataOutputStream) -> Unit): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeByte(VERSION)
                output.writeByte(type)
                write(output)
            }
            return bytes.toByteArray().also {
                require(it.size <= MAX_PACKET_BYTES) { "Secure-pairing packet is too large" }
            }
        }

        private fun writeField(output: DataOutputStream, value: ByteArray, maximum: Int) {
            require(value.isNotEmpty() && value.size <= maximum)
            output.writeShort(value.size)
            output.write(value)
        }

        private fun readField(input: DataInputStream, maximum: Int): ByteArray {
            val length = input.readUnsignedShort()
            require(length in 1..maximum) { "Secure-pairing field length is invalid" }
            return ByteArray(length).also(input::readFully)
        }

        private fun canonicalHello(
            deviceId: String,
            signingPublicKey: ByteArray,
            ephemeralPublicKey: ByteArray,
            nonce: ByteArray
        ): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { output ->
                output.write("LINKA-PEER-HELLO-v1".toByteArray(StandardCharsets.UTF_8))
                output.writeByte(VERSION)
                writeField(output, deviceId.toByteArray(StandardCharsets.UTF_8), 64)
                writeField(output, signingPublicKey, 256)
                writeField(output, ephemeralPublicKey, 256)
                writeField(output, nonce, NONCE_BYTES)
            }
            return bytes.toByteArray()
        }

        private fun canonicalTranscript(first: Hello, second: Hello): ByteArray {
            val ordered = listOf(first, second).sortedBy { it.deviceId }
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { output ->
                output.write("LINKA-PEER-TRANSCRIPT-v1".toByteArray(StandardCharsets.UTF_8))
                ordered.forEach { hello ->
                    writeField(output, hello.signedBody(), 768)
                    writeField(output, hello.signature, 96)
                }
            }
            return bytes.toByteArray()
        }

        private fun sha256(value: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(value)

        private fun hmacSha256(key: ByteArray, value: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(value)
            }

        private fun hkdf(
            inputKeyMaterial: ByteArray,
            salt: ByteArray,
            info: ByteArray,
            length: Int
        ): ByteArray {
            val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
            val prk = hmacSha256(actualSalt, inputKeyMaterial)
            val output = ByteArrayOutputStream()
            var previous = ByteArray(0)
            var counter = 1
            while (output.size() < length) {
                previous = hmacSha256(
                    prk,
                    previous + info + byteArrayOf(counter.toByte())
                )
                output.write(previous)
                counter++
                require(counter <= 256) { "HKDF output length is too large" }
            }
            prk.fill(0)
            previous.fill(0)
            return output.toByteArray().copyOf(length)
        }

        private fun ByteArray.toHex(): String =
            joinToString("") { "%02X".format(Locale.ROOT, it) }
    }
}

/** Authenticated keys for a single trusted direct peer connection. */
class SecurePeerSession internal constructor(
    private val localDeviceId: String,
    val remoteDeviceId: String,
    val remoteFingerprint: String,
    private val rootKey: ByteArray
) {
    private val controlChannel = payloadChannel("LINKA-CONTROL-v1")

    fun encryptControl(plaintext: ByteArray): ByteArray =
        controlChannel.encrypt(plaintext)

    fun decryptControl(packet: ByteArray): ByteArray =
        controlChannel.decrypt(packet)

    fun forCall(callId: String): SecureCallCipher {
        require(callId.isNotBlank() && callId.length <= 128)
        val salt = MessageDigest.getInstance("SHA-256")
            .digest(callId.toByteArray(StandardCharsets.UTF_8))
        val prefix = "LINKA-CALL-v1:$callId:"
        val send = deriveDirectionalKey(salt, prefix + localDeviceId)
        val receive = deriveDirectionalKey(salt, prefix + remoteDeviceId)
        return SecureCallCipher(send, receive, callId)
    }

    private fun payloadChannel(context: String): SecurePayloadChannel {
        val send = deriveDirectionalKey(ByteArray(0), "$context:$localDeviceId")
        val receive = deriveDirectionalKey(ByteArray(0), "$context:$remoteDeviceId")
        return SecurePayloadChannel(send, receive, context.toByteArray(StandardCharsets.UTF_8))
    }

    fun destroy() {
        controlChannel.destroy()
        rootKey.fill(0)
    }

    private fun deriveDirectionalKey(salt: ByteArray, label: String): ByteArray =
        hkdf(rootKey, salt, label.toByteArray(StandardCharsets.UTF_8), 32)

    companion object {
        private fun hmac(key: ByteArray, input: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(input)
            }

        private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
            val result = ByteArrayOutputStream()
            var previous = ByteArray(0)
            var counter = 1
            while (result.size() < length) {
                previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
                result.write(previous)
                counter++
                require(counter <= 256)
            }
            prk.fill(0)
            previous.fill(0)
            return result.toByteArray().copyOf(length)
        }
    }
}

/** AES-GCM framing with distinct send/receive keys and replay rejection. */
private class SecurePayloadChannel(
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray,
    private val context: ByteArray
) {
    private val sendSequence = AtomicLong(0)
    private var lastReceivedSequence = -1L
    @Volatile private var destroyed = false

    fun encrypt(plaintext: ByteArray): ByteArray {
        check(!destroyed) { "Secure payload channel has been destroyed" }
        require(plaintext.isNotEmpty() && plaintext.size <= MAX_PLAINTEXT_BYTES)
        val sequence = sendSequence.getAndIncrement()
        check(sequence >= 0) { "Secure sequence space exhausted" }
        val sequenceBytes = ByteBuffer.allocate(8).putLong(sequence).array()
        val cipherText = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"), GCMParameterSpec(128, nonce(sendKey, sequence)))
            updateAAD(context + sequenceBytes)
            doFinal(plaintext)
        }
        return sequenceBytes + cipherText
    }

    @Synchronized
    fun decrypt(packet: ByteArray): ByteArray {
        check(!destroyed) { "Secure payload channel has been destroyed" }
        require(packet.size in (8 + TAG_BYTES + 1)..(8 + TAG_BYTES + MAX_PLAINTEXT_BYTES)) {
            "Encrypted LINKA packet size is invalid"
        }
        val sequence = ByteBuffer.wrap(packet, 0, 8).long
        require(sequence >= 0 && sequence > lastReceivedSequence) {
            "Replayed or out-of-order LINKA packet rejected"
        }
        val sequenceBytes = packet.copyOfRange(0, 8)
        val encrypted = packet.copyOfRange(8, packet.size)
        val plaintext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(receiveKey, "AES"), GCMParameterSpec(128, nonce(receiveKey, sequence)))
            updateAAD(context + sequenceBytes)
            doFinal(encrypted)
        }
        lastReceivedSequence = sequence
        return plaintext
    }

    @Synchronized
    @Synchronized
    fun destroy() {
        if (destroyed) return
        destroyed = true
        sendKey.fill(0)
        receiveKey.fill(0)
        lastReceivedSequence = Long.MAX_VALUE
    }

    private fun nonce(key: ByteArray, sequence: Long): ByteArray {
        val prefix = MessageDigest.getInstance("SHA-256").digest(key).copyOf(4)
        return ByteBuffer.allocate(12).put(prefix).putLong(sequence).array()
    }

    companion object {
        private const val TAG_BYTES = 16
        private const val MAX_PLAINTEXT_BYTES = 8192
    }
}

/** Per-call caller-to-recipient encrypted voice-frame channel. */
class SecureCallCipher internal constructor(
    sendKey: ByteArray,
    receiveKey: ByteArray,
    callId: String
) {
    private val channel = SecurePayloadChannel(
        sendKey = sendKey,
        receiveKey = receiveKey,
        context = "LINKA-VOICE-FRAME-v1:$callId".toByteArray(StandardCharsets.UTF_8)
    )

    fun encryptFrame(plaintextFrame: ByteArray): ByteArray = channel.encrypt(plaintextFrame)
    fun decryptFrame(encryptedFrame: ByteArray): ByteArray = channel.decrypt(encryptedFrame)

    fun destroy() = channel.destroy()

    companion object {
        val ENCRYPTED_FRAME_BYTES: Int = 8 + VoiceFrameCodec.ENCODED_FRAME_BYTES + 16
    }
}
