package com.vladimir.messenger.data.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Видеоаватар в живом зеркале профиля (запрос владельца 2026-11-06).
 *
 * Договор тот же, что у дня рождения: ролик, подпись и форма входят в снимок
 * профиля со своей меткой времени, а ОБЩИЙ отпечаток («pd» в hello/hb) про них
 * не знает — иначе устройство на старой сборке расходилось бы с новым вечно и
 * они гоняли бы полный снимок профиля каждые 10 секунд. У видеоаватара свой
 * отпечаток «pv».
 *
 * Только чистая арифметика: `toJson`/`fromJson` не трогаем — `org.json` в
 * JVM-тестах Android-модуля не работает («not mocked»), а чтение ключей с
 * умолчаниями стережёт `scripts/ci/check-video-avatar.py`.
 */
class ProfileMirrorVideoAvatarTest {

    private fun state(
        sha: String = "",
        name: String = "",
        bytes: Long = 0L,
        caption: String = "",
        circle: Boolean = true,
        stamp: Long = 0L,
    ) = ProfileMirror.State(
        name = "Владимир",
        nick = "vladimir",
        theme = "auto",
        avName = "avatar_cropped.jpg",
        avSha = "a".repeat(64),
        wallName = "",
        wallSha = "",
        tName = 1L,
        tNick = 1L,
        tTheme = 1L,
        tAv = 1L,
        tWall = 1L,
        vavName = name,
        vavSha = sha,
        vavBytes = bytes,
        vavCaption = caption,
        vavCircle = circle,
        tVav = stamp,
    )

    private val withVideo = state(sha = "b".repeat(64), name = "video_avatar_1.mp4", bytes = 3_000_000L)

    @Test
    fun sharedProfileFingerprintKnowsNothingAboutTheVideoAvatar() {
        // Старая сборка считает «pd» без видеоаватара: расхождения быть не должно.
        assertEquals(state().digest(), withVideo.digest())
        assertEquals(
            state().digest(),
            withVideo.copy(vavCaption = "привет", vavCircle = false, tVav = 9L).digest(),
        )
    }

    @Test
    fun videoAvatarHasItsOwnFingerprint() {
        assertEquals(state().videoDigest(), state().copy(vavCaption = "пусто").videoDigest())
        assertNotEquals(state().videoDigest(), withVideo.videoDigest())
        assertNotEquals(withVideo.videoDigest(), withVideo.copy(vavCaption = "подпись").videoDigest())
        assertNotEquals(withVideo.videoDigest(), withVideo.copy(vavCircle = false).videoDigest())
        assertNotEquals(withVideo.videoDigest(), withVideo.copy(vavSha = "c".repeat(64)).videoDigest())
        // Одинаковое видео на двух устройствах — одинаковый отпечаток.
        assertEquals(withVideo.videoDigest(), withVideo.copy(tVav = 42L).videoDigest())
    }

    @Test
    fun videoKeyIsEmptyWithoutTheClipSoAnOldPartnerMatches() {
        assertEquals("", state().videoKey())
        assertEquals("", state(caption = "осталась подпись", circle = false).videoKey())
        assertTrue(withVideo.videoKey().startsWith("b".repeat(64) + "|"))
    }

    @Test
    fun divergenceIsNamedInDiagnostics() {
        assertTrue("видеоаватар" in state().contentDiff(withVideo))
        assertTrue("видеоаватар" in withVideo.contentDiff(withVideo.copy(vavCircle = false)))
        assertEquals(emptyList<String>(), withVideo.contentDiff(withVideo.copy(tVav = 7L)))
    }

    @Test
    fun transferAsksForTheClipByNameHashAndSize() {
        val transfer = ProfileMirror.transferOf(withVideo, ProfileMirror.FIELD_VAV)
        assertEquals("video_avatar_1.mp4", transfer?.first)
        assertEquals("b".repeat(64), transfer?.second)
        assertEquals(3_000_000L, transfer?.third)
        // У партнёра без видео запрашивать нечего.
        assertNull(ProfileMirror.transferOf(state(), ProfileMirror.FIELD_VAV))
        // Прежние виды не сломались: аватар и обои берутся из своих полей.
        assertEquals("avatar_cropped.jpg", ProfileMirror.transferOf(state(), ProfileMirror.FIELD_AV)?.first)
    }

    @Test
    fun videoCeilingIsTheExportBudgetNotTheImageOne() {
        assertEquals(24L * 1024 * 1024, ProfileMirror.VIDEO_MAX_BYTES)
        assertTrue(ProfileMirror.IMAGE_MAX_BYTES < ProfileMirror.VIDEO_MAX_BYTES)
    }
}
