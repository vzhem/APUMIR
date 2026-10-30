package com.vladimir.messenger.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Владелец 2026-10-30: ответ сервиса принёс `apk_url` прежнего домена, и
 * обновление не скачивалось. Адрес обновления берём только свой.
 */
class UpdateLinksTest {

    private val host = "p2p-relay.apuchat.workers.dev"

    @Test
    fun `our own download link is used as is`() {
        val raw = "https://$host/update/apk"
        assertEquals(raw, UpdateLinks.apkDownloadUrl(raw))
    }

    @Test
    fun `a link from the previous domain is replaced`() {
        val stale = "https://p2p-relay.1985vzhem.workers.dev/update/apk"
        assertEquals("https://$host/update/apk", UpdateLinks.apkDownloadUrl(stale))
    }

    @Test
    fun `a foreign or insecure link is replaced`() {
        val fallback = "https://$host/update/apk"
        assertEquals(fallback, UpdateLinks.apkDownloadUrl("https://github.com/vzhem/APUMIR/releases"))
        assertEquals(fallback, UpdateLinks.apkDownloadUrl("http://$host/update/apk"))
        assertEquals(fallback, UpdateLinks.apkDownloadUrl("не ссылка"))
        assertEquals(fallback, UpdateLinks.apkDownloadUrl(""))
        assertEquals(fallback, UpdateLinks.apkDownloadUrl(null))
    }

    @Test
    fun `a lookalike host does not pass`() {
        assertEquals(
            "https://$host/update/apk",
            UpdateLinks.apkDownloadUrl("https://$host.example.org/update/apk"),
        )
        assertEquals(
            "https://$host/update/apk",
            UpdateLinks.apkDownloadUrl("https://evil-$host/update/apk"),
        )
    }

    @Test
    fun `the fallback follows the given root`() {
        // Чужой хост при своём корне: берём свой путь на переданном корне.
        assertEquals(
            "https://$host/update/apk",
            UpdateLinks.apkDownloadUrl("https://example.org/update/apk"),
        )
        assertEquals(
            "https://example.org/update/apk",
            UpdateLinks.apkDownloadUrl("", "https://example.org/"),
        )
    }
}
