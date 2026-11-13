#!/usr/bin/env python3
"""Fail when Android notification, outbox, push-storage or core-ownership tests did not all run."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

CLASS = "com.vladimir.messenger.data.notification.NotificationArrivalInstrumentedTest"
INCOMING_CLASS = "com.vladimir.messenger.data.repository.IncomingMessageStoreInstrumentedTest"
DIRECT_REPLY_CLASS = "com.vladimir.messenger.data.reply.DirectReplyIngressInstrumentedTest"
DIRECT_REPLY_FFI_CLASS = "com.vladimir.messenger.data.reply.DirectReplyRealFfiInstrumentedTest"
SCOPED_ACK_CLASS = "com.vladimir.messenger.data.receipt.ScopedDeliveryAckInstrumentedTest"
MIRROR_OUTGOING_CLASS = "com.vladimir.messenger.data.repository.MirrorOutgoingStoreInstrumentedTest"
MIRROR_PENDING_CLASS = "com.vladimir.messenger.data.repository.MirrorPendingOutgoingInstrumentedTest"
OUTBOX_CLASS = "com.vladimir.messenger.data.repository.OutboxFairnessInstrumentedTest"
REPLY_OUTBOX_CLASS = "com.vladimir.messenger.data.repository.ReplyAwareOutboxSenderInstrumentedTest"
DELETION_OUTBOX_ROUTING_CLASS = "com.vladimir.messenger.data.repository.DeletionOutboxRoutingInstrumentedTest"
CLEAR_LOOKUP_CLASS = "com.vladimir.messenger.data.chatclear.ChatClearTombstonesInstrumentedTest"
PUSH_CLASS = "com.vladimir.messenger.data.push.PushEpochStoreInstrumentedTest"
REGISTRATION_CLASS = "com.vladimir.messenger.data.push.PushRegistrationStoreInstrumentedTest"
PUSH_INTEGRATION_CLASS = "com.vladimir.messenger.data.push.PushWakeIntegrationInstrumentedTest"
MAILBOX_DRAIN_CLASS = "com.vladimir.messenger.data.push.PushMailboxDrainInstrumentedTest"
MAILBOX_ROOM_CLASS = "com.vladimir.messenger.data.push.PushMailboxRoomStoreInstrumentedTest"
MAILBOX_OUTBOX_CLASS = "com.vladimir.messenger.data.push.PushMailboxOutboxInstrumentedTest"
MAILBOX_OUTBOX_FFI_CLASS = "com.vladimir.messenger.data.push.PushMailboxOutboxRealFfiInstrumentedTest"
PUSH_INBOX_CLASS = "com.vladimir.messenger.data.push.PushWakeInboxInstrumentedTest"
PUSH_PERIODIC_CLASS = "com.vladimir.messenger.data.push.PushWakePeriodicRecoveryInstrumentedTest"
PUSH_RECOVERY_CHECKPOINT_CLASS = "com.vladimir.messenger.data.push.PushWakeRecoveryCheckpointInstrumentedTest"
PUSH_DRAIN_CLASS = "com.vladimir.messenger.data.push.PushWakeDrainInstrumentedTest"
PUSH_WORK_CLASS = "com.vladimir.messenger.data.push.PushWakeWorkManagerInstrumentedTest"
FIREBASE_CLASS = "com.vladimir.messenger.data.push.FirebaseClientConfigurationInstrumentedTest"
CORE_LIFECYCLE_CLASS = "com.vladimir.messenger.data.CoreLifecycleOwnershipInstrumentedTest"
HEARTBEAT_CLASS = "com.vladimir.messenger.service.DeliveryHeartbeatInstrumentedTest"
PERMISSION_CLASS = "com.vladimir.messenger.service.NotificationPermissionRecoveryInstrumentedTest"
EXPECTED_NOTIFICATION = {
    "migrationFortyToFortyOnePreservesHistoryAndRoomValidatesSchema",
    "migrationFortyOneToFortyTwoPreservesRowsAndIndexesSentRetries",
    "delayedArrivalsAreClaimedOnceAndSurviveRestartWithoutRearmingDuplicates",
    "boundedQueueContinuesAfterFirstBatchIsClaimed",
    "mirrorSnapshotsNeverExportOrApplyLocalNotificationWork",
    "backupMergeDoesNotImportPendingWorkOrClearLiveWork",
    "backupMergeRollsBackEveryTableWhenAnInsertFails",
    "stagedBackupSanitizerReadsWalAndLeavesTheOriginalUntouched",
}
EXPECTED = {CLASS: EXPECTED_NOTIFICATION, INCOMING_CLASS: {
    "rowPreviewUnreadAndNotificationCommitOnceAcrossReopen",
    "concurrentDuplicatesAndConflictingSenderNeverModifyTheWinner",
    "suppressedOrMissingChatDoesNotLeaveAnOrphanOrNotification",
    "suppressionArrivingDuringTheTransactionLeavesNoPartialState",
    "clearTombstoneWrittenDuringInsertionIsObservedBeforeCommit",
    "clearAfterAnOldCheckEventuallyRemovesTheCommittedRowAndMetadata",
    "sqliteFailureRollsBackEverythingAndTheSameIdCanBeRetried",
    "cancellationRollsBackAndNonAlertingServiceRowsKeepTheirPolicy",
}, DIRECT_REPLY_CLASS: {
    "verifiedReplyCommitsBeforeAckAndSurvivesReopen",
    "wrongSenderChatOutgoingGroupAndTopicNeverChange",
    "onlyCanonicalPersonalChannelsCanApplyReplyOrTriggerAck",
    "unauthenticatedAndEarlyPacketsAreNotAcknowledged",
    "cancellationRollsBackTheRowAndNeverAcknowledges",
    "sqliteFailureAndClearPreflightCannotAcknowledge",
    "conflictingReplyCannotOverwriteTheFirstQuote",
    "groupReplyUpdateRequiresTheMatchingGroupAtUpdateTime",
    "groupEarlyRepliesRespectOwnerAndStoredAuthorInRealRoom",
    "groupReplyMembershipMustStillExistAtTheRoomUpdate",
    "bannedGroupMembershipBlocksImmediateAndBufferedReplies",
    "leftGroupWithStaleMemberCannotApplyOrBufferReplies",
}, DIRECT_REPLY_FFI_CLASS: {
    "selfClaimedIdCannotPassThePinnedReplyOrLegacyMirrorBinding",
}, SCOPED_ACK_CLASS: {
    "onlyMatchingPeerCanCommitReleaseOrForwardAndDuplicateRecoversCleanup",
    "savedAndLegacyRecipientsAreScopedAndGroupsNeverMatch",
    "missingActiveRowCanReachPartnerWithoutReleasingLocalCustody",
    "repositoryFallbackScopesIncomingButNeverTreatsItsOwnMirrorAckAsDelivery",
    "repositoryLegacyJunkRepairUsesStoredSenderBeforeDeletingRows",
    "roomTransactionPreventsRetargetingBetweenScopeCheckAndUpdate",
    "failedOrCancelledTransactionCannotReleaseOrForward",
    "observerAndCleanupFailuresAreIndependentButCancellationStillPropagates",
    "legacyMqttEventWithoutSenderRemainsUnscopedForCompatibility",
}, MIRROR_OUTGOING_CLASS: {
    "replayedPartnerFramesNeverReplaceDeliveredRows",
    "concurrentFirstFramesHaveExactlyOneInsertionAcrossReopen",
    "idCollisionsCannotTouchAnotherChatOrIncomingRow",
    "peerScopedClearBlocksAllMirrorPathsWithoutTouchingOtherPeers",
    "missingOrChangedChatAndMalformedRecipientFailClosed",
    "clearDuringInsertionRemovesOnlyTheNewRowAndLeavesNoPreview",
    "retryRequiresSameScopeBodyAndCurrentNonterminalStatus",
    "missingManualRetryIsDurableButClearedIdsAreNotRecreated",
    "echoIsScopedAndNeverDowngradesSentOrDeliveryReceipt",
    "pairedTextReplyAndPaletteSurviveRoomReopen",
    "replySidecarUsesParentClearScopeAndRequiresTheRoomRow",
    "storageFailureAndCancellationRollbackInsertedRowAndPreview",
    "echoNeverDeletesAnIgnoredConflictingRowWhenClearArrives",
}, MIRROR_PENDING_CLASS: {
    "queuedQuoteAndPaletteSurviveReopenJsonAndActiveStaging",
    "personalQueryExcludesGroupsAndTopicsBeforeTheLimit",
    "durableTombstoneSuppressesPendingFrameAcrossReopen",
    "pendingPagesPassBlockedRowsAndWrapWithRealRoom",
    "rowOrGroupChangingAfterSelectionDoesNotProduceAFrame",
}, OUTBOX_CLASS: {
    "actualRoomQueueReachesLaterRecipientPastManyBlockedRows",
    "aBadOldestMessageDoesNotHideLaterMessagesInTheSameRoomChat",
    "growingRoomQueueEndsScanAndRetriesBlockedChat",
    "discoveryQueryIsContactScopedAndBoundedAcrossChats",
    "initialFailureCannotUndoConcurrentSuccessOrAck",
    "roomKeysetPaginationKeepsAllEqualTimestampIds",
    "unconfirmedPersonalTextRetriesSameIdAndStopsAfterRoomAck",
    "unconfirmedPumpSkipsGroupsDeferredMediaAndServicesAndRespectsMirror",
    "roomReconciliationRepairsCommittedAckAfterRestartWithoutTouchingOtherRows",
    "ackDuringLateNativeSendReconcilesTheNewlyRetainedCopy",
    "queuedPageRereadsAcknowledgedDeletedAndEditedRows",
    "queuedRetriesKeepSavedRecipientAndParseLegacyLinksWithoutUnsafeFallback",
    "discoveryRoomQueryAndRetryRespectSavedRecipientsAndLiveRows",
    "unconfirmedRoomRetriesLegacyRecipientInsteadOfChangedChat",
    "durableClearTombstonesSuppressAllAutomaticRetriesWithoutBlockingOtherRows",
    "discoveryRotatesPastClearedRoomWindowsWithoutSendingThem",
    "failedClearPreflightSkipsOnlyThatRowAndNeverCountsAsNetwork",
    "cancelledClearPreflightPropagatesAndEachRetryCanResume",
}, REPLY_OUTBOX_CLASS: {
    "queuedAndUnconfirmedRetriesSurviveReopenUntilParentAck",
    "outgoingSidecarRejectsNoncanonicalChannelsAfterRoomReopen",
    "discoveryRetryOffersSavedReplyAndStopsAfterParentAck",
    "earlyReplyIsNotAcknowledgedUntilARealRoomParentExists",
    "deletedRetargetedGroupTopicAndClearedRowsNeverSendReply",
    "parentAckDuringBodyKeepsCurrentReplyButStopsLaterScans",
    "failedReplyAndParentAckLeaveNoDurableSidecarRetry",
    "manualRetryUsesManualTransportAndParentClearScope",
}, DELETION_OUTBOX_ROUTING_CLASS: {
    "legacyAckRouteRemovesOnlyTheNamedPeerRequest",
    "legacyAckRouteLeavesAmbiguousRequestsForRetry",
}, CLEAR_LOOKUP_CLASS: {
    "survivesRestartAndScopesByOwnerAndPeer",
    "pagesAreStableAndOwnerScoped",
    "firstLookupRequiresIdentityAndUsesRealStorageFromAnAbsentFile",
    "lookupWaitsForAConcurrentFirstTombstoneTransaction",
}, PUSH_CLASS: {
    "encryptedStateSurvivesCloseAndReopenAndRejectsRollback",
    "unsealedWrongPinWrongNodeAndWrongTargetNeverCreateState",
    "changedPinOrLocalBindingRequiresExplicitRecoveryAndCannotOverwriteOldState",
    "corruptedCiphertextAndMissingKeystoreKeyFailClosed",
    "quotaDoesNotEvictOrRearmTheFirstPeer",
    "conflictingConcurrentOffersHaveExactlyOneWinnerAcrossInstances",
}, REGISTRATION_CLASS: {
    "reservationIsEncryptedAndSurvivesReopen",
    "concurrentReservationsNeverReuseASequenceAcrossInstances",
    "bindingChangesAndWrongNodesCannotOverwriteExistingState",
    "delayedRegistrationResponseCannotConfirmANewerAttempt",
    "revocationAndRotationSurviveRestartWithoutLosingTheOldSequence",
    "corruptionOrMissingKeystoreKeyNeverSilentlyResetsState",
    "routeIndexIsEncryptedAndRemovedAsSoonAsRevocationBegins",
    "collidingRouteNeverEvictsAnotherRegistrationOrConfirmsTheWrongPeer",
    "damagedRouteIndexCannotBeUsedOrSilentlyReplaced",
    "versionOneMigrationPreservesCountersButRequiresAConfirmedRoute",
    "recoveryPagesAreBoundedDurableAndRepeatableAfterConcurrentInsertion",
    "recoveryPageNeverExceedsSixteenRoutesAndUsesKeysetContinuation",
    "recoveryPageNeverReturnsAPartialPageAroundCorruptEncryptedState",
    "recoveryPageDetectsMalformedAndPartialIndexEvenBeforeTheCursor",
    "recoveryPageRejectsEmbeddedNullBeforeTheCursor",
    "recoveryPageDoesNotRegenerateALostKeystoreKey",
    "recoveryRejectsAnOversizedJournalInsteadOfSkippingItsTail",
    "concurrentCreatorsCannotExceedTheRegistrationQuota",
    "quotaAndFailedEntropyDoNotEvictOrCreateARegistration",
}, PUSH_INTEGRATION_CLASS: {
    "realFfiAndDeviceJournalsMatchWorkerVectorsWithoutProviderNetwork",
    "mailboxRoundTripAuthenticatesRealFfiAndPreviouslyPinnedParticipants",
}, MAILBOX_DRAIN_CLASS: {
    "realRoomCommitPrecedesAckAndReplayedEnvelopeDeduplicatesAfterReopen",
}, MAILBOX_ROOM_CLASS: {
    "rowPreviewUnreadAndAlertCommitOnceAcrossReopenEvenWhenArrivalTimeDiffers",
    "missingChangedPinsOrAmbiguousChatsNeverMutateRoom",
    "conflictingRowsOrRichMetadataAreNeverTreatedAsDuplicates",
    "existingFileOrPollMetadataPreventsFalseTextCommit",
    "staleSuppressionCancellationAndMutatedPinRollBackAllChanges",
    "clearRecordedDuringInsertionIsObservedBeforeRoomCommit",
    "sqliteFailureRollsBackAndTheSameMessageCanBeRetried",
    "delayedTextKeepsNewerPreviewAndFutureTimeIsCapped",
    "concurrentCopiesAndOrdinaryIngressCannotRearmOrReplaceTheWinner",
}, MAILBOX_OUTBOX_CLASS: {
    "unsupportedBodiesNeverCreateOrReadAnOutboxRecord",
    "committedTripleSurvivesReopenAndALostResponseWithoutResealing",
    "concurrentDifferentCiphertextsAndCutoffsCommitOneWinner",
    "changedMessagePinBindingOrEpochCannotReplaceAnExistingRecord",
    "invalidInputsOrCutoffsNeverSealOrInsert",
    "expiryIsTerminalAndRollbackOfWallClockNeverExtendsIt",
    "retiringIsCompareAndSetAndAStaleCallbackRollsBack",
    "staleOrCancelledPrepareAfterInsertRollsBackTheTransaction",
    "quotasNeverEvictTerminalHistoryOrPartiallyInsertLargeCiphertexts",
    "boundedExpirySweepPreservesMarkersAndOtherActiveRecords",
    "corruptedPayloadOrUnauthenticatedExpiryCannotBeReplaced",
    "missingKeystoreKeyDoesNotRecreateItWhenRecordsExist",
}, MAILBOX_OUTBOX_FFI_CLASS: {
    "realFfiPersistsAndReusesTheWireBeforeAnAuthenticatedRoundTrip",
}, PUSH_INBOX_CLASS: {
    "routeAndOverflowRecoverySurviveReopenWithoutPrivateFields",
    "newWakeDuringLeaseCannotBeErasedByTheOldCompletion",
    "abandonedLeaseRecoversAndLateOwnerCannotDeleteReplacement",
    "expiryAndReinsertionCannotReuseAnOldLease",
    "expiredRoutesCoalesceIntoDurableRecoveryRatherThanDisappearing",
    "recoveryMarkerSurvivesMultipleExpiryPeriodsAndReopen",
    "expiringRouteCannotBeErasedByAnInflightRecoveryCompletion",
    "expiredRecoveryLeaseRequiresANewRecoveryPass",
    "recoveryAndExpiredRouteDeletionRollBackTogetherOnSqlFailure",
    "exhaustedRecoveryRevisionDoesNotDiscardExpiredRoutes",
    "recreatedDatabaseRejectsAnOldClaimWithMatchingCounters",
    "restoredPreLeaseSnapshotRejectsOldClaimEvenWhenCountersRepeat",
    "versionOneMigrationPreservesWakesAndReleasesLegacyLeases",
    "versionTwoMigrationPreservesMarkerAndFencesLegacyLeases",
    "markerIncarnationSurvivesRetryAndRevisionButNotARecreatedMarker",
    "invalidMarkerEntropyOrCorruptIncarnationCannotConsumeWakes",
    "failedLeaseEntropyRollsBackTheClaimAndPreservesTheWake",
    "wrongOrCorruptedLeaseNonceCannotConsumeARealClaim",
    "failedWorkRespectsBackoffAndDoesNotLoseANewerWake",
    "recoveryEventsCoalesceWithoutBeingLostDuringAnActiveSync",
    "rollbackOfWallClockCannotLeaveAnAbandonedLeaseStuckForDays",
    "concurrentInstancesCoalesceAndStillPreserveAnInflightWake",
    "dispatcherRedrivesNewSignalsAndRestartUsingRealInboxWithFakeScheduler",
    "unavailableAdmissionPersistsOnlyGenericRecoveryAcrossReopen",
    "malformedRoutesBadClockAndExhaustedLeaseSequenceFailClosed",
}, PUSH_PERIODIC_CLASS: {
    "emptyPeriodicTickCreatesOneGenericMarkerAcrossRestart",
    "periodicTicksPreserveRetryBackoffAcrossReopen",
    "providerEventsDuringPeriodicLeaseAdvanceRecoveryRevision",
    "periodicNormalizationCommitsExpiredRouteDuringARecoveryLease",
    "periodicNormalizationRenewsAnExpiredMarkerWithoutErasingItsLease",
    "periodicNormalizationPreservesBackoffWhileCoalescingExpiredRoute",
    "failedPeriodicNormalizationRollsBackBeforeRetry",
    "concurrentPeriodicTicksCoalesceWithoutHidingProviderEvent",
    "periodicPassRepairsFailedCallbackWithSqliteAndFakeSync",
    "expiredLeaseCannotStartConcurrentDrainsInOneClassloader",
    "periodicTickRacingLeasedScanNeedsLaterTickForUnrecordedCallback",
}, PUSH_RECOVERY_CHECKPOINT_CLASS: {
    "encryptedProgressAndFailureBitSurviveReopen",
    "scopeChangesResetCursorAndInvalidateOlderWritersAcrossInstances",
    "progressIsMonotonicAndOnlyTheCurrentSnapshotMayReset",
    "revisionIsAuthenticatedAndNewerWakeDoesNotInterruptAnUnfinishedPass",
    "newerRecoveryBetweenLeasesNeedsAFullFreshPassAcrossReopen",
    "recreatedInboxCannotResumeAnOldMarkersPartialCheckpoint",
    "currentCheckAfterWriteRollsBackAdvancesResetsAndScopeChanges",
    "concurrentWritersHaveExactlyOneWinnerAndStaleTokensStayStale",
    "malformedCiphertextTokenAndMissingKeyNeverSilentlyResetProgress",
    "malformedTokenAndOversizedOrWrongTypePayloadFailClosed",
    "missingOrRepeatedEntropyCannotAdvanceCheckpoint",
    "cancellationAfterCheckpointCommitKeepsTheWakeAndResumesTheNextRoute",
    "cancellationAfterFinalResetReplaysPassAndKeepsWake",
    "realIndexInboxAndCheckpointResumeAcrossReopenWithoutLosingFailedRoute",
}, PUSH_DRAIN_CLASS: {
    "mixedSignalsAreInspectedAndAllowedRoutesShareOneGenericSync",
    "batchOutcomesConsumeOnlyTheCorrespondingSqliteLeases",
    "duplicateBatchResultRetainsAllSqliteLeasesForRetry",
    "wakesArrivingWhileSyncRunsSurviveAndAreRedriven",
    "inspectAndSyncFailuresRetryWithoutBlockingOtherRoutes",
    "startingWithoutACompletedReceiveWindowDoesNotConsumeTheWake",
    "cancellationKeepsLeaseAndRecoveryCanRetryAfterDeadline",
    "nonCooperativeSyncCancellationKeepsSqliteLeaseForRecovery",
    "cancellationAfterSqliteCommitLeavesRemainingLeaseForRecovery",
    "eachPassIsBoundedAndUnprocessedRoutesRemainPending",
}, PUSH_WORK_CLASS: {
    "independentSuccessorRunsBeforeOlderWorkerReturnsAndSurvivesItsFailure",
    "enqueueFailuresReleaseTheGateAndPreserveThePersistedSignal",
    "concurrentWorkersRetryWithoutOverlappingSyncAndPeriodicRecoveryRedrives",
    "retryResultKeepsTheSignalUntilIndependentPeriodicRecovery",
    "periodicRecoveryDirectlyDrainsPersistentInboxDespiteStaleTicket",
}, FIREBASE_CLASS: {
    "configuredSdkHasAutoInitDisabledWithoutTokenRequest",
    "wakeCryptoFfiRejectsInvalidInputWithoutStartingPush",
}, CORE_LIFECYCLE_CLASS: {
    "staleServiceStopCannotStopARealEngineAdoptedByAnotherOwner",
    "rejectedRealEngineIsStoppedBeforeAReplacementIsPublished",
}, HEARTBEAT_CLASS: {
    "oldServiceCallbacksDoNotClearOrRefreshAReplacementHeartbeat",
    "externalStartRefusalDoesNotDisableTheRunningServicesFuturePulses",
}}

# Real Room, NotificationManager and WorkManager adapters on an empty emulator.
LOCAL_NOTIFICATION_EXPECTED = {
    'com.vladimir.messenger.data.backup.BackupNotificationRestoreInstrumentedTest': {
        'replaceConsumesWalBeforeMovingDatabaseAndNeverRearmsHistory',
        'populatedMergeImportsWalHistoryButKeepsLiveNotificationWork',
        'firstInstallMergeCopiesCheckpointedWalHistoryWithoutPendingWork',
        'sanitizerFailureStopsBeforeAnyLiveDatabaseOrIdentityMutation',
    },
    'com.vladimir.messenger.service.IncomingNotificationRecoveryInstrumentedTest': {
        'snapshotCompletionDoesNotClearChangedOrForeignRows',
        'postBeforeCompletionSurvivesReopenAndActivePostAvoidsSecondAlert',
        'simultaneousServiceAndWorkerProcessorsPostOnce',
        'boundedCursorSurvivesReopenAndVisitsPastFailuresBeforeWrapping',
        'checkpointIsPrivateProfileScopedAndMalformedValuesRescan',
        'hiltCreatesTheActualWorkerAndNoIdentityStopsTheSweep',
        'directReadClearsOnlyPriorWorkAndLaterArrivalsRemainPending',
        'directReadWaitsForAnInFlightPostAndCancelsItAfterCompletion',
        'groupAndTopicReadsClearOnlyTheirScopedPendingAlerts',
        'groupReadWaitsForInFlightPostAndCancelsOnlyTheReadTopic',
        'mirroredTopicReadKeepsOtherTopicUnreadAndPending',
        'sqlitePagesIncludeNegativeAndZeroRowidsAfterReopen',
        'notificationSchedulingIsAfterCommitAndNeverRearmsDuplicates',
    },
    'com.vladimir.messenger.service.NotificationManagerRecoveryInstrumentedTest': {
        'collidingChatIdsKeepSeparatePostsAndTapIntents',
        'readingOneTopicCancelsOnlyItsPostsIncludingSos',
        'disabledOrMissingAndroidChannelIsNotReportedAsPostable',
        'appMuteIsAnIntentionalSuppressionRatherThanASystemPost',
    },
    PERMISSION_CLASS: {
        'disposableProfileStartsWithPermissionGranted',
        'revokedPermissionLeavesTheCommittedNotificationPending',
        'grantingPermissionLetsTheReopenedPendingRowPostOnce',
    },
    'com.vladimir.messenger.worker.NotificationRecoveryWorkManagerInstrumentedTest': {
        'arrivalAfterFinalCheckStartsIndependentSuccessorBeforeOlderFailure',
        'arrivalBeforeFinishCreatesOneSuccessorEvenIfTheOlderWorkerFails',
        'independentPeriodicPassRepairsACommitWithoutAnEnqueueDespiteAStaleTicket',
    },
}
EXPECTED.update(LOCAL_NOTIFICATION_EXPECTED)


def check(results: Path, log: Path, status: int, groups=EXPECTED, strict=False) -> int:
    seen = set()
    errors = []
    for report in sorted(results.rglob("*.xml")):
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError as error:
            errors.append(f"Invalid test report {report}: {error}")
            continue
        for case in root.iter("testcase"):
            classname = case.get("classname", "").split("$")[0]
            name = case.get("name", "")
            if classname not in groups:
                if strict:
                    errors.append(f"Unexpected test in permission phase: {classname}.{name}")
                continue
            if strict and name not in groups[classname]:
                errors.append(f"Unexpected test in permission phase: {classname}.{name}")
                continue
            key = (classname, name)
            if key in seen and name in groups[classname]:
                errors.append(f"Duplicate expected test: {classname}.{name}")
            seen.add(key)
            for tag in ("failure", "error", "skipped"):
                for bad in case.findall(tag):
                    detail = (bad.get("message") or bad.text or tag).strip()[:1000]
                    errors.append(f"{name}: {tag}: {detail}")
    if status:
        lines = log.read_text(encoding="utf-8", errors="replace").splitlines() if log.exists() else []
        important = [line for line in lines if line.startswith("e: ")]
        errors.insert(0, f"Gradle exited {status}: " + " | ".join((important[-10:] + lines[-10:]))[:2000])
    expected = {(classname, name) for classname, names in groups.items() for name in names}
    errors += [f"Expected test did not execute: {classname}.{name}" for classname, name in sorted(expected - seen)]
    for error in errors:
        safe = error.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::error::{safe}")
    print(f"::notice::Android notification, outbox, experimental push storage, heartbeat and core ownership tests: {len(seen & expected)}/{len(expected)}")
    return 1 if errors else 0


def main() -> int:
    if len(sys.argv) not in (4, 6):
        print("usage: check-android-notification-results.py RESULT_DIR GRADLE_LOG GRADLE_STATUS [--phase METHOD]", file=sys.stderr)
        return 2
    groups, strict = EXPECTED, False
    if len(sys.argv) == 6:
        if sys.argv[4] != "--phase" or sys.argv[5] not in EXPECTED[PERMISSION_CLASS]:
            print("Unknown permission test phase", file=sys.stderr)
            return 2
        groups, strict = {PERMISSION_CLASS: {sys.argv[5]}}, True
    return check(Path(sys.argv[1]), Path(sys.argv[2]), int(sys.argv[3]), groups, strict)


if __name__ == "__main__":
    raise SystemExit(main())
