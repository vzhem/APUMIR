package com.vladimir.messenger.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Чтение файлов с потолком по размеру и без загрузки лишнего в память.
 *
 * Зачем отдельный файл: аудит 2026-10-26 (п. 4.5) нашёл `readBytes()` на путях,
 * где размер файла никто не проверяет, — копия профиля, бэкап азбуки, секреты
 * file-exchange. На телефоне с 2 ГБ RAM такой файл — это `OutOfMemoryError`,
 * причём в самом неудачном месте: посреди восстановления профиля.
 *
 * Правило: если файл читается целиком, лимит объявляется рядом с вызовом и
 * проверяется ДО выделения памяти. Если массив не нужен (нужна только сумма
 * или нужно отдать байты в поток) — потоковая обработка вместо `readBytes()`.
 */

/** Файл (или поток) больше разрешённого: читать его целиком нельзя. */
class FileTooLargeException(
    val limitBytes: Long,
    val actualBytes: Long,
) : IOException("file is $actualBytes bytes, limit is $limitBytes")

/**
 * Прочитать файл целиком, но не больше [limitBytes].
 *
 * Размер сначала берётся из `length()` (быстрый отказ без выделения памяти), а
 * затем ещё раз проверяется во время чтения: файл может вырасти между этими
 * двумя моментами, и `length()` — не гарантия.
 */
fun File.readBytesAtMost(limitBytes: Long): ByteArray {
    val declared = length()
    if (declared > limitBytes) throw FileTooLargeException(limitBytes, declared)
    return inputStream().use { it.readBytesAtMost(limitBytes) }
}

/** То же для произвольного потока: читает кусками и падает, если лимит превышен. */
fun InputStream.readBytesAtMost(limitBytes: Long): ByteArray {
    val out = ByteArrayOutputStream(minOf(limitBytes, DEFAULT_BUFFER_SIZE.toLong()).toInt())
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > limitBytes) throw FileTooLargeException(limitBytes, total)
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/**
 * SHA-256 файла без чтения его в память.
 *
 * Раньше это делалось как `digest.digest(file.readBytes())`: для бэкапа азбуки
 * файл целиком оказывался в куче только ради 32 байт результата.
 */
fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Перелить [input] в [out], не превышая [limitBytes].
 *
 * Возвращает `false`, если лимит превышен; уже записанный хвост вызывающий
 * обязан удалить сам. Нужен там, где поток читается из сети или из файла
 * неизвестного размера и держать его целиком в памяти нельзя.
 */
fun copyWithLimit(
    input: InputStream,
    out: OutputStream,
    limitBytes: Long,
    bufferBytes: Int = DEFAULT_BUFFER_SIZE,
): Boolean {
    val buffer = ByteArray(bufferBytes)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return true
        total += read
        if (total > limitBytes) return false
        out.write(buffer, 0, read)
    }
}
