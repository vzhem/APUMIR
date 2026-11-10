#!/usr/bin/env python3
"""Fail when the Android notification/migration acceptance tests did not all run."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

CLASS = "com.vladimir.messenger.data.notification.NotificationArrivalInstrumentedTest"
EXPECTED = {
    "migrationFortyToFortyOnePreservesHistoryAndRoomValidatesSchema",
    "delayedArrivalsAreClaimedOnceAndSurviveRestartWithoutRearmingDuplicates",
    "boundedQueueContinuesAfterFirstBatchIsClaimed",
    "mirrorSnapshotsNeverExportOrApplyLocalNotificationWork",
    "backupMergeDoesNotImportPendingWorkOrClearLiveWork",
    "stagedBackupSanitizerReadsWalAndLeavesTheOriginalUntouched",
}


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
            if case.get("classname", "").split("$")[0] != CLASS:
                continue
            name = case.get("name", "")
            seen.add(name)
            for tag in ("failure", "error", "skipped"):
                for bad in case.findall(tag):
                    detail = (bad.get("message") or bad.text or tag).strip()[:1000]
                    errors.append(f"{name}: {tag}: {detail}")
    if status:
        lines = log.read_text(encoding="utf-8", errors="replace").splitlines() if log.exists() else []
        important = [line for line in lines if line.startswith("e: ")]
        errors.insert(0, f"Gradle exited {status}: " + " | ".join((important[-10:] + lines[-10:]))[:2000])
    errors += [f"Expected test did not execute: {name}" for name in sorted(EXPECTED - seen)]
    for error in errors:
        safe = error.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::error::{safe}")
    print(f"::notice::Android notification tests: {len(seen & EXPECTED)}/{len(EXPECTED)}")
    return 1 if errors else 0


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: check-android-notification-results.py RESULT_DIR GRADLE_LOG GRADLE_STATUS", file=sys.stderr)
        return 2
    return check(Path(sys.argv[1]), Path(sys.argv[2]), int(sys.argv[3]))


if __name__ == "__main__":
    raise SystemExit(main())
