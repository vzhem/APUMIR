package com.vladimir.messenger.util

import com.vladimir.messenger.data.link.ServiceHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Что попадает в QR-код «Мой код».
 *
 * Главное требование владельца (2026-11-19, скрин «Мой код»): «Нужно чтобы
 * когда показывал qr код, у кого нет приложения — чтобы сразу скачивал с
 * официального сайта приложение и устанавливало. Так как тут показываешь другу
 * лично».
 *
 * Значит в коде обязан лежать адрес, который откроет КАМЕРА телефона без APU.
 * Собственная схема `apu://a/…` для такой камеры не адрес вовсе — на ней
 * приложение и спотыкалось: пока сервис коротких ссылок не ответил, в QR ехал
 * `apu://`, и друг не получал ничего.
 */
class OfficialSiteTest {

    private val node = "pk_" + "ab".repeat(16)
    private val contact = ApuLink.build(node, "server10")!!

    @Test
    fun `qr is always an https address a camera can open`() {
        assertTrue(OfficialSite.qrLink(null, contact).startsWith("https://"))
        assertTrue(OfficialSite.qrLink(null, null).startsWith("https://"))
        assertTrue(OfficialSite.qrLink("", "").startsWith("https://"))
    }

    @Test
    fun `без сервиса в qr едет официальный сайт, а не apu-схема`() {
        val link = OfficialSite.qrLink(null, contact)
        // Это и было поломкой: прежний код отдавал сюда self-made `apu://a/…`.
        assertFalse("в QR не должно быть схемы приложения", link.startsWith("apu://"))
        assertTrue(link.startsWith(OfficialSite.URL))
    }

    @Test
    fun `код контакта не теряется на странице сайта`() {
        // Установив APU, друг наводит на тот же код сканером - контакт должен
        // добавиться, иначе человек скачал приложение и остался ни с чем.
        val link = OfficialSite.qrLink(null, contact)
        val restored = OfficialSite.contactFromQr(link)
        assertEquals(contact, restored)
        assertEquals(node, InviteLinkParser.parse(restored)?.nodeId)
    }

    @Test
    fun `никнейм тоже доезжает до приложения`() {
        val link = OfficialSite.qrLink(null, contact)
        assertEquals("server10", InviteLinkParser.parse(OfficialSite.contactFromQr(link))?.username)
    }

    @Test
    fun `проверенная короткая ссылка лучше сайта`() {
        // Страница /s/<код> ведёт сразу на добавление контакта: у кого APU
        // стоит, Android откроет приложение без лишнего шага.
        val short = "https://" + ServiceHost.HOST + "/s/AbCdEfGhJk"
        assertEquals(short, OfficialSite.qrLink(short, contact))
        assertTrue(OfficialSite.isServicePage(short))
    }

    @Test
    fun `чужая https-ссылка в qr не попадает`() {
        // Экран берёт адрес из нескольких источников; чужой адрес в коде -
        // это утечка человека на посторонний сайт.
        // Свой код `apu://` в этот список НЕ входит: он не «чужой», а
        // правильный код контакта - из него и строится адрес сайта.
        for (foreign in listOf(
            "https://example.com/s/AbCdEfGhJk",
            "https://" + ServiceHost.HOST + ".evil.test/s/AbCdEfGhJk",
            "http://" + ServiceHost.HOST + "/s/AbCdEfGhJk",
            "https://t.me/p2p_messenger_relay_bot?start=" + node,
        )) {
            assertFalse(foreign, OfficialSite.isServicePage(foreign))
            // Чужой адрес в код не попадает: остаётся страница сайта (с кодом
            // контакта, он-то как раз правильный), а не присланная ссылка.
            val inQr = OfficialSite.qrLink(foreign, contact)
            assertTrue(inQr.startsWith(OfficialSite.URL + "/"))
            assertNotEquals(foreign, inQr)
        }
    }

    @Test
    fun `кривой код контакта не ломает страницу`() {
        // Ничего лишнего в адрес сайта не дописываем: неизвестную запись
        // отбрасываем, отдаём чистый сайт.
        for (bad in listOf("apu://a/", "apu://a/short", "apu://a/$node/x/y", "apu://a/$node/плохой")) {
            assertEquals("кривой код не должен менять адрес: $bad",
                OfficialSite.URL, OfficialSite.qrLink(null, bad))
        }
    }

    @Test
    fun `страница сервиса без пути не считается страницей`() {
        assertFalse(OfficialSite.isServicePage("https://" + ServiceHost.HOST))
        assertFalse(OfficialSite.isServicePage("https://" + ServiceHost.HOST + "?x=1"))
        assertFalse(OfficialSite.isServicePage(null))
        assertNull(OfficialSite.contactFromQr("https://example.com/?apu=a/xyz"))
    }

    @Test
    fun `адрес сайта не прокручивает страницу к якорю`() {
        // Якорь `#…` браузер встречает прокруткой, и кнопка «Скачать» уезжает
        // с первого экрана - ровно туда, куда смотрит друг.
        assertFalse(OfficialSite.qrLink(null, contact).contains("#"))
    }
}
