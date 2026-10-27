#!/usr/bin/env bash
# ============================================================================
# check-android-compile.sh — полная компиляция Kotlin приложения на runner.
#
# Зачем. :app:compileReleaseKotlin до появления этого шага выполнялся ТОЛЬКО
# в сборке тега (build-release.yml). Первые два тега v11.74.171 и тег
# v11.74.175 умерли именно там, а логи прогона из песочницы Arena не
# скачиваются (results-receiver недоступен): ошибка становится видна только
# владельцу на вкладке Actions. Этот шаг гоняет ту же задачу компиляции в
# ci-core-check на каждом PR, и разбор падения уходит комментарием к PR
# (механика ci-core-check.sh).
#
# Что делает: JDK из образа runner (temurin 21/17, иначе openjdk из apt),
# Android SDK cmdline-tools с dl.google.com + platforms;android-35 и
# build-tools;34.0.0 (те же версии, что в gradle/libs.versions.toml и
# build-release.yml), затем ./gradlew :app:compileReleaseKotlin --no-daemon.
# Перед Kotlin генерируем мост из текущего UDL. Native runtime проверяет релизный gate.
#
# Статус блокировки решает ci-core-check.sh: после проверки стабильности
# шаг блокирующий. Ошибки Kotlin не должны скрываться зелёным Rust CI.
# ============================================================================

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT" || exit 1

# ---- JDK + Android SDK ------------------------------------------------------
# Общий bootstrap с шагом lint (scripts/ci/android-env.sh): одна копия вместо
# двух, иначе правка версии SDK забывается в одном из шагов.
source "$(dirname "${BASH_SOURCE[0]}")/android-env.sh"

# ---- мост для текущего UDL, не устаревший tracked snapshot ------------------
# Генератор обновляет рабочую копию runner; это compile-only, native ABI собирает release.
cargo run --manifest-path tools/uniffi-bindgen/Cargo.toml -- \
    generate rust-core/src/lib.udl --language kotlin --config rust-core/uniffi.toml \
    --out-dir android-app/app/src/main/java --no-format || exit 1

# ---- сама компиляция --------------------------------------------------------
chmod +x android-app/gradlew
cd android-app || exit 1
./gradlew :app:compileReleaseKotlin --no-daemon --console=plain || exit 1

# ---- JVM-регрессии, связанные с изменениями ветки --------------------------
# Кроме прежних проверок ранга/роя, гоняем диагностику ключей и правило
# «APK без broker-фрагментов» + стабильность ID обычных файлов. Это реальные
# unit-тесты после компиляции Kotlin, не подмена полной сборки релизного APK.
./gradlew :app:testDebugUnitTest --no-daemon --console=plain \
    --tests '*HeartGamePolicyTest*' --tests '*HeartGameWireTest*' --tests '*GroupSlowModeTest*' --tests '*GroupQuietHoursTest*' --tests '*GroupWallpaperTest*' --tests '*RankWireTest*' --tests '*FileTransferRankPolicyTest*' --tests '*OnlineHelperPolicyTest*' \
    --tests '*ShortLinksTest*' --tests '*DiagnosticsReportTest*' --tests '*RelayStorageDiagnosticsTest*' --tests '*RuntimeHealthTest*' --tests '*OperationalHealthTest*' --tests '*StoryDeliveryQueueTest*' --tests '*StoryVideoDraftTest*' --tests '*StoryVideoWireTest*' --tests '*StoryPublishTest*' --tests '*StoryOutboxTest*' --tests '*StoryStoreMergeTest*' --tests '*StoryCropTest*' --tests '*ProfileMirrorBirthdayTest*' \
    --tests '*StoryFeedbackWireTest*' --tests '*StoryFeedbackLedgerTest*' --tests '*StoryFeedbackOutboxTest*' \
    --tests '*GroupFileSeederTest*' --tests '*FileTransferWireTest*' --tests '*FileTransferReceiverTest*' --tests '*FileTransferSenderTest*' --tests '*FileTransferChunkStoreTest*' --tests '*TemporaryFileSweepTest*' --tests '*EphemeralControlTest*' \
    --tests '*SendTimePolicyTest*' --tests '*DeferredWireTest*' --tests '*DeferredDeliveryPolicyTest*'
TESTS_STATUS=$?

# ---- почему тесты упали: словами в лог, а не «красный шаг без причины» -------
# Сырые логи прогонов из песочницы бота не скачиваются, а комментарий к PR
# собирается из ЭТОГО вывода. Поэтому печатаем сводку JUnit-XML и текст падений.
RESULTS="$REPO_ROOT/android-app/app/build/test-results/testDebugUnitTest"
if [ -d "$RESULTS" ]; then
    echo "---- test results (testDebugUnitTest) ----"
    for f in "$RESULTS"/*.xml; do
        [ -f "$f" ] || continue
        echo "$(basename "$f"): $(head -2 "$f" | tail -1 | cut -c1-240)"
    done
    grep -h -A 10 "<failure" "$RESULTS"/*.xml | head -100
else
    echo "---- test results (testDebugUnitTest): отчётов нет ----"
fi
# Compile the Android renderer/device tests too. This does NOT run them:
# geometry, RTL, large-font and touch behaviour still need a device/emulator.
if [ "$TESTS_STATUS" -eq 0 ]; then
    ./gradlew :app:compileDebugAndroidTestKotlin --no-daemon --console=plain || exit 1
fi
exit "$TESTS_STATUS"
