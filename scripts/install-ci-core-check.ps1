param(
    [string[]]$Branch = @(),
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
# The script can be run from the clone or from a downloaded copy: the repo
# root is taken from the script location, or from the current folder when the
# script sits outside the clone. It mirrors the target branch, adds the file,
# commits and pushes, then returns to the branch that was checked out before.
#
# What the check does: `cargo check --release --features mqtt-dual-broker` and
# a uniffi generation check of lib.udl on every pull request. No APK, no
# signing, no release: the tag pipeline (build-release.yml) stays the only
# thing that publishes.
#
# ASCII only on purpose: PowerShell 5.1 misreads UTF-8 without BOM.
# ============================================================================

$ErrorActionPreference = 'Stop'

$DefaultBranch = 'arena/01a0b097-apumir'
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

function Branch-Exists([string]$Name) {
    $out = (& git branch --list $Name | Out-String).Trim()
    return ($out -ne '')
}

Write-Output "===== core compile check ====="

$RepoRoot = Find-RepoRoot
if ($RepoRoot -eq '') {
    Stop-With 'no git clone found: run the script from the clone root (C:\APU-M8), or pass a folder that contains .git'
}
Write-Output "clone: $RepoRoot"

Push-Location $RepoRoot
try {
    $Target = if ($Branch.Count -gt 0) { $Branch[0] } else { $DefaultBranch }

    $Dirty = (& git status --porcelain | Out-String).Trim()
    if ($Dirty -ne '') {
        Write-Output $Dirty
        Stop-With 'the working tree is not clean - commit or stash the changes first'
    }

    $Back = (& git rev-parse --abbrev-ref HEAD | Out-String).Trim()
    Write-Output "current branch: $Back"
    Write-Output "target branch:  $Target"

    Write-Output 'fetching origin...'
    & git fetch origin
    if ($LASTEXITCODE -ne 0) { Stop-With 'git fetch failed - check the network' }

    $Remote = (& git rev-parse --verify --quiet "refs/remotes/origin/$Target" | Out-String).Trim()
    if ($Remote -eq '') { Stop-With "origin has no branch $Target - did the pull request get closed?" }

    if ($DryRun) {
        Write-Output ''
        Write-Output "DRY RUN: would put $SourceRel into $WorkflowPath on $Target and push."
        Write-Output 'nothing was changed.'
        exit 0
    }

    if ($Back -ne $Target) {
        Write-Output "switching to $Target (a local mirror of origin/$Target)..."
        & git checkout -B $Target "origin/$Target"
        if ($LASTEXITCODE -ne 0) { Stop-With "git checkout $Target failed" }
    }

    $Source = Join-Path $RepoRoot $SourceRel
    if (-not (Test-Path -LiteralPath $Source)) {
        Stop-With "no source file $Source - is the pull-request branch complete?"
    }

    $Dir = Split-Path -Parent (Join-Path $RepoRoot $WorkflowPath)
    if (-not (Test-Path -LiteralPath $Dir)) { $null = New-Item -ItemType Directory -Path $Dir -Force }
    Copy-Item -LiteralPath $Source -Destination (Join-Path $RepoRoot $WorkflowPath) -Force
    Write-Output "copied: $SourceRel -> $WorkflowPath"

    $Count = (& git status --porcelain | Measure-Object -Line).Lines
    if ($Count -eq 0) {
        Write-Output "RESULT: nothing to do - $WorkflowPath is already in place on $Target."
        if ($Back -ne $Target) { & git checkout $Back | Out-Null }
        exit 0
    }

    & git add $WorkflowPath
    if ($LASTEXITCODE -ne 0) { Stop-With 'git add failed' }

    & git commit -m 'ci: core compile check on pull requests (ci-core-check.yml)'
    if ($LASTEXITCODE -ne 0) { Stop-With 'git commit failed' }

    & git push origin $Target
    if ($LASTEXITCODE -ne 0) { Stop-With 'git push failed - push the commit manually' }

    if ($Back -ne $Target) {
        Write-Output "returning to $Back..."
        & git checkout $Back | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Output "NOTE: could not switch back to $Back - do it manually." }
    }

    Write-Output ''
    Write-Output 'RESULT: the workflow is in place and pushed into the pull request.'
    Write-Output 'The check starts by itself on the next pull-request update;'
    Write-Output 'on GitHub it shows up as the "Core compile check" action.'
    exit 0
}
finally {
    Pop-Location
}
