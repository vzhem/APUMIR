#!/usr/bin/env bash
# Реальный контроллер обновлений + поддельный DownloadManager в отдельном JVM.
# Зависимости компилятора и JUnit уже скачаны check-chat-history.sh.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAVA="$1"
CP="$2"
OUT="$3"
KOTLIN="$4"
BASE="$ROOT/android-app/app/src/main/java/com/vladimir/messenger"
SIM="$ROOT/scripts/ci/update-download-sim"
mkdir -p "$OUT/update-download-sim"
"$JAVA" -cp "$OUT/compiler/*:$OUT/runtime/kotlin-stdlib-$KOTLIN.jar" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 -classpath "$CP" -d "$OUT/update-download-sim" \
    "$BASE/data/update/ApkUpdate.kt" "$BASE/service/UpdateCheckPolicy.kt" \
    "$BASE/service/OfficialUpdatePolicy.kt" "$BASE/service/OfficialUpdateDownloads.kt" \
    "$BASE/service/UpdateDownloadGate.kt" "$BASE/data/link/ServiceHost.kt" \
    "$SIM"/*.kt
"$JAVA" -cp "$OUT/update-download-sim:$CP" com.vladimir.messenger.service.DownloadHarnessKt
