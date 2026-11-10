#!/usr/bin/env python3
"""Fail if an emulator navigation test failed, was skipped, or did not run.

Errors are also emitted as GitHub annotations: CI log artifacts can be
unavailable to a sandbox, whereas check-run annotations remain inspectable.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "com.vladimir.messenger.ui.navigation."
EXPECTED = {
    "BackButtonsInstrumentedTest": {
        "backButtonIsVisibleContrastingAndClickableOnLightAndDarkWallpapers",
        "deviceHeaderStaysVisibleWhilePairingContentScrolls",
        "savedScreenCanNavigateToEveryMainTabIncludingChats",
    },
    "SettingsNavigationInstrumentedTest": {
        "everySectionReturnsToRootByButtonAndSystemBack",
        "deepRouteReturnsToSectionThenRestoresRootScrollPosition",
    },
    "FolderReturnInstrumentedTest": {
        "createAndEditReturnToFolderListWithoutSavingOrLeavingIt",
        "savingStillCreatesTheFolderAndReturnsToTheList",
    },
    "MenuDialogsInstrumentedTest": {
        "invitationFromChatListCanBeCancelledWithoutSending",
        "invitationFromCommunitiesHasCancelAndPreservesAllThreeActions",
        "inputPanelCanBeClosedFromGifEmojiAndStickerSections",
    },
    "CallAndCommunityNavigationInstrumentedTest": {
        "minimizedCallCanBeReopenedWithoutCoveringTheCurrentScreen",
        "callCanBeMinimizedAndReopenedRepeatedlyWithoutAnOutgoingPeerArgument",
        "returnBannerCoversOnlyLiveCallPhasesAndNotTheCallScreen",
        "leavingCommunityReturnsToCommunitiesOrFallsBackToChats",
        "leavingCommunityWithoutNormalRootDoesNotRevealTheDeletedGroup",
        "unknownDeepLinkStackIsReplacedInsteadOfReturningToDeletedGroup",
    },
}


def annotation(kind: str, message: str) -> None:
    safe = message[:3500].replace("%", "%25").replace("\r", "").replace("\n", "%0A")
    print(f"::{kind}::{safe}")


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: check-android-navigation-results.py RESULT_DIR GRADLE_LOG GRADLE_STATUS", file=sys.stderr)
        return 2
    result_dir, log, status = Path(sys.argv[1]), Path(sys.argv[2]), int(sys.argv[3])
    seen = {name: set() for name in EXPECTED}
    report_errors = []
    case_errors = []
    missing_cases = []
    for path in sorted(result_dir.rglob("*.xml")):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            report_errors.append(f"Invalid Android test report {path}: {exc}")
            continue
        for case in root.iter("testcase"):
            classname = case.get("classname", "")
            if not classname.startswith(PACKAGE):
                continue
            name = classname.removeprefix(PACKAGE).split("$")[0]
            if name not in seen:
                continue
            case_name = case.get("name", "")
            seen[name].add(case_name)
            for tag in ("failure", "error", "skipped"):
                for bad in case.findall(tag):
                    message = (bad.get("message") or bad.text or tag).strip()
                    case_errors.append(f"{name}.{case_name}: {tag}: {message[:1000]}")
    for name, expected in EXPECTED.items():
        for missing in sorted(expected - seen[name]):
            missing_cases.append(f"{name}.{missing}: expected test did not execute")
    if status:
        lines = log.read_text(encoding="utf-8", errors="replace").splitlines() if log.exists() else []
        compiler = [line for line in lines if line.startswith("e: ")]
        tail = [line for line in lines[-45:] if not line.startswith("w: ")]
        # GitHub caps annotations per step. Put the cause first; a long list
        # of missing test names must not hide a compiler error or task failure.
        diagnostic = "\n".join((compiler[-20:] + tail[-20:]))
        annotation("error", f"Navigation Gradle task exited {status}.\n{diagnostic}")
    if case_errors:
        annotation("error", "Android navigation failures:\n" + "\n".join(case_errors))
    if report_errors:
        annotation("error", "\n".join(report_errors))
    if missing_cases:
        annotation("error", "\n".join(missing_cases))
    summary = ", ".join(f"{name}={len(seen[name] & expected)}/{len(expected)}" for name, expected in EXPECTED.items())
    annotation("notice", f"Android navigation tests: {summary}")
    return 1 if status or case_errors or report_errors or missing_cases else 0


if __name__ == "__main__":
    raise SystemExit(main())
