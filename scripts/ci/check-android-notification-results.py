#!/usr/bin/env python3
"""Fail when Android notification, outbox, push-storage or core-ownership tests did not all run."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

CLASS = "com.vladimir.messenger.data.notification.NotificationArrivalInstrumentedTest"
OUTBOX_CLASS = "com.vladimir.messenger.data.repository.OutboxFairnessInstrumentedTest"
PUSH_CLASS = "com.vladimir.messenger.data.push.PushEpochStoreInstrumentedTest"
REGISTRATION_CLASS = "com.vladimir.messenger.data.push.PushRegistrationStoreInstrumentedTest"
PUSH_INTEGRATION_CLASS = "com.vladimir.messenger.data.push.PushWakeIntegrationInstrumentedTest"
PUSH_INBOX_CLASS = "com.vladimir.messenger.data.push.PushWakeInboxInstrumentedTest"
PUSH_DRAIN_CLASS = "com.vladimir.messenger.data.push.PushWakeDrainInstrumentedTest"
FIREBASE_CLASS = "com.vladimir.messenger.data.push.FirebaseClientConfigurationInstrumentedTest"
CORE_LIFECYCLE_CLASS = "com.vladimir.messenger.data.CoreLifecycleOwnershipInstrumentedTest"
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
EXPECTED = {CLASS: EXPECTED_NOTIFICATION, OUTBOX_CLASS: {
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
    "quotaAndFailedEntropyDoNotEvictOrCreateARegistration",
}, PUSH_INTEGRATION_CLASS: {
    "realFfiAndDeviceJournalsMatchWorkerVectorsWithoutProviderNetwork",
}, PUSH_INBOX_CLASS: {
    "routeAndOverflowRecoverySurviveReopenWithoutPrivateFields",
    "newWakeDuringLeaseCannotBeErasedByTheOldCompletion",
    "abandonedLeaseRecoversAndLateOwnerCannotDeleteReplacement",
    "expiryAndReinsertionCannotReuseAnOldLease",
    "failedWorkRespectsBackoffAndDoesNotLoseANewerWake",
    "recoveryEventsCoalesceWithoutBeingLostDuringAnActiveSync",
    "rollbackOfWallClockCannotLeaveAnAbandonedLeaseStuckForDays",
    "concurrentInstancesCoalesceAndStillPreserveAnInflightWake",
    "malformedRoutesBadClockAndExhaustedLeaseSequenceFailClosed",
}, PUSH_DRAIN_CLASS: {
    "mixedSignalsAreInspectedAndAllowedRoutesShareOneGenericSync",
    "wakesArrivingWhileSyncRunsSurviveAndAreRedriven",
    "inspectAndSyncFailuresRetryWithoutBlockingOtherRoutes",
    "startingWithoutACompletedReceiveWindowDoesNotConsumeTheWake",
    "cancellationKeepsLeaseAndRecoveryCanRetryAfterDeadline",
    "eachPassIsBoundedAndUnprocessedRoutesRemainPending",
}, FIREBASE_CLASS: {
    "configuredSdkHasAutoInitDisabledWithoutTokenRequest",
    "wakeCryptoFfiRejectsInvalidInputWithoutStartingPush",
}, CORE_LIFECYCLE_CLASS: {
    "staleServiceStopCannotStopARealEngineAdoptedByAnotherOwner",
}}


def check(results: Path, log: Path, status: int) -> int:
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
            if classname not in EXPECTED:
                continue
            name = case.get("name", "")
            seen.add((classname, name))
            for tag in ("failure", "error", "skipped"):
                for bad in case.findall(tag):
                    detail = (bad.get("message") or bad.text or tag).strip()[:1000]
                    errors.append(f"{name}: {tag}: {detail}")
    if status:
        lines = log.read_text(encoding="utf-8", errors="replace").splitlines() if log.exists() else []
        important = [line for line in lines if line.startswith("e: ")]
        errors.insert(0, f"Gradle exited {status}: " + " | ".join((important[-10:] + lines[-10:]))[:2000])
    expected = {(classname, name) for classname, names in EXPECTED.items() for name in names}
    errors += [f"Expected test did not execute: {classname}.{name}" for classname, name in sorted(expected - seen)]
    for error in errors:
        safe = error.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::error::{safe}")
    print(f"::notice::Android notification, outbox, experimental push storage and core ownership tests: {len(seen & expected)}/{len(expected)}")
    return 1 if errors else 0


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: check-android-notification-results.py RESULT_DIR GRADLE_LOG GRADLE_STATUS", file=sys.stderr)
        return 2
    return check(Path(sys.argv[1]), Path(sys.argv[2]), int(sys.argv[3]))


if __name__ == "__main__":
    raise SystemExit(main())
