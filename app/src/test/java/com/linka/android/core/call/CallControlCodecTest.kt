package com.linka.android.core.call

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CallControlCodecTest {
    @Test
    fun everyControlTypeRoundTrips() {
        CallControlType.entries.forEach { type ->
            val original = CallControlMessage(type, "call-123", "device-456")
            assertEquals(original, CallControlCodec.decode(CallControlCodec.encode(original)))
        }
    }

    @Test
    fun utf8IdentifiersRoundTripWithinByteLimit() {
        val original = CallControlMessage(
            CallControlType.INVITE,
            "call-æ",
            "device-東京"
        )
        assertEquals(original, CallControlCodec.decode(CallControlCodec.encode(original)))
    }

    @Test
    fun encodedMessageHasStableSignatureAndVersion() {
        val bytes = CallControlCodec.encode(
            CallControlMessage(CallControlType.END, "call-1", "device-1")
        )
        assertArrayEquals(byteArrayOf(0x4C, 0x4E, 0x4B, 0x41, 1, 4), bytes.copyOfRange(0, 6))
    }

    @Test
    fun rejectsInvalidSignatureAndTrailingBytes() {
        val valid = CallControlCodec.encode(
            CallControlMessage(CallControlType.ACCEPT, "call-1", "device-1")
        )
        val badMagic = valid.copyOf().also { it[0] = 0 }
        assertThrows(IllegalArgumentException::class.java) {
            CallControlCodec.decode(badMagic)
        }

        val trailing = valid + byteArrayOf(1)
        assertThrows(IllegalArgumentException::class.java) {
            CallControlCodec.decode(trailing)
        }
    }

    @Test
    fun rejectsUnsupportedVersionAndOversizedIdentifiers() {
        val valid = CallControlCodec.encode(
            CallControlMessage(CallControlType.BUSY, "call-1", "device-1")
        )
        val wrongVersion = valid.copyOf().also { it[4] = 2 }
        assertThrows(IllegalArgumentException::class.java) {
            CallControlCodec.decode(wrongVersion)
        }

        assertThrows(IllegalArgumentException::class.java) {
            CallControlCodec.encode(
                CallControlMessage(CallControlType.INVITE, "x".repeat(65), "device-1")
            )
        }
    }

    @Test
    fun rejectsUnknownWireType() {
        val valid = CallControlCodec.encode(
            CallControlMessage(CallControlType.BUSY, "call-1", "device-1")
        )
        val unknown = valid.copyOf().also { it[5] = 99 }
        assertThrows(IllegalArgumentException::class.java) {
            CallControlCodec.decode(unknown)
        }
    }
}
