#!/usr/bin/env bash
# ============================================================================
# release-tests.sh — тесты ядра для РЕЛИЗНОГО прогона (этап K6).
#
# Запускается из build-release.yml (шаг "Core tests (K6)"). Правило этапа:
# падение тестов = нет релиза. Скрипт живёт в репозитории, а не в YAML,
# потому что файл воркфлоу агент пушить не может (у GitHub-приложения Arena
# нет права `workflows`), а этот файл - может: правила тестов меняются
# обычным коммитом.
#
# Почему отдельный скрипт, а не шаг в ci-core-check.sh: релизный прогон
# ставит NDK и собирает под Android, поэтому хостовые пакеты для нативных
# зависимостей он не ставит. Здесь те же пакеты ставятся явно.
# ============================================================================

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT" || exit 1

LOG="$REPO_ROOT/rust-core/target/release-tests.log"
mkdir -p "$(dirname "$LOG")"

echo "=== системные пакеты для нативных зависимостей ядра ==="
if command -v apt-get >/dev/null 2>&1; then
    sudo apt-get update -qq >>"$LOG" 2>&1 || true
    sudo apt-get install -y -qq pkg-config libssl-dev cmake clang nasm >>"$LOG" 2>&1 || \
        echo "::warning::пакеты поставить не удалось - тесты могут не собраться"
fi

echo "=== cargo test --lib (release, mqtt-dual-broker) ==="
if (cd rust-core && cargo test --release --features mqtt-dual-broker --lib 2>&1 | tee -a "$LOG"); then
    echo "RESULT: тесты ядра пройдены"
    exit 0
fi

echo "RESULT: тесты ядра упали - релиз не собираем"
echo "--- хвост вывода ---"
tail -c 4000 "$LOG" || true
exit 1
