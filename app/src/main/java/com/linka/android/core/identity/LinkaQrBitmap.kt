package com.linka.android.core.identity

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object LinkaQrBitmap {
    fun create(payload: String, size: Int = 640): Bitmap {
        require(payload.isNotBlank() && payload.length <= 2_048)
        require(size in 256..1_024)

        val matrix = QRCodeWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
        )

        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val rowOffset = y * size
            for (x in 0 until size) {
                pixels[rowOffset + x] = if (matrix[x, y]) {
                    android.graphics.Color.BLACK
                } else {
                    android.graphics.Color.WHITE
                }
            }
        }
        return Bitmap.createBitmap(
            pixels,
            size,
            size,
            Bitmap.Config.ARGB_8888
        )
    }
}
