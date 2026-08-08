#!/usr/bin/env bash
set -euo pipefail

# ─── Настройка ─────────────────────────────────────────────────────
: "${ANDROID_NDK_HOME:?Переменная ANDROID_NDK_HOME не задана. Пример: export ANDROID_NDK_HOME=\$HOME/Android/Sdk/ndk/26.2.11394342}"

RUST_DIR="rust-core"
ANDROID_JNI_DIR="android-app/app/src/main/jniLibs"
KOTLIN_OUT_DIR="android-app/app/src/main/java"

# ─── Целевые платформы ─────────────────────────────────────────────
TARGETS=(
    "aarch64-linux-android:arm64-v8a"
    "x86_64-linux-android:x86_64"
)

# ─── Проверка Rust targets ─────────────────────────────────────────
for pair in "${TARGETS[@]}"; do
    target="${pair%%:*}"
    if ! rustup target list --installed | grep -q "$target"; then
        echo ">>> Устанавливаю Rust target: $target"
        rustup target add "$target"
    fi
done

# ─── Определяем host-триплет NDK ───────────────────────────────────
HOST_TAG=""
case "$(uname -s)" in
    Linux*)   HOST_TAG="linux-x86_64" ;;
    Darwin*)  HOST_TAG="darwin-x86_64" ;;
    MINGW*|MSYS*|CYGWIN*) HOST_TAG="windows-x86_64" ;;
    *) echo "Неподдерживаемая OS: $(uname -s)"; exit 1 ;;
esac

TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG"
API_LEVEL=26

if [ ! -d "$TOOLCHAIN" ]; then
    echo "Не найдено: $TOOLCHAIN"
    echo "Проверь ANDROID_NDK_HOME и версию NDK."
    exit 1
fi

# ─── Переменные окружения для кросс-компиляции ─────────────────────
export CC_aarch64_linux_android="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang"
export CXX_aarch64_linux_android="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang++"
export AR_aarch64_linux_android="$TOOLCHAIN/bin/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC_aarch64_linux_android"

export CC_x86_64_linux_android="$TOOLCHAIN/bin/x86_64-linux-android${API_LEVEL}-clang"
export CXX_x86_64_linux_android="$TOOLCHAIN/bin/x86_64-linux-android${API_LEVEL}-clang++"
export AR_x86_64_linux_android="$TOOLCHAIN/bin/llvm-ar"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$CC_x86_64_linux_android"

# ─── Сборка Rust под все ABI ───────────────────────────────────────
pushd "$RUST_DIR" > /dev/null

for pair in "${TARGETS[@]}"; do
    target="${pair%%:*}"
    abi="${pair##*:}"
    echo ""
    echo ">>> [$abi] Сборка Rust для $target..."
    cargo build --release --target "$target"

    mkdir -p "../$ANDROID_JNI_DIR/$abi"
    cp "target/$target/release/libp2p_core.so" "../$ANDROID_JNI_DIR/$abi/libp2p_core.so"
    echo ">>> [$abi] .so скопирован в $ANDROID_JNI_DIR/$abi/"
done

# ─── Генерируем Kotlin-биндинги ────────────────────────────────────
echo ""
echo ">>> Сборка uniffi-bindgen (для хоста)..."
cargo build --release --bin uniffi-bindgen

echo ">>> Генерация Kotlin-биндингов..."
# Один из готовых .so нужен для чтения scaffolding metadata
./target/release/uniffi-bindgen generate \
    --library "target/aarch64-linux-android/release/libp2p_core.so" \
    --language kotlin \
    --out-dir "../$KOTLIN_OUT_DIR" \
    || ./target/release/uniffi-bindgen generate \
        src/lib.udl \
        --language kotlin \
        --out-dir "../$KOTLIN_OUT_DIR"

popd > /dev/null

echo ""
echo "✅ Готово! Теперь:"
echo "   cd android-app && ./gradlew clean installDebug"