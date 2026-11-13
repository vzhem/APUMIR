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
        self.assertIn('APP_DATABASE_VERSION = 42', database)
        self.assertIn('object : Migration(40, 41)', database)
        self.assertIn('ADD COLUMN `notificationPending` INTEGER NOT NULL DEFAULT 0', database)
        self.assertIn('object : Migration(41, 42)', database)
        self.assertIn('index_messages_outbox_status_order', entity)
        self.assertIn('AppDatabase.MIGRATION_40_41', source("di/AppModule.kt"))
        self.assertIn('AppDatabase.MIGRATION_41_42', source("di/AppModule.kt"))

    def test_pending_work_posts_before_snapshot_completion_and_ignores_sender_clock(self):
        dao = source('data/local/dao/MessageDao.kt')
        query = section(dao, '/** Newly inserted local work', '// ── Группы')
        for marker in ('notificationPending = 1', 'recipientId = :recipientId', 'LIMIT 512',
                       'MAX(rowid)', 'rowid <= :through', ':after IS NULL',
                       'AND notificationPending = 1 AND isFromMe = 0', 'topicId IS :topicId',
                       'channel = :channel'):
            self.assertIn(marker, query)
        self.assertNotIn('strftime', query)
        self.assertNotIn('timestamp >=', query)
        processor = source('service/IncomingNotificationProcessor.kt')
        self.assertIn('IncomingNotificationGate.run', processor)
        self.assertLess(processor.index('alert(message, kind)'), processor.index('complete(message)'))
        self.assertIn('IncomingNotificationPost.BLOCKED', processor)
        self.assertIn('return@run Result.BLOCKED', processor)
        self.assertIn('isActive(message)', processor)
        self.assertIn('cancel(message)', processor)
        self.assertNotIn('claimPendingNotification', processor)
        for marker in ('InlineImage.isPart', 'ChatPreviews.isServiceEnvelope', 'IncomingAlert.SOS', 'IncomingAlert.QUIET'):
            self.assertIn(marker, processor)
        observer = source('service/IncomingMessageNotifications.kt')
        for marker in ('DeferredInboxStore.owns', 'DeferredInboxStore.isCancelled', 'ChatClearRegistry',
                       'quiet = kind == IncomingAlert.QUIET', 'completePendingNotification', 'IncomingNotificationSweep'):
            self.assertIn(marker, observer)
        helper = source('service/NotificationHelper.kt')
        for marker in ('isMuted(chatId, topicId, group, nowMs)', 'POST_NOTIFICATIONS', 'areNotificationsEnabled()',
                       'IMPORTANCE_NONE', 'IncomingNotificationPost.SUPPRESSED', 'IncomingNotificationPost.BLOCKED',
                       'LocalMessageNotifications.EXTRA_SNAPSHOT'):
            self.assertIn(marker, helper)

    def test_recovery_and_read_boundaries_are_wired_but_not_claimed_atomic(self):
        worker = source('worker/IncomingNotificationRecoveryWorker.kt')
        self.assertLess(worker.index('.drainBatch(owner, cursor)'), worker.index('checkpoint.save(owner, step.cursor)'))
        self.assertIn('IncomingNotificationIdentity.current', worker)
        self.assertIn('scheduleGate.workerFinished(id', worker)
        adapter = source('worker/NotificationRecoveryWorkManager.kt')
        self.assertIn('manager.enqueue(request)', adapter)
        self.assertIn('enqueueUniquePeriodicWork', adapter)
        self.assertNotIn('NetworkType.CONNECTED', adapter)
        store = source('data/repository/IncomingMessageStore.kt')
        self.assertLess(store.index('val saved = database.withTransaction'), store.index('notificationCommitted()'))
        self.assertIn('IncomingNotificationRecoveryWorker.enqueue(context)', source('data/repository/ChatRepository.kt'))
        self.assertIn('IncomingNotificationRecoveryWorker.schedule(applicationContext)', source('MessengerApplication.kt'))
        self.assertIn('IncomingNotificationRecoveryWorker.enqueue(applicationContext)', source('MainActivity.kt'))
        self.assertIn('NotificationRecoveryOperationObserver.watch(scheduler.oneOff(id).result, onFailure)', worker)
        self.assertIn('NotificationRecoveryOperationObserver.watch(scheduler.periodic().result)', worker)
        for path in ('data/repository/IncomingNotificationReads.kt', 'data/repository/IncomingGroupNotificationReads.kt'):
            self.assertIn('IncomingNotificationGate.run', source(path))
        self.assertIn('notificationReads.topic(groupId, topicId)', source('data/group/GroupRepository.kt'))
        self.assertIn('notificationReads.group(groupId)', source('data/group/GroupRepository.kt'))
        self.assertIn('.mirror(groupId, topicId)', source('service/CoreServerService.kt'))
        self.assertIn('LocalMessageNotifications.cancelTopic', source('data/repository/IncomingGroupNotificationReads.kt'))

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

    def test_direct_message_uses_one_room_transaction_for_arrival_state(self):
        repo = source('data/repository/ChatRepository.kt')
        incoming = section(repo, '    suspend fun saveIncomingMessage(', '    suspend fun isInviteBlocked(')
        self.assertIn('incomingMessageStore.save(entity', incoming)
        self.assertIn('DeferredInboxStore.isCancelled', incoming)
        self.assertIn('ChatClearRegistry.isCleared', incoming)
        self.assertIn('!com.vladimir.messenger.util.ChatPreviews.isServiceEnvelope(cleanContent)', incoming)
        store = source('data/repository/IncomingMessageStore.kt')
        for marker in ('database.withTransaction', 'messages.insertMessageIgnore(message)',
                       'suppressed()', 'database.chatDao().recordIncoming(', 'deleteByIdChatAndSender'):
            self.assertIn(marker, store)
        chat_dao = source('data/local/dao/ChatDao.kt')
        self.assertIn('unreadCount = unreadCount + :unreadDelta', chat_dao)
        runner = (ROOT / 'scripts/ci/run-android-navigation-tests.sh').read_text()
        guard = (ROOT / 'scripts/ci/check-android-notification-results.py').read_text()
        tests = ROOT / 'android-app/app/src/androidTest/java/com/vladimir/messenger/data/repository/IncomingMessageStoreInstrumentedTest.kt'
        self.assertTrue(tests.is_file())
        self.assertIn('IncomingMessageStoreInstrumentedTest', runner)
        for method in ('rowPreviewUnreadAndNotificationCommitOnceAcrossReopen',
                       'concurrentDuplicatesAndConflictingSenderNeverModifyTheWinner',
                       'suppressedOrMissingChatDoesNotLeaveAnOrphanOrNotification',
                       'suppressionArrivingDuringTheTransactionLeavesNoPartialState',
                       'clearTombstoneWrittenDuringInsertionIsObservedBeforeCommit',
                       'clearAfterAnOldCheckEventuallyRemovesTheCommittedRowAndMetadata',
                       'sqliteFailureRollsBackEverythingAndTheSameIdCanBeRetried',
                       'cancellationRollsBackAndNonAlertingServiceRowsKeepTheirPolicy'):
            self.assertIn(method, tests.read_text())
            self.assertIn(method, guard)

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
        self.assertIn('PRAGMA wal_checkpoint(TRUNCATE)', sanitizer)
        self.assertIn('Staged WAL checkpoint incomplete', sanitizer)
        self.assertIn('wal.length() == 0L', sanitizer)
        self.assertNotIn('stagedWal.copyTo', backup)
        self.assertNotIn('moveFile(wal, File(dbFile.path + "-wal"))', backup)
        runner = (ROOT / 'scripts/ci/run-android-navigation-tests.sh').read_text()
        self.assertIn('BackupNotificationRestoreInstrumentedTest', runner)

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
                       'migrationFortyOneToFortyTwoPreservesRowsAndIndexesSentRetries',
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
