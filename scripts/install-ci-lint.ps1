param(
    [string]$Branch = 'arena/87dd1a2e-apumir',
    [switch]$DryRun
)

# ============================================================================
# install-ci-lint.ps1 - puts the Android Lint check into
# .github/workflows/ and pushes it into the pull-request branch.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install-ci-lint.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install-ci-lint.ps1 -DryRun
#
# Why a separate script even though the app CAN push workflows now (2026-10-27):
# the source of truth stays in scripts\ci\, and the installer re-creates
# .github/workflows/lint-ci.yml from it in one command. Editing YAML by hand
# through the browser is what this avoids - indentation in YAML is unforgiving.
# (Before 2026-10-27 the app had no `workflows` permission at all, and this was
# the only way to install the file; that limitation is gone, the workflow is
# installed.)
#
# The script never touches the working copy that is checked out. It fetches
# the branch, opens a temporary git worktree for it (works even if the clone
# has uncommitted changes), puts the workflow in place, commits, pushes and
# removes the worktree. Run it from the clone or from a downloaded copy: the
# repo root is taken from the script location, otherwise from the current
# folder.
#
# What the check does: `./gradlew :app:lintRelease` (Android Lint ships with AGP) on
# every pull request that touches android-app. No APK, no signing, no release.
# First versions are report-only: scripts/ci/lint-release.sh always exits 0
# until the marker file scripts/ci/lint-blocking appears, so a red lint step
# cannot block work before the backlog of old findings is cleared.
#
# ASCII only on purpose: PowerShell 5.1 misreads UTF-8 without BOM.
# ============================================================================

$ErrorActionPreference = 'Stop'

$WorkflowPath = '.github/workflows/lint-ci.yml'
$SourceRel = 'scripts/ci/lint-ci.yml'

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

# GitHub rejects a whole workflow file when a step name holds ": " without
# quotes (YAML reads it as a nested mapping) - and it does that silently: the
# run appears with the file path instead of the workflow name and no job log.
# That already cost one pointless install round on 2026-09-17, so the file is
# checked here, before the push.
function Assert-StepNamesLookSane([string]$Path) {
    $problems = @()
    $num = 0
    foreach ($line in (Get-Content -LiteralPath $Path -Encoding UTF8)) {
        $num++
        if ($line.TrimStart().StartsWith('#')) { continue }
        $m = [regex]::Match($line, "^\s*-\s*name:\s+(\S.*)$")
        if (-not $m.Success) { continue }
        $value = $m.Groups[1].Value.TrimEnd()
        $first = $value.Substring(0, 1)
        if ($first -eq '"' -or $first -eq "'") { continue }
        if ($value.Contains(': ')) {
            $problems += "line ${num}: step name contains ': ' without quotes -> $value"
        }
    }
    return $problems
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

Write-Output "===== android lint check ====="

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

    $Worktree = Join-Path $env:TEMP 'apu-m8-ci-lint'
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

    $Problems = Assert-StepNamesLookSane (Join-Path $Worktree $WorkflowPath)
    if ($Problems.Count -gt 0) {
        Write-Output $Problems
        Stop-With 'the workflow file is not installable: fix the step names above (quotes around a name that holds a colon)'
    }

    Push-Location $Worktree
    try {
        $Changes = (& git status --porcelain | Out-String).Trim()
        if ($Changes -eq '') {
            Write-Output "RESULT: nothing to do - $WorkflowPath is already in place on $Branch."
            exit 0
        }

        & git add $WorkflowPath
        if ($LASTEXITCODE -ne 0) { Stop-With 'git add failed' }

        & git commit -m 'ci: android lint on pull requests (lint-ci.yml)'
        if ($LASTEXITCODE -ne 0) { Stop-With 'git commit failed' }

        & git push origin "HEAD:refs/heads/$Branch"
        if ($LASTEXITCODE -ne 0) { Stop-With 'git push failed - push the commit manually' }

        Write-Output ''
        Write-Output 'RESULT: the workflow is in place and pushed into the pull request.'
        Write-Output 'On GitHub it shows up as the "Android lint" action and starts'
        Write-Output 'by itself; the report appears in the pull request checks.'
        Write-Output 'It stays report-only until scripts/ci/lint-blocking is committed.'
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
