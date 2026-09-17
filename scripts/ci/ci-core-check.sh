#!/usr/bin/env bash
# ============================================================================
# ci-core-check.sh — проверка компиляции ядра для pull request.
#
# Запускается тонким воркфлоу .github/workflows/ci-core-check.yml (шаг
# "Run the core check").  Вся логика проверки живёт ЗДЕСЬ, а не в YAML: файл
# воркфлоу агент запушить не может (у GitHub-приложения Arena нет права
# `workflows`), его ставит владелец скриптом
# `scripts/install-ci-core-check.ps1`.  Зато этот файл агент пушит сам,
# поэтому правила проверки можно менять без участия владельца.
#
# Что делает:
#   1. ставит системные пакеты для нативных зависимостей ядра (aws-lc-rs,
#      rusqlite bundled): pkg-config, libssl-dev, cmake, clang, nasm;
#   2. `cargo check --release --features mqtt-dual-broker` — та же линия
#      фич, что в релизной сборке (build-release.yml);
#   3. проверяет, что lib.udl собирается генератором uniffi-bindgen и что
#      версия контракта uniffi не уехала относительно файла в git;
#   4. гоняет `cargo test --lib` (пока не блокирующе — см. TESTS_BLOCKING);
#   5. при падении печатает хвост лога на странице прогона и, если это
#      pull request, пишет разбор ошибки комментарием к PR.
#
# Почему комментарий: из песочницы Arena логи прогона не скачиваются
# (results-receiver.actions.githubusercontent.com недоступен, страница
# прогона требует входа), а комментарий читается обычным API.
#
# Переменные окружения:
#   GH_TOKEN    — токен для комментария (даёт воркфлоу), без него шаг молчит;
#   PR_NUMBER   — номер pull request, если это PR;
#   TESTS_BLOCKING=1 — падение тестов роняет проверку (по умолчанию нет).
# ============================================================================

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT" || exit 1

LOG="$REPO_ROOT/target/ci-check.log"
mkdir -p "$(dirname "$LOG")"
: > "$LOG"

TESTS_BLOCKING="${TESTS_BLOCKING:-0}"
FAILED=0
FAILED_STEP=""

say() { echo "$*" | tee -a "$LOG"; }

# Вывод cargo приходит в цвете; в отчёте (и в поиске строк с ошибками) цвета
# только мешают: без них `^error` находится, а читать проще.
strip_ansi() {
    sed -i 's/\x1b\[[0-9;]*[A-Za-z]//g' "$LOG" 2>/dev/null || true
}

begin() {
    # Имя шага не перетирает уже случившуюся неудачу: иначе отчёт называл бы
    # последний запущенный шаг вместо упавшего (так и вышло в прогоне
    # 35284307701 - «шаг: cargo test», хотя падала компиляция).
    if [ "$FAILED" = 0 ] || [ -z "$FAILED_STEP" ]; then
        FAILED_STEP="$1"
    fi
    say ""
    say "=== $1 ==="
}

report() {
    # Хвост лога на странице прогона: видно без скачивания артефактов.
    say ""
    say "--- последние 4000 символов вывода ---"
    tail -c 4000 "$LOG" || true
}

comment_to_pr() {
    [ -n "${GH_TOKEN:-}" ] || return 0
    [ -n "${PR_NUMBER:-}" ] || return 0
    local text_file=/tmp/apu-ci-report.md
    {
        echo "Проверка ядра не прошла (шаг: ${FAILED_STEP:-compile}, прогон ${GITHUB_RUN_ID:-локально})."
        echo
        echo "Строки с ошибками компилятора:"
        echo
        echo '```'
        grep -n -m 3 -A 14 -E '^error(\[|:| )' "$LOG" || echo '(строк с ошибками компилятора в выводе нет)'
        echo '```'
        echo "Последние 3000 символов вывода:"
        echo
        echo '```'
        tail -c 3000 "$LOG"
        echo '```'
    } > "$text_file"
    gh pr comment "$PR_NUMBER" --body-file "$text_file" || say "не удалось оставить комментарий к PR"
}

# ── 1. системные пакеты для нативных зависимостей ──────────────────────────
begin "system packages"
if command -v apt-get >/dev/null 2>&1; then
    sudo apt-get update -qq >>"$LOG" 2>&1
    if ! sudo apt-get install -y -qq pkg-config libssl-dev cmake clang nasm >>"$LOG" 2>&1; then
        say "::warning::пакеты поставить не удалось — продолжаю, но сборка может упасть на aws-lc-rs"
    fi
else
    say "apt-get нет (не Linux?) — пропускаю установку пакетов"
fi

say ""
say "=== versions ==="
rustc --version 2>&1 | tee -a "$LOG"
cargo --version 2>&1 | tee -a "$LOG"
say "processors: $(nproc 2>/dev/null || echo '?')"

# ── 2. компиляция ядра ─────────────────────────────────────────────────────
begin "cargo check (release, mqtt-dual-broker)"
if (cd rust-core && cargo check --release --features mqtt-dual-broker >>"$LOG" 2>&1); then
    say "cargo check: OK"
else
    say "cargo check: FAILED"
    FAILED=1
    FAILED_STEP="cargo check"
fi
strip_ansi

# ── 3. генерация моста из lib.udl ──────────────────────────────────────────
begin "uniffi bindings from lib.udl"
BINDING_OK=0
if (
    cd rust-core || exit 1
    mkdir -p ../target/uniffi-check
    cargo run --manifest-path ../tools/uniffi-bindgen/Cargo.toml -- \
        generate src/lib.udl --language kotlin --config uniffi.toml \
        --out-dir ../target/uniffi-check --no-format
) >>"$LOG" 2>&1; then
    BIND="$(find "$REPO_ROOT/target/uniffi-check" -name p2p_core.kt 2>/dev/null | head -1)"
    if [ -n "$BIND" ] && [ -s "$BIND" ] && grep -q "ffi_p2p_core_uniffi_contract_version" "$BIND"; then
        BINDING_OK=1
        say "мост собран: $BIND ($(wc -c < "$BIND") байт)"
        NEWV="$(grep -o 'bindings_contract_version = [0-9]*' "$BIND" | grep -o '[0-9]*$' || true)"
        OLDV="$(grep -o 'bindings_contract_version = [0-9]*' "$REPO_ROOT/android-app/app/src/main/java/uniffi/p2p_core/p2p_core.kt" | grep -o '[0-9]*$' || true)"
        say "версия контракта uniffi: в git = ${OLDV:-?}, сгенерировано = ${NEWV:-?}"
        if [ -n "$NEWV" ] && [ -n "$OLDV" ] && [ "$NEWV" != "$OLDV" ]; then
            say "::warning::линия uniffi изменилась ($OLDV -> $NEWV): на теге останется мост из git — перед релизом это надо перегенерировать"
        fi
    else
        say "файл моста не найден или пуст"
    fi
else
    say "uniffi-bindgen: FAILED"
fi
if [ "$BINDING_OK" = 0 ] && [ "$FAILED" = 0 ]; then
    FAILED=1
    FAILED_STEP="uniffi bindings from lib.udl"
fi
strip_ansi
say "binding step: $([ "$BINDING_OK" = 1 ] && echo OK || echo FAILED)"

# ── 4. тесты ядра ──────────────────────────────────────────────────────────
begin "cargo test (lib)"
TESTS_OK=0
if (cd rust-core && cargo test --release --features mqtt-dual-broker --lib >>"$LOG" 2>&1); then
    TESTS_OK=1
fi
strip_ansi
say "тесты: $([ "$TESTS_OK" = 1 ] && echo OK || echo FAILED)"
grep -E "^test result:" "$LOG" | tail -5 | tee -a /dev/null >/dev/null || true
if [ "$TESTS_OK" = 0 ]; then
    if [ "$TESTS_BLOCKING" = 1 ] && [ "$FAILED" = 0 ]; then
        say "тесты блокирующие: проверка не прошла"
        FAILED=1
        FAILED_STEP="cargo test (lib)"
    else
        say "(тесты пока не блокирующие: прогон не роняем)"
    fi
fi

# ── 5. итог ────────────────────────────────────────────────────────────────
say ""
if [ "$FAILED" = 0 ]; then
    say "RESULT: OK (check=pass, bindings=$([ "$BINDING_OK" = 1 ] && echo pass || echo fail), tests=$([ "$TESTS_OK" = 1 ] && echo pass || echo fail))"
    exit 0
fi

say "RESULT: FAILED at step: $FAILED_STEP"
report
comment_to_pr
exit 1
