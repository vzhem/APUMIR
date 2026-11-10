#!/usr/bin/env python3
"""Regression guard for one-shot arrival notifications, migration and history isolation."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "android-app/app/src/main"
JAVA = MAIN / "java/com/vladimir/messenger"
TEST = ROOT / "android-app/app/src/androidTest/java/com/vladimir/messenger/data/notification/NotificationArrivalInstrumentedTest.kt"


def source(path):
    return (JAVA / path).read_text(encoding="utf-8")


def section(text, first, last):
    start = text.index(first)
    return text[start:text.index(last, start)]


class NotificationArrivalContract(unittest.TestCase):
    def test_room_migration_preserves_history_and_is_registered(self):
        entity = source("data/local/entity/MessageEntity.kt")
        database = source("data/local/AppDatabase.kt")
        self.assertIn('val notificationPending: Boolean = false', entity)
        self.assertIn('@ColumnInfo(defaultValue = "0")', entity)
        self.assertIn('index_messages_notification_pending', entity)
        self.assertIn('APP_DATABASE_VERSION = 41', database)
        self.assertIn('object : Migration(40, 41)', database)
        self.assertIn('ADD COLUMN `notificationPending` INTEGER NOT NULL DEFAULT 0', database)
        self.assertIn('AppDatabase.MIGRATION_40_41', source("di/AppModule.kt"))

    def test_queue_and_atomic_claim_do_not_depend_on_sender_timestamp(self):
        dao = source("data/local/dao/MessageDao.kt")
        query = section(dao, '/** Newly inserted local work', '// ── Группы')
        self.assertIn('notificationPending = 1', query)
        self.assertIn('recipientId = :recipientId', query)
        self.assertIn('LIMIT 512', query)
        self.assertNotIn("strftime", query)
        self.assertNotIn('timestamp >=', query)
        self.assertIn('UPDATE messages SET notificationPending = 0', query)
        self.assertIn('AND notificationPending = 1 AND isFromMe = 0', query)
        processor = source('service/IncomingMessageNotifications.kt')
        self.assertLess(processor.index('if (!claim(message.id)) return'), processor.index('alert(message, kind)'))
        for marker in ('DeferredInboxStore.owns', 'InlineImage.isPart', 'ChatPreviews.isServiceEnvelope',
                       'IncomingAlert.SOS', 'IncomingAlert.QUIET', 'quiet = kind == IncomingAlert.QUIET'):
            self.assertIn(marker, processor)
        self.assertIn('isMuted(chatId, topicId, group, nowMs)', source('service/NotificationHelper.kt'))

    def test_live_sources_history_and_deferred_are_separated(self):
        repo = source('data/repository/ChatRepository.kt')
        self.assertIn('timestamp = timestamp, notificationPending = notifyArrival', repo)
        self.assertIn('senderNameColorOverride = row.senderNameColor, notifyArrival = notify', repo)
        self.assertIn('timestamp = now, notificationPending = true', repo)
        self.assertIn('status = "RECEIVED"', repo)
        self.assertIn('notificationPending = com.vladimir.messenger.data.notification.NotificationArrivalPolicy.groupMessage(',
                      source('data/group/GroupRepository.kt'))
        self.assertIn('notifyArrival = false', source('data/schedule/DeferredDelivery.kt'))
        mirror = source('data/mirror/MirrorSync.kt')
        self.assertIn('bridge.applyIncoming(row, notify = false)', mirror)
        self.assertIn('bridge.applyIncoming(row, notify = true)', mirror)
        self.assertIn('bridge.onFileMeta(meta, notify = false)', mirror)
        self.assertIn('bridge.onFileMeta(meta, notify = true)', mirror)
        self.assertIn('notifyArrival = notify', source('data/file/MirrorFileMetaApplier.kt'))
        service = source('service/CoreServerService.kt')
        self.assertIn('startIncomingNotifications(nodeId)', service)
        self.assertIn('startEventPolling(nodeId ?: legacyRoutingId)', service)
        self.assertIn('startIncomingNotifications(recipientId)', service)
        mirror_in = section(service, 'override suspend fun applyIncoming(', 'override suspend fun applySent(')
        self.assertIn('applyMirrorIncoming(row, notify)', mirror_in)
        self.assertNotIn('showMessageNotification', mirror_in)

    def test_history_cannot_rearm_notification_work(self):
        snapshot = source('data/mirror/MirrorAccountSnapshot.kt')
        self.assertIn('"messages" to setOf("notificationPending")', snapshot)
        self.assertGreaterEqual(snapshot.count('SNAPSHOT_EXCLUDED_COLUMNS[table].orEmpty()'), 2)
        backup = source('data/backup/ProfileBackup.kt')
        self.assertLess(backup.index('BackupNotificationState.clearFromStaged('), backup.index('applyStagedWithSecrets(app,'))
        self.assertIn('table.name == "messages" && it.name == "notificationPending"', source('data/backup/BackupRestoreMerge.kt'))
        sanitizer = source('data/backup/BackupNotificationState.kt')
        self.assertIn('PRAGMA table_info', sanitizer)
        self.assertIn('UPDATE messages SET notificationPending = 0', sanitizer)

    def test_honest_settings_and_legacy_tcp_millisecond_timestamp(self):
        for path, truth in ((MAIN / 'res/values/strings.xml', 'Системного push в APU нет'),
                            (MAIN / 'res/values-en/strings.xml', 'APU has no system push')):
            text = path.read_text(encoding='utf-8')
            self.assertIn(truth, text)
            self.assertNotIn('like Telegram', text[text.index('name="delivery_switch_hint"'):text.index('name="delivery_item_notifications"')])
            self.assertIn('name="delivery_state_disabled"', text)
        core = (ROOT / 'rust-core/src/engine/core.rs').read_text(encoding='utf-8')
        listener = section(core, 'async fn run_tcp_listener(', 'async fn connect_ready_mqtt_session(')
        self.assertIn('crate::storage::models::now_ms()', listener)
        self.assertNotIn('.as_secs()', listener)

    def test_acceptance_suite_cannot_be_skipped(self):
        self.assertTrue(TEST.is_file())
        test = TEST.read_text(encoding='utf-8')
        runner = (ROOT / 'scripts/ci/run-android-navigation-tests.sh').read_text()
        guard = (ROOT / 'scripts/ci/check-android-notification-results.py').read_text()
        for method in ('migrationFortyToFortyOnePreservesHistoryAndRoomValidatesSchema',
                       'delayedArrivalsAreClaimedOnceAndSurviveRestartWithoutRearmingDuplicates',
                       'boundedQueueContinuesAfterFirstBatchIsClaimed',
                       'mirrorSnapshotsNeverExportOrApplyLocalNotificationWork',
                       'backupMergeDoesNotImportPendingWorkOrClearLiveWork',
                       'backupMergeRollsBackEveryTableWhenAnInsertFails',
                       'stagedBackupSanitizerReadsWalAndLeavesTheOriginalUntouched'):
            self.assertIn(method, test)
            self.assertIn(method, guard)
        self.assertIn('NotificationArrivalInstrumentedTest', runner)
        self.assertIn('check-android-notification-results.py', runner)
        self.assertIn('notification_status', runner)


if __name__ == '__main__':
    unittest.main(verbosity=2)
