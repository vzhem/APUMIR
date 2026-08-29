param(
    [string]$WorkBranch = '',
    [switch]$DryRun
)

# ============================================================================
# sync-main.ps1 - bring branch "main" up to the working tip (fast-forward).
# ASCII only on purpose: PowerShell 5.1 misreads UTF-8 without BOM.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\sync-main.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\sync-main.ps1 -WorkBranch arena/01a04df9-apumir
#   powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\sync-main.ps1 -DryRun
#
# Why this script exists (rule written in docs/START_HERE.md, section 9):
#   main must always hold the real working tip. From 2026-08-17 till
#   2026-08-29 main sat on 6d28249 while 628 commits of real work lived only
#   in arena/* branches. main is the branch everybody clones; if it lags, the
#   next session reads dead code.
#
# What it does:
#   1. refuses to run on a dirty working tree;
#   2. picks the working branch (parameter, or the branch you are on now);
#   3. checks the working branch contains every commit of origin/main, so the
#      update of main is a pure fast-forward (no merge commit, no history
#      rewrite);
#   4. fast-forwards local main to the working tip and pushes it.
#
# It never creates tags, never touches releases, never touches .github.
# Pushing a branch does not build anything: build-release.yml only runs on
# "push: tags: v*".
# ============================================================================

$ErrorActionPreference = 'Stop'

$RepoRoot = 'C:\APU-M8'

function Write-Step { param([string]$Text) Write-Output ''; Write-Output "===== $Text =====" }

# ---- 0. repo and clean tree -------------------------------------------------
Write-Step 'repo state'
if (-not (Test-Path (Join-Path $RepoRoot '.git'))) {
    Write-Output "FATAL: $RepoRoot is not a git repository."
    exit 1
}
Push-Location $RepoRoot

$StartBranch = (& git rev-parse --abbrev-ref HEAD | Out-String).Trim()
Write-Output "repo: $RepoRoot"
Write-Output "current branch: $StartBranch"

$Dirty = (& git status --porcelain | Out-String).Trim()
if ($Dirty -ne '') {
    Write-Output 'FATAL: the working tree is not clean. Commit or stash first:'
    Write-Output $Dirty
    Pop-Location
    exit 1
}

# ---- 1. fetch ---------------------------------------------------------------
Write-Step 'fetch origin'
& git fetch origin --prune
if ($LASTEXITCODE -ne 0) { Write-Output 'RESULT: fetch failed.'; Pop-Location; exit 1 }
& git fetch --tags origin
if ($LASTEXITCODE -ne 0) { Write-Output 'RESULT: tag fetch failed.'; Pop-Location; exit 1 }

# ---- 2. which branch is the working one -------------------------------------
Write-Step 'working branch'
if ($WorkBranch -eq '') {
    if ($StartBranch -eq 'main') {
        Write-Output 'FATAL: you are on main and did not say which branch is the'
        Write-Output 'working one. Pass it explicitly, for example:'
        Write-Output '  ... -File .\scripts\sync-main.ps1 -WorkBranch arena/01a04df9-apumir'
        Pop-Location
        exit 1
    }
    $WorkBranch = $StartBranch
}
& git show-ref --verify --quiet "refs/heads/$WorkBranch"
if ($LASTEXITCODE -ne 0) {
    Write-Output "FATAL: local branch $WorkBranch does not exist."
    Pop-Location
    exit 1
}
$WorkTip = (& git rev-parse $WorkBranch | Out-String).Trim()
Write-Output "working branch: $WorkBranch"
Write-Output "working tip:    $WorkTip"

# ---- 3. main must be a strict ancestor (fast-forward only) ------------------
Write-Step 'fast-forward check'
$MainTip = (& git rev-parse refs/remotes/origin/main | Out-String).Trim()
Write-Output "origin/main:    $MainTip"
if ($MainTip -eq $WorkTip) {
    Write-Output 'RESULT: main is already at the working tip. Nothing to do.'
    Pop-Location
    exit 0
}
& git merge-base --is-ancestor $MainTip $WorkTip
if ($LASTEXITCODE -ne 0) {
    Write-Output 'FATAL: origin/main has commits that are NOT in the working branch.'
    Write-Output 'Fix that first, on the working branch:'
    Write-Output '  git merge origin/main'
    Write-Output 'resolve conflicts in favour of the working code, commit, push,'
    Write-Output 'then run this script again.'
    $Ahead = (& git rev-list --count "$WorkTip..$MainTip" | Out-String).Trim()
    Write-Output "commits main has and the working branch does not: $Ahead"
    Pop-Location
    exit 1
}
$Behind = (& git rev-list --count "$MainTip..$WorkTip" | Out-String).Trim()
Write-Output "main is behind by $Behind commit(s); fast-forward is safe."

if ($DryRun) {
    Write-Output ''
    Write-Output "DRY RUN: would fast-forward main $MainTip -> $WorkTip and push."
    Pop-Location
    exit 0
}

# ---- 4. fast-forward local main and push ------------------------------------
Write-Step 'updating main'
& git checkout main
if ($LASTEXITCODE -ne 0) { Write-Output 'RESULT: could not check out main.'; Pop-Location; exit 1 }
& git merge --ff-only $WorkTip
if ($LASTEXITCODE -ne 0) {
    Write-Output 'RESULT: fast-forward failed; main was NOT moved.'
    Pop-Location
    exit 1
}
& git push origin main
if ($LASTEXITCODE -ne 0) {
    Write-Output 'RESULT: main moved locally but the PUSH failed (network?).'
    Write-Output 'Run this script again, or push manually: git push origin main'
    Pop-Location
    exit 1
}

# ---- 5. verify against the server -------------------------------------------
Write-Step 'verify'
$RemoteMain = (& git ls-remote origin refs/heads/main | Out-String).Trim()
Write-Output "remote main now: $RemoteMain"
$RemoteTip = ($RemoteMain -split "`t")[0]
if ($RemoteTip -ne $WorkTip) {
    Write-Output "RESULT: MISMATCH - remote main is $RemoteTip, expected $WorkTip."
    Pop-Location
    exit 1
}
Write-Output "RESULT: OK - origin/main = working tip $WorkTip"

& git checkout $StartBranch
if ($LASTEXITCODE -ne 0) {
    Write-Output "NOTE: could not return to $StartBranch; you are on main now."
}
Pop-Location
