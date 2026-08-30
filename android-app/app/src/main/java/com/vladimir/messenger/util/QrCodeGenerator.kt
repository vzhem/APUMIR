package com.vladimir.messenger.util

import android.graphics.Bitmap
import android.graphics.Color

object QrCodeGenerator {

    /**
     * Единственный генератор QR в приложении: приглашение, группа, экран
     * профиля, «Мой QR-код» и шаг регистрации рисуют код здесь, поэтому
     * плотность и поля у них одинаковые.
     *
     * Уровень коррекции L. Ссылка-приглашение несёт подписанный токен и
     * занимает 426 символов вместо прежних 77, поэтому код стал заметно плотнее.
     * Измерено на реальной ссылке (segno, те же байты): при H 105x105 модулей,
     * при Q 93, при M 81, при L 73; с полями ZXing MARGIN=1 это 107 / 95 / 83 /
     * 75. На 280.dp и 420 dpi модуль выходит 6.87 / 7.74 / 8.86 / 9.80 px —
     * то есть каждый шаг вниз по коррекции даёт камере заметно больше пикселей
     * на модуль. На экране код не рвётся и не пачкается, поэтому восстановление
     * повреждений здесь не нужно, а L даёт самый крупный модуль.
     *
     * @param size сторона битмапа в пикселях. 1024, а не 512: код показывается
     *   в 200-280.dp, что на 420 dpi даёт 525-735 px, и при 512 битмап
     *   растягивался в 1.44 раза билинейным фильтром, размывая границы модулей.
     *   1024 везде уменьшается, а не увеличивается, поэтому края остаются
     *   резкими. Памяти это 2 МБ на RGB_565, на экране один код за раз.
     * @return Bitmap или null при ошибке.
     */
    fun generateQrCode(content: String, size: Int = 1024): Bitmap? {
        if (content.isEmpty() || size <= 0) return null
        return try {
            val hints = mapOf(
                com.google.zxing.EncodeHintType.ERROR_CORRECTION to
                    com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L,
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
