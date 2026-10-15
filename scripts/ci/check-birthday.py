#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Контракт «Дня рождения»: дата в профиле, галочка видимости и пузырь у друзей.

Владелец 2026-10-15: «В профиле по желанию человек может поставить свою дату
рождения. Рядом поставить галочку видна дата рождения всем или скрыть от всех.
Также в день рождения у всех друзей появляется всплывающий вверху пузырь с
профилем у кого день рождения. Если человек скрыл дату то уведомление не
появляется у друзей».

Проверяются правила, которые ломаются незаметно:

* скрытая дата НЕ покидает телефон (рассылка не уходит вовсе), а при скрытии
  уходит отзыв — иначе у друзей дата осталась бы навсегда;
* год рождения по сети не едет (в конверте его нет);
* дата едет только своим контактам, без роевой эпидемии;
* ответ «представься» тоже уважает галочку (иначе он был бы лазейкой);
* пузырь показывается только по контактам и только тем, кто дату не скрыл;
* «×» скрывает именинника до конца дня, а в следующем году он снова виден;
* все новые строки есть и в RU, и в EN и все реально используются.

Это не компилятор и не телефон: типы и видимость проверяет сборка, поведение
на устройстве — прогон с двумя телефонами.
"""
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
UI = MAIN / "ui"
PROFILE = MAIN / "data/profile"

RU_STRINGS = (
    "settings_birthday",
    "settings_birthday_not_set",
    "settings_birthday_visible",
    "settings_birthday_visible_hint",
    "settings_birthday_hidden_hint",
    "birthday_dialog_title",
    "birthday_pick_date",
    "birthday_year_hint",
    "birthday_clear",
    "birthday_save",
    "birthday_banner_suffix",
    "birthday_banner_action",
    "birthday_banner_hide",
    "birthday_banner_more",
)


def source(path):
    return (ROOT / path).read_text(encoding="utf-8")


def kt(path):
    return (MAIN / path).read_text(encoding="utf-8")


def flat(text):
    """Текст без переносов: вызовы в коде разбиты на строки по ширине."""
    return re.sub(r"\s+", " ", text)


class BirthdayContractTest(unittest.TestCase):

    def test_wire_format_carries_day_and_month_but_never_the_year(self):
        wire = kt("data/group/GroupWire.kt")
        self.assertIn('const val KIND_BIRTHDAY = "bday"', wire)
        build = wire.split("fun buildBirthday(", 1)[1].split("\n", 1)[0]
        self.assertIn("day: Int", build)
        self.assertIn("month: Int", build)
        self.assertIn("updatedAtMs: Long", build)
        # Год рождения в конверт не попадает: строка собирается из day|month.
        body = wire.split("fun buildBirthday(", 1)[1].split("\n\n", 1)[0]
        self.assertIn('"$PREFIX|$KIND_BIRTHDAY|$ownerId|$day|$month|$updatedAtMs"', body)
        self.assertNotIn("year", body)
        # Разбор: своя ветка, строгая проверка существования даты и отзыва.
        self.assertIn("KIND_BIRTHDAY -> if (parts.size == 6) {", wire)
        self.assertIn("data class Birthday(", wire)
        branch = wire.split("KIND_BIRTHDAY ->", 1)[1].split("KIND_", 1)[0]
        self.assertIn("Birthday.isValid(day, month)", branch)
        self.assertIn("revoke", branch)

    def test_my_date_lives_in_profile_prefs_with_a_visibility_flag(self):
        store = (PROFILE / "BirthdayStore.kt").read_text(encoding="utf-8")
        self.assertIn('private const val PREFS = "p2p_prefs"', store)
        for key in ('KEY_DAY = "my_birthday_day"', 'KEY_MONTH = "my_birthday_month"',
                    'KEY_YEAR = "my_birthday_year"', 'KEY_VISIBLE = "my_birthday_visible"'):
            self.assertIn(key, store)
        self.assertIn("fun setVisible(", store)
        self.assertIn("fun isVisible(", store)
        self.assertIn("fun clear(", store)
        # Поток изменений: смена галочки должна запускать рассылку (или отзыв).
        self.assertIn("val mine: StateFlow<Birthday?>", store)

    def test_hidden_date_never_leaves_the_phone_and_revoke_is_sent(self):
        repo = kt("data/group/GroupRepository.kt")
        publish = repo.split("suspend fun publishMyBirthday(", 1)[1].split("\n    /**", 1)[0]
        # Скрытая дата: без force рассылки нет вообще.
        self.assertIn("val visible = birthday != null && birthday.visible && Birthday.isComplete(birthday)", publish)
        self.assertIn("if (!force && !visible) return", publish)
        # Скрытие — это отзыв (нулевые день и месяц), иначе дата осталась бы.
        self.assertIn("GroupWire.buildBirthday(ownerId = me, day = 0, month = 0", publish)
        # Только своим контактам, без эпидемии: получатели, а не пересылка.
        self.assertIn('delivery.deliver("birthdays", envelope, ids)', publish)
        self.assertNotIn("hops", publish)
        # Ответ «представься» уважает ту же галочку.
        self.assertIn("myBirthday()?.takeIf { it.visible && Birthday.isComplete(it) }", repo)
        # Чужой отзыв и чужая дата обрабатываются без пересылки дальше.
        handler = repo.split("private fun handleBirthday(", 1)[1].split("\n    }", 1)[0]
        self.assertIn("if (packet.ownerId == myId()) return", handler)
        self.assertIn("onPeerBirthday(", handler)
        self.assertNotIn("deliver(", handler)

    def test_repository_wiring_uses_the_profile_stores(self):
        module = kt("di/GroupsModule.kt")
        self.assertIn("BirthdayStore.read(context.applicationContext)", module)
        self.assertIn("PeerBirthdayStore.apply(", module)
        service = kt("service/CoreServerService.kt")
        self.assertIn("publishMyBirthday()", service)
        self.assertIn("publishMyBirthday(force = true)", service)
        self.assertIn("BirthdayStore.mine", flat(service))
        # Оба хранилища поднимаются при запуске приложения.
        activity = source("android-app/app/src/main/java/com/vladimir/messenger/MainActivity.kt")
        self.assertIn("BirthdayStore.init(this)", activity)
        self.assertIn("PeerBirthdayStore.init(this)", activity)

    def test_banner_is_for_contacts_and_respects_hiding(self):
        screen = kt("ui/screens/chat/ChatListScreen.kt")
        self.assertIn("com.vladimir.messenger.ui.components.BirthdayBannerRow(", screen)
        self.assertIn("BirthdayBanner.guests(", screen)
        # Только личные чаты: группу поздравлять нельзя, а имя берём из контакта.
        self.assertIn("items.filterIsInstance<InboxItem.Personal>()", screen)
        self.assertIn("birthdayContext", screen)
        self.assertIn(".dismiss(birthdayContext, guest.nodeId)", flat(screen))
        # Пузырь живёт в тех же разделах, что полоса историй и «Избранное».
        self.assertIn("val showBirthdayBanner = !isSearchActive &&", screen)
        self.assertIn("birthdayGuests.firstOrNull()", screen)

    def test_pure_rules_are_used_instead_of_inline_dates(self):
        banner = (PROFILE / "BirthdayBook.kt").read_text(encoding="utf-8")
        for marker in ("object BirthdayBook", "object BirthdayBanner", "fun guests(",
                       "fun apply(", "fun encode(", "fun decode(", "fun dismissKey("):
            self.assertIn(marker, banner)
        core = (PROFILE / "Birthday.kt").read_text(encoding="utf-8")
        for marker in ("fun isValid(", "fun celebrateDayIn(", "fun isToday(", "fun label(",
                       "NO_YEAR", "fun isComplete("):
            self.assertIn(marker, core)
        # Ключ скрытия содержит дату: иначе скрытый человек исчез бы навсегда.
        key = banner.split("fun dismissKey(", 1)[1].split("fun ", 1)[0]
        self.assertIn("${today.year}-${today.monthValue}-${today.dayOfMonth}", key)
        # Свои и чужие даты не смешиваются: у друзей отдельные настройки.
        peer = (PROFILE / "PeerBirthdayStore.kt").read_text(encoding="utf-8")
        self.assertIn('private const val PREFS = "apu_birthdays"', peer)
        self.assertIn("fun dismiss(", peer)

    def test_profile_row_has_the_switch_next_to_the_date(self):
        screen = kt("ui/screens/settings/SettingsScreen.kt")
        self.assertIn("R.string.settings_birthday", screen)
        self.assertIn(".setVisible(context, it)", flat(screen))
        self.assertIn("BirthdayStore.set(", flat(screen))
        self.assertIn("BirthdayStore.clear(context)", flat(screen))
        self.assertIn("com.vladimir.messenger.ui.components.BirthdayEditDialog(", flat(screen))
        # Переключатель рядом с датой в самом профиле, а не спрятан в другом экране.
        # Галочка стоит в той же строке, что и дата: до переключателя всё ещё
        # речь про день рождения, а не про другой пункт настроек.
        row = flat(screen).split("R.string.settings_birthday", 1)[1].split("ApuPremiumSwitch(", 1)[0]
        self.assertIn("birthday", row.lower())
        self.assertIn("Icons.Default.Cake", screen)
        # Окно выбора даты — фирменное, системный календарь открывается из него.
        dialog = (UI / "components/BirthdayUi.kt").read_text(encoding="utf-8")
        self.assertIn("ApuSettingsDialog(", dialog)
        self.assertIn("DatePickerDialog(", dialog)
        self.assertIn("Birthday.label(", dialog)

    def test_strings_exist_in_both_languages_and_are_all_used(self):
        used = ""
        for path in UI.rglob("*.kt"):
            used += path.read_text(encoding="utf-8")
        for locale in ("values", "values-en"):
            xml = source(f"android-app/app/src/main/res/{locale}/strings.xml")
            for name in RU_STRINGS:
                with self.subTest(locale=locale, string=name):
                    self.assertIn(f'<string name="{name}"', xml)
        for name in RU_STRINGS:
            with self.subTest(string=name):
                self.assertIn(f"R.string.{name}", used)

    def test_tests_and_notes_came_with_the_feature(self):
        tests = ROOT / "android-app/app/src/test/java/com/vladimir/messenger"
        core_test = (tests / "data/profile/BirthdayTest.kt").read_text(encoding="utf-8")
        wire_test = (tests / "data/group/BirthdayWireTest.kt").read_text(encoding="utf-8")
        # Главные правила владельца закрыты тестом, а не только кодом.
        self.assertIn("fun revokeRemovesTheDate()", core_test)
        self.assertIn("fun hiddenTodayGuestIsNotShownAndComesBackNextYear()", core_test)
        self.assertIn("fun onlyFriendsWithTodaysBirthdayAreShown()", core_test)
        self.assertIn("fun envelopeHasNoYearField()", wire_test)
        self.assertIn("fun malformedBirthdayIsRejected()", wire_test)
        notes = source("docs/AI_COLLABORATION_NOTES.md")
        self.assertIn("День рождения в профиле и пузырь-поздравление у друзей", notes)

    def test_nothing_is_sent_without_a_date_or_a_contact(self):
        repo = kt("data/group/GroupRepository.kt")
        publish = repo.split("suspend fun publishMyBirthday(", 1)[1].split("\n    /**", 1)[0]
        # Пустой список контактов — не повод слать хоть что-то.
        self.assertIn("val ids = contactIds()", publish)
        self.assertIn("if (ids.isEmpty()) return", publish)
        screen = kt("ui/screens/chat/ChatListScreen.kt")
        # Без дат у друзей вообще не считаем список: лишней работы на кадр нет.
        self.assertIn("if (showBirthdayBanner && birthdays.isNotEmpty())", screen)
        # Регексп-страховка: в UI не появилось «ручного» сравнения месяца с датой.
        self.assertNotRegex(screen, re.compile(r"monthValue\s*==\s*LocalDate\.now\("))


if __name__ == "__main__":
    unittest.main(verbosity=2)
