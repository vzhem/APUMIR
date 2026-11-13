#!/usr/bin/env python3
"""Require mailbox, mirror and reply-retry JVM regressions to run, without skips.

The full Gradle test task normally discovers these automatically. This gate
also detects a missing/renamed class or a test accidentally filtered out. It is
not evidence of real TLS, Android Room/Keystore, UniFFI, a provider, or delivery.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

PREFIX = "com.vladimir.messenger.data.push."
PERIODIC_CLASS = PREFIX + "PushWakePeriodicRecoveryTest"
PERIODIC_EXPECTED = {
    "allowedTickPersistsGenericMarkerBeforeDirectDrain",
    "disabledOrUnavailableAdmissionNeverWritesOrStartsDrain",
    "unavailableOrUnexpectedMarkerNeverRunsOrReportsSuccess",
    "retryingDrainDoesNotPretendTheSavedMarkerWasProcessed",
    "failedAdmissionMarkerOrDrainAreReportedWithoutBecomingSuccess",
    "cancellationPropagatesFromEachStageWithoutBeingReported",
    "cancellationAfterMarkerCommitLeavesItForAnotherTick",
    "cancellationWhileAdmissionIsSuspendedDoesNotWriteMarker",
    "cancellationAfterAdmissionOrDrainCannotReturnATaskResult",
    "cancellationWhileDrainIsSuspendedLeavesTheMarkerForAnotherTick",
    "diagnosticFailureCannotMakeARecoverableFailureTerminal",
    "cancellationRacingAnOrdinaryFailureIsNotReturnedAsRetry",
    "independentTickRepairsAnUnrecordedCallbackDespiteAStaleGate",
}
EXPECTED = {
    PERIODIC_CLASS: PERIODIC_EXPECTED,
    PREFIX + "PushMailboxHttpClientTest": {
        "putStoresOnlyWithAValidServerReceiptAndClearsTransientBytes",
        "serverAcceptanceAndRetryStatesAreNotDelivery",
        "pullMatchesSignedWorkerVectorAndReturnsOnlyABoundedCheckedPage",
        "acknowledgementsAreSortedSnapshotAndOnlyMeanServerAcceptedAnOperation",
        "invalidRequestsNeverReachTheNetworkAndInputIsBounded",
        "putIdBindsCiphertextAndImmutableCutoffToTheIndependentWorkerVector",
        "envelopeRequiresCanonicalBase64AndExactSeparators",
        "malformedDuplicateOversizedAndAmbiguousResponsesFailClosed",
        "failureAndCancellationDoNotLeakOrTurnIntoSuccess",
    },
    PREFIX + "PushMailboxAccessSignerTest": {
        "signedPullAndAckMatchWorkerVectorsAndWipeBorrowedBuffers",
        "onlyCurrentRegisteredReceiverWithMatchingPinAndEpochCanSign",
        "cursorAndReceiptsAreSignedAsBinaryAndNotSilentlyResorted",
        "alteredFfiInputsAreRejectedAndEveryTemporaryBufferIsWiped",
        "changedCallerBindingsDuringFfiAreRejectedWithoutClearingCallerArrays",
        "closedMaterialAndBadSignerOutputCannotCreateAnAccessRequest",
        "failuresAndCancellationAreGenericAndClearInputs",
    },
    PREFIX + "PushMailboxHttpsTransportTest": {
        "postsOnlyToTheReviewedHttpsHostWithRedirectsDisabledAndBoundedIo",
        "redirectsAndErrorBodiesAreNeverReturnedAsSuccessData",
        "malformedContentTypeEncodingAndDeclaredLengthsFailClosed",
        "identityEncodingAndExactlyTheResponseCapRemainAcceptable",
        "streamingCapAndDeclaredLengthAreBothEnforcedAndStreamsClosed",
        "pathsOutsideTheEndpointAllowlistCannotOpenAConnection",
    },
    PREFIX + "PushEpochEnvelopeTest": {
        "offerAndRevokeUseTheExistingEncryptedWireAndClearFfiInputs",
        "unregisteredStateChangedPinsAndInvalidKeysFailBeforeFfi",
        "malformedWrongRecipientAndWrongManifestAreRejected",
        "mutatedFfiInputsAreRejectedEvenWhenTheReturnedHeaderLooksCorrect",
        "zeroEntropyDoesNotReachFfi",
        "ffiFailuresAndCancellationWipeBorrowedBuffers",
    },
    PREFIX + "PushMailboxMessageCodecTest": {
        "authenticatedPayloadCarriesTheOriginalIdBothPeersAndExactUnicodeText",
        "independentlyExpectedPeersAndCanonicalMetadataAreRequired",
        "byteLimitAndMalformedUtf16AreRejectedWithoutSplittingTheBody",
    },
    PREFIX + "PushMailboxMessageOpenerTest": {
        "validOpenedMessageIsBoundToThePinnedSenderLocalRecipientAndOriginalId",
        "missingWrongOrSelfPinsAndWrongNodesNeverUnwrapAKey",
        "ciphertextAndUtf8TamperingAndWrongBoundMetadataAreRejected",
        "alteredBindingIdentityFfiCannotRelabelTheEnvelope",
        "changedFfiInputsFailClosedAndAllBorrowedBuffersAreCleared",
        "changedCallerSnapshotsOrCurrentStateCannotReturnPlaintext",
        "invalidKeyExceptionsAndCancellationNeverProduceAMessage",
    },
    PREFIX + "PushMailboxMessageSealerTest": {
        "originalMessageRoundTripsInsideAnAuthenticatedEnvelopeAndBuffersAreCleared",
        "invalidInputsPeersAndStateNeverInvokeKeyCreation",
        "changingFfiInputsOrReturningMalformedHeadersFailsClosed",
        "changedCallerSnapshotsAndCurrentStateCannotReturnWire",
        "changingBindingWhileResolvingItsNodeIdIsRejectedAndCleared",
        "zeroEntropyExceptionsAndCancellationNeverCreateAResult",
        "eachSealingGeneratesANewKeyAndNonceRatherThanAStableRetryWire",
    },
    PREFIX + "PushMailboxPutBuilderTest": {
        "senderBuildsOnlyTheStoredTripleForTheMatchingOfferedEpoch",
        "wrongPinIdentityEpochOrCutoffCannotProduceARequest",
        "changedCallerBuffersOrClosedMaterialDuringBuildFailClosed",
        "callbackFailureAndCancellationDoNotLeakOrTurnIntoSuccess",
    },
    PREFIX + "PushMailboxDrainTest": {
        "ackFollowsDurableCommitsAndUsesSortedServerReceipts",
        "unprocessedItemsAreNeverAcknowledgedButLaterItemsAreStillVisited",
        "commitExceptionsAreNotAckedAndCancellationNeverAcknowledgesThePage",
        "failedAckRetriesTheSameAlreadyCommittedMessageWithoutAdvancingCursor",
        "changedTrustWithholdsTheAckEvenAfterACommit",
        "malformedPagesAreRejectedBeforeAnyItemIsCommitted",
        "repeatedIdsAcrossPagesAreRejectedWithoutProcessingThemAgain",
        "boundedCheckpointPreservesUnprocessedStateAndCursorExpiryRestarts",
        "boundedSnapshotSurvivesPageMutationAndRejectsUnboundedViews",
        "recentIdsDetectCyclesAcrossDurableContinuationBoundaries",
        "longerCycleIsBoundedEvenAfterOldIdsLeaveTheCheckpoint",
        "checkpointValidationAndTotalPageBudgetFailClosedWithoutMoreEffects",
        "transportFailuresAndCancellationNeverTurnIntoCompletedScans",
    },
}

MIRROR_PENDING_EXPECTED = {
    "com.vladimir.messenger.data.repository.MirrorPendingOutgoingTest": {
        "pendingFramesKeepStoredReplyColorsAndRoutingFields",
        "absentTargetNeverExportsOrphanedQuoteText",
        "changedOrDeletedSnapshotsNeverProduceFramesAndDoNotBlockOthers",
        "groupTopicIncomingTerminalMalformedAndMissingChatAreRejected",
        "rawAndCanonicalTombstonesAndParentPreflightFailClosed",
        "perRowFailureIsReportedAndCancellationPropagates",
        "queryLimitIsBoundedAndNonPositiveLimitsDoNotRead",
        "blockedOldRowsCannotHideLaterRowsAndAreRevisitedAfterWrap",
        "growingQueueCannotStopAnExistingPassFromWrapping",
        "concurrentPageRequestsAdvanceTheSingleScanWithoutDuplicates",
    },
    "com.vladimir.messenger.data.repository.MirrorPendingScheduleIntegrationTest": {
        "periodicPagesReachAQuoteBeyondBlockedRowsAndRetryALostOffer",
        "newLocalWorkInterruptsIdleBackoffWithoutPretendingItWasDelivered",
    },
    "com.vladimir.messenger.data.mirror.MirrorPendingFlusherTest": {
        "pairingCanCarryAPageBeforeTheSocketOpensButNotAfterShutdown",
        "freshPartnerBecomingActiveWakesWithoutEveryHeartbeatDoingSo",
        "wakeShortensIdleBackoffButStillRespectsCooldown",
        "availabilityAndCooldownBoundPeriodicRequests",
        "concurrentTriggersNeverOverlapAnInflightPage",
        "disconnectDuringLoadOrOffersStopsTheRemainingFrames",
        "aFailedOfferDoesNotBecomeAnAckAndCanBeOfferedAgain",
        "cancellationAndFailureReleaseGateWithoutLeakingAnErrorAsSuccess",
        "cancellationAfterLoadingDoesNotStartIdleBackoff",
        "clockRollbackDoesNotDisableTheGateForever",
        "reorderedConcurrentClockSamplesCannotBypassCooldown",
        "completedSweepsBackOffButNewWorkRestoresTheShortDelay",
        "idleSweepBackoffIsCappedAtTenMinutes",
        "unfinishedPagesUseTheShortDelayEvenAfterIdleBackoff",
        "newWorkDuringADeferredPageIsNotLostAtCompletion",
        "alreadyCancelledScopeDoesNotStartWork",
    },
}
EXPECTED.update(MIRROR_PENDING_EXPECTED)

MIRROR_OUTGOING_FRAME_CLASS = "com.vladimir.messenger.data.mirror.MirrorOutgoingFrameTest"
MIRROR_OUTGOING_FRAME_EXPECTED = {
    "openingPrecedesTrustAndBothSeeTheSameWire",
    "failedOpenNeverConsultsTrustOrProducesARow",
    "pairedRoomKeyAcceptsOnlyAnIntactAuthenticatedFrame",
    "anOpenedPayloadStillNeedsTheCorrectPairingKey",
    "legacyFallbackRequiresTheExactLocalBindingNotAClaimedNode",
    "exceptionsCannotTurnAnUnverifiedFrameIntoAnAcceptedRow",
}
EXPECTED[MIRROR_OUTGOING_FRAME_CLASS] = MIRROR_OUTGOING_FRAME_EXPECTED

MIRROR_OUTGOING_DISPATCH_CLASS = "com.vladimir.messenger.data.repository.MirrorOutgoingDispatcherTest"
MIRROR_OUTGOING_DISPATCH_EXPECTED = {
    "policyAcceptsOnlyRetryableWellFormedPairedRequests",
    "relabelledSentEchoCannotCreateChatOrTriggerSend",
    "scopedTombstoneAndIdentityFailurePreventChatCreationOrSend",
    "aliasChecksBothScopesAndRoutesOnlyTheMatchingMode",
    "aClearedCanonicalAliasNeverCreatesAChat",
    "cancellationPropagatesAndOrdinaryFailuresAreReported",
}
EXPECTED[MIRROR_OUTGOING_DISPATCH_CLASS] = MIRROR_OUTGOING_DISPATCH_EXPECTED

REPLY_OUTBOX_CLASS = "com.vladimir.messenger.data.repository.ReplyAwareOutboxSenderTest"
REPLY_OUTBOX_EXPECTED = {
    "retriesStoredReplyAfterBodyUsingStableSidecarId",
    "falseBodyResultStillOffersReplyButBlockedBodyDoesNot",
    "manualRetryUsesItsOwnBodySenderAndSameReplyPolicy",
    "deletedChangedIncomingGroupOrMissingReplyNeverSendsSidecar",
    "replySidecarRequiresCanonicalPersonalChannelOnItsOwnReread",
    "malformedParentOrPeerAndRejectedScopeNeverSend",
    "sidecarRereadsChangedQuoteOrRecipientBeforeTransport",
    "parentClearPreflightAfterBodySuppressesReply",
    "racingBodyAckDoesNotSuppressTheCurrentReplyAttempt",
    "replyFailureIsRecordedButDoesNotChangeBodyResult",
    "parentTransportExceptionNeverAttemptsReply",
    "cancellationPropagatesAndReleasesReplyGate",
    "concurrentAutomaticAndManualRetriesShareOneReplyGate",
    "cancellationDuringReplyReleasesTheGateForTheNextRetry",
    "boundedGateSkipsOverflowWithoutBlockingBodiesAndRecovers",
    "blockedReplyDoesNotBlockAnotherMessage",
}
EXPECTED[REPLY_OUTBOX_CLASS] = REPLY_OUTBOX_EXPECTED


def check(results: Path) -> int:
    expected = {(classname, name) for classname, names in EXPECTED.items() for name in names}
    seen = set()
    errors = []
    for report in sorted(results.rglob("*.xml")):
        try:
            root = ET.parse(report).getroot()
        except (ET.ParseError, OSError) as error:
            errors.append(f"Invalid test report {report}: {error}")
            continue
        for case in root.iter("testcase"):
            key = (case.get("classname", ""), case.get("name", ""))
            if key not in expected:
                continue
            if key in seen:
                errors.append(f"Duplicate expected test: {key[0]}.{key[1]}")
            seen.add(key)
            for tag in ("failure", "error", "skipped"):
                if case.find(tag) is not None:
                    errors.append(f"Expected test {tag}: {key[0]}.{key[1]}")
    errors += [f"Expected test did not execute: {classname}.{name}" for classname, name in sorted(expected - seen)]
    for error in errors:
        safe = error.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::error::{safe}")
    print(f"::notice::Mailbox, mirror and reply-retry JVM regressions: {len(seen & expected)}/{len(expected)}")
    return 1 if errors else 0


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: check-push-mailbox-jvm-results.py RESULT_DIR", file=sys.stderr)
        return 2
    return check(Path(sys.argv[1]))


if __name__ == "__main__":
    raise SystemExit(main())
