#!/usr/bin/env python3
"""Wiring contracts supplement, not replace, compiled policy/signature tests."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'android-app/app/src/main/java/com/vladimir/messenger'

class HeartGameContracts(unittest.TestCase):
    def test_one_global_overlay_and_three_receive_paths(self):
        main = (SRC / 'MainActivity.kt').read_text()
        self.assertEqual(main.count('HeartGameOverlay(heartNetwork)'), 1)
        core = (SRC / 'service/CoreServerService.kt').read_text()
        # Приём идёт по всем путям доставки (direct, relay, mirror, ...), поэтому
        # вызовов не меньше трёх: конверт не должен теряться на одном из путей.
        # Сам разбор живёт в RankPacketRouting (god-файл не растёт) и идёт
        # ОТДЕЛЬНО от ранга: сбой одного конверта не съедает второй.
        routing = (SRC / 'service/RankPacketRouting.kt').read_text()
        self.assertGreaterEqual(
            core.count('routeRankOrHeartPacket(heartGameNetwork, peerRankRouter, senderId, text)'), 3)
        self.assertIn('heartGameNetwork.routeIncoming(senderId, text)', routing)
        self.assertIn('routePacket("CoreServerService", "heartGame")', routing)

    def test_foreground_only_and_single_click(self):
        ui = (SRC / 'ui/components/HeartGameOverlay.kt').read_text()
        for text in ('repeatOnLifecycle(Lifecycle.State.RESUMED)', 'finally', 'policy.cancel()', 'enabled = !busy',
                     'HeartGameStore.catchHeart', 'rotationZ', 'alpha = (1f - progress.value)', '"+1"'):
            self.assertIn(text, ui)
        self.assertNotIn('pointerInput', ui)  # No full-screen gesture interception.

    def test_plus_one_keeps_original_size_then_grows_and_floats_up(self):
        ui = (SRC / 'ui/components/HeartGameOverlay.kt').read_text()
        self.assertIn('val scale = 1f + .42f * grow', ui)
        self.assertIn('translationY = -32.dp.toPx() * progress', ui)
        self.assertIn('scaleX = scale', ui)
        self.assertIn('scaleY = scale', ui)

    def test_main_header_shows_score_and_leaderboard_place_under_rank(self):
        chat_list = (SRC / 'ui/screens/chat/ChatListScreen.kt').read_text()
        self.assertIn('HeartGameHeaderStatus(', chat_list)
        self.assertIn('modifier = Modifier.padding(start = 32.dp)', chat_list)
        header = (SRC / 'ui/components/HeartGameSettings.kt').read_text()
        self.assertIn('if (!enabled) return', header)
        self.assertIn('heart_game_header_place', header)
        self.assertIn('HeartGameStore.board(context, id)', header)
        self.assertIn('HeartGameStore.score(context, id)', header)

    def test_leaderboard_numbers_top_100_and_pins_own_place(self):
        ui = (SRC / 'ui/components/HeartGameSettings.kt').read_text()
        for text in ('take(100)', 'take(99)', '(100 to entries[ownIndex])', 'LazyColumn(', 'heart_game_you_place',
                     'TextAlign.End', 'coerceIn(200f, 420f)'):
            self.assertIn(text, ui)

    def test_one_participant_is_one_row_in_the_leaderboard(self):
        # Владелец, скриншот 28.10.2026: «В таблице один и тот же участник
        # задвоился». У одного человека бывает несколько ключей подписи
        # (телефон, компьютер, переустановка), и keyId у них разные - значит,
        # склейка обязана идти по имени, а не по ключу.
        wire = (SRC / 'data/heartgame/HeartGameWire.kt').read_text()
        self.assertIn('fun table(entries: Collection<Entry>, ownKey: String?)', wire)
        self.assertIn('mergeKey(group)', wire)
        self.assertIn('lowercase(java.util.Locale.ROOT)', wire)
        # Своя строка не подменяется чужим счётом под тем же именем.
        self.assertIn('group.firstOrNull { it.keyId == ownKey }', wire)
        # Первый шаг склейки - узел: телефон и компьютер одного человека дают
        # разные ключи подписи, но общий nodeId.
        self.assertIn('byNode.getOrPut("node:" + entry.nodeId', wire)
        # Пустое имя не склеивает разных людей: остаётся склейка по узлу.
        self.assertIn('"node:" + node', wire)
        self.assertNotIn('heart_game_unknown_player', (SRC / 'ui/components/HeartGameSettings.kt').read_text(),
                         'выдуманный ник «Участник» вернулся в таблицу')
        ui = (SRC / 'ui/components/HeartGameSettings.kt').read_text()
        self.assertIn('HeartGameWire.table(', ui)
        self.assertNotIn('HeartGameWire.ranked(HeartGameStore.board', ui)
        for path in (ROOT / 'android-app/app/src/main/res/values/strings.xml',
                     ROOT / 'android-app/app/src/main/res/values-en/strings.xml'):
            self.assertIn('heart_game_board_merged', path.read_text(),
                          'нет пояснения о склейке строк в ' + path.name)
        test = (ROOT / 'android-app/app/src/test/java/com/vladimir/messenger/data/heartgame/'
                'HeartGameWireTest.kt').read_text()
        for case in ('oneNameWithTwoSigningKeysTakesASingleRowWithTheBestScore',
                     'ownRowStaysOwnEvenWhenAnotherKeyUnderTheSameNameClaimsMore',
                     'twoDevicesOfOneIdentityAreOneRowEvenWithoutAName',
                     'ownOldKeyIsFoldedIntoTheOwnRow',
                     'namelessParticipantsOfDifferentNodesAreNeverMerged',
                     'caseAndOuterSpacesDoNotSplitOneName'):
            self.assertIn(case, test, 'нет теста склейки: ' + case)

    def test_nameless_result_is_never_shown_as_a_made_up_nickname(self):
        # Владелец, 29.10.2026: «В таблице почему-то написан участник. Хотя
        # таких ников вообще нет». Пустое имя в подписанной записи означает
        # «профиль без имени», а не человека по имени «Участник».
        ui = (SRC / 'ui/components/HeartGameSettings.kt').read_text()
        self.assertIn('rememberBoardContactNames(', ui)
        self.assertIn('isRealName(contact.displayName)', ui)
        self.assertIn('canonicalNodeId(contact.id)', ui)
        self.assertIn('R.string.chat_no_name', ui)
        self.assertIn('R.string.heart_game_board_no_name', ui)
        for path in (ROOT / 'android-app/app/src/main/res/values/strings.xml',
                     ROOT / 'android-app/app/src/main/res/values-en/strings.xml'):
            text = path.read_text()
            self.assertIn('heart_game_board_no_name', text,
                          'нет пояснения «Без имени» в ' + path.name)
            self.assertNotIn('heart_game_unknown_player', text,
                             'строка-выдумка «Участник» осталась в ' + path.name)

    def test_private_account_scoped_score_and_opt_in(self):
        store = (SRC / 'data/heartgame/HeartGameStore.kt').read_text()
        self.assertIn('heart-game-v1-$id', store)
        self.assertIn('getBoolean("enabled", false)', store)
        self.assertIn('policy.claim(', store)
        self.assertIn('.commit()', store)
        self.assertNotIn('HeartRepository', store)
        # Приглашения теперь дают бонус к счёту, но счёт по-прежнему личный
        # (префикс аккаунта) и бонус начисляется ровно один раз: отметка
        # REFERRAL_BONUS_COUNT_KEY хранится рядом со счётом.
        self.assertIn('REFERRAL_BONUS_COUNT_KEY', store)
        self.assertIn('synchronizeReferralBonusesLocked', store)

    def test_bounded_signed_network_not_fake_global_table(self):
        network = (SRC / 'data/heartgame/HeartGameNetwork.kt').read_text()
        for text in ('verifyIdentitySigningBinding', 'MAX_PACKET', 'MAX_RECORDS', 'MAX_TARGETS_PER_ROUND',
                     'HeartSyncPolicy.isDue', 'HeartSyncPrefs.markGlobal', 'received > 60', 'canSendNow()'):
            self.assertIn(text, network)
        # Синхронизация не зависит от включённой игры и не держит отсчёт в памяти.
        self.assertNotIn('HeartGameStore.enabled', network)
        self.assertNotIn('lastSend', network)

    def test_sync_runs_when_game_disabled_and_rarely(self):
        policy = (SRC / 'data/heartgame/HeartSyncPolicy.kt').read_text()
        self.assertIn('const val SYNC_INTERVAL_MS = 6L * 60 * 60 * 1000', policy)
        self.assertIn('fun isPhoneFree(', policy)
        self.assertIn('fun newcomerAllowed(', policy)
        prefs = (SRC / 'data/heartgame/HeartSyncPrefs.kt').read_text()
        self.assertIn('last_global_ms', prefs)
        self.assertIn('getSharedPreferences(PREFS', prefs)
        core = (SRC / 'service/CoreServerService.kt').read_text()
        self.assertIn('heartGameNetwork.broadcastIfNeeded()', core)
        self.assertIn('heartGameNetwork.syncWithNewcomer(existing.id)', core)

    def test_overlay_no_longer_broadcasts_every_minute(self):
        ui = (SRC / 'ui/components/HeartGameOverlay.kt').read_text()
        self.assertNotIn('broadcastIfNeeded', ui)
        self.assertNotIn('delay(60_000)', ui)
        wire = (SRC / 'data/heartgame/HeartGameWire.kt').read_text()
        self.assertIn('Ed25519.verify', wire)
        self.assertIn('thenBy { it.keyId }', wire)
        ephemeral = (SRC / 'data/security/EphemeralControl.kt').read_text()
        self.assertIn('text.startsWith("APUTYP1|APUGAME1|")', ephemeral)
        mirror = (SRC / 'data/mirror/MirrorSync.kt').read_text()
        self.assertIn('text.startsWith(com.vladimir.messenger.data.heartgame.HeartGameWire.PREFIX)', mirror)

if __name__ == '__main__':
    unittest.main(verbosity=2)
