#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Контракт передачи ранга: собеседник сам сообщает свой ранг, знак VIP у имени.

Задача владельца от 2026-10-07: «Да, сделай передачу ранга» — знак VIP должен
стоять не только у своего звания, но и у имён собеседников.

Проверяется по исходникам (телефона в песочнице нет, а UI-тестов экрана нет):

* проводной конверт `APURANK1` строго разбирается, а его форма узла совпадает с
  транспортом (`NodeIds`: `pk_` + 7..128 букв/цифр) — иначе живые ранги терялись;
* конверт поглощается приёмником ДО истории чата: текст никогда не становится
  сообщением, даже если он битый;
* заявленный узел сверяется с отправителем — чужое имя так не подставить;
* запись ранга идёт только «вперёд» (опоздавший пакет не откатывает знак);
* знак VIP у имени читается из базы и живёт по ОДНОМУ порогу политики рангов,
  без второй копии числа «20»;
* свой ранг рассылается собеседникам с ограничением частоты, а не на каждой
  перерисовке экрана.

Запуск: `python3 scripts/ci/check-peer-rank.py`; код возврата 0 — контракт цел.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
WIRE = (KOTLIN / "data/rank/RankWire.kt").read_text()
ROUTER = (KOTLIN / "data/rank/PeerRankRouter.kt").read_text()
BROADCASTER = (KOTLIN / "data/rank/PeerRankBroadcaster.kt").read_text()
STORE = (KOTLIN / "data/rank/PeerRankStore.kt").read_text()
NODE_IDS = (KOTLIN / "util/NodeIds.kt").read_text()
CORE_SERVICE = (KOTLIN / "service/CoreServerService.kt").read_text()
CONTACT_ENTITY = (KOTLIN / "data/local/entity/ContactEntity.kt").read_text()
CONTACT_DAO = (KOTLIN / "data/local/dao/ContactDao.kt").read_text()
DATABASE = (KOTLIN / "data/local/AppDatabase.kt").read_text()
POLICY = (KOTLIN / "data/file/FileTransferRankPolicy.kt").read_text()
CONTACT_CARD = (KOTLIN / "ui/components/ContactCard.kt").read_text()
CHAT_LIST = (KOTLIN / "ui/screens/chat/ChatListScreen.kt").read_text()
CHAT_LIST_VM = (KOTLIN / "ui/screens/chat/ChatListViewModel.kt").read_text()
CHAT_DETAIL = (KOTLIN / "ui/screens/chat/ChatDetailScreen.kt").read_text()
CHAT_DETAIL_VM = (KOTLIN / "ui/screens/chat/ChatDetailViewModel.kt").read_text()
MAIN_ACTIVITY = (KOTLIN / "MainActivity.kt").read_text()


class PeerRankContractTest(unittest.TestCase):

    def test_envelope_is_strict_and_accepts_live_node_ids(self):
        # Формат и версия зафиксированы: старый пакет не должен притворяться новым.
        self.assertIn('const val PREFIX = "APURANK1"', WIRE)
        self.assertIn('const val VERSION = "1"', WIRE)
        self.assertIn("const val MAX_COUNT = 1_000", WIRE)
        # Разбор строгий: пакет из пяти частей, иначе null.
        self.assertIn("if (parts.size != 5) return null", WIRE)
        self.assertIn("if (parts[0] != PREFIX || parts[1] != VERSION) return null", WIRE)
        # Форма узла - как у транспорта, а не строже его.
        self.assertIn("fun canonicalNodeId(value: String?): String?", WIRE)
        self.assertIn("private const val NODE_PREFIX = \"pk_\"", WIRE)
        self.assertIn("private const val MIN_BODY = 7", NODE_IDS)
        self.assertIn("private const val MAX_BODY = 128", NODE_IDS)
        self.assertIn("private const val NODE_MIN_BODY = 7", WIRE)
        self.assertIn("private const val NODE_MAX_BODY = 128", WIRE)
        # Свои часы вперёд не принимаем без предела: иначе подделка «свежее».
        self.assertIn("FUTURE_TOLERANCE_MS", WIRE)

    def test_incoming_envelope_never_becomes_a_chat_message(self):
        # Поглощение: true и на битом пакете - служебной строке не место в чате.
        self.assertIn("if (!RankWire.isRankPacket(text)) return@withContext false", ROUTER)
        self.assertRegex(ROUTER, re.compile(r"val packet = RankWire\.parse\(text, nowMs\)\s*\n"
                                            r"\s*if \(packet == null\) \{"))
        self.assertIn("return@withContext true", ROUTER)
        # Узел в конверте обязан совпасть с отправителем.
        self.assertIn("sender != packet.nodeId", ROUTER)
        # Роутер подключён в ядре (цепочка роутеров и старый путь приёма).
        self.assertIn("lateinit var peerRankRouter", CORE_SERVICE)
        self.assertGreaterEqual(CORE_SERVICE.count("peerRankRouter.routeIncoming("), 2)

    def test_stored_rank_only_moves_forward(self):
        self.assertIn("peerRankQualified", CONTACT_ENTITY)
        self.assertIn("peerRankUpdatedAtMs", CONTACT_ENTITY)
        # «Свежее» - условие в самом запросе, а не в вызывающем коде.
        self.assertRegex(CONTACT_DAO, re.compile(
            r"WHERE id = :contactId AND peerRankUpdatedAtMs < :updatedAtMs"))
        # Версия базы поднята вместе с миграцией: обновление не теряет переписку.
        self.assertIn("APP_DATABASE_VERSION = 27", DATABASE)
        self.assertIn("MIGRATION_26_27", DATABASE)
        self.assertIn("ADD COLUMN `peerRankQualified` INTEGER NOT NULL DEFAULT -1", DATABASE)
        # Экраны узнают об изменении сразу, а не после перезахода.
        self.assertIn("fun notifyChanged()", STORE)
        self.assertIn("PeerRankStore.notifyChanged()", ROUTER)

    def test_vip_sign_lives_next_to_the_peer_name_with_one_threshold(self):
        # Порог один на всё приложение: политика рангов, а не второе число.
        self.assertIn("const val VIP_MINIMUM_QUALIFIED_REFERRALS = 20", POLICY)
        for text in (CHAT_LIST_VM, CHAT_DETAIL_VM):
            self.assertIn("vipMinimumReferrals", text)
        # Карточка списка умеет показать знак рядом с именем.
        self.assertIn("peerVip: Boolean = false", CONTACT_CARD)
        self.assertIn("ApuVipBadge(compact = true)", CONTACT_CARD)
        # Знак передаётся из списка чатов и из шапки переписки.
        self.assertIn("peerVip = item.chat.contactId.lowercase() in vipPeerIds", CHAT_LIST)
        self.assertIn("vipPeerIds = uiState.vipPeerIds", CHAT_LIST)
        self.assertIn("vipPeerIds", CHAT_LIST_VM)
        self.assertIn("ApuVipBadge(compact = true)", CHAT_DETAIL)
        self.assertIn("peerVip = peerVip", CHAT_DETAIL_VM)
        # Знака нет, пока ранг не сообщён: -1 означает «неизвестно».
        self.assertIn("?: -1", CHAT_DETAIL_VM)

    def test_own_rank_is_broadcast_with_a_frequency_limit(self):
        # Рассылка адресуется чатом: ядру нужен chatId, а не только узел.
        self.assertIn("chatDao.getChatByContactId(contactId)?.id", BROADCASTER)
        self.assertIn("RankWire.build(own, qualified, nowMs)", BROADCASTER)
        self.assertIn("RankBroadcastPrefs.shouldBroadcast(app, qualified, nowMs)", BROADCASTER)
        self.assertIn("REFRESH_INTERVAL_MS", BROADCASTER)
        # Отметка ставится даже при нуле доставок - иначе долбёж на каждой отрисовке.
        self.assertIn("RankBroadcastPrefs.markBroadcast(app, qualified, nowMs)", BROADCASTER)
        # Запускается при старте приложения и когда ранг вырос на экране списка.
        self.assertIn("peerRankBroadcaster()", MAIN_ACTIVITY)
        self.assertIn("broadcastIfNeeded()", CHAT_LIST_VM)


if __name__ == "__main__":
    unittest.main(verbosity=2)
