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
# Rust-ядро не трогает: jniLibs лежат в git, для компиляции Kotlin их хватает.
#
# Статус блокировки решает ci-core-check.sh: пока шаг не доказал стабильность,
# его падение = warning + комментарий (паттерн K6, как у cargo test).
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

# ---- сама компиляция --------------------------------------------------------
chmod +x android-app/gradlew
cd android-app || exit 1
./gradlew :app:compileReleaseKotlin --no-daemon --console=plain

# ---- JVM-тесты конверта ранга (чистая логика, без Android) -------------------
# Разбор APURANK1 - единственное место передачи ранга, которое можно проверить
# без телефона: тест гоняется тем же gradle, что уже поднят выше, и стоит
# секунды. Фильтр узкий нарочно - полный прогон тестов приложения сюда не
# входит (в песочнице владельца его нет, и трогать чужие тесты не наша задача).
./gradlew :app:testDebugUnitTest --no-daemon --console=plain --tests '*RankWireTest*'
