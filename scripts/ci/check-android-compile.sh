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

# ---- JDK: образ runner уже несёт temurin; apt - только если образа нет ----
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
    for d in /usr/lib/jvm/temurin-21-jdk-* /usr/lib/jvm/java-21-openjdk-* \
             /usr/lib/jvm/temurin-17-jdk-* /usr/lib/jvm/java-17-openjdk-*; do
        if [ -x "$d/bin/java" ]; then export JAVA_HOME="$d"; break; fi
    done
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
    echo "JDK из образа не найден - ставлю openjdk-21 из apt"
    sudo apt-get update -qq
    sudo apt-get install -y -qq openjdk-21-jdk-headless
    export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
fi
export PATH="$JAVA_HOME/bin:$PATH"
java -version 2>&1

# ---- Android SDK: образ runner уже несёт SDK; AGP терпит только одну -------
# ---- переменную: оставляем ANDROID_HOME, иначе «Several environment    ----
# ---- variables contain different paths to the SDK» (прогон 37305248268).----
if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
    unset ANDROID_SDK_ROOT
fi
SDK="${ANDROID_HOME:-}"
if [ -z "$SDK" ] || [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    SDK="$REPO_ROOT/target/android-sdk"
    mkdir -p "$SDK/cmdline-tools"
    curl -fsSL -o /tmp/cmdline-tools.zip \
        https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    unzip -q -o /tmp/cmdline-tools.zip -d "$SDK/cmdline-tools"
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
export ANDROID_HOME="$SDK"
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
# compileSdk = 35 (gradle/libs.versions.toml); build-tools 34.0.0 - дефолт AGP 8.7.
"$SDK/cmdline-tools/latest/bin/sdkmanager" --install \
    "platforms;android-35" "build-tools;34.0.0" >/dev/null

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
    --tests '*GroupSlowModeTest*' --tests '*GroupQuietHoursTest*' --tests '*RankWireTest*' --tests '*FileTransferRankPolicyTest*' --tests '*OnlineHelperPolicyTest*' \
    --tests '*ShortLinksTest*' --tests '*DiagnosticsReportTest*' --tests '*RelayStorageDiagnosticsTest*' --tests '*RuntimeHealthTest*' --tests '*OperationalHealthTest*' --tests '*StoryDeliveryQueueTest*' --tests '*StoryVideoDraftTest*' --tests '*StoryVideoWireTest*' --tests '*StoryPublishTest*' --tests '*StoryOutboxTest*' --tests '*StoryStoreMergeTest*' --tests '*StoryCropTest*' --tests '*ProfileMirrorBirthdayTest*' \
    --tests '*StoryFeedbackWireTest*' --tests '*StoryFeedbackLedgerTest*' --tests '*StoryFeedbackOutboxTest*' \
    --tests '*GroupFileSeederTest*' --tests '*FileTransferWireTest*' --tests '*FileTransferChunkStoreTest*' --tests '*TemporaryFileSweepTest*' --tests '*EphemeralControlTest*' \
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
