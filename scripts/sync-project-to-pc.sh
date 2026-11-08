#!/usr/bin/env bash
# ============================================================================
# sync-project-to-pc.sh — положить ВЕСЬ проект APUMIR на компьютер.
#
# Что делает:
#   1) рабочая копия (ветка main) со всеми ветвями как remote-tracking refs;
#   2) полная история: не «shallow», а весь лог от первого коммита;
#   3) все теги (их сотни — по ним собираются релизы `v11.74.x`);
#   4) по желанию — зеркало репозитория (`--mirror`): bare-копия всех ссылок,
#      ровно то, что лежит на GitHub, без рабочего дерева;
#   5) по желанию — релизные APK и контрольные суммы из GitHub Releases
#      (`--releases latest|N`), с предупреждением о размере.
#
# Что НЕ переносится и переноситься не должно (это локальные артефакты):
#   android-app/app/build, android-app/.gradle, rust-core/target, .idea,
#   android-app/local.properties — они создаются сборкой заново.
#   Ключ подписи `android-app/app/p2p-release.jks` лежит в репозитории, поэтому
#   клон сразу готов собирать подписанный релизный APK.
#
# Запуск:
#   bash scripts/sync-project-to-pc.sh --target ~/APUMIR
#   bash scripts/sync-project-to-pc.sh --target ~/APUMIR --mirror ~/APUMIR-mirror.git
#   bash scripts/sync-project-to-pc.sh --target ~/APUMIR --releases latest
#   bash scripts/sync-project-to-pc.sh --target ~/APUMIR --track-all-branches
#   bash scripts/sync-project-to-pc.sh --target ~/APUMIR --dry-run
#
# Код возврата: 0 — всё на месте, 1 — ошибка, 2 — неверные аргументы.
# Тот же сценарий для Windows: scripts/sync-project-to-pc.ps1.
# ============================================================================

set -uo pipefail

REMOTE_DEFAULT="https://github.com/vzhem/APUMIR.git"
# Владелец и имя репозитория для gh (GitHub CLI): берутся из REMOTE, если он на GitHub.
REMOTE_REPO_DEFAULT="vzhem/APUMIR"
TARGET=""
MIRROR=""
RELEASES="none"          # none | latest | <число последних релизов>
RELEASE_DIR=""
SHALLOW=0
TRACK_ALL_BRANCHES=0
DRY_RUN=0
SKIP_FSCK=0
REMOTE="$REMOTE_DEFAULT"
REMOTE_REPO="${APUMIR_REPO:-$REMOTE_REPO_DEFAULT}"

usage() {
    sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

log() { printf '%s\n' "$*"; }
warn() { printf 'ВНИМАНИЕ: %s\n' "$*" >&2; }
die() { printf 'ОШИБКА: %s\n' "$*" >&2; exit 1; }

need_value() {
    [ -n "${2:-}" ] || die "параметр $1 требует значения"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --target|-Target) need_value "$1" "${2:-}"; TARGET="$2"; shift 2 ;;
        --mirror|-Mirror) need_value "$1" "${2:-}"; MIRROR="$2"; shift 2 ;;
        --releases|-WithReleases) need_value "$1" "${2:-}"; RELEASES="$2"; shift 2 ;;
        --release-dir) need_value "$1" "${2:-}"; RELEASE_DIR="$2"; shift 2 ;;
        --remote) need_value "$1" "${2:-}"; REMOTE="$2"; shift 2 ;;
        --shallow) SHALLOW=1; shift ;;
        --track-all-branches) TRACK_ALL_BRANCHES=1; shift ;;
        --skip-fsck) SKIP_FSCK=1; shift ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) printf 'Неизвестный параметр: %s\n\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
done

[ -n "$TARGET" ] || { usage >&2; exit 2; }
case "$RELEASES" in
    none|latest|all|[0-9]*) ;;
    *) die "--releases принимает latest, all или число последних релизов" ;;
esac

run() {
    if [ "$DRY_RUN" = 1 ]; then
        printf '  [dry-run] %s\n' "$*"
        return 0
    fi
    "$@"
}

command -v git >/dev/null 2>&1 || die "не найден git — установите Git for Windows / git"

START_TS=$(date +%s)
log "APUMIR: синхронизация проекта с компьютером"
log "  источник: $REMOTE"
log "  рабочая копия: $TARGET"
[ -n "$MIRROR" ] && log "  зеркало: $MIRROR"
[ "$SHALLOW" = 1 ] && warn "выбран --shallow: история будет неполной (только последние коммиты)"
[ "$DRY_RUN" = 1 ] && warn "режим --dry-run: ничего не меняется, только печать команд"

# ── 1. рабочая копия ─────────────────────────────────────────────────────────
if [ -e "$TARGET" ] && [ ! -d "$TARGET/.git" ] && [ -n "$(ls -A "$TARGET" 2>/dev/null)" ]; then
    die "$TARGET существует, не пуст и не является git-копией — выберите другой путь"
fi
if [ -d "$TARGET/.git" ]; then
    log ""
    log "[1/5] рабочая копия уже есть — обновляю"
    if [ -n "$(git -C "$TARGET" status --porcelain 2>/dev/null)" ]; then
        warn "в $TARGET есть незакоммиченные изменения — обновление пропущено, файлы не тронуты"
        warn "закоммитьте/уберите изменения и запустите снова"
    else
        run git -C "$TARGET" fetch --all --tags --prune
        if [ "$(git -C "$TARGET" rev-parse --is-shallow-repository 2>/dev/null)" = "true" ] && [ "$SHALLOW" = 0 ]; then
            log "  клон был незавершённым — дотягиваю полную историю"
            run git -C "$TARGET" fetch --unshallow --tags || warn "не удалось дотянуть историю (нет сети?)"
        fi
        run git -C "$TARGET" checkout main >/dev/null 2>&1 || true
        run git -C "$TARGET" merge --ff-only "@{u}" >/dev/null 2>&1 || warn "ветка main не обновилась fast-forward (локальные коммиты?)"
    fi
else
    log ""
    log "[1/5] клонирую проект в $TARGET"
    [ -e "$TARGET" ] && [ -n "$(ls -A "$TARGET" 2>/dev/null)" ] && \
        die "$TARGET существует и не пуст — выберите другой путь или уберите его"
    if [ "$SHALLOW" = 1 ]; then
        run git clone --single-branch --depth 1 --no-tags "$REMOTE" "$TARGET"
    else
        # --no-single-branch: все ветви как remote-tracking; теги забираем ниже.
        run git clone --no-single-branch "$REMOTE" "$TARGET"
        run git -C "$TARGET" fetch --tags --prune
    fi
fi

# ── 2. все ветви и теги ──────────────────────────────────────────────────────
log ""
log "[2/5] ветви и теги"
if [ -d "$TARGET/.git" ]; then
    run git -C "$TARGET" fetch --all --tags --prune >/dev/null 2>&1 || warn "не удалось обновить ссылки"
    if [ "$TRACK_ALL_BRANCHES" = 1 ]; then
        log "  создаю локальные ветви для всех remote-ветвей (может занять минуту)"
        run bash -c '
            cd "$1" || exit 1
            git for-each-ref --format="%(refname:short)" refs/remotes/origin \
              | grep -v "^origin/HEAD$" \
              | while read -r ref; do
                    name=${ref#origin/}
                    git show-ref --verify --quiet "refs/heads/$name" || git branch --track "$name" "$ref" >/dev/null 2>&1
                done
        ' _ "$TARGET"
    fi
fi

# ── 3. зеркало всех ссылок ───────────────────────────────────────────────────
if [ -n "$MIRROR" ]; then
    log ""
    log "[3/5] зеркало репозитория: $MIRROR"
    if [ -d "$MIRROR" ]; then
        run git -C "$MIRROR" remote update --prune || warn "зеркало не обновилось"
    else
        run git clone --mirror "$REMOTE" "$MIRROR"
    fi
else
    log ""
    log "[3/5] зеркало не запрошено (--mirror), пропускаю"
fi

# ── 4. релизы ────────────────────────────────────────────────────────────────
log ""
if [ "$RELEASES" = "none" ]; then
    log "[4/5] релизы не запрошены (--releases), пропускаю"
else
    log "[4/5] релизные APK из GitHub Releases ($RELEASES)"
    if ! command -v gh >/dev/null 2>&1; then
        warn "не найден gh (GitHub CLI) — релизные файлы пропущены"
        warn "установите: winget install GitHub.cli, затем gh auth login"
    else
        DEST="${RELEASE_DIR:-$TARGET/releases}"
        run mkdir -p "$DEST"
        tags=""
        if [ "$RELEASES" = "latest" ]; then
            tags=$(gh release view --repo "$REMOTE_REPO" --json tagName -q .tagName 2>/dev/null || true)
            [ -z "$tags" ] && tags=$(gh release list --repo "$REMOTE_REPO" --limit 1 --json tagName -q '.[0].tagName' 2>/dev/null || true)
        else
            limit=10
            [ "$RELEASES" != "all" ] && limit="$RELEASES"
            tags=$(gh release list --repo "$REMOTE_REPO" --limit "$limit" --json tagName -q '.[].tagName' 2>/dev/null || true)
        fi
        if [ -z "$tags" ]; then
            warn "список релизов не получен (gh не авторизован?) — файлы пропущены"
        else
            for tag in $tags; do
                log "  $tag"
                run gh release download "$tag" --repo "$REMOTE_REPO" --dir "$DEST/$tag" \
                    --pattern '*.apk' --pattern 'SHA256SUMS.txt' --skip-existing \
                    || warn "не все файлы $tag скачались"
            done
            [ "$DRY_RUN" = 0 ] && du -sh "$DEST" 2>/dev/null | sed 's/^/  занято: /'
        fi
    fi
fi

# ── 5. проверка ──────────────────────────────────────────────────────────────
log ""
log "[5/5] проверка целостности"
if [ -d "$TARGET/.git" ] && [ "$DRY_RUN" = 0 ]; then
    [ "$SKIP_FSCK" = 1 ] || run git -C "$TARGET" fsck --full --no-progress || warn "git fsck нашёл проблемы — переклонируйте проект"
    BRANCHES=$(git -C "$TARGET" for-each-ref --format='%(refname:short)' refs/remotes/origin \
        | grep -v '^origin/HEAD$' | wc -l | tr -d ' ')
    TAGS=$(git -C "$TARGET" tag --list | wc -l | tr -d ' ')
    COMMITS=$(git -C "$TARGET" rev-list --count --all 2>/dev/null || echo '?')
    HEAD_SHA=$(git -C "$TARGET" rev-parse --short HEAD 2>/dev/null || echo '?')
    SIZE=$(du -sh "$TARGET/.git" 2>/dev/null | cut -f1)
    log ""
    log "ИТОГО"
    log "  ветвей: $BRANCHES, тегов: $TAGS, коммитов в истории: $COMMITS"
    log "  текущий коммит: $HEAD_SHA, размер .git: $SIZE"
    [ -n "$MIRROR" ] && [ -d "$MIRROR" ] && log "  зеркало: $(du -sh "$MIRROR" 2>/dev/null | cut -f1)"
    REPORT="$TARGET/SYNC_REPORT.txt"
    {
        echo "APUMIR sync report"
        echo "date: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
        echo "remote: $REMOTE"
        echo "branches: $BRANCHES"
        echo "tags: $TAGS"
        echo "commits: $COMMITS"
        echo "head: $HEAD_SHA"
        echo "git_size: $SIZE"
        [ -n "$MIRROR" ] && echo "mirror: $MIRROR"
        echo "releases: $RELEASES"
    } > "$REPORT"
    log "  отчёт: $REPORT"
fi

ELAPSED=$(( $(date +%s) - START_TS ))
log ""
log "Готово за ${ELAPSED} с."
log "Дальше: откройте $TARGET в Android Studio (android-app) и собирайте как обычно."
