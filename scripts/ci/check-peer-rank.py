#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Контракт элиты APU: ранг собеседника по сети, знак VIP и золотое кольцо.

Задачи владельца:
* 2026-10-07, «Да, сделай передачу ранга» — знак VIP у имён собеседников;
* 2026-10-07, «Нужно чтобы ранг 10 Проводник уже был VIP. Также чтобы аккаунт VIP
  должно быть видно везде и в группах и в каналах. Вокруг Аватарки должно быть
  объёмное золотое кольцо с иногда проходящим блеском».

Проверяется по исходникам (телефона и Android в песочнице нет):

* проводной конверт `APURANK1` строго разбирается и едет в обёртке «печатает…» —
  старые сборки её проглатывают, а не показывают строку в переписке;
* конверт поглощается приёмником ДО истории чата, заявленный узел сверяется с
  отправителем, запись идёт только «вперёд»;
* ранги живут в ОДНОЙ таблице на все узлы (`peer_ranks`) — иначе знак не
  появился бы у того, кого нет в адресной книге, а «видно везде» этого требует;
* порог элиты один на всё приложение и равен десятке («Проводник»);
* знак и кольцо стоят у имён и аватарок: список чатов, личный чат, группы,
  каналы, участники группы и свой профиль;
* кольцо объёмное и с блеском, но анимация живёт только у VIP-аватарок;
* свой ранг уходит контактам, участникам своих групп и подписчикам каналов.

Запуск: `python3 scripts/ci/check-peer-rank.py`; код возврата 0 — контракт цел.
"""
import re
import unittest
from pathlib import Path

# Основной контракт проверяется по исходникам; адресации по узлам в песочнице
# нет (телефона и Android SDK здесь тоже нет).

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
WIRE = (KOTLIN / "data/rank/RankWire.kt").read_text()
ROUTER = (KOTLIN / "data/rank/PeerRankRouter.kt").read_text()
STORE = (KOTLIN / "data/rank/PeerRankStore.kt").read_text()
BROADCASTER = (KOTLIN / "data/rank/PeerRankBroadcaster.kt").read_text()
NODE_IDS = (KOTLIN / "util/NodeIds.kt").read_text()
TYPING = (KOTLIN / "data/typing/TypingWire.kt").read_text()
CORE_SERVICE = (KOTLIN / "service/CoreServerService.kt").read_text()
PEER_ENTITY = (KOTLIN / "data/local/entity/PeerRankEntity.kt").read_text()
PEER_DAO = (KOTLIN / "data/local/dao/PeerRankDao.kt").read_text()
CONTACT_ENTITY = (KOTLIN / "data/local/entity/ContactEntity.kt").read_text()
CONTACT_DAO = (KOTLIN / "data/local/dao/ContactDao.kt").read_text()
DATABASE = (KOTLIN / "data/local/AppDatabase.kt").read_text()
POLICY = (KOTLIN / "data/file/FileTransferRankPolicy.kt").read_text()
RING = (KOTLIN / "ui/components/ApuVipRing.kt").read_text()
MY_AVATAR = (KOTLIN / "ui/components/MyAvatar.kt").read_text()
CONTACT_CARD = (KOTLIN / "ui/components/ContactCard.kt").read_text()
PROFILE_SHEET = (KOTLIN / "ui/components/PeerProfileSheet.kt").read_text()
CHAT_LIST = (KOTLIN / "ui/screens/chat/ChatListScreen.kt").read_text()
CHAT_LIST_VM = (KOTLIN / "ui/screens/chat/ChatListViewModel.kt").read_text()
CHAT_DETAIL = (KOTLIN / "ui/screens/chat/ChatDetailScreen.kt").read_text()
CHAT_DETAIL_VM = (KOTLIN / "ui/screens/chat/ChatDetailViewModel.kt").read_text()
GROUP_SCREEN = (KOTLIN / "ui/screens/groups/GroupChatScreen.kt").read_text()
GROUP_VM = (KOTLIN / "ui/screens/groups/GroupChatViewModel.kt").read_text()
ADMIN_SCREEN = (KOTLIN / "ui/screens/groups/GroupAdminScreen.kt").read_text()
ADMIN_VM = (KOTLIN / "ui/screens/groups/GroupAdminViewModel.kt").read_text()
CHANNEL_SCREEN = (KOTLIN / "ui/screens/channels/ChannelScreen.kt").read_text()
CHANNEL_VM = (KOTLIN / "ui/screens/channels/ChannelViewModel.kt").read_text()
MAIN_ACTIVITY = (KOTLIN / "MainActivity.kt").read_text()


class PeerRankContractTest(unittest.TestCase):

    def test_envelope_is_strict_and_wrapped_for_older_builds(self):
        # Формат и версия зафиксированы: старый пакет не должен притворяться новым.
        self.assertIn('const val PREFIX = "APURANK1"', WIRE)
        self.assertIn('const val VERSION = "1"', WIRE)
        self.assertIn("const val MAX_COUNT = 1_000", WIRE)
        # Разбор строгий: пакет из пяти частей, иначе null.
        self.assertIn("if (parts.size != 5) return null", WIRE)
        self.assertIn("if (parts[0] != PREFIX || parts[1] != VERSION) return null", WIRE)
        # Форма узла - как у транспорта, а не строже его: проверяем ХВОСТ после
        # `pk_` (подчёркивание в префиксе - не буква и не цифра).
        self.assertIn("fun canonicalNodeId(value: String?): String?", WIRE)
        self.assertIn("for (i in NODE_PREFIX.length until text.length)", WIRE)
        self.assertIn("private const val MIN_BODY = 7", NODE_IDS)
        self.assertIn("private const val MAX_BODY = 128", NODE_IDS)
        self.assertIn("FUTURE_TOLERANCE_MS", WIRE)
        # Обёртка «печатает…»: старые сборки видят «перестал печатать» - без
        # строки в переписке и без уведомления. Разбор принимает и голый конверт
        # (его присылала v11.74.190).
        self.assertIn('const val WRAPPER = "APUTYP1|"', WIRE)
        self.assertIn('const val PREFIX = "APUTYP1|"', TYPING)
        self.assertIn("private fun payload(text: String): String = text.removePrefix(WRAPPER)", WIRE)
        self.assertIn("return WRAPPER + listOf(", WIRE)

    def test_incoming_envelope_is_swallowed_before_history(self):
        # Поглощение: true и на битом пакете - служебной строке не место в чате.
        self.assertIn("if (!RankWire.isRankPacket(text)) return@withContext false", ROUTER)
        self.assertRegex(ROUTER, re.compile(r"val packet = RankWire\.parse\(text, nowMs\)\s*\n"
                                            r"\s*if \(packet == null\) \{"))
        self.assertIn("sender != packet.nodeId", ROUTER)
        # Ранг разбирается РАНЬШЕ «печатает…»: обёртку не должен съесть чужой
        # роутер - иначе ранг молча терялся бы.
        chain = CORE_SERVICE[
            CORE_SERVICE.index("peerRankRouter.routeIncoming(senderId, text)"):
            CORE_SERVICE.index("TypingRouter.routeIncoming(senderId, text)")
        ]
        self.assertNotIn("TypingRouter.routeIncoming", chain)
        self.assertGreaterEqual(CORE_SERVICE.count("peerRankRouter.routeIncoming("), 2)
        self.assertIn("lateinit var peerRankRouter", CORE_SERVICE)

    def test_ranks_live_in_one_table_for_every_node(self):
        # Ранг не в адресной книге: в группе и канале собеседник часто не контакт.
        self.assertIn('@Entity(tableName = "peer_ranks")', PEER_ENTITY)
        self.assertIn("val nodeId: String,", PEER_ENTITY)
        self.assertIn("observeVipNodeIds(vipThreshold: Int)", PEER_DAO)
        self.assertRegex(PEER_DAO, re.compile(
            r"WHERE nodeId = :nodeId AND updatedAtMs < :updatedAtMs"))
        # Версия базы поднята вместе с миграцией; уже принятые ранги переезжают.
        self.assertIn("APP_DATABASE_VERSION = 28", DATABASE)
        self.assertIn("MIGRATION_27_28", DATABASE)
        self.assertIn("peer_ranks", DATABASE)
        self.assertIn("SELECT lower(`id`), `peerRankQualified`, `peerRankUpdatedAtMs`", DATABASE)
        # Старые колонки в contacts остаются только ради схемы v27.
        self.assertIn("peerRankQualified", CONTACT_ENTITY)
        self.assertNotIn("updatePeerRank", CONTACT_DAO)
        self.assertNotIn("vipPeerContactIds", CONTACT_DAO)
        # Хранилище отдаёт поток узлов-элиты: экраны не опрашивают базу сами.
        self.assertIn("val vipNodeIds: Flow<Set<String>>", STORE)
        self.assertIn(".observeVipNodeIds(FileTransferRankPolicy.vipMinimumReferrals)", STORE)
        self.assertIn("store.remember(packet.nodeId, packet.qualified, packet.updatedAtMs)", ROUTER)

    def test_vip_starts_at_the_tenth_rank(self):
        # Порог один на всё приложение: политика рангов, а не второе число.
        self.assertIn("const val VIP_MINIMUM_QUALIFIED_REFERRALS = 10", POLICY)
        self.assertIn(
            "fun isVipRank(qualified: Int?): Boolean =\n"
            "            (qualified ?: -1) >= FileTransferRankPolicy.vipMinimumReferrals",
            STORE,
        )
        # Экраны берут признак из потока, а не считают порог у себя.
        for text in (CHAT_LIST_VM, GROUP_VM, ADMIN_VM, CHANNEL_VM, CHAT_DETAIL_VM):
            self.assertIn("peerRankStore", text)
            self.assertIn("vipNodeIds", text)

    def test_gold_ring_is_volumetric_and_used_around_avatars(self):
        # Кольцо объёмное: три слоя (кант, лента, тень) и перелив металла.
        self.assertIn("fun VipRing(", RING)
        self.assertIn("Brush.sweepGradient(", RING)
        self.assertIn("GoldEdge", RING)
        self.assertIn("GoldShadow", RING)
        # Блеск редкий и живёт только у VIP: анимация в общем компоненте.
        self.assertIn("SHEEN_CYCLE_MS", RING)
        self.assertIn("rememberInfiniteTransition(label = \"vip-ring\")", RING)
        # Аватарка с кольцом: картинка или инициалы, кольцо по краю круга.
        self.assertIn("fun PeerAvatar(", RING)
        self.assertIn("val VipRingWidth: Dp = 2.5.dp", RING)
        # Знак и кольцо — везде, где видно человека.
        for name, text in (
            ("список чатов", CONTACT_CARD),
            ("шапка личного чата", CHAT_DETAIL),
            ("карточка профиля", PROFILE_SHEET),
            ("сообщения группы", GROUP_SCREEN),
            ("участники группы", ADMIN_SCREEN),
            ("посты канала", CHANNEL_SCREEN),
        ):
            self.assertIn("PeerAvatar(", text, name)
            self.assertIn("vip =", text, name)
        # Свой аватар рисуется своим компонентом, но кольцо у него то же.
        self.assertIn("rememberSelfVip()", MY_AVATAR)
        self.assertIn("VipRing(Modifier.matchParentSize())", MY_AVATAR)
        self.assertIn("vip = uiState.peerVip || uiState.selfVip", CHAT_DETAIL)
        self.assertIn("selfVip", CHAT_DETAIL_VM)
        self.assertIn("rememberSelfVip()", RING)
        self.assertIn("vip = senderVip", GROUP_SCREEN)
        self.assertIn("vip = authorVip", CHANNEL_SCREEN)
        self.assertIn("vip = member.nodeId.lowercase() in vipNodeIds", ADMIN_SCREEN)
        # Порог берётся из политики, второй копии числа «10» у экранов нет.
        self.assertIn("peerVip = item.chat.contactId.lowercase() in vipPeerIds", CHAT_LIST)
        self.assertIn("vipPeerIds = uiState.vipPeerIds", CHAT_LIST)

    def test_rank_is_looked_at_before_typing_everywhere(self):
        # Обёртка ранга - это префикс «печатает…». Любой разбор, который смотрит
        # на «печатает…» РАНЬШЕ ранга, превратил бы конверт в чужое событие и
        # потерял бы ранг: таких мест не должно остаться.
        previews = (KOTLIN / "util/ChatPreviews.kt").read_text()
        # Предпросмотры и список чатов: служебная строка, и опознаётся по рангу.
        self.assertIn("RankWire.isRankPacket(t)", previews)
        self.assertIn('if (com.vladimir.messenger.data.rank.RankWire.isRankPacket(t)) return true', previews)
        self.assertIn('RankWire.isRankPacket(t)) return "знак VIP"', previews)
        # Зеркало: ранг разрешён к передаче второму телефону той же личности
        # (иначе знак VIP стоял бы только на активном устройстве) и на приёме
        # разбирается ПЕРЕД индикатором набора.
        mirror = (KOTLIN / "data/mirror/MirrorSync.kt").read_text()
        self.assertIn("RankWire.isRankPacket(text) ||", mirror)
        envelope = CORE_SERVICE[
            CORE_SERVICE.index("private suspend fun applyMirrorEnvelope("):
            CORE_SERVICE.index("private suspend fun mirrorApplyActionLocally(")
        ]
        self.assertLess(
            envelope.index("peerRankRouter.routeIncoming(senderId, text)"),
            envelope.index("TypingWire.parse(text)"),
        )
        action = CORE_SERVICE[
            CORE_SERVICE.index("private suspend fun mirrorApplyActionLocally("):
            CORE_SERVICE.index("private suspend fun mirrorSendActionToPeers(")
        ]
        self.assertLess(
            action.index("RankWire.isRankPacket(text)"),
            action.index("TypingWire.isTypingPacket(text)"),
        )

    def test_own_rank_is_broadcast_to_contacts_groups_and_channels(self):
        # Контакты: у них есть личная переписка.
        self.assertIn("chatDao.getChatByContactId(contactId)?.id", BROADCASTER)
        # Участники групп и подписчики каналов: там собеседник может быть незнаком.
        self.assertIn("groupDao.getMembers(group.id)", BROADCASTER)
        self.assertIn("groupDao.getGroups() + groupDao.getChannels()", BROADCASTER)
        self.assertIn("suspend fun getChannels()", (KOTLIN / "data/local/dao/GroupDao.kt").read_text())
        # Конверт тот же, обёртка та же, частота ограничена.
        self.assertIn("RankWire.build(own, qualified, nowMs)", BROADCASTER)
        self.assertIn("RankBroadcastPrefs.shouldBroadcast(app, qualified, nowMs)", BROADCASTER)
        self.assertIn("REFRESH_INTERVAL_MS", BROADCASTER)
        self.assertIn("RankBroadcastPrefs.markBroadcast(app, qualified, nowMs)", BROADCASTER)
        # Запуск: при старте приложения и когда ранг вырос на экране списка.
        self.assertIn("peerRankBroadcaster()", MAIN_ACTIVITY)
        self.assertIn("broadcastIfNeeded()", CHAT_LIST_VM)
        # Проверки на runner: конверт и порог элиты должны гоняться в CI, иначе
        # правило «ранг 10 - уже VIP» держалось бы только на честном слове.
        compile_step = (ROOT / "scripts/ci/check-android-compile.sh").read_text()
        self.assertIn("--tests '*RankWireTest*'", compile_step)
        self.assertIn("--tests '*FileTransferRankPolicyTest*'", compile_step)
        core_check = (ROOT / "scripts/ci/ci-core-check.sh").read_text()
        self.assertIn("python3 scripts/ci/check-peer-rank.py", core_check)


if __name__ == "__main__":
    unittest.main(verbosity=2)
