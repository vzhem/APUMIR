#!/usr/bin/env bash
# Real Kotlin/JVM history, pin-notice, splash and diagnostics-report regressions,
# without an Android SDK or native core.
# Uses the same Kotlin/coroutines/JUnit versions as the app. All downloads and
# compiled output stay in ignored target/. Requires Java 17+, curl and Python 3.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
python3 "$ROOT/scripts/ci/check-deferred-cancel.py"
python3 "$ROOT/scripts/ci/check-delete-read.py"
python3 "$ROOT/scripts/ci/check-story-editor.py"
python3 "$ROOT/scripts/ci/check-chat-header.py"
python3 "$ROOT/scripts/ci/check-story-feedback.py"
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
# Ed25519 постов (net.i2p.crypto:eddsa) нужен мосту манифестов, на который
# ссылается групповой конверт GroupWire. Версия - та же, что в libs.versions.toml.
fetch net.i2p.crypto eddsa 0.3.0 runtime
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
HEART_WIRE="$BASE/main/java/com/vladimir/messenger/data/heart/HeartWire.kt"
HEART_TEST="$BASE/test/java/com/vladimir/messenger/data/heart/HeartWireTest.kt"
GROUP_PERMS="$BASE/main/java/com/vladimir/messenger/data/group/GroupPermissions.kt"
GROUP_PERMS_TEST="$BASE/test/java/com/vladimir/messenger/data/group/GroupPermissionsTest.kt"
# Групповой конверт (р250: ответы `mrep`, опросы `poll`/`pvot`/`pcls`,
# медленный режим `slow`). Формат критичен к обратной совместимости: прежние
# телефоны обязаны молча пропускать незнакомые виды и понимать старые `msg`/`info`.
POST_MANIFEST="$BASE/main/java/com/vladimir/messenger/data/swarm/PostManifest.kt"
GROUP_WIRE="$BASE/main/java/com/vladimir/messenger/data/group/GroupWire.kt"
QUIET_HOURS="$BASE/main/java/com/vladimir/messenger/data/group/GroupQuietHours.kt"
SLOW_MODE="$BASE/main/java/com/vladimir/messenger/data/group/GroupSlowMode.kt"
SLOW_MODE_TEST="$BASE/test/java/com/vladimir/messenger/data/group/GroupSlowModeTest.kt"
QUIET_HOURS_TEST="$BASE/test/java/com/vladimir/messenger/data/group/GroupQuietHoursTest.kt"
GROUP_WIRE_TEST="$BASE/test/java/com/vladimir/messenger/data/group/GroupWireTest.kt"
GROUP_WIRE_UPDATE_TEST="$BASE/test/java/com/vladimir/messenger/data/group/GroupWireUpdateTest.kt"
# Отчёт «Логи» и разбор строки MQTT: чистый Kotlin без Android. Здесь они
# компилируются и проверяются на runner, то есть формат отчёта ловится до
# выпуска, а не по скриншоту с телефона владельца (2026-10-06).
DIAG_REPORT="$BASE/main/java/com/vladimir/messenger/data/diagnostics/DiagnosticsReport.kt"
RELAY_STORAGE="$BASE/main/java/com/vladimir/messenger/data/diagnostics/RelayStorageDiagnostics.kt"
RELAY_STORAGE_TEST="$BASE/test/java/com/vladimir/messenger/data/diagnostics/RelayStorageDiagnosticsTest.kt"
RUNTIME_HEALTH="$BASE/main/java/com/vladimir/messenger/data/diagnostics/RuntimeHealth.kt"
RUNTIME_HEALTH_TEST="$BASE/test/java/com/vladimir/messenger/data/diagnostics/RuntimeHealthTest.kt"
OP_HEALTH="$BASE/main/java/com/vladimir/messenger/data/diagnostics/OperationalHealth.kt"
OP_BOUNDED_READ="$BASE/main/java/com/vladimir/messenger/data/diagnostics/BoundedDiagnosticRead.kt"
OP_CLEANUP="$BASE/main/java/com/vladimir/messenger/data/diagnostics/RelayCleanupWindow.kt"
OP_HEALTH_TEST="$BASE/test/java/com/vladimir/messenger/data/diagnostics/OperationalHealthTest.kt"
STORY_QUEUE="$BASE/main/java/com/vladimir/messenger/data/story/StoryDeliveryQueue.kt"
STORY_QUEUE_TEST="$BASE/test/java/com/vladimir/messenger/data/story/StoryDeliveryQueueTest.kt"
# Отклики на истории (реакции и комментарии, запрос владельца 2026-10-14):
# провод, книга слияния и очередь - чистый Kotlin, поэтому формат и правила
# «побеждает последний по времени» ловятся здесь, до сборки APK.
STORY_FEEDBACK_WIRE="$BASE/main/java/com/vladimir/messenger/data/story/StoryFeedbackWire.kt"
STORY_FEEDBACK_LEDGER="$BASE/main/java/com/vladimir/messenger/data/story/StoryFeedbackLedger.kt"
STORY_FEEDBACK_OUTBOX="$BASE/main/java/com/vladimir/messenger/data/story/StoryFeedbackOutbox.kt"
STORY_FEEDBACK_WIRE_TEST="$BASE/test/java/com/vladimir/messenger/data/story/StoryFeedbackWireTest.kt"
STORY_FEEDBACK_LEDGER_TEST="$BASE/test/java/com/vladimir/messenger/data/story/StoryFeedbackLedgerTest.kt"
STORY_FEEDBACK_OUTBOX_TEST="$BASE/test/java/com/vladimir/messenger/data/story/StoryFeedbackOutboxTest.kt"
DIAG_MQTT="$BASE/main/java/com/vladimir/messenger/data/diagnostics/MqttLinkText.kt"
DIAG_REPORT_TEST="$BASE/test/java/com/vladimir/messenger/data/diagnostics/DiagnosticsReportTest.kt"
DIAG_MQTT_TEST="$BASE/test/java/com/vladimir/messenger/data/diagnostics/MqttLinkTextTest.kt"
UPDATE_POLICY="$BASE/main/java/com/vladimir/messenger/service/UpdateCheckPolicy.kt"
UPDATE_POLICY_TEST="$BASE/test/java/com/vladimir/messenger/service/UpdateCheckerTest.kt"
# Fail-closed личных сообщений и безопасный cooldown тяжёлого самосева:
# чистые Kotlin-правила проверяются тем же runner без Android SDK.
CALL_WIRE="$BASE/main/java/com/vladimir/messenger/data/call/CallWire.kt"
FILE_CODEC="$BASE/main/java/com/vladimir/messenger/data/file/FileTransferPacketCodec.kt"
FILE_WIRE="$BASE/main/java/com/vladimir/messenger/data/file/FileTransferWire.kt"
FILE_UDP="$BASE/main/java/com/vladimir/messenger/data/file/FileUdpWire.kt"
SEALED_WIRE="$BASE/main/java/com/vladimir/messenger/data/security/SealedWire.kt"
OUTGOING_POLICY="$BASE/main/java/com/vladimir/messenger/data/security/OutgoingPayloadPolicy.kt"
OUTGOING_POLICY_TEST="$BASE/test/java/com/vladimir/messenger/data/security/OutgoingPayloadPolicyTest.kt"
DEFERRED_WIRE="$BASE/main/java/com/vladimir/messenger/data/schedule/DeferredWire.kt"
DEFERRED_POLICY="$BASE/main/java/com/vladimir/messenger/data/schedule/DeferredDeliveryPolicy.kt"
DEFERRED_WIRE_TEST="$BASE/test/java/com/vladimir/messenger/data/schedule/DeferredWireTest.kt"
DEFERRED_POLICY_TEST="$BASE/test/java/com/vladimir/messenger/data/schedule/DeferredDeliveryPolicyTest.kt"
SEND_TIME_POLICY="$BASE/main/java/com/vladimir/messenger/data/schedule/SendTimePolicy.kt"
SEND_TIME_TEST="$BASE/test/java/com/vladimir/messenger/data/schedule/SendTimePolicyTest.kt"
SELF_SEED_POLICY="$BASE/main/java/com/vladimir/messenger/data/update/SelfSeedRetryPolicy.kt"
SELF_SEED_POLICY_TEST="$BASE/test/java/com/vladimir/messenger/data/update/SelfSeedRetryPolicyTest.kt"
# Сроки хранения «потеряшек» (владелец 2026-10-07: тяжёлое — сутки, текст и
# малое — неделя). Файл чистый, поэтому проверяется здесь же на runner.
STORY_PUBLISH="$BASE/main/java/com/vladimir/messenger/ui/components/StoryPublish.kt"
STORY_PUBLISH_TEST="$BASE/test/java/com/vladimir/messenger/ui/components/StoryPublishTest.kt"
RETENTION="$BASE/main/java/com/vladimir/messenger/data/file/FileTransferRetention.kt"
RETENTION_TEST="$BASE/test/java/com/vladimir/messenger/data/file/FileTransferRetentionTest.kt"
# Дата рождения (владелец 2026-10-15): правила даты, книга слияния и конверт
# «bday» в групповом проводе - чистый Kotlin. Здесь они компилируются и
# проверяются на runner, поэтому «29 февраля» и отзыв даты ловятся до выпуска.
BIRTHDAY="$BASE/main/java/com/vladimir/messenger/data/profile/Birthday.kt"
BIRTHDAY_BOOK="$BASE/main/java/com/vladimir/messenger/data/profile/BirthdayBook.kt"
BIRTHDAY_TEST="$BASE/test/java/com/vladimir/messenger/data/profile/BirthdayTest.kt"
BIRTHDAY_WIRE_TEST="$BASE/test/java/com/vladimir/messenger/data/group/BirthdayWireTest.kt"
# «Телефон молчит»: причина состояния без Android (ключ не читается, не успел,
# первый запуск). Файл чистый, поэтому проверяется здесь же на runner.
IDENTITY_HEALTH="$BASE/main/java/com/vladimir/messenger/data/security/IdentityHealth.kt"
IDENTITY_HEALTH_TEST="$BASE/test/java/com/vladimir/messenger/data/security/IdentityHealthTest.kt"
STORY_VIDEO_DRAFT="$BASE/main/java/com/vladimir/messenger/data/story/StoryVideoDraft.kt"
STORY_VIDEO_DRAFT_TEST="$BASE/test/java/com/vladimir/messenger/data/story/StoryVideoDraftTest.kt"
"$JAVA" -cp "$OUT/compiler/*:$OUT/runtime/kotlin-stdlib-$KOTLIN.jar" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 -classpath "$CP" -d "$OUT/classes" \
    "$STORY_VIDEO_DRAFT" "$STORY_VIDEO_DRAFT_TEST" "$MODEL/Message.kt" "$MODEL/MessageStatus.kt" "$MODEL/MessageChannel.kt" "$OBSERVER" "$TEST" "$PIN_POLICY" "$PIN_TEST" "$SPLASH_MATH" "$SPLASH_TEST" "$SETTINGS_LAYOUT" "$SETTINGS_TEST" "$HEART_WIRE" "$HEART_TEST" "$GROUP_PERMS" "$GROUP_PERMS_TEST" \
    "$SLOW_MODE" "$SLOW_MODE_TEST" "$QUIET_HOURS" "$QUIET_HOURS_TEST" "$POST_MANIFEST" "$BASE/main/java/com/vladimir/messenger/data/group/GroupWallpaper.kt" "$BASE/test/java/com/vladimir/messenger/data/group/GroupWallpaperTest.kt" "$GROUP_WIRE" "$GROUP_WIRE_TEST" "$GROUP_WIRE_UPDATE_TEST" \
    "$OP_HEALTH" "$OP_BOUNDED_READ" "$OP_CLEANUP" "$OP_HEALTH_TEST" "$STORY_QUEUE" "$STORY_QUEUE_TEST" "$RUNTIME_HEALTH" "$RUNTIME_HEALTH_TEST" "$DIAG_REPORT" "$RELAY_STORAGE" "$RELAY_STORAGE_TEST" "$DIAG_MQTT" "$DIAG_REPORT_TEST" "$DIAG_MQTT_TEST" \
    "$STORY_FEEDBACK_WIRE" "$STORY_FEEDBACK_LEDGER" "$STORY_FEEDBACK_OUTBOX" \
    "$STORY_FEEDBACK_WIRE_TEST" "$STORY_FEEDBACK_LEDGER_TEST" "$STORY_FEEDBACK_OUTBOX_TEST" \
    "$UPDATE_POLICY" "$UPDATE_POLICY_TEST" \
    "$CALL_WIRE" "$FILE_CODEC" "$FILE_WIRE" "$FILE_UDP" "$SEALED_WIRE" "$OUTGOING_POLICY" "$OUTGOING_POLICY_TEST" \
    "$DEFERRED_WIRE" "$DEFERRED_POLICY" "$DEFERRED_WIRE_TEST" "$DEFERRED_POLICY_TEST" \
    "$SELF_SEED_POLICY" "$SELF_SEED_POLICY_TEST" "$SEND_TIME_POLICY" "$SEND_TIME_TEST" \
    "$STORY_PUBLISH" "$STORY_PUBLISH_TEST" "$RETENTION" "$RETENTION_TEST" \
    "$BIRTHDAY" "$BIRTHDAY_BOOK" "$BIRTHDAY_TEST" "$BIRTHDAY_WIRE_TEST" \
    "$IDENTITY_HEALTH" "$IDENTITY_HEALTH_TEST"
"$JAVA" -cp "$OUT/classes:$CP" org.junit.runner.JUnitCore \
    com.vladimir.messenger.data.group.GroupWallpaperTest \
    com.vladimir.messenger.ui.screens.chat.ChatHistoryObserverTest \
    com.vladimir.messenger.data.local.MessagePinPolicyTest \
    com.vladimir.messenger.ui.components.StoryPublishTest \
    com.vladimir.messenger.data.profile.BirthdayTest \
    com.vladimir.messenger.data.group.BirthdayWireTest \
    com.vladimir.messenger.data.security.IdentityHealthTest \
    com.vladimir.messenger.ui.components.SplashOrbitGeometryTest \
    com.vladimir.messenger.ui.components.ApuSettingsLayoutTest \
    com.vladimir.messenger.data.heart.HeartWireTest \
    com.vladimir.messenger.data.group.GroupPermissionsTest \
    com.vladimir.messenger.data.story.StoryVideoDraftTest \
    com.vladimir.messenger.data.group.GroupSlowModeTest \
    com.vladimir.messenger.data.group.GroupQuietHoursTest \
    com.vladimir.messenger.data.group.GroupWireTest \
    com.vladimir.messenger.data.group.GroupWireUpdateTest \
    com.vladimir.messenger.data.diagnostics.DiagnosticsReportTest \
    com.vladimir.messenger.data.diagnostics.RelayStorageDiagnosticsTest \
    com.vladimir.messenger.data.diagnostics.RuntimeHealthTest \
    com.vladimir.messenger.data.diagnostics.OperationalHealthTest \
    com.vladimir.messenger.data.story.StoryDeliveryQueueTest \
    com.vladimir.messenger.data.story.StoryFeedbackWireTest \
    com.vladimir.messenger.data.story.StoryFeedbackLedgerTest \
    com.vladimir.messenger.data.story.StoryFeedbackOutboxTest \
    com.vladimir.messenger.data.diagnostics.MqttLinkTextTest \
    com.vladimir.messenger.service.UpdateCheckerTest \
    com.vladimir.messenger.data.security.OutgoingPayloadPolicyTest \
    com.vladimir.messenger.data.update.SelfSeedRetryPolicyTest \
    com.vladimir.messenger.data.schedule.DeferredWireTest \
    com.vladimir.messenger.data.schedule.DeferredDeliveryPolicyTest \
    com.vladimir.messenger.data.schedule.SendTimePolicyTest \
    com.vladimir.messenger.data.file.FileTransferRetentionTest
