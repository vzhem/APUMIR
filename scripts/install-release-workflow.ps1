param(
    [switch]$DryRun
)

# ============================================================================
# install-release-workflow.ps1 - puts the release workflow into
# .github/workflows/ from the copy in the repository.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install-release-workflow.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install-release-workflow.ps1 -DryRun
#
# Why: the GitHub App behind the Arena sandbox has no `workflows` permission,
# so a file under .github/workflows cannot be pushed by the agent. The release
# workflow is kept as a full copy in scripts\ci\build-release.yml, the owner
# installs it with this script. That is the same trick as with the core check
# (scripts\install-ci-core-check.ps1): the copy in the repository is the
# source of truth, so later changes to the release pipeline are ordinary
# commits plus one run of this script - no retyping YAML by hand.
#
# What is new in this copy (2026-09-17): the K6 step "Core tests (K6)" calls
# scripts/ci/release-tests.sh before the native build. Rule of the stage: if
# the core tests fail, there is no release.
#
# The script never touches the checkout: it opens a temporary git worktree,
# copies the file there, commits, pushes and removes the worktree.
#
# ASCII only on purpose: PowerShell 5.1 misreads UTF-8 without BOM.
# ============================================================================

$ErrorActionPreference = 'Stop'

$WorkflowPath = '.github/workflows/build-release.yml'
$SourceRel = 'scripts/ci/build-release.yml'

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

function Invoke-Quiet([string]$Exe, [string[]]$Arguments) {
    $Old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Exe @Arguments 2>$null | Out-Null } catch { } finally { $ErrorActionPreference = $Old }
}

# GitHub rejects the whole file when a step name holds ": " without quotes,
# and it does so silently. scripts\ci\check-workflow-yaml.py catches that and
# the other workflow-only traps before anything is pushed.
function Assert-WorkflowLooksSane([string]$Path) {
    $python = Get-Command python -ErrorAction SilentlyContinue
    if (-not $python) { $python = Get-Command python3 -ErrorAction SilentlyContinue }
    if (-not $python) {
        Write-Output 'NOTE: python not found - workflow file is not pre-checked (GitHub will tell).'
        return
    }
    $checker = Join-Path (Split-Path -Parent $Path) 'check-workflow-yaml.py'
    if (-not (Test-Path -LiteralPath $checker)) { return }
    & $python.Source $checker $Path
    if ($LASTEXITCODE -ne 0) { Stop-With 'the workflow file has YAML problems (see above)' }
}

Write-Output "===== release workflow (K6 test gate) ====="

$RepoRoot = Find-RepoRoot
if ($RepoRoot -eq '') { Stop-With 'no git clone found: run the script from the clone root (C:\APU-M8)' }
Write-Output "clone: $RepoRoot"

Push-Location $RepoRoot
$Worktree = ''
$Created = $false
try {
    Write-Output 'fetching origin...'
    & git fetch origin
    if ($LASTEXITCODE -ne 0) { Stop-With 'git fetch failed - check the network' }

    $Branch = (& git rev-parse --abbrev-ref HEAD | Out-String).Trim()
    if ($Branch -eq 'HEAD' -or $Branch -eq '') { $Branch = 'main' }
    Write-Output "branch: $Branch"

    $Remote = (& git rev-parse --verify --quiet "refs/remotes/origin/$Branch" | Out-String).Trim()
    if ($Remote -eq '') { Stop-With "origin has no branch $Branch" }

    if ($DryRun) {
        Write-Output ''
        Write-Output "DRY RUN: would put $SourceRel into $WorkflowPath on $Branch and push it."
        exit 0
    }

    $Worktree = Join-Path $env:TEMP 'apu-m8-release-wf'
    if (Test-Path -LiteralPath $Worktree) { Remove-Item -LiteralPath $Worktree -Recurse -Force }
    Invoke-Quiet 'git' @('worktree', 'prune')

    Write-Output "opening a temporary working folder: $Worktree"
    & git worktree add --detach $Worktree "origin/$Branch"
    if ($LASTEXITCODE -ne 0) { Stop-With 'git worktree add failed' }
    $Created = $true

    $Source = Join-Path $Worktree $SourceRel
    if (-not (Test-Path -LiteralPath $Source)) {
        Stop-With "no source file $Source - is the branch complete?"
    }
    Assert-WorkflowLooksSane $Source

    $Dir = Split-Path -Parent (Join-Path $Worktree $WorkflowPath)
    if (-not (Test-Path -LiteralPath $Dir)) { $null = New-Item -ItemType Directory -Path $Dir -Force }
    Copy-Item -LiteralPath $Source -Destination (Join-Path $Worktree $WorkflowPath) -Force
    Write-Output "copied: $SourceRel -> $WorkflowPath"

    Push-Location $Worktree
    try {
        $Changes = (& git status --porcelain | Out-String).Trim()
        if ($Changes -eq '') {
            Write-Output "RESULT: nothing to do - $WorkflowPath already matches the copy in the repository."
            exit 0
        }
        Write-Output $Changes

        & git add $WorkflowPath
        if ($LASTEXITCODE -ne 0) { Stop-With 'git add failed' }

        & git commit -m 'ci: core tests before the release build (K6)'
        if ($LASTEXITCODE -ne 0) { Stop-With 'git commit failed' }

        & git push origin "HEAD:refs/heads/$Branch"
        if ($LASTEXITCODE -ne 0) { Stop-With 'git push failed - push the commit manually' }

        Write-Output ''
        Write-Output 'RESULT: the release workflow now runs the core tests before building the APK.'
        Write-Output 'If the tests fail, the release build stops and no release is published.'
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
