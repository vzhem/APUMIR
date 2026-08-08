package com.vladimir.messenger.util

import android.graphics.Bitmap
import android.graphics.Color

object QrCodeGenerator {

    /**
     * енерирует QR-код для публичного ключа.
     * спользует ZXing через зависимость.
     * озвращает Bitmap или null при ошибке.
     */
    fun generateQrCode(content: String, size: Int = 512): Bitmap? {
        return try {
            // спользуем ZXing для генерации QR
            val hints = mapOf(
                com.google.zxing.EncodeHintType.ERROR_CORRECTION to
                    com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H,
                com.google.zxing.EncodeHintType.MARGIN to 1
            )

            val writer = com.google.zxing.qrcode.QRCodeWriter()
            val bitMatrix = writer.encode(
                content,
                com.google.zxing.BarcodeFormat.QR_CODE,
                size,
                size,
                hints
            )

            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(
                        x, y,
                        if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
                    )
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * ормирует строку для QR-кода из публичного ключа
     * ормат: p2p://key/<base64_public_key>
     */
    fun buildContactUri(publicKey: String): String {
        return "p2p://key/$publicKey"
    }

    /**
     * арсит URI из QR-кода
     * озвращает публичный ключ или null
     */
    fun parseContactUri(uri: String): String? {
        return if (uri.startsWith("p2p://key/")) {
            uri.removePrefix("p2p://key/").trim()
        } else {
            null
        }
    }
}
