param(
    [string]$Branch = 'arena/01a0b097-apumir',
    [switch]$DryRun
)

# ============================================================================
# install-ci-core-check.ps1 - puts the core compile check into
# .github/workflows/ and pushes it into the pull-request branch.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install-ci-core-check.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install-ci-core-check.ps1 -DryRun
#
# Why a separate script: the GitHub App behind the Arena sandbox has no
# `workflows` permission, so a file under .github/workflows cannot be pushed
# by the agent (same story as build-release.yml, which the owner copied by
# hand in 2026-09-09). Keeping the workflow in scripts\ci\ and installing it
# from the clone avoids hand-copying YAML through the browser: indentation in
# YAML is unforgiving.
#
# The script never touches the working copy that is checked out. It fetches
# the branch, opens a temporary git worktree for it (works even if the clone
# has uncommitted changes), puts the workflow in place, commits, pushes and
# removes the worktree. Run it from the clone or from a downloaded copy: the
# repo root is taken from the script location, otherwise from the current
# folder.
#
# What the check does: `cargo check --release --features mqtt-dual-broker` and
# a uniffi generation check of lib.udl on every pull request. No APK, no
# signing, no release: the tag pipeline (build-release.yml) stays the only
# thing that publishes.
#
# ASCII only on purpose: PowerShell 5.1 misreads UTF-8 without BOM.
# ============================================================================

$ErrorActionPreference = 'Stop'

$WorkflowPath = '.github/workflows/ci-core-check.yml'
$SourceRel = 'scripts/ci/ci-core-check.yml'

function Stop-With([string]$Text) {
    Write-Output "FATAL: $Text"
    exit 1
}

function Find-RepoRoot {
    $candidates = @()
    if ($PSScriptRoot) { $candidates += (Split-Path -Parent $PSScriptRoot) }
    $candidates += (Get-Location).Path
    foreach ($c in $candidates) {
        if ($c -and (Test-Path -LiteralPath (Join-Path $c '.git'))) { return $c }
    }
    return ''
}

# Service git calls: their stderr must not become a terminating error, because
# PowerShell 5.1 turns native stderr into NativeCommandError while
# ErrorActionPreference is 'Stop'.
function Invoke-Quiet([string]$Exe, [string[]]$Arguments) {
    $Old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Exe @Arguments 2>$null | Out-Null
    }
    catch { }
    finally {
        $ErrorActionPreference = $Old
    }
}

Write-Output "===== core compile check ====="

$RepoRoot = Find-RepoRoot
if ($RepoRoot -eq '') {
    Stop-With 'no git clone found: run the script from the clone root (C:\APU-M8)'
}
Write-Output "clone:  $RepoRoot"
Write-Output "branch: $Branch"

Push-Location $RepoRoot
$Worktree = ''
$Created = $false
try {
    Write-Output 'fetching origin...'
    & git fetch origin
    if ($LASTEXITCODE -ne 0) { Stop-With 'git fetch failed - check the network' }

    $Remote = (& git rev-parse --verify --quiet "refs/remotes/origin/$Branch" | Out-String).Trim()
    if ($Remote -eq '') { Stop-With "origin has no branch $Branch - is the pull request still open?" }

    if ($DryRun) {
        Write-Output ''
        Write-Output "DRY RUN: would put $SourceRel into $WorkflowPath on $Branch and push it."
        Write-Output 'nothing was changed.'
        exit 0
    }

    $Worktree = Join-Path $env:TEMP 'apu-m8-ci-check'
    if (Test-Path -LiteralPath $Worktree) { Remove-Item -LiteralPath $Worktree -Recurse -Force }
    Invoke-Quiet 'git' @('worktree', 'prune')

    Write-Output "opening a temporary working folder: $Worktree"
    & git worktree add --detach $Worktree "origin/$Branch"
    if ($LASTEXITCODE -ne 0) { Stop-With 'git worktree add failed' }
    $Created = $true

    $Source = Join-Path $Worktree $SourceRel
    if (-not (Test-Path -LiteralPath $Source)) {
        Stop-With "no source file $Source - is the pull-request branch complete?"
    }

    $Dir = Split-Path -Parent (Join-Path $Worktree $WorkflowPath)
    if (-not (Test-Path -LiteralPath $Dir)) { $null = New-Item -ItemType Directory -Path $Dir -Force }
    Copy-Item -LiteralPath $Source -Destination (Join-Path $Worktree $WorkflowPath) -Force
    Write-Output "copied: $SourceRel -> $WorkflowPath"

    Push-Location $Worktree
    try {
        $Changes = (& git status --porcelain | Out-String).Trim()
        if ($Changes -eq '') {
            Write-Output "RESULT: nothing to do - $WorkflowPath is already in place on $Branch."
            exit 0
        }

        & git add $WorkflowPath
        if ($LASTEXITCODE -ne 0) { Stop-With 'git add failed' }

        & git commit -m 'ci: core compile check on pull requests (ci-core-check.yml)'
        if ($LASTEXITCODE -ne 0) { Stop-With 'git commit failed' }

        & git push origin "HEAD:refs/heads/$Branch"
        if ($LASTEXITCODE -ne 0) { Stop-With 'git push failed - push the commit manually' }

        Write-Output ''
        Write-Output 'RESULT: the workflow is in place and pushed into the pull request.'
        Write-Output 'On GitHub it shows up as the "Core compile check" action and starts'
        Write-Output 'by itself; the compile result appears in the pull request checks.'
        exit 0
    }
    finally {
        Pop-Location
    }
}
finally {
    if ($Created -and $Worktree -ne '') {
        Invoke-Quiet 'git' @('worktree', 'remove', '--force', $Worktree)
        if (Test-Path -LiteralPath $Worktree) { Remove-Item -LiteralPath $Worktree -Recurse -Force }
        Invoke-Quiet 'git' @('worktree', 'prune')
        Write-Output 'temporary working folder removed'
    }
    Pop-Location
}
