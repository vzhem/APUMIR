#!/usr/bin/env bash
# ============================================================================
# lint-release.sh — Android Lint для pull request (аудит 2026-10-26, п. 4.3).
#
# Зачем. В проекте не было статического анализа вообще: ошибки жизненного
# цикла, забытые `@Suppress`-и, несовместимые API на minSdk и мёртвые ресурсы
# находил только человек на телефоне. Android Lint входит в AGP — ставить
# ничего не нужно, задача `:app:lintRelease` есть из коробки.
#
# Что делает:
#   1. берёт JDK и Android SDK из общего bootstrap (scripts/ci/android-env.sh);
#   2. гоняет `./gradlew :app:lintRelease --no-daemon`;
#   3. печатает сводку по типам замечаний из текстового отчёта;
#   4. при падении оставляет комментарий к PR — логи прогона из песочницы
#      Arena не скачиваются (results-receiver.actions.githubusercontent.com
#      недоступен), а комментарий читается обычным API.
#
# Блокировка. Первый прогон почти наверняка красный: накопленные замечания
# никто не разбирал. Поэтому по умолчанию шаг НЕ блокирует PR, а печатает
# сводку и зелёный статус — как когда-то `cargo test` в ci-core-check.sh.
# Включение блокировки не требует правки воркфлоу: достаточно файла-маркера
# в репозитории.
#
#   scripts/ci/lint-blocking     — файл есть: падение lint роняет проверку.
#
# Переменные окружения:
#   GH_TOKEN     — токен для комментария (даёт воркфлоу), без него шаг молчит;
#   PR_NUMBER    — номер pull request, если это PR;
#   LINT_BLOCKING=1 — блокировать принудительно, не дожидаясь маркера.
# ============================================================================

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT" || exit 1

LOG="$REPO_ROOT/target/lint-release.log"
mkdir -p "$(dirname "$LOG")"
: > "$LOG"

say() { echo "$*" | tee -a "$LOG"; }

if [ -z "${LINT_BLOCKING:-}" ]; then
    if [ -f "$REPO_ROOT/scripts/ci/lint-blocking" ]; then
        LINT_BLOCKING=1
    else
        LINT_BLOCKING=0
    fi
fi

# ---- JDK + Android SDK -----------------------------------------------------
source "$REPO_ROOT/scripts/ci/android-env.sh" 2>&1 | tee -a "$LOG"

# ---- сама проверка ---------------------------------------------------------
# Мост uniffi не перегенерируем: он лежит в git (пост-релизный шаг его
# коммитит), а lint работает со статикой, не с ABI ядра. Если мост устарел,
# это поймает check-android-compile.sh, у которого задача другая.
chmod +x android-app/gradlew
cd android-app || exit 1
LINT_OK=0
if ./gradlew :app:lintRelease --no-daemon --console=plain >>"$LOG" 2>&1; then
    LINT_OK=1
fi
cd "$REPO_ROOT" || exit 1

sed -i 's/\x1b\[[0-9;]*[A-Za-z]//g' "$LOG" 2>/dev/null || true

REPORT_DIR="$REPO_ROOT/android-app/app/build/reports"
TXT="$REPORT_DIR/lint-results-release.txt"
HTML="$REPORT_DIR/lint-results-release.html"

# ---- сводка по типам замечаний ---------------------------------------------
# «12 errors, 340 warnings» на странице прогона ничего не говорит о том, что
# чинить. Считаем по типу проверки: [UnusedResources] и т.п.
lint_digest() {
    {
        if [ -f "$TXT" ]; then
            say "---- замечания по типам ----"
            sed -n 's/.*\[\([A-Za-z0-9_]*\)\].*/\1/p' "$TXT" \
                | sort | uniq -c | sort -rn | head -30
            say ""
            say "---- итог ----"
            tail -3 "$TXT"
            say ""
            say "---- первые строки отчёта ----"
            grep -E "^[^ ].*(Warning|Error):" "$TXT" | head -40
        else
            say "---- текстового отчёта нет: $TXT ----"
            tail -c 3000 "$LOG"
        fi
    } > /tmp/apu-lint-digest.txt 2>&1
}

comment_to_pr() {
    [ -n "${GH_TOKEN:-}" ] || return 0
    [ -n "${PR_NUMBER:-}" ] || return 0
    local text_file=/tmp/apu-lint-report.md
    {
        echo "Android Lint нашёл замечания (прогон ${GITHUB_RUN_ID:-локально})."
        if [ "$LINT_BLOCKING" != 1 ]; then
            echo
            echo "Шаг пока не блокирующий: разберите замечания, и блокировку включает"
            echo "файл-маркер \`scripts/ci/lint-blocking\` (правка воркфлоу не нужна)."
        fi
        echo
        echo '```'
        head -120 /tmp/apu-lint-digest.txt
        echo '```'
    } > "$text_file"
    gh pr comment "$PR_NUMBER" --body-file "$text_file" || say "не удалось оставить комментарий к PR"
}

if [ "$LINT_OK" = 1 ]; then
    say "Android Lint: замечаний уровня error нет (отчёт: $TXT)"
    [ -f "$TXT" ] && grep -c "Warning:" "$TXT" 2>/dev/null | sed 's/^/предупреждений: /' | tee -a "$LOG"
    exit 0
fi

lint_digest
say ""
say "--- разбор lint ---"
cat /tmp/apu-lint-digest.txt | tee -a "$LOG"

if [ "$LINT_BLOCKING" = 1 ]; then
    comment_to_pr
    exit 1
fi

say ""
say "Android Lint нашёл замечания, но шаг пока не блокирующий (см. scripts/ci/lint-blocking)."
exit 0
