@echo off
echo ============================================
echo Сборка Rust-ядра для Android
echo ============================================

REM Переход в папку rust-core
cd /d "%~dp0"

REM Установка Android-таргетов (если ещё не установлены)
echo [1/4] Установка Rust-таргетов...
rustup target add aarch64-linux-android
rustup target add armv7-linux-androideabi
rustup target add x86_64-linux-android

REM Сборка под все архитектуры
echo [2/4] Сборка под arm64-v8a...
cargo build --release --target aarch64-linux-android

echo [3/4] Сборка под armeabi-v7a...
cargo build --release --target armv7-linux-androideabi

echo [4/4] Сборка под x86_64...
cargo build --release --target x86_64-linux-android

REM Копирование .so файлов в jniLibs
echo.
echo Копирование .so файлов в jniLibs...

mkdir ..\android-app\app\src\main\jniLibs\arm64-v8a 2>nul
mkdir ..\android-app\app\src\main\jniLibs\armeabi-v7a 2>nul
mkdir ..\android-app\app\src\main\jniLibs\x86_64 2>nul

copy target\aarch64-linux-android\release\libp2p_core.so ..\android-app\app\src\main\jniLibs\arm64-v8a\
copy target\armv7-linux-androideabi\release\libp2p_core.so ..\android-app\app\src\main\jniLibs\armeabi-v7a\
copy target\x86_64-linux-android\release\libp2p_core.so ..\android-app\app\src\main\jniLibs\x86_64\

echo.
echo ============================================
echo Сборка завершена успешно!
echo ============================================
pause