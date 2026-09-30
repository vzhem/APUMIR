#!/usr/bin/env bash
# sync-main.sh - fast-forward "main" up to the working branch tip (agent side).
#
# Why this exists (rule: docs/START_HERE.md, section 9): main is the single
# branch of truth and it must not lag behind the working branch. From
# 2026-09-18 till 2026-09-30 main sat 292 commits behind (v11.73.0) while 122
# releases v11.74.0..v11.74.121 went by unnoticed - every new Arena session
# branches from main and reads dead code.
#
# The owner's PC has scripts/sync-main.ps1. The Arena sandbox has no Windows
# and the GitHub App behind it has contents:write but NO `workflows`
# permission, so:
#   * the main branch ref is moved through the REST API, fast-forward only
#     (force=false) - history is never rewritten, tags are never touched;
#   * a file inside .github/workflows cannot be pushed by the agent at all
#     (that is why the release workflow lives as a copy in
#     scripts/ci/build-release.yml and is installed by the owner with
#     scripts/install-release-workflow.ps1).
#
# Usage:
#   bash scripts/sync-main.sh              # fast-forward main to HEAD
#   bash scripts/sync-main.sh --dry-run    # show what would happen, change nothing
#
# Exit codes: 0 = main is up to date (or was fast-forwarded), 1 = cannot sync.
set -euo pipefail

REPO="${APU_REPO:-vzhem/APUMIR}"
DRY_RUN=0
if [ "${1:-}" = "--dry-run" ]; then
  DRY_RUN=1
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "FATAL: gh is not installed - on the owner's PC use scripts/sync-main.ps1" >&2
  exit 1
fi

DIRTY="$(git status --porcelain)"
if [ -n "$DIRTY" ]; then
  echo "FATAL: the working tree is not clean - commit or stash first:" >&2
  echo "$DIRTY" >&2
  exit 1
fi

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
TIP="$(git rev-parse HEAD)"
if [ -z "$(git ls-remote origin "refs/heads/$BRANCH" 2>/dev/null)" ]; then
  echo "FATAL: origin has no branch $BRANCH - push it first" >&2
  exit 1
fi

git fetch -q origin '+refs/heads/main:refs/remotes/origin/main'
MAIN=""
if git rev-parse -q --verify refs/remotes/origin/main >/dev/null; then
  MAIN="$(git rev-parse refs/remotes/origin/main)"
fi

echo "repo:   $REPO"
echo "branch: $BRANCH"
echo "tip:    $TIP"
echo "main:   ${MAIN:-unknown}"

if [ "$MAIN" = "$TIP" ]; then
  echo "RESULT: main already equals the working tip - nothing to do"
  exit 0
fi

# Refuse to move main when it holds commits the tip does not: that is not a
# fast-forward, and rewriting history here would break the tags and the owner's
# clone. The working branch has to take origin/main in first (git merge
# origin/main), then this script runs again.
if [ -n "$MAIN" ] && ! git merge-base --is-ancestor "$MAIN" "$TIP" 2>/dev/null; then
  echo "FATAL: main is not an ancestor of the working tip - not a fast-forward." >&2
  echo "       Run: git fetch origin && git merge origin/main, then retry." >&2
  exit 1
fi

if [ "$DRY_RUN" = "1" ]; then
  echo "DRY RUN: would fast-forward main ${MAIN:0:7} -> $TIP through the REST API"
  exit 0
fi

gh api -X PATCH "repos/$REPO/git/refs/heads/main" \
  -f sha="$TIP" -F force=false \
  --jq '"RESULT: main fast-forwarded to " + .object.sha'
