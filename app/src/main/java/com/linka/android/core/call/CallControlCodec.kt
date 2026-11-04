package com.linka.android.core.call

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

enum class CallControlType(val wireValue: Int) {
    INVITE(1),
    ACCEPT(2),
    REJECT(3),
    END(4),
    BUSY(5);

    companion object {
        fun fromWireValue(value: Int): CallControlType =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unknown call-control message type")
    }
}

data class CallControlMessage(
    val type: CallControlType,
    val callId: String,
    val senderDeviceId: String
)

/**
 * Small, versioned, bounded binary envelope for control messages.
 *
 * This codec provides framing and validation only. The call controller
 * authenticates the sender against the QR-trusted peer identity, then protects
 * this envelope with directional AES-GCM keys from the peer session. Routed
 * controls use the same end-to-end keys inside ciphertext-only mesh packets.
 */
object CallControlCodec {
    private const val MAGIC = 0x4C4E4B41 // "LNKA"
    private const val VERSION = 1
    private const val MAX_IDENTIFIER_BYTES = 64
    private const val HEADER_BYTES = 8
    private const val MAX_MESSAGE_BYTES = HEADER_BYTES + (MAX_IDENTIFIER_BYTES * 2)

    fun encode(message: CallControlMessage): ByteArray {
        validateIdentifier(message.callId, "callId")
        validateIdentifier(message.senderDeviceId, "senderDeviceId")

        val callId = message.callId.toByteArray(StandardCharsets.UTF_8)
        val senderId = message.senderDeviceId.toByteArray(StandardCharsets.UTF_8)
        val output = ByteBuffer.allocate(HEADER_BYTES + callId.size + senderId.size)
            .order(ByteOrder.BIG_ENDIAN)

        output.putInt(MAGIC)
        output.put(VERSION.toByte())
        output.put(message.type.wireValue.toByte())
        output.put(callId.size.toByte())
        output.put(senderId.size.toByte())
        output.put(callId)
        output.put(senderId)
        return output.array()
    }

    fun decode(bytes: ByteArray): CallControlMessage {
        require(bytes.size in HEADER_BYTES..MAX_MESSAGE_BYTES) { "Invalid call-control message size" }

        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(input.int == MAGIC) { "Invalid call-control message signature" }
        require(input.get().toInt() and 0xFF == VERSION) { "Unsupported call-control protocol version" }

        val type = CallControlType.fromWireValue(input.get().toInt() and 0xFF)
        val callIdLength = input.get().toInt() and 0xFF
        val senderLength = input.get().toInt() and 0xFF
        require(callIdLength in 1..MAX_IDENTIFIER_BYTES) { "Invalid call ID length" }
        require(senderLength in 1..MAX_IDENTIFIER_BYTES) { "Invalid sender ID length" }
        require(input.remaining() == callIdLength + senderLength) { "Unexpected call-control payload length" }

        val callId = decodeIdentifier(ByteArray(callIdLength).also(input::get), "callId")
        val senderId = decodeIdentifier(ByteArray(senderLength).also(input::get), "senderDeviceId")
        return CallControlMessage(type, callId, senderId)
    }

    private fun decodeIdentifier(bytes: ByteArray, field: String): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val value = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        validateIdentifier(value, field)
        return value
    }

    private fun validateIdentifier(value: String, field: String) {
        val encodedSize = value.toByteArray(StandardCharsets.UTF_8).size
        require(value.isNotBlank() && encodedSize <= MAX_IDENTIFIER_BYTES) {
            "Invalid $field"
        }
        require(value.all { it.isLetterOrDigit() || it in "._:-" }) {
            "Invalid characters in $field"
        }
    }
}
