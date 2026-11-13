#!/usr/bin/env python3
"""Require mailbox, mirror and reply-retry JVM regressions to run, without skips.

The full Gradle test task normally discovers these automatically. This gate
also detects a missing/renamed class or a test accidentally filtered out. It is
not evidence of Android Room/Keystore, UniFFI, provider TLS, or delivery.
The transport tests include real TLS only against a disposable loopback server.
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
RUN_LOOP_CLASS = PREFIX + "PushWakeRunLoopTest"
RUN_LOOP_EXPECTED = {
    "newSignalDuringDrainIsRetriedAndAfterFinishSchedulesASuccessor",
    "failedDrainOrUnavailableFinalReadNeverBecomeSuccess",
    "cancellationReleasesOnlyTheWorkerTicketAndAllowsAnotherRequest",
    "busyLoopRetriesWithoutReplacingTheActiveTicket",
    "distinctDispatchersShareTheClassloaderDrainLimit",
    "simultaneousThreadsAdmitOneDrainAndRetryEveryOtherWorker",
    "cancelledCallerCannotTurnBusyLimitIntoRetry",
    "cancellationReleasesTheClassloaderLimitForAnotherLoop",
    "exceptionsAndBrokenDiagnosticsReleaseTheClassloaderLimit",
    "cancellationAfterDrainDoesNotReturnSuccessAndReleasesTicket",
    "cancellationRacingAnErrorReleasesTicketAndClassloaderLimit",
    "independentRecoveryPassCanRepairAStaleRetryTicket",
}
DRAIN_CLASS = PREFIX + "PushWakeDrainTest"
DRAIN_EXPECTED = {
    "startedButUnfinishedWindowRetriesAndOnlyACompletedWindowConsumes",
    "cancelledWindowLeavesTheClaimForLeaseRecovery",
    "cancellationRacingCallbacksCannotConsumeOrReleaseTheClaim",
    "cancellationExceptionsFromCallbacksPropagateWithoutCompletingClaims",
    "cancellationAfterOneCompletionLeavesTheRemainingLeaseUntouched",
    "cancellationDuringFinalPendingReadCannotReturnSuccess",
    "cancellationAtFinalClockDoesNotStartPendingRead",
    "brokenDiagnosticsStillRetryEachUnprocessedSignal",
    "failedInspectionRetriesAndDroppedRouteDoesNotStartSync",
    "onlyAllowedHintsReachTheBatchAndEachOutcomeControlsItsOwnLease",
    "recoveryItemCarriesTheExactLocalMarkerIdentityAndRevision",
    "malformedMarkerNeverReachesSyncOrCompletesItsLease",
    "wrongOutcomeCountRetriesEveryLeaseInsteadOfGuessing",
    "duplicateOrForeignResultsRetryTheEntireBatch",
    "exceptionReadingAResultRetriesAllLeasesWithoutPartialCompletion",
    "aPreviousBatchResultCannotCompleteANewLeaseForTheSameRoute",
    "separateLeasesForTheSameRouteRemainDistinctItems",
    "aBatchNeverIncludesMoreThanSixteenHints",
    "syncExceptionRetriesRatherThanAcknowledgingSignal",
}
RECOVERY_SCAN_CLASS = PREFIX + "PushWakeRecoveryScanTest"
RECOVERY_SCAN_EXPECTED = {
    "boundedPassResumesAndOnlyCompletesAfterTheLastReceiveWindow",
    "failedRouteDoesNotStarveLaterRoutesAndForcesAFullRescan",
    "droppedHintIsNotSyncedAndInspectionRetryStillPreventsSuccess",
    "unavailableProgressNeverPretendsTheScanCompleted",
    "crashBeforeCheckpointReplaysOnlyTheUncheckpointedRoute",
    "invalidPagesAndUnavailableIndexCannotStartEffects",
    "malformedOrInconsistentProgressCannotAuthorizeCompletion",
    "newerRevisionsFinishTheOldPassBeforeStartingAFullNewPass",
    "recreatedMarkerNeverInheritsAnEarlierPartialPass",
    "invalidOrOlderMarkerCannotCompleteOrStartEffects",
    "maximumStableRegistrationIndexFitsTheDefaultScanBudget",
    "changedScopeRestartsAndInvalidatesAnOlderWriter",
    "anotherWriterCannotBeOverwrittenAfterTheEffect",
    "changedCurrentIdentityPreventsFurtherEffectsAndCheckpointing",
    "cancelledCallbacksNeverTurnIntoCompletedRecovery",
    "cancellationRacingCurrentOrFailedProgressCannotReturnRetry",
    "thrownCancellationPropagatesInsteadOfRecordingOrAdvancing",
    "ordinaryFailuresDoNotStarveOtherRoutesEvenIfDiagnosticsIsBroken",
    "totalScanBudgetSurvivesContinuationAndRestartsWithoutFalseSuccess",
    "insertionBeforeCursorIsPickedUpByTheNextFullPass",
    "emptyIndexCompletesOnlyAfterDurableResetAndInvalidScopeNeverReads",
}
EXPECTED = {
    PERIODIC_CLASS: PERIODIC_EXPECTED,
    RUN_LOOP_CLASS: RUN_LOOP_EXPECTED,
    DRAIN_CLASS: DRAIN_EXPECTED,
    RECOVERY_SCAN_CLASS: RECOVERY_SCAN_EXPECTED,
    PREFIX + "PushHttpClientTest": {
        "configRequiresAnExactAllowedHttpsHostAndForbidsPathsAndRedirects",
        "registrationMatchesWorkerVectorAndRequestBytesAreCleared",
        "wakeHasOnlyOpaqueRouteFieldsAndAcceptedIsNotADeliveryReceipt",
        "invalidFieldsNeverReachTheNetwork",
        "cancellationPropagatesAndStillClearsTheRequestBody",
        "cancellationRacingAResultOrErrorCannotBecomeSuccessOrFailure",
        "brokenDiagnosticsCannotMaskCancellationOrPretendSuccess",
        "statusMappingAndFailuresNeverPretendToDeliver",
    },
    PREFIX + "PushWakeCryptoTest": {
        "matchingSenderAndReceiverProduceTheWorkerRouteAndWipeFfiBuffers",
        "receiverSignsOnlyReservedMatchingStateAndHttpUsesTheSameVector",
        "mismatchedEpochBindingsDirectionAndUnreservedStatesNeverSign",
        "revocationRequiresItsOwnReservationAndEmptyToken",
        "invalidInputAndMalformedFfiOutputFailClosedWithoutLeaks",
        "failureAndCancellationClearBorrowedArraysWithoutLoggingInputs",
        "modifiedDerivationInputsAreRejectedAndTemporaryBuffersAreWiped",
        "modifiedSigningInputsNeverProduceARegistration",
        "badSignerOutputCannotProduceARequest",
    },
    PREFIX + "PushMailboxHttpClientTest": {
        "putStoresOnlyWithAValidServerReceiptAndClearsTransientBytes",
        "serverAcceptanceAndRetryStatesAreNotDelivery",
        "pullMatchesSignedWorkerVectorAndReturnsOnlyABoundedCheckedPage",
        "acknowledgementsAreSortedSnapshotAndOnlyMeanServerAcceptedAnOperation",
        "invalidRequestsNeverReachTheNetworkAndInputIsBounded",
        "putIdBindsCiphertextAndImmutableCutoffToTheIndependentWorkerVector",
        "envelopeRequiresCanonicalBase64AndExactSeparators",
        "malformedDuplicateOversizedAndAmbiguousResponsesFailClosed",
        "cancellationRacingAnyMailboxResultOrErrorClearsBuffersAndPropagates",
        "brokenDiagnosticsCannotMaskCancellationOrPretendMailboxSuccess",
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
        "clientPolicyHasTotalAndSocketTimeoutsAndDoesNotFollowRedirects",
        "approvedHttpsPathAndHeadersReachTheFactoryWithAnIsolatedBody",
        "redirectsAndErrorBodiesAreClosedWithoutBeingReadOrReturned",
        "malformedOrAmbiguousHeadersFailBeforeReadingTheBody",
        "streamingCapAndDeclaredLengthAreBothEnforced",
        "unapprovedPathsAndOversizedRequestsNeverCreateACall",
        "cancellationCancelsTheCallAndLateResponsesAreClosedWithoutReading",
        "timedOutCallsKeepTheirSlotUntilTheirRealCallbackFinishes",
        "parentCancellationIsNotConvertedIntoAnInternalDeadlineFailure",
        "enqueueFailureReturnsItsSlotAndClearsTheRequestCopy",
    },
    PREFIX + "PushHttpLoopbackTlsTest": {
        "localTlsRoundTripUsesTheExpectedRequestAndClosesTheResponse",
        "untrustedTlsCertificateFailsAndRedirectIsNeverFollowed",
        "parentCancellationClosesARealTlsCallWaitingForAResponse",
        "deadlineAlsoClosesARealTlsCallStalledMidResponseBody",
        "overallDeadlineCancelsARealTlsCallAfterRequestWrite",
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
    PREFIX + "PushMailboxTextPolicyTest": {
        "boundedHumanTextPreservesUnicodePunctuationAndEmoji",
        "controlsMediaCardsAndReservedFamiliesAreNeverPlainText",
        "separatorsAndNonTextControlsAreRejectedEvenInsideAWordOrLaterLine",
    },
    PREFIX + "PushMailboxMessageOpenerTest": {
        "validOpenedMessageIsBoundToThePinnedSenderLocalRecipientAndOriginalId",
        "authenticatedUnsupportedBodiesAreRejectedAfterDecryption",
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
        "unsupportedBodiesNeverReachEncryptionOrFfi",
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
        "cancellationAtAnyCallbackBoundaryCannotStartAnotherEffectOrReportSuccess",
        "brokenDiagnosticsCannotDropLaterItemsOrConvertFailureToSuccess",
        "cancellationInsideBrokenDiagnosticsStopsTheRemainingPage",
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
