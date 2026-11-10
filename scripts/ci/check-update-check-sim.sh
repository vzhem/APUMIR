#!/usr/bin/env bash
# Actual UpdateChecker and coordinator, with fake Android and HTTPS in a separate JVM.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAVA="$1"
CP="$2"
OUT="$3"
KOTLIN="$4"
BASE="$ROOT/android-app/app/src/main/java/com/vladimir/messenger"
ANDROID="$ROOT/scripts/ci/update-download-sim"
SIM="$ROOT/scripts/ci/update-check-sim"
mkdir -p "$OUT/update-check-sim"
"$JAVA" -cp "$OUT/compiler/*:$OUT/runtime/kotlin-stdlib-$KOTLIN.jar" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 -classpath "$CP" -d "$OUT/update-check-sim" \
    "$BASE/data/update/ApkUpdate.kt" "$BASE/data/link/ServiceHost.kt" "$BASE/util/BoundedFiles.kt" \
    "$BASE/service/UpdateCheckPolicy.kt" "$BASE/service/OfficialUpdatePolicy.kt" \
    "$BASE/service/OfficialUpdateDownloads.kt" "$BASE/service/UpdateDownloadGate.kt" \
    "$BASE/service/UpdateSiteMirror.kt" "$BASE/service/UpdateLinks.kt" "$BASE/service/UpdateChecker.kt" \
    "$ANDROID"/Android*.kt "$ANDROID/GroupWireStub.kt" "$ANDROID/MonitorStub.kt" "$ANDROID/StubR.kt" \
    "$SIM"/*.kt
"$JAVA" -cp "$OUT/update-check-sim:$CP" com.vladimir.messenger.service.CheckHarnessKt
