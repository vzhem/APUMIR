#!/usr/bin/env python3
"""Контракт плашки «этот телефон молчит» (владелец 2026-10-15).

Run: python3 scripts/ci/check-mute-notice.py

Зачем. Сводки владельца показали телефон, который сутками не мог ни отправить,
ни вскрыть личное (ключ не читается из Keystore), и при этом на экране не было
ни слова о причине: истории просто ждали получателя. Эта проверка держит
разводку, которая объясняет человеку происходящее, - и ловит её откат:

* причина считается ТОЛЬКО из настоящих строк установки ключей до старта ядра
  (`signing_install_before_core`/`file_exchange_install_before_core`), а не из
  догадки в UI;
* плашка показывается из общего состояния `IdentityHealth`, а не рисуется
  локально в списке чатов;
* у неё есть действие «восстановить профиль из файла» и путь навигации к нему;
* строки есть в обеих локалях и все использованы (нет мёртвых строк);
* чистое правило (первый запуск, «не читается», «не успел») компилируется и
  проверяется на runner без Android SDK - это файлы из `check-chat-history.sh`.

Компиляции Kotlin и проверки на телефоне здесь нет: их делают
`scripts/ci/check-android-compile.sh`, CI и владелец.
"""
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
RES = ROOT / "android-app/app/src/main/res"
TEST = ROOT / "android-app/app/src/test/java/com/vladimir/messenger"

HEALTH = MAIN / "data/security/IdentityHealth.kt"
ROW = MAIN / "ui/components/IdentityMuteUi.kt"
CHAT_LIST = MAIN / "ui/screens/chat/ChatListScreen.kt"
SERVICE = MAIN / "service/CoreServerService.kt"
NAV = MAIN / "ui/navigation/NavGraph.kt"
RUNNER = ROOT / "scripts/ci/check-chat-history.sh"
HEALTH_TEST = TEST / "data/security/IdentityHealthTest.kt"

NEW_STRINGS = (
    "identity_mute_title_unreadable",
    "identity_mute_body_unreadable",
    "identity_mute_title_timeout",
    "identity_mute_body_timeout",
    "identity_mute_action",
    "identity_mute_hide",
)


def source(path):
    return path.read_text(encoding="utf-8")


class MuteNoticeContracts(unittest.TestCase):
    def test_state_is_fed_by_the_real_boot_words(self):
        """Причина берётся из установки ключей до старта ядра, а не из догадки."""
        service = source(SERVICE)
        self.assertIn("IdentityHealth", service)
        call = re.search(
            r"IdentityHealth\s*\n?\s*\.noteBoot\(\s*(\w+)\s*,\s*(\w+)\s*\)",
            service,
        )
        self.assertIsNotNone(call, "CoreServerService не сообщает состояние IdentityHealth")
        signing, exchange = call.group(1), call.group(2)
        for name in (signing, exchange):
            self.assertTrue(
                re.search(rf"val {name} = when \{{", service),
                f"{name} должен считаться из результата установки, а не задаваться числом",
            )

    def test_classifier_is_pure_and_lives_with_the_rule(self):
        health = source(HEALTH)
        for token in ("enum class Reason", "UNREADABLE", "TIMEOUT", "NO_IDENTITY",
                      "fun classify(", "fun noteBoot(", "fun notice(", "StateFlow"):
            self.assertIn(token, health, f"в IdentityHealth нет {token}")
        # Никаких ключей и Android: правило обязано остаться проверяемым на JVM.
        for token in ("KeyStore", "Cipher", "android.", "Context"):
            self.assertNotIn(token, health, f"IdentityHealth не должен знать про {token}")

    def test_chat_list_shows_the_reason_it_received(self):
        chat_list = source(CHAT_LIST)
        self.assertIn("IdentityHealth.state", chat_list, "список чатов не подписан на состояние")
        self.assertIn("IdentityHealth.notice(", chat_list, "список чатов не спрашивает причину")
        self.assertIn("IdentityMuteRow(", chat_list, "плашка не показана в списке чатов")

    def test_row_has_the_action_and_no_stock_widgets(self):
        row = source(ROW)
        self.assertIn("identity_mute_action", row, "у плашки нет действия")
        self.assertIn("onRestore", row, "плашка не ведёт к восстановлению")
        self.assertIn("identity_mute_hide", row, "плашку нельзя скрыть до перезапуска")
        for banned in ("AlertDialog(", "TextButton(", "OutlinedTextField("):
            self.assertNotIn(banned, row, f"стоковый элемент {banned} в премиальной плашке")

    def test_restore_screen_is_reachable_from_it(self):
        nav = source(NAV)
        self.assertIn("onRestoreProfileClick", nav)
        self.assertIn("Screen.ProfileBackup.route", nav)

    def test_strings_exist_in_both_locales_and_are_used(self):
        used = source(ROW) + source(CHAT_LIST)
        for name in NEW_STRINGS:
            self.assertIn(name, used, f"строка {name} нигде не используется")
            for locale, path in (("values", RES / "values/strings.xml"),
                                 ("values-en", RES / "values-en/strings.xml")):
                self.assertIn(
                    f'name="{name}"',
                    source(path),
                    f"нет строки {name} в {locale}",
                )

    def test_pure_rule_is_compiled_and_run_without_android(self):
        runner = source(RUNNER)
        self.assertIn("IdentityHealth.kt", runner, "правило не компилируется на runner")
        self.assertIn("IdentityHealthTest", runner, "тест правила не запускается на runner")
        self.assertTrue(HEALTH_TEST.is_file(), "нет JVM-теста состояния")
        test = source(HEALTH_TEST)
        for case in ("skipped_no_identity", "unavailable", "timeout"):
            self.assertIn(case, test, f"тест не покрывает случай {case}")


if __name__ == "__main__":
    unittest.main(verbosity=2)
    sys.exit(0)
