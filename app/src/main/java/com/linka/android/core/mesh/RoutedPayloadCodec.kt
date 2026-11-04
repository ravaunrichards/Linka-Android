package com.linka.android.core.mesh

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

enum class RoutedPayloadType(val wireValue: Int) {
    PEER_HANDSHAKE(1),
    CALL_CONTROL(2),
    VOICE_FRAME(3);

    companion object {
        fun fromWire(value: Int): RoutedPayloadType =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unknown LINKA routed payload type")
    }
}

/** Small bounded discriminator inside the mesh's end-to-end opaque payload. */
object RoutedPayloadCodec {
    const val MAX_PAYLOAD_BYTES = 700
    private const val MAGIC = 0x4C4E5250 // "LNRP"
    private const val VERSION = 1
    private const val HEADER_BYTES = 7

    data class Payload(val type: RoutedPayloadType, val bytes: ByteArray)

    fun encode(type: RoutedPayloadType, bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PAYLOAD_BYTES - HEADER_BYTES) {
            "Routed LINKA payload size is invalid"
        }
        return ByteBuffer.allocate(HEADER_BYTES + bytes.size)
            .putInt(MAGIC)
            .put(VERSION.toByte())
            .put(type.wireValue.toByte())
            .put(bytes.size.toByte())
            .put(bytes)
            .array()
    }

    fun decode(bytes: ByteArray): Payload {
        require(bytes.size in (HEADER_BYTES + 1)..MAX_PAYLOAD_BYTES) {
            "Routed LINKA payload size is invalid"
        }
        val input = ByteBuffer.wrap(bytes)
        require(input.int == MAGIC) { "Routed LINKA payload signature is invalid" }
        require(input.get().toInt() and 0xFF == VERSION) {
            "Routed LINKA payload version is unsupported"
        }
        val type = RoutedPayloadType.fromWire(input.get().toInt() and 0xFF)
        val length = input.get().toInt() and 0xFF
        require(length > 0 && length == input.remaining()) {
            "Routed LINKA payload length is invalid"
        }
        return Payload(type, ByteArray(length).also(input::get))
    }
}
