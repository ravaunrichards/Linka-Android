package com.linka.android.core.audio

/**
 * ITU-T G.711 mu-law companding for low-bitrate narrowband speech.
 * Input/output is signed 16-bit linear PCM and one byte per sample.
 */
object MuLawCodec {
    private const val BIAS = 0x84
    private const val CLIP = 32635

    fun encode(pcm: ShortArray): ByteArray =
        ByteArray(pcm.size) { index -> encodeSample(pcm[index]).toByte() }

    fun decode(encoded: ByteArray): ShortArray =
        ShortArray(encoded.size) { index -> decodeSample(encoded[index].toInt() and 0xFF).toShort() }

    fun encodeSample(sample: Short): Int {
        var magnitude = sample.toInt()
        val sign = if (magnitude < 0) {
            magnitude = -magnitude
            0x80
        } else {
            0
        }

        magnitude = magnitude.coerceAtMost(CLIP) + BIAS
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && (magnitude and mask) == 0) {
            exponent--
            mask = mask ushr 1
        }

        val mantissa = (magnitude ushr (exponent + 3)) and 0x0F
        return (sign or (exponent shl 4) or mantissa).inv() and 0xFF
    }

    fun decodeSample(encoded: Int): Int {
        val value = encoded.inv() and 0xFF
        val sign = value and 0x80
        val exponent = (value ushr 4) and 0x07
        val mantissa = value and 0x0F
        var magnitude = ((mantissa shl 3) + BIAS) shl exponent
        magnitude -= BIAS
        return if (sign != 0) -magnitude else magnitude
    }
}
