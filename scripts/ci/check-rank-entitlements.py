#!/usr/bin/env python3
"""Контракт рангов: «до третьего ранга — минимум, дальше — по списку».

Почему это проверяется отдельным контрактом. Ранговые ограничения ломаются не
сразу, а тихо: кто-то добавляет кнопку «поделиться геолокацией» прямо в экран,
кто-то проверяет ранг только в UI, а deep link открывает то же действие мимо
кнопки. Тогда в приложении оказывается два разных правила, и владелец видит
«у меня закрыто, а у друга открыто».

Что проверяется:

  1) пороги собраны В ОДНОМ месте (`data/rank/FeatureRankPolicy.kt`), и
     `FileTransferRankPolicy` не держит собственных чисел: он берёт порог из
     `FeatureRankPolicy`/`RankCapability`;
  2) ключевые ограничения проверяются НЕ ТОЛЬКО в Compose, но и там, где
     действие реально начинается (ViewModel/сервис): вложения, геолокация,
     звонки, истории, видео;
  3) звонки не ограничены при приёме — входящий звонок нельзя «потерять» из-за
     ранга вызывающего или своего;
  4) тексты отказов берутся из одного ресурса (`rank_feature_locked`), а не
     пишутся в коде экрана по-русски;
  5) ключ есть и в русском, и в английском `strings.xml` (иначе в английской
     локали отказ останется русским).

Запуск:
    python3 scripts/ci/check-rank-entitlements.py
Код возврата: 0 — в порядке, 1 — нарушение.
"""
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
RES = ROOT / "android-app/app/src/main/res"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class RankEntitlementsContract(unittest.TestCase):
    def setUp(self):
        self.policy = read(JAVA / "data/rank/FeatureRankPolicy.kt")
        self.file_policy = read(JAVA / "data/file/FileTransferRankPolicy.kt")

    # 1) один источник порогов ------------------------------------------------
    def test_thresholds_live_in_one_capability_list(self):
        for name, threshold in (
            ("SEND_PHOTO", 3),
            ("SEND_VOICE", 3),
            ("SEND_GIF", 3),
            ("SEND_STICKER", 3),
            ("SEND_DOCUMENT", 3),
            ("SHARE_LOCATION", 3),
            ("PUBLISH_STORY", 3),
            ("CALL_VOICE", 3),
            ("CALL_VIDEO", 3),
            ("CREATE_POLL", 3),
            ("SEND_VIDEO", 10),
            ("CREATE_GROUP", 10),
            ("CREATE_CHANNEL", 30),
        ):
            self.assertRegex(
                self.policy,
                rf"{name}\({threshold}\)",
                f"{name} must open at rank {threshold} in FeatureRankPolicy",
            )
        # Минимум до третьего ранга остаётся бесплатным навсегда: иначе ранг
        # нечем заработать и нечем пользоваться свежей установке.
        for name in ("SEND_TEXT", "RECEIVE_MEDIA", "JOIN_COMMUNITIES", "HEART_GAME", "SHARE_CONTACT"):
            self.assertRegex(self.policy, rf"{name}\(0\)", f"{name} must stay free at rank 0")

    def test_attachment_policy_does_not_hardcode_ranks_again(self):
        self.assertIn("FeatureRankPolicy.CIRCLE_RANK", self.file_policy)
        self.assertIn("RankCapability.SEND_VIDEO.minimumQualifiedReferrals", self.file_policy)
        self.assertIn("RankFeatureSummary.describe(", self.file_policy)
        # Старое правило «>= 3» вручную — это второй источник правды: ранг
        # обязан приходить из FeatureRankPolicy, а не быть числом в файле.
        self.assertIsNone(
            re.search(r"minimumQualifiedReferrals >= 3\b", self.file_policy),
            "the circle threshold must come from FeatureRankPolicy.CIRCLE_RANK",
        )
        # Создание сообществ тоже берёт порог из списка возможностей.
        for symbol in ("RankCapability.CREATE_GROUP", "RankCapability.CREATE_CHANNEL",
                       "RankCapability.PROXY_MANUAL", "RankCapability.PROXY_AUTOMATIC"):
            self.assertIn(symbol, self.file_policy)

    # 2) проверка не только в Compose ----------------------------------------
    def test_gates_are_enforced_outside_compose_too(self):
        # Вложения: подготовка файла в сервисе и в рое — вторая линия обороны.
        self.assertIn("FileTransferRankPolicy.requireCanSend(",
                      read(JAVA / "data/file/OutgoingFilePreparationService.kt"))
        self.assertIn("FileTransferRankPolicy.requireCanSend(",
                      read(JAVA / "data/group/GroupFileSwarm.kt"))
        # Личный чат: скрепка, голос, геолокация, видео.
        chat_vm = read(JAVA / "ui/screens/chat/ChatDetailViewModel.kt")
        for symbol in ("RankRights(", "isVideoLocked(", "gifAllowed()", "SHARE_LOCATION"):
            self.assertIn(symbol, chat_vm)
        # Группа: видео и геолокация проверяются в ViewModel, не только в UI.
        group_vm = read(JAVA / "ui/screens/groups/GroupChatViewModel.kt")
        for symbol in ("RankCapability.SEND_VIDEO", "RankCapability.SHARE_LOCATION"):
            self.assertIn(symbol, group_vm)
        # Звонки: ворота стоят в менеджере звонков, а не только на кнопке.
        self.assertIn("CallRankGate.rejection(", read(JAVA / "data/call/CallManager.kt"))
        # Истории: публикация проверяется в ViewModel списка чатов.
        self.assertIn("StoryRankGate.canPublish(", read(JAVA / "ui/screens/chat/ChatListViewModel.kt"))

    # 3) приём и входящие звонки не ограничены --------------------------------
    def test_incoming_sides_are_never_rank_limited(self):
        calls = read(JAVA / "data/call/CallManager.kt")
        self.assertIn("canReceiveAtAnyRank", self.file_policy)
        # Входящий звонок не проходит через CallRankGate: только startOutgoing.
        gate_uses = [line for line in calls.splitlines() if "CallRankGate.rejection(" in line]
        self.assertEqual(len(gate_uses), 1, "rank gate must guard only the outgoing call")
        self.assertIn("private fun rejectByRank(", calls)
        self.assertNotIn("CallRankGate.rejection(appContext, video) ?: run", calls)

    # 4) тексты отказов — из одного ресурса ----------------------------------
    def test_refusals_come_from_one_string_resource(self):
        self.assertIn("R.string.rank_feature_locked", read(JAVA / "data/rank/RankEntitlementText.kt"))
        in_ui = []
        for path in (JAVA / "ui").rglob("*.kt"):
            text = read(path)
            if "rank_feature_locked" in text:
                in_ui.append(path.name)
        self.assertEqual(in_ui, [], f"UI must call RankEntitlementText, not build the phrase: {in_ui}")

    # 5) ключ есть в обеих локалях -------------------------------------------
    def test_refusal_key_exists_in_both_locales(self):
        ru = read(RES / "values/strings.xml")
        en = read(RES / "values-en/strings.xml")
        for name in ("rank_feature_locked",):
            for text, where in ((ru, "values"), (en, "values-en")):
                self.assertEqual(
                    len(re.findall(rf'<string name="{name}"', text)), 1,
                    f"{name} must exist exactly once in {where}",
                )
        ru_match = re.search(r'<string name="rank_feature_locked">(.*?)</string>', ru, re.S)
        en_match = re.search(r'<string name="rank_feature_locked">(.*?)</string>', en, re.S)
        placeholders_ru = sorted(re.findall(r"%\d+\$[ds]", ru_match.group(1)))
        placeholders_en = sorted(re.findall(r"%\d+\$[ds]", en_match.group(1)))
        self.assertEqual(placeholders_ru, placeholders_en,
                         "translation must keep the same placeholders as the original")


if __name__ == "__main__":
    unittest.main(verbosity=2)
