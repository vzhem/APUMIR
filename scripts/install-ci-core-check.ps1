param(
    [switch]$DryRun
)

# ============================================================================
# install-ci-core-check.ps1 - puts the core compile check into
# .github/workflows/ and pushes it.
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
# What the check does: `cargo check --release --features mqtt-dual-broker` and
# a uniffi generation check of lib.udl on every pull request. No APK, no
# signing, no release: the tag pipeline (build-release.yml) stays the only
# thing that publishes.
#
# ASCII only on purpose: PowerShell 5.1 misreads UTF-8 without BOM.
# ============================================================================

$ErrorActionPreference = 'Stop'

function Stop-With([string]$Text) {
    Write-Output "FATAL: $Text"
    exit 1
}

# The script lives in scripts\, the clone root is one level up.
$RepoRoot = Split-Path -Parent $PSScriptRoot
$Source = Join-Path $RepoRoot 'scripts\ci\ci-core-check.yml'
$Target = Join-Path $RepoRoot '.github\workflows\ci-core-check.yml'

Write-Output "===== core compile check ====="
if (-not (Test-Path -LiteralPath $Source)) { Stop-With "no source file $Source" }
if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot '.git'))) {
    Stop-With "$RepoRoot is not a git clone"
}

Push-Location $RepoRoot
try {
    $Branch = (& git rev-parse --abbrev-ref HEAD | Out-String).Trim()
    Write-Output "branch: $Branch"
    $Dirty = (& git status --porcelain | Out-String).Trim()
    if ($Dirty -ne '') {
        Write-Output $Dirty
        Stop-With 'the working tree is not clean - commit or stash the changes first'
    }

    $Dir = Split-Path -Parent $Target
    if (-not (Test-Path -LiteralPath $Dir)) {
        $null = New-Item -ItemType Directory -Path $Dir -Force
    }
    Copy-Item -LiteralPath $Source -Destination $Target -Force
    Write-Output 'copied: scripts\ci\ci-core-check.yml -> .github\workflows\ci-core-check.yml'

    if ($DryRun) {
        Write-Output ''
        Write-Output 'DRY RUN - nothing was committed or pushed.'
        exit 0
    }

    & git add '.github/workflows/ci-core-check.yml'
    if ($LASTEXITCODE -ne 0) { Stop-With 'git add failed' }

    & git commit -m 'ci: core compile check on pull requests (ci-core-check.yml)'
    if ($LASTEXITCODE -ne 0) { Stop-With 'git commit failed' }

    & git push origin $Branch
    if ($LASTEXITCODE -ne 0) { Stop-With 'git push failed - push the commit manually' }

    Write-Output ''
    Write-Output 'RESULT: the workflow is in place and pushed.'
    Write-Output 'From now on every pull request touching rust-core is compiled by CI,'
    Write-Output 'and the check can also be started by hand: Actions -> Core compile check -> Run workflow.'
    exit 0
}
finally {
    Pop-Location
}
