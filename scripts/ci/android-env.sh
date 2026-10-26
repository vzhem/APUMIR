#!/usr/bin/env bash
# ============================================================================
# android-env.sh — JDK и Android SDK для шагов сборки приложения на runner.
#
# Зачем отдельным файлом. Одинаковое окружение нужно и компиляции Kotlin
# (check-android-compile.sh), и Android Lint (lint-release.sh). Копия этих
# тридцати строк разъехалась бы: правку в одной версии легко забыть во второй,
# а расхождение видно только на CI.
#
# Подключать через `source`, не запускать: файл только выставляет переменные.
#
#   source "$(dirname "$0")/android-env.sh"
#
# Что выставляет: JAVA_HOME + PATH (temurin/openjdk из образа runner, иначе
# apt), ANDROID_HOME с cmdline-tools (образ runner, иначе dl.google.com),
# platforms;android-35 и build-tools;34.0.0 — те же версии, что в
# gradle/libs.versions.toml и build-release.yml.
# ============================================================================

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
# ---- переменную: оставляем ANDROID_HOME, иначе «Several environment    -----
# ---- variables contain different paths to the SDK» (прогон 37305248268).----
if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
    unset ANDROID_SDK_ROOT
fi
ANDROID_ENV_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ANDROID_ENV_SDK="${ANDROID_HOME:-}"
if [ -z "$ANDROID_ENV_SDK" ] || [ ! -x "$ANDROID_ENV_SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    ANDROID_ENV_SDK="$ANDROID_ENV_REPO_ROOT/target/android-sdk"
    mkdir -p "$ANDROID_ENV_SDK/cmdline-tools"
    curl -fsSL -o /tmp/cmdline-tools.zip \
        https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    unzip -q -o /tmp/cmdline-tools.zip -d "$ANDROID_ENV_SDK/cmdline-tools"
    rm -rf "$ANDROID_ENV_SDK/cmdline-tools/latest"
    mv "$ANDROID_ENV_SDK/cmdline-tools/cmdline-tools" "$ANDROID_ENV_SDK/cmdline-tools/latest"
fi
export ANDROID_HOME="$ANDROID_ENV_SDK"
yes | "$ANDROID_ENV_SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
# compileSdk = 35 (gradle/libs.versions.toml); build-tools 34.0.0 - дефолт AGP 8.7.
"$ANDROID_ENV_SDK/cmdline-tools/latest/bin/sdkmanager" --install \
    "platforms;android-35" "build-tools;34.0.0" >/dev/null

unset ANDROID_ENV_REPO_ROOT ANDROID_ENV_SDK
