package com.linka.android.core.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RoutedPayloadCodecTest {
    @Test
    fun roundTripsEveryRoutedPayloadType() {
        val bytes = ByteArray(180) { (it * 11).toByte() }
        RoutedPayloadType.entries.forEach { type ->
            val encoded = RoutedPayloadCodec.encode(type, bytes)
            val decoded = RoutedPayloadCodec.decode(encoded)
            assertEquals(type, decoded.type)
            assertArrayEquals(bytes, decoded.bytes)
        }
    }

    @Test
    fun supportsSignedHandshakePayloadsLargerThan255Bytes() {
        val handshake = ByteArray(340) { (it * 13).toByte() }
        val encoded = RoutedPayloadCodec.encode(RoutedPayloadType.PEER_HANDSHAKE, handshake)
        val decoded = RoutedPayloadCodec.decode(encoded)
        assertEquals(RoutedPayloadType.PEER_HANDSHAKE, decoded.type)
        assertArrayEquals(handshake, decoded.bytes)
    }

    @Test
    fun rejectsEmptyAndOversizedPayloads() {
        assertThrows(IllegalArgumentException::class.java) {
            RoutedPayloadCodec.encode(RoutedPayloadType.CALL_CONTROL, ByteArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RoutedPayloadCodec.encode(
                RoutedPayloadType.VOICE_FRAME,
                ByteArray(RoutedPayloadCodec.MAX_PAYLOAD_BYTES)
            )
        }
    }

    @Test
    fun rejectsTamperingUnknownTypesAndInvalidLength() {
        val valid = RoutedPayloadCodec.encode(
            RoutedPayloadType.VOICE_FRAME,
            ByteArray(200) { it.toByte() }
        )

        val invalidMagic = valid.copyOf().also { it[0] = 0 }
        assertThrows(IllegalArgumentException::class.java) {
            RoutedPayloadCodec.decode(invalidMagic)
        }

        val unknownType = valid.copyOf().also { it[5] = 99 }
        assertThrows(IllegalArgumentException::class.java) {
            RoutedPayloadCodec.decode(unknownType)
        }

        val invalidLength = valid.copyOf().also { it[6] = 10 }
        assertThrows(IllegalArgumentException::class.java) {
            RoutedPayloadCodec.decode(invalidLength)
        }
    }
}
