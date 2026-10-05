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

# ---- Android SDK: cmdline-tools + платформа под compileSdk = 35 ------------
SDK="$REPO_ROOT/target/android-sdk"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    mkdir -p "$SDK/cmdline-tools"
    curl -fsSL -o /tmp/cmdline-tools.zip \
        https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    unzip -q -o /tmp/cmdline-tools.zip -d "$SDK/cmdline-tools"
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
export ANDROID_HOME="$SDK"
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" --install \
    "platforms;android-35" "build-tools;34.0.0" "platform-tools" >/dev/null

# ---- сама компиляция --------------------------------------------------------
chmod +x android-app/gradlew
cd android-app || exit 1
./gradlew :app:compileReleaseKotlin --no-daemon --console=plain
