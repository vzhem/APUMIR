package com.vladimir.messenger.util

import android.graphics.Bitmap
import android.graphics.Color

object QrCodeGenerator {

    /**
     * Единственный генератор QR в приложении: приглашение, группа, экран
     * профиля, «Мой QR-код» и шаг регистрации рисуют код здесь, поэтому
     * плотность и поля у них одинаковые.
     *
     * Уровень коррекции M, а не H. Ссылка-приглашение несёт подписанный токен и
     * занимает ~430 символов вместо прежних ~50, поэтому код и так стал плотнее:
     * при H это ~101x101 модулей, при M ~81x81, при L ~65x65. На экране телефона
     * код не рвётся и не пачкается, а 15% восстановления M с запасом покрывают
     * блики и расфокус, тогда как H отъедал четверть площади впустую.
     *
     * @return Bitmap или null при ошибке.
     */
    fun generateQrCode(content: String, size: Int = 512): Bitmap? {
        if (content.isEmpty() || size <= 0) return null
        return try {
            val hints = mapOf(
                com.google.zxing.EncodeHintType.ERROR_CORRECTION to
                    com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
                com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
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
