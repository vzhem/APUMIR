package com.vladimir.messenger.data.profile

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * День рождения: правила даты, книга чужих дат и решение «кого поздравлять».
 *
 * Всё это чистые функции без Android, поэтому проверяются здесь без телефона —
 * включая случай «человек скрыл дату»: скрытая дата не должна ни показываться,
 * ни оставаться у друзей после отзыва.
 */
class BirthdayTest {

    @Test
    fun onlyRealDatesAreAccepted() {
        assertTrue(Birthday.isValid(1, 1))
        assertTrue(Birthday.isValid(31, 12))
        assertTrue(Birthday.isValid(29, 2)) // 29 февраля существует
        assertFalse(Birthday.isValid(30, 2))
        assertFalse(Birthday.isValid(31, 4))
        assertFalse(Birthday.isValid(0, 6))
        assertFalse(Birthday.isValid(12, 0))
        assertFalse(Birthday.isValid(12, 13))
    }

    @Test
    fun yearIsOptionalAndNeverFromTheFuture() {
        assertTrue(Birthday.isValidYear(Birthday.NO_YEAR, 2026))
        assertTrue(Birthday.isValidYear(1900, 2026))
        assertTrue(Birthday.isValidYear(2026, 2026))
        assertFalse(Birthday.isValidYear(2027, 2026))
        assertFalse(Birthday.isValidYear(1899, 2026))
    }

    @Test
    fun leapDayIsCelebratedOnFebruary28InCommonYears() {
        assertEquals(29, Birthday.celebrateDayIn(29, 2, 2024))
        assertEquals(28, Birthday.celebrateDayIn(29, 2, 2025))
        assertEquals(28, Birthday.celebrateDayIn(29, 2, 1900)) // 1900 не високосный
        assertEquals(29, Birthday.celebrateDayIn(29, 2, 2000)) // 2000 високосный (правило 400 лет)
        assertEquals(12, Birthday.celebrateDayIn(12, 6, 2025))
    }

    @Test
    fun isTodayMatchesDayAndMonth() {
        assertTrue(Birthday.isToday(15, 10, LocalDate.of(2026, 10, 15)))
        assertFalse(Birthday.isToday(15, 10, LocalDate.of(2026, 10, 16)))
        assertFalse(Birthday.isToday(14, 10, LocalDate.of(2026, 10, 15)))
        // 29 февраля в невисокосный год поздравляют 28-го.
        assertTrue(Birthday.isToday(29, 2, LocalDate.of(2025, 2, 28)))
        assertFalse(Birthday.isToday(29, 2, LocalDate.of(2025, 3, 1)))
        assertTrue(Birthday.isToday(29, 2, LocalDate.of(2024, 2, 29)))
        // Мусорная дата не совпадает ни с чем.
        assertFalse(Birthday.isToday(0, 0, LocalDate.of(2026, 10, 15)))
    }

    @Test
    fun labelShowsTheYearOnlyWhenItIsKnown() {
        val russian = Locale("ru")
        // Название месяца берём у платформы: подпись не должна зависеть от
        // того, какие имена месяцев знает конкретная сборка Android.
        val monthDay = LocalDate.of(2024, 6, 12)
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMMM", russian))
        assertEquals(monthDay, Birthday.label(12, 6, Birthday.NO_YEAR, russian))
        assertEquals("$monthDay 1990", Birthday.label(12, 6, 1990, russian))
        // 29 февраля подписывается без ошибки, хотя такого дня в обычном году нет.
        assertTrue(Birthday.label(29, 2, Birthday.NO_YEAR, russian).startsWith("29"))
        // Битый месяц не превращается в мусорную подпись.
        assertEquals("", Birthday.label(0, 0, Birthday.NO_YEAR, russian))
    }

    @Test
    fun completeDateNeedsARealDayAndMonth() {
        assertTrue(Birthday.isComplete(Birthday(12, 6)))
        assertFalse(Birthday.isComplete(null))
        assertFalse(Birthday.isComplete(Birthday(0, 0)))
        assertFalse(Birthday.isComplete(Birthday(31, 4)))
    }

    // ── Книга чужих дат ─────────────────────────────────────────────────────

    @Test
    fun announcementIsStoredUnderLowercasedNode() {
        val book = BirthdayBook.apply(emptyMap(), "Node-A", day = 12, month = 6, updatedAtMs = 100, nowMs = 100)
        assertEquals(PeerBirthday(12, 6, 100), book["node-a"])
    }

    @Test
    fun olderAnnouncementDoesNotOverwriteTheNewerOne() {
        val first = BirthdayBook.apply(emptyMap(), "node-a", 12, 6, 200, nowMs = 200)
        val stale = BirthdayBook.apply(first, "node-a", 1, 1, 100, nowMs = 300)
        assertEquals(PeerBirthday(12, 6, 200), stale["node-a"])
        // Равная метка — повтор рассылки, значение не меняется.
        val repeat = BirthdayBook.apply(first, "node-a", 1, 1, 200, nowMs = 300)
        assertEquals(PeerBirthday(12, 6, 200), repeat["node-a"])
    }

    /** Человек закрыл галочку: дата уходит с телефонов друзей, а не остаётся. */
    @Test
    fun revokeRemovesTheDate() {
        val stored = BirthdayBook.apply(emptyMap(), "node-a", 12, 6, 100, nowMs = 100)
        val revoked = BirthdayBook.apply(stored, "node-a", 0, 0, 200, nowMs = 200)
        assertTrue(revoked.isEmpty())
        // Отзыв без записи ничего не ломает.
        assertTrue(BirthdayBook.apply(emptyMap(), "node-a", 0, 0, 200, nowMs = 200).isEmpty())
        // Старый отзыв не стирает более свежую дату.
        assertFalse(BirthdayBook.apply(stored, "node-a", 0, 0, 50, nowMs = 200).isEmpty())
    }

    @Test
    fun junkAnnouncementsAreIgnored() {
        val empty = emptyMap<String, PeerBirthday>()
        assertTrue(BirthdayBook.apply(empty, "", 12, 6, 100, nowMs = 100).isEmpty())
        assertTrue(BirthdayBook.apply(empty, "node-a", 32, 6, 100, nowMs = 100).isEmpty())
        assertTrue(BirthdayBook.apply(empty, "node-a", 12, 6, -5, nowMs = 100).isEmpty())
        assertTrue(BirthdayBook.apply(empty, "node-a", 30, 2, 100, nowMs = 100).isEmpty())
    }

    @Test
    fun bookIsBoundedAndDropsTheOldestEntry() {
        var book = emptyMap<String, PeerBirthday>()
        val total = BirthdayBook.MAX_ENTRIES + 8
        for (i in 0 until total) {
            book = BirthdayBook.apply(book, "peer-$i", 12, 6, updatedAtMs = i.toLong(), nowMs = i.toLong())
        }
        assertEquals(BirthdayBook.MAX_ENTRIES, book.size)
        assertTrue(book.containsKey("peer-${total - 1}")) // свежая запись на месте
        assertFalse(book.containsKey("peer-0")) // самая старая вытеснена
    }

    @Test
    fun bookSurvivesSaveAndLoad() {
        val book = BirthdayBook.apply(emptyMap(), "Node-A", 29, 2, 1_700_000_000_000L, nowMs = 0)
        val restored = BirthdayBook.decode(BirthdayBook.encode(book))
        assertEquals(book, restored)
    }

    @Test
    fun damagedBookIsSkippedLineByLine() {
        val text = listOf(
            "node-a\t12\t6\t100",
            "node-b\t12", // мало полей
            "node-c\tдень\t6\t100", // не число
            "node-d\t32\t6\t100", // несуществующая дата
            "node-e\t12\t6\t-1", // отрицательная метка
            "\t12\t6\t100", // нет узла
            "node-f\t1\t1\t100",
        ).joinToString("\n")
        val book = BirthdayBook.decode(text)
        assertEquals(setOf("node-a", "node-f"), book.keys)
        assertTrue(BirthdayBook.decode(null).isEmpty())
        assertTrue(BirthdayBook.decode("").isEmpty())
    }

    // ── Кого поздравлять ────────────────────────────────────────────────────

    private val today = LocalDate.of(2026, 10, 15)

    @Test
    fun onlyFriendsWithTodaysBirthdayAreShown() {
        val book = mapOf(
            "node-a" to PeerBirthday(15, 10, 1),
            "node-b" to PeerBirthday(16, 10, 1),
            "node-c" to PeerBirthday(15, 10, 1),
        )
        val contacts = listOf(
            "node-a" to "Алёна",
            "node-b" to "Игорь",
            "node-z" to "Незнакомец", // даты нет
        )
        val guests = BirthdayBanner.guests(book, contacts, today, dismissed = emptySet())
        assertEquals(listOf("Алёна"), guests.map { it.name })
    }

    @Test
    fun hiddenTodayGuestIsNotShownAndComesBackNextYear() {
        val book = mapOf("node-a" to PeerBirthday(15, 10, 1))
        val contacts = listOf("node-a" to "Алёна")
        val hidden = setOf(BirthdayBanner.dismissKey("node-a", today))
        assertTrue(BirthdayBanner.guests(book, contacts, today, hidden).isEmpty())
        // В следующем году тот же человек снова в списке.
        val nextYear = today.plusYears(1)
        val stillHidden = BirthdayBanner.guests(book, contacts, nextYear, hidden)
        assertEquals(listOf("Алёна"), stillHidden.map { it.name })
    }

    @Test
    fun guestsAreDedupedAndOrderedByName() {
        val book = mapOf(
            "node-a" to PeerBirthday(15, 10, 1),
            "node-b" to PeerBirthday(15, 10, 1),
        )
        val contacts = listOf(
            "node-b" to "Борис",
            "node-a" to "Алёна",
            "node-b" to "Борис", // тот же чат дважды (двойники контакта)
        )
        val guests = BirthdayBanner.guests(book, contacts, today, emptySet())
        assertEquals(listOf("Алёна", "Борис"), guests.map { it.name })
        // Узел в гостях — без регистра: по нему ищут аватар и чат.
        assertEquals(listOf("node-a", "node-b"), guests.map { it.nodeId })
    }

    @Test
    fun emptyInputsGiveNoBanner() {
        assertTrue(BirthdayBanner.guests(emptyMap(), listOf("a" to "А"), today, emptySet()).isEmpty())
        assertTrue(BirthdayBanner.guests(mapOf("a" to PeerBirthday(15, 10, 1)), emptyList(), today, emptySet()).isEmpty())
    }

    @Test
    fun dismissKeyIgnoresNodeCaseButKeepsTheDate() {
        assertEquals(
            BirthdayBanner.dismissKey("Node-A", today),
            BirthdayBanner.dismissKey("node-a", today),
        )
        assertFalse(
            BirthdayBanner.dismissKey("node-a", today) ==
                BirthdayBanner.dismissKey("node-a", today.plusDays(1)),
        )
    }

    @Test fun futureBirthDateIsRejected() {
        val today = java.time.LocalDate.of(2026, 10, 19)
        assertTrue(Birthday.isFuture(20, 10, 2026, today))
        assertTrue(Birthday.isFuture(1, 1, 2027, today))
        assertFalse(Birthday.isFuture(19, 10, 2026, today))
        assertFalse(Birthday.isFuture(19, 10, Birthday.NO_YEAR, today))
        assertFalse(Birthday.isValidBirthDate(31, 12, 2026, today))
        assertTrue(Birthday.isValidBirthDate(19, 10, 2026, today))
        assertTrue(Birthday.isValidBirthDate(29, 2, Birthday.NO_YEAR, today))
    }
}
