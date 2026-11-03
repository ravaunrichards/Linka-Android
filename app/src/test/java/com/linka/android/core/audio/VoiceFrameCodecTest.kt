package com.linka.android.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.abs

class VoiceFrameCodecTest {
    @Test
    fun silenceEncodesAndDecodesWithoutOffset() {
        val pcm = ShortArray(VoiceFrameCodec.SAMPLES_PER_FRAME)
        val decoded = MuLawCodec.decode(MuLawCodec.encode(pcm))
        assertEquals(0, decoded.average().toInt())
    }

    @Test
    fun frameRoundTripsWithExpectedSizeAndSequence() {
        val pcm = ShortArray(VoiceFrameCodec.SAMPLES_PER_FRAME) { index ->
            (10_000.0 * kotlin.math.sin(index * 2.0 * Math.PI / 40.0)).toInt().toShort()
        }
        val encoded = VoiceFrameCodec.encode(42, pcm)

        assertEquals(VoiceFrameCodec.ENCODED_FRAME_BYTES, encoded.size)
        val decoded = VoiceFrameCodec.decode(encoded)
        assertEquals(42L, decoded.sequence)
        assertEquals(pcm.size, decoded.pcm.size)

        val averageAbsoluteError = pcm.indices
            .map { abs(pcm[it].toInt() - decoded.pcm[it].toInt()) }
            .average()
        assert(averageAbsoluteError < 1_200.0)
    }

    @Test
    fun streamReaderReadsFramesUntilCleanEof() {
        val output = ByteArrayOutputStream()
        val pcm = ShortArray(VoiceFrameCodec.SAMPLES_PER_FRAME) { 1234 }
        VoiceFrameCodec.write(output, 1, pcm)
        VoiceFrameCodec.write(output, 2, pcm)

        val input = ByteArrayInputStream(output.toByteArray())
        assertEquals(1L, VoiceFrameCodec.read(input)?.sequence)
        assertEquals(2L, VoiceFrameCodec.read(input)?.sequence)
        assertNull(VoiceFrameCodec.read(input))
    }

    @Test
    fun rejectsInvalidFrameSignatureAndSize() {
        val pcm = ShortArray(VoiceFrameCodec.SAMPLES_PER_FRAME)
        val encoded = VoiceFrameCodec.encode(0, pcm)
        val invalid = encoded.copyOf().also { it[0] = 0 }

        assertThrows(IllegalArgumentException::class.java) {
            VoiceFrameCodec.decode(invalid)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceFrameCodec.decode(encoded.copyOf(encoded.size - 1))
        }
    }

    @Test
    fun rejectsNegativeSequenceAndWrongSampleCount() {
        assertThrows(IllegalArgumentException::class.java) {
            VoiceFrameCodec.encode(-1, ShortArray(VoiceFrameCodec.SAMPLES_PER_FRAME))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceFrameCodec.encode(0, ShortArray(10))
        }
    }

    @Test
    fun rejectsTruncatedStreamFrame() {
        val pcm = ShortArray(VoiceFrameCodec.SAMPLES_PER_FRAME)
        val encoded = VoiceFrameCodec.encode(10, pcm)
        assertThrows(IOException::class.java) {
            VoiceFrameCodec.read(ByteArrayInputStream(encoded.copyOf(encoded.size - 5)))
        }
    }
}
