#!/usr/bin/env bash
# Real Kotlin/JVM history, pin-notice and splash regressions, without an Android SDK or native core.
# Uses the same Kotlin/coroutines/JUnit versions as the app. All downloads and
# compiled output stay in ignored target/. Requires Java 17+, curl and Python 3.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$ROOT/target/chat-history-check"
mkdir -p "$OUT/compiler" "$OUT/runtime" "$OUT/classes"
read -r KOTLIN COROUTINES JUNIT < <(python3 - "$ROOT/android-app/gradle/libs.versions.toml" <<'PY'
import re, sys
text = open(sys.argv[1]).read()
print(*(re.search(r'^' + name + r'\s*=\s*"([^"]+)"', text, re.M).group(1)
        for name in ('kotlin', 'coroutines', 'junit')))
PY
)
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v "$JAVA" >/dev/null || { echo 'Java 17+ is required for the chat history tests'; exit 1; }
MAVEN=https://repo.maven.apache.org/maven2
fetch() {
    local group="$1" name="$2" version="$3" directory="$4"
    local path="${group//.//}/$name/$version/$name-$version.jar"
    local dest="$OUT/$directory/$name-$version.jar"
    if [ ! -s "$dest" ]; then
        curl --fail --location --retry 2 --connect-timeout 15 --max-time 120 \
            --silent --show-error "$MAVEN/$path" -o "$dest.tmp"
        mv "$dest.tmp" "$dest"
    fi
}
fetch org.jetbrains.kotlin kotlin-compiler-embeddable "$KOTLIN" compiler
fetch org.jetbrains.kotlin kotlin-daemon-embeddable "$KOTLIN" compiler
fetch org.jetbrains.kotlin kotlin-script-runtime "$KOTLIN" compiler
fetch org.jetbrains.kotlin kotlin-reflect 1.6.10 compiler
fetch org.jetbrains.intellij.deps trove4j 1.0.20200330 compiler
fetch org.jetbrains.kotlinx kotlinx-coroutines-core-jvm 1.6.4 compiler
fetch org.jetbrains annotations 13.0 compiler
fetch org.jetbrains.kotlin kotlin-stdlib "$KOTLIN" runtime
fetch org.jetbrains.kotlinx kotlinx-coroutines-core-jvm "$COROUTINES" runtime
fetch org.jetbrains.kotlinx kotlinx-coroutines-test-jvm "$COROUTINES" runtime
fetch junit junit "$JUNIT" runtime
fetch org.hamcrest hamcrest-core 1.3 runtime
CP="$(printf '%s:' "$OUT"/runtime/*.jar)${OUT}/compiler/annotations-13.0.jar"
BASE="$ROOT/android-app/app/src"
MODEL="$BASE/main/java/com/vladimir/messenger/domain/model"
OBSERVER="$BASE/main/java/com/vladimir/messenger/ui/screens/chat/ChatHistoryObserver.kt"
TEST="$BASE/test/java/com/vladimir/messenger/ui/screens/chat/ChatHistoryObserverTest.kt"
PIN_POLICY="$BASE/main/java/com/vladimir/messenger/data/local/MessagePinPolicy.kt"
PIN_TEST="$BASE/test/java/com/vladimir/messenger/data/local/MessagePinPolicyTest.kt"
SPLASH_MATH="$BASE/main/java/com/vladimir/messenger/ui/components/SplashOrbitGeometry.kt"
SPLASH_TEST="$BASE/test/java/com/vladimir/messenger/ui/components/SplashOrbitGeometryTest.kt"
SETTINGS_LAYOUT="$BASE/main/java/com/vladimir/messenger/ui/components/ApuSettingsLayout.kt"
SETTINGS_TEST="$BASE/test/java/com/vladimir/messenger/ui/components/ApuSettingsLayoutTest.kt"
"$JAVA" -cp "$OUT/compiler/*:$OUT/runtime/kotlin-stdlib-$KOTLIN.jar" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 -classpath "$CP" -d "$OUT/classes" \
    "$MODEL/Message.kt" "$MODEL/MessageStatus.kt" "$MODEL/MessageChannel.kt" "$OBSERVER" "$TEST" "$PIN_POLICY" "$PIN_TEST" "$SPLASH_MATH" "$SPLASH_TEST" "$SETTINGS_LAYOUT" "$SETTINGS_TEST"
"$JAVA" -cp "$OUT/classes:$CP" org.junit.runner.JUnitCore \
    com.vladimir.messenger.ui.screens.chat.ChatHistoryObserverTest \
    com.vladimir.messenger.data.local.MessagePinPolicyTest \
    com.vladimir.messenger.ui.components.SplashOrbitGeometryTest \
    com.vladimir.messenger.ui.components.ApuSettingsLayoutTest
