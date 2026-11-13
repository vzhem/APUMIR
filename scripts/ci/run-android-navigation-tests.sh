#!/usr/bin/env bash
# Run the existing storage suite and the navigation suite in the same emulator.
# Preserve reports and fail if a navigation class was skipped or never started.
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# This suite installs the debug app and changes permissions/notification state.
# Refuse physical devices, ambiguous adb targets, or an emulator that already
# has either package; do not install over someone's existing profile.
serial="$(adb get-serialno 2>/dev/null | tr -d '\r')" || exit 1
case "$serial" in emulator-[0-9]*) ;; *) echo '::error::A fresh local emulator is required'; exit 1 ;; esac
qemu="$(adb shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r\n')" || exit 1
[ "$qemu" = 1 ] || { echo '::error::Refusing a non-emulator device'; exit 1; }
packages="$(adb shell pm list packages com.vladimir.messenger 2>/dev/null | tr -d '\r')" || exit 1
if printf '%s\n' "$packages" | grep -Eq '^package:com\.vladimir\.messenger(\.test)?$'; then
    echo '::error::Emulator already has the app or test package; use a fresh emulator'
    exit 1
fi
# Exercise the actual Room SQL on host SQLite as well as the emulator tests.
python3 "$root/scripts/ci/check-outbox-fairness.py" || exit 1
python3 "$root/scripts/ci/check-outbox-recipient-sql.py" || exit 1
python3 "$root/scripts/ci/check-direct-reply-scope.py" || exit 1
python3 "$root/scripts/ci/check-push-mailbox-sql.py" || exit 1
python3 "$root/scripts/ci/check-local-notification-sql.py" || exit 1
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
    -Pandroid.testInstrumentationRunnerArguments.class=com.vladimir.messenger.data.chatclear.ChatClearTombstonesInstrumentedTest,com.vladimir.messenger.data.notification.NotificationArrivalInstrumentedTest,com.vladimir.messenger.data.backup.BackupNotificationRestoreInstrumentedTest,com.vladimir.messenger.data.repository.IncomingMessageStoreInstrumentedTest,com.vladimir.messenger.data.reply.DirectReplyIngressInstrumentedTest,com.vladimir.messenger.data.reply.DirectReplyRealFfiInstrumentedTest,com.vladimir.messenger.data.receipt.ScopedDeliveryAckInstrumentedTest,com.vladimir.messenger.data.repository.MirrorOutgoingStoreInstrumentedTest,com.vladimir.messenger.data.repository.MirrorPendingOutgoingInstrumentedTest,com.vladimir.messenger.data.repository.OutboxFairnessInstrumentedTest,com.vladimir.messenger.data.repository.ReplyAwareOutboxSenderInstrumentedTest,com.vladimir.messenger.data.repository.DeletionOutboxRoutingInstrumentedTest,com.vladimir.messenger.data.push.PushEpochStoreInstrumentedTest,com.vladimir.messenger.data.push.PushRegistrationStoreInstrumentedTest,com.vladimir.messenger.data.push.PushWakeIntegrationInstrumentedTest,com.vladimir.messenger.data.push.PushMailboxDrainInstrumentedTest,com.vladimir.messenger.data.push.PushMailboxRoomStoreInstrumentedTest,com.vladimir.messenger.data.push.PushMailboxOutboxInstrumentedTest,com.vladimir.messenger.data.push.PushMailboxOutboxRealFfiInstrumentedTest,com.vladimir.messenger.data.push.PushWakeInboxInstrumentedTest,com.vladimir.messenger.data.push.PushWakePeriodicRecoveryInstrumentedTest,com.vladimir.messenger.data.push.PushWakeRecoveryCheckpointInstrumentedTest,com.vladimir.messenger.data.push.PushWakeDrainInstrumentedTest,com.vladimir.messenger.data.push.PushWakeWorkManagerInstrumentedTest,com.vladimir.messenger.data.push.FirebaseClientConfigurationInstrumentedTest,com.vladimir.messenger.data.CoreLifecycleOwnershipInstrumentedTest,com.vladimir.messenger.service.DeliveryHeartbeatInstrumentedTest,com.vladimir.messenger.service.IncomingNotificationRecoveryInstrumentedTest,com.vladimir.messenger.service.NotificationManagerRecoveryInstrumentedTest,com.vladimir.messenger.worker.NotificationRecoveryWorkManagerInstrumentedTest \
    2>&1 | tee "$reports/notification.log"
notification_gradle=${PIPESTATUS[0]}
save_report notification
notification_status=0
if [ "$notification_gradle" -ne 0 ]; then notification_status=1; fi

# The ownership check starts a disposable native engine. Stop it, then install
# the already built debug APKs ONCE. Subsequent raw instrumentation invocations
# never reinstall the app, so both permission state and the Room fixture survive.
permission_isolated=1
if ! adb shell am force-stop com.vladimir.messenger || \
    ! ./gradlew :app:installDebug :app:installDebugAndroidTest --no-daemon; then
    echo '::error::Could not isolate or install the permission tests'
    notification_status=1
    permission_isolated=0
fi

permission_class=com.vladimir.messenger.service.NotificationPermissionRecoveryInstrumentedTest
permission_marker=files/ci_notification_permission_original_state.txt
permission_original=unknown
permission_changed=0
permission_guarded=0
restore_test_permission() {
    if [ "$permission_changed" -eq 1 ]; then
        local action
        case "$permission_original" in granted) action=grant ;; denied) action=revoke ;; *) return 1 ;; esac
        if adb shell pm "$action" com.vladimir.messenger android.permission.POST_NOTIFICATIONS; then
            permission_changed=0
        else
            echo '::error::Could not restore the original emulator notification permission'
            return 1
        fi
    fi
}
# Also restore on early exit. A strict, non-skipped guard and a readable snapshot
# of the original state must succeed before any permission command is issued.
trap restore_test_permission EXIT
run_permission_phase() {
    local method="$1" status verify
    clear_report
    adb shell am instrument -w -r -e class "$permission_class#$method" \
        com.vladimir.messenger.test/androidx.test.runner.AndroidJUnitRunner \
        2>&1 | tee "$reports/permission-$method.log"
    status=${PIPESTATUS[0]}
    python3 "$root/scripts/ci/check-android-instrumentation-phase.py" \
        "$reports/permission-$method.log" "$status" "$method" "$results/TEST-$method.xml"
    verify=$?
    if [ "$verify" -eq 0 ]; then
        python3 "$root/scripts/ci/check-android-notification-results.py" \
            "$results" "$reports/permission-$method.log" "$status" --phase "$method" || verify=1
    fi
    save_report "notification/permission-$method"
    [ "$status" -eq 0 ] && [ "$verify" -eq 0 ]
}

if [ "$permission_isolated" -eq 1 ] && run_permission_phase disposableProfileSnapshotsTheOriginalPermission; then
    permission_guarded=1
    if ! permission_original="$(adb shell run-as com.vladimir.messenger cat "$permission_marker" | tr -d '\r\n')"; then
        permission_original=unknown
    fi
    permission_ready=1
    case "$permission_original" in
        granted) ;;
        denied)
            # Record the need to restore BEFORE attempting any system change.
            permission_changed=1
            if ! adb shell pm grant com.vladimir.messenger android.permission.POST_NOTIFICATIONS || \
                ! adb shell am force-stop com.vladimir.messenger; then permission_ready=0; fi
            ;;
        *) permission_ready=0 ;;
    esac
    if [ "$permission_ready" -eq 1 ] && run_permission_phase permissionIsGrantedBeforeRevocation; then
        permission_changed=1
        if adb shell pm revoke com.vladimir.messenger android.permission.POST_NOTIFICATIONS && \
            adb shell am force-stop com.vladimir.messenger; then
            if run_permission_phase revokedPermissionLeavesTheCommittedNotificationPending; then
                if adb shell pm grant com.vladimir.messenger android.permission.POST_NOTIFICATIONS && \
                    adb shell am force-stop com.vladimir.messenger; then
                    run_permission_phase grantingPermissionLetsTheReopenedPendingRowPostOnce || notification_status=1
                else
                    echo '::error::Could not grant permission for the recovery phase'
                    notification_status=1
                fi
            else
                notification_status=1
            fi
        else
            echo '::error::Could not revoke notification permission on the disposable emulator'
            notification_status=1
        fi
    else
        echo '::error::Could not verify the granted permission before revocation'
        notification_status=1
    fi
else
    echo '::error::Permission test guard failed; no permission was changed'
    notification_status=1
fi

# Restore either original state, even if any phase failed. If this fails the
# EXIT trap retries; the run remains red rather than hiding the failure.
restore_test_permission || notification_status=1
if [ "$permission_guarded" -eq 1 ]; then
    # These names were proved absent by the guard and belong only to this test.
    if ! adb shell run-as com.vladimir.messenger rm -f "$permission_marker" \
        databases/ci_notification_permission_recovery_fixture.db \
        databases/ci_notification_permission_recovery_fixture.db-wal \
        databases/ci_notification_permission_recovery_fixture.db-shm \
        databases/ci_notification_permission_recovery_fixture.db-journal; then
        echo '::error::Could not clean the permission-test fixture on the emulator'
        notification_status=1
    fi
fi

# The acceptance report covers the main selection plus all three isolated
# permission phases, and rejects missing, skipped, failed or duplicate cases.
python3 "$root/scripts/ci/check-android-notification-results.py" \
    "$reports/notification" "$reports/notification.log" "$notification_gradle" || notification_status=1
if ! adb shell am force-stop com.vladimir.messenger; then
    echo '::error::Could not isolate navigation from the notification tests'
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
trap 'restore_test_permission; restore_metrics' EXIT
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
