package com.linka.android.core.audio

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DecodedVoiceFrame(
    val sequence: Long,
    val pcm: ShortArray
)

/**
 * LINKA narrowband voice frame format:
 * magic(4), version(1), codec(1), sequence(8), payloadLength(2), G.711 data.
 *
 * At 8 kHz, each frame contains 160 samples (20 ms). The fixed frame size
 * bounds memory and gives the stream reader deterministic framing.
 */
object VoiceFrameCodec {
    const val SAMPLE_RATE_HZ = 8_000
    const val FRAME_DURATION_MS = 20
    const val SAMPLES_PER_FRAME = SAMPLE_RATE_HZ * FRAME_DURATION_MS / 1_000

    private const val MAGIC = 0x4C4E5646 // "LNVF"
    private const val VERSION = 1
    private const val CODEC_MU_LAW = 1
    private const val HEADER_BYTES = 16
    private const val COMPRESSED_FRAME_BYTES = SAMPLES_PER_FRAME
    const val ENCODED_FRAME_BYTES = HEADER_BYTES + COMPRESSED_FRAME_BYTES

    fun encode(sequence: Long, pcm: ShortArray): ByteArray {
        require(sequence >= 0) { "Voice frame sequence must be non-negative" }
        require(pcm.size == SAMPLES_PER_FRAME) { "Voice frames must contain exactly 20 ms of 8 kHz audio" }

        val compressed = MuLawCodec.encode(pcm)
        return ByteBuffer.allocate(ENCODED_FRAME_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC)
            .put(VERSION.toByte())
            .put(CODEC_MU_LAW.toByte())
            .putLong(sequence)
            .putShort(compressed.size.toShort())
            .put(compressed)
            .array()
    }

    fun decode(bytes: ByteArray): DecodedVoiceFrame {
        require(bytes.size == ENCODED_FRAME_BYTES) { "Invalid voice frame size" }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(input.int == MAGIC) { "Invalid voice frame signature" }
        require(input.get().toInt() and 0xFF == VERSION) { "Unsupported voice frame version" }
        require(input.get().toInt() and 0xFF == CODEC_MU_LAW) { "Unsupported voice codec" }

        val sequence = input.long
        require(sequence >= 0) { "Invalid voice frame sequence" }
        val length = input.short.toInt() and 0xFFFF
        require(length == COMPRESSED_FRAME_BYTES && input.remaining() == length) {
            "Invalid voice frame payload length"
        }

        val compressed = ByteArray(length)
        input.get(compressed)
        return DecodedVoiceFrame(sequence, MuLawCodec.decode(compressed))
    }

    fun write(output: OutputStream, sequence: Long, pcm: ShortArray) {
        output.write(encode(sequence, pcm))
        output.flush()
    }

    /**
     * Reads one complete frame. Returns null only when the stream ends before
     * any byte of a new frame; truncated headers or frames are rejected.
     */
    @Throws(IOException::class)
    fun read(input: InputStream): DecodedVoiceFrame? {
        val data = DataInputStream(input)
        val first = input.read()
        if (first == -1) return null

        val header = ByteArray(HEADER_BYTES)
        header[0] = first.toByte()
        try {
            data.readFully(header, 1, HEADER_BYTES - 1)
        } catch (error: EOFException) {
            throw IOException("Truncated voice frame header", error)
        }

        val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val magic = headerBuffer.int
        val version = headerBuffer.get().toInt() and 0xFF
        val codec = headerBuffer.get().toInt() and 0xFF
        val sequence = headerBuffer.long
        val length = headerBuffer.short.toInt() and 0xFFFF

        require(magic == MAGIC) { "Invalid voice frame signature" }
        require(version == VERSION) { "Unsupported voice frame version" }
        require(codec == CODEC_MU_LAW) { "Unsupported voice codec" }
        require(sequence >= 0) { "Invalid voice frame sequence" }
        require(length == COMPRESSED_FRAME_BYTES) { "Invalid voice frame payload length" }

        val compressed = ByteArray(length)
        try {
            data.readFully(compressed)
        } catch (error: EOFException) {
            throw IOException("Truncated voice frame payload", error)
        }
        return DecodedVoiceFrame(sequence, MuLawCodec.decode(compressed))
    }
}
