package com.vladimir.messenger.data.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Обёртка запечатанного сообщения.
 *
 * Сама криптография живёт в ядре и проверяется его тестами; здесь —
 * распознавание формата и разбор, потому что ошибка тут либо превратит
 * переписку в мусор, либо (хуже) заставит принять испорченный конверт.
 */
class SealedWireTest {

    private fun sample(
        keyEnvelope: ByteArray = ByteArray(200) { (it % 251).toByte() },
        iv: ByteArray = ByteArray(12) { it.toByte() },
        ciphertext: ByteArray = ByteArray(128) { (255 - it % 256).toByte() },
    ) = SealedWire.encode(keyEnvelope, iv, ciphertext)

    @Test
    fun `round trip preserves all three parts`() {
        val keyEnvelope = ByteArray(200) { (it % 251).toByte() }
        val iv = ByteArray(12) { it.toByte() }
        val ciphertext = ByteArray(128) { (255 - it % 256).toByte() }

        val parts = SealedWire.decode(SealedWire.encode(keyEnvelope, iv, ciphertext))

        assertTrue(parts != null)
        assertArrayEquals(keyEnvelope, parts!!.keyEnvelope)
        assertArrayEquals(iv, parts.iv)
        assertArrayEquals(ciphertext, parts.ciphertext)
    }

    @Test
    fun `plain chat text is not mistaken for an envelope`() {
        assertFalse(SealedWire.isSealed("Привет, как дела?"))
        assertFalse(SealedWire.isSealed(""))
        assertNull(SealedWire.decode("Привет, как дела?"))
    }

    @Test
    fun `other apu protocols are not mistaken for envelopes`() {
        // Групповые, реакции и файловые пакеты идут тем же транспортом.
        assertFalse(SealedWire.isSealed("APUGRP1|msg|payload"))
        assertFalse(SealedWire.isSealed("APUREACT1|like"))
        assertFalse(SealedWire.isSealed("apu-file1|offer"))
        assertFalse(SealedWire.isSealed("APUREF1|attr|2|x"))
    }

    @Test
    fun `wrong field count is rejected`() {
        assertNull(SealedWire.decode(SealedWire.PREFIX))
        assertNull(SealedWire.decode(SealedWire.PREFIX + "AAAA"))
        assertNull(SealedWire.decode(SealedWire.PREFIX + "AAAA|BBBB"))
        assertNull(SealedWire.decode(SealedWire.PREFIX + "AAAA|BBBB|CCCC|DDDD"))
    }

    @Test
    fun `corrupt base64 is rejected without throwing`() {
        assertNull(SealedWire.decode(SealedWire.PREFIX + "!!!|@@@|###"))
    }

    @Test
    fun `wrong iv length is rejected`() {
        // Короткий вектор — признак порчи или чужого формата.
        val bad = SealedWire.encode(ByteArray(64) { 1 }, ByteArray(8) { 2 }, ByteArray(32) { 3 })
        assertNull(SealedWire.decode(bad))
    }

    @Test
    fun `empty parts are rejected`() {
        assertNull(SealedWire.decode(SealedWire.encode(ByteArray(0), ByteArray(12), ByteArray(32))))
        assertNull(SealedWire.decode(SealedWire.encode(ByteArray(64), ByteArray(12), ByteArray(0))))
    }

    @Test
    fun `oversized wire is rejected`() {
        assertNull(SealedWire.decode(SealedWire.PREFIX + "A".repeat(200 * 1024)))
    }

    @Test
    fun `binary payloads with all byte values survive`() {
        val ciphertext = ByteArray(256) { it.toByte() }
        val parts = SealedWire.decode(sample(ciphertext = ciphertext))
        assertArrayEquals(ciphertext, parts!!.ciphertext)
    }

    @Test
    fun `envelope carries no node ids`() {
        // Раунд 80: манифест раньше включал имена узлов, а один и тот же узел
        // может называться 32- или 64-символьным видом ключа. На пути через
        // ретранслятор приходил другой вид, ключ не совпадал и сообщение молча
        // не открывалось. Формат обязан оставаться независимым от имён узлов.
        val wire = sample()
        assertFalse(wire.contains("pk_"))
    }

    @Test
    fun `bootstrap protocols must stay distinguishable from envelopes`() {
        // Эти два разбираются транспортом ДО расшифровки, поэтому обязаны
        // уходить открытыми и не опознаваться как конверт:
        //   apu-file-hello1 - несёт сам ключ шифрования;
        //   APULAN1         - адрес для поднятия прямого канала.
        // Раунд 81: APULAN1 запечатывался, прямой канал не поднимался, и связь
        // работала только в одну сторону.
        assertFalse(SealedWire.isSealed("apu-file-hello1|AAAA"))
        assertFalse(SealedWire.isSealed("APULAN1|req|192.168.1.5|48610"))
        assertFalse(SealedWire.isSealed("APULAN1|offer|192.168.1.5|48610"))
    }

    @Test
    fun `prefix is stable`() {
        // Смена префикса рассинхронизирует телефоны — фиксируем значение.
        assertEquals("APUSEAL1|", SealedWire.PREFIX)
    }
}
