#!/usr/bin/env python3
"""Require every disabled mailbox JVM regression to run, without skips.

The full Gradle test task normally discovers these automatically. This gate
also detects a missing/renamed class or a test accidentally filtered out. It is
not evidence of real TLS, Android Keystore, UniFFI, a provider, or delivery.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

PREFIX = "com.vladimir.messenger.data.push."
EXPECTED = {
    PREFIX + "PushMailboxHttpClientTest": {
        "putStoresOnlyWithAValidServerReceiptAndClearsTransientBytes",
        "serverAcceptanceAndRetryStatesAreNotDelivery",
        "pullMatchesSignedWorkerVectorAndReturnsOnlyABoundedCheckedPage",
        "acknowledgementsAreSortedSnapshotAndOnlyMeanServerAcceptedAnOperation",
        "invalidRequestsNeverReachTheNetworkAndInputIsBounded",
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
    PREFIX + "PushMailboxDrainTest": {
        "ackFollowsDurableCommitsAndUsesSortedServerReceipts",
        "unprocessedItemsAreNeverAcknowledgedButLaterItemsAreStillVisited",
        "commitExceptionsAreNotAckedAndCancellationNeverAcknowledgesThePage",
        "failedAckRetriesTheSameAlreadyCommittedMessageWithoutAdvancingCursor",
        "changedTrustWithholdsTheAckEvenAfterACommit",
        "malformedPagesAreRejectedBeforeAnyItemIsCommitted",
        "repeatedIdsAcrossPagesAreRejectedWithoutProcessingThemAgain",
        "boundedCheckpointPreservesUnprocessedStateAndCursorExpiryRestarts",
        "transportFailuresAndCancellationNeverTurnIntoCompletedScans",
    },
}


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
    print(f"::notice::Disabled push mailbox JVM regressions: {len(seen & expected)}/{len(expected)}")
    return 1 if errors else 0


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: check-push-mailbox-jvm-results.py RESULT_DIR", file=sys.stderr)
        return 2
    return check(Path(sys.argv[1]))


if __name__ == "__main__":
    raise SystemExit(main())
