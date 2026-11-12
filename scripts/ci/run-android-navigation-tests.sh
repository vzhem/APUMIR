#!/usr/bin/env bash
# Run the existing storage suite and the navigation suite in the same emulator.
# Preserve reports and fail if a navigation class was skipped or never started.
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# Exercise the actual Room SQL on host SQLite as well as the emulator tests.
python3 "$root/scripts/ci/check-outbox-fairness.py" || exit 1
python3 "$root/scripts/ci/check-outbox-recipient-sql.py" || exit 1
python3 "$root/scripts/ci/check-direct-reply-scope.py" || exit 1
cd "$root/android-app" || exit 1
chmod +x gradlew
reports="$root/target/android-test-reports"
rm -rf "$reports"
mkdir -p "$reports"
results="app/build/outputs/androidTest-results/connected"
html="app/build/reports/androidTests/connected"

save_report() {
    local dest="$reports/$1"
    mkdir -p "$dest"
    [ ! -d "$results" ] || cp -R "$results" "$dest/results"
    [ ! -d "$html" ] || cp -R "$html" "$dest/html"
}
clear_report() {
    rm -rf "$results" "$html"
}

./gradlew :app:connectedDebugAndroidTest --no-daemon \
    -Pandroid.testInstrumentationRunnerArguments.package=com.vladimir.messenger.data.chatclear
storage_status=$?
save_report storage
if [ "$storage_status" -ne 0 ]; then
    echo "::error::Android storage tests failed (Gradle exit $storage_status)"
fi

# Exercise Room upgrade, notification/restore, outbox fairness and experimental
# push storage, heartbeat ownership and a disposable native-engine ownership
# check; never accept a green task that silently skipped acceptance cases.
clear_report
./gradlew :app:connectedDebugAndroidTest --no-daemon \
    -Pandroid.testInstrumentationRunnerArguments.class=com.vladimir.messenger.data.chatclear.ChatClearTombstonesInstrumentedTest,com.vladimir.messenger.data.notification.NotificationArrivalInstrumentedTest,com.vladimir.messenger.data.repository.IncomingMessageStoreInstrumentedTest,com.vladimir.messenger.data.reply.DirectReplyIngressInstrumentedTest,com.vladimir.messenger.data.reply.DirectReplyRealFfiInstrumentedTest,com.vladimir.messenger.data.receipt.ScopedDeliveryAckInstrumentedTest,com.vladimir.messenger.data.repository.MirrorOutgoingStoreInstrumentedTest,com.vladimir.messenger.data.repository.MirrorPendingOutgoingInstrumentedTest,com.vladimir.messenger.data.repository.OutboxFairnessInstrumentedTest,com.vladimir.messenger.data.repository.ReplyAwareOutboxSenderInstrumentedTest,com.vladimir.messenger.data.push.PushEpochStoreInstrumentedTest,com.vladimir.messenger.data.push.PushRegistrationStoreInstrumentedTest,com.vladimir.messenger.data.push.PushWakeIntegrationInstrumentedTest,com.vladimir.messenger.data.push.PushMailboxDrainInstrumentedTest,com.vladimir.messenger.data.push.PushMailboxOutboxInstrumentedTest,com.vladimir.messenger.data.push.PushMailboxOutboxRealFfiInstrumentedTest,com.vladimir.messenger.data.push.PushWakeInboxInstrumentedTest,com.vladimir.messenger.data.push.PushWakeDrainInstrumentedTest,com.vladimir.messenger.data.push.PushWakeWorkManagerInstrumentedTest,com.vladimir.messenger.data.push.FirebaseClientConfigurationInstrumentedTest,com.vladimir.messenger.data.CoreLifecycleOwnershipInstrumentedTest,com.vladimir.messenger.service.DeliveryHeartbeatInstrumentedTest \
    2>&1 | tee "$reports/notification.log"
notification_gradle=${PIPESTATUS[0]}
python3 "$root/scripts/ci/check-android-notification-results.py" \
    "$results" "$reports/notification.log" "$notification_gradle"
notification_verify=$?
save_report notification
notification_status=0
if [ "$notification_gradle" -ne 0 ] || [ "$notification_verify" -ne 0 ]; then
    notification_status=1
fi
# The ownership check starts a disposable native engine; reset process-local
# engine caches before the unrelated navigation suite, even if the check failed.
if ! adb shell am force-stop com.vladimir.messenger; then
    echo '::error::Could not isolate navigation from the native lifecycle test'
    notification_status=1
fi

run_navigation() {
    local variant="$1" status verify
    clear_report
    ./gradlew :app:connectedDebugAndroidTest --no-daemon \
        -Pandroid.testInstrumentationRunnerArguments.package=com.vladimir.messenger.ui.navigation \
        2>&1 | tee "$reports/navigation-$variant.log"
    status=${PIPESTATUS[0]}
    python3 "$root/scripts/ci/check-android-navigation-results.py" \
        "$results" "$reports/navigation-$variant.log" "$status"
    verify=$?
    save_report "navigation-$variant"
    [ "$status" -eq 0 ] && [ "$verify" -eq 0 ]
}

normal_status=0
run_navigation normal || normal_status=1

# Repeat the same controls on a small screen with enlarged text. This changes
# the actual emulator window metrics, including Dialog windows, not just a Box.
old_scale="$(adb shell settings get system font_scale | tr -d '\r')"
restore_metrics() {
    adb shell wm size reset >/dev/null || true
    adb shell wm density reset >/dev/null || true
    if [ -z "$old_scale" ] || [ "$old_scale" = null ]; then
        adb shell settings delete system font_scale >/dev/null || true
    else
        adb shell settings put system font_scale "$old_scale" >/dev/null || true
    fi
}
trap restore_metrics EXIT
compact_status=0
if adb shell wm size 360x640 && adb shell wm density 160 && \
    adb shell settings put system font_scale 1.4 && \
    adb shell am force-stop com.vladimir.messenger; then
    run_navigation compact-large-text || compact_status=1
else
    echo '::error::Could not set up the small-screen/large-text emulator run'
    compact_status=1
fi

if [ "$storage_status" -ne 0 ] || [ "$notification_status" -ne 0 ] || [ "$normal_status" -ne 0 ] || [ "$compact_status" -ne 0 ]; then
    exit 1
fi
