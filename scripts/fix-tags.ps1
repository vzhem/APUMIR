param(
    [switch]$DryRun
)

# ============================================================================
# fix-tags.ps1 - make every LOCAL tag identical to the tag in origin.
# ASCII only on purpose: PowerShell 5.1 misreads UTF-8 without BOM.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\fix-tags.ps1 -DryRun
#   powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\fix-tags.ps1
#
# Why this script exists (rule in docs/START_HERE.md, section 9):
#   a tag is a release. There is ONE truth about a release: the tag in origin.
#   On 2026-08-29 the owner's PC had a LOCAL v11.17.1 pointing at c1dfd82
#   while origin's v11.17.1 points at 6deb532 (two commits later, the commit
#   the published APK was built from). Every "git fetch --tags" then died with
#   "! [rejected] v11.17.1 (would clobber existing tag)", and sync-main.ps1
#   stopped with "RESULT: tag fetch failed."
#
# What it does, per tag:
#   * tag missing locally           -> fetch it from origin;
#   * local tag == origin tag       -> nothing;
#   * local tag != origin tag       -> move it to origin's version, but ONLY
#     after checking the local commit is reachable from origin (nothing is
#     lost). If it is NOT reachable, the script refuses and tells you to save
#     that commit first;
#   * local tag absent from origin  -> listed as LOCAL ONLY, never touched.
#
# It never creates tags, never deletes tags, never touches releases, branches
# or .github. Pushing nothing: this script only fixes the local clone.
# ============================================================================

$ErrorActionPreference = 'Stop'

$RepoRoot = 'C:\APU-M8'

function Write-Step { param([string]$Text) Write-Output ''; Write-Output "===== $Text =====" }

# ---- 0. repo ----------------------------------------------------------------
Write-Step 'repo state'
if (-not (Test-Path (Join-Path $RepoRoot '.git'))) {
    Write-Output "FATAL: $RepoRoot is not a git repository."
    exit 1
}
Push-Location $RepoRoot

# ---- 1. what origin says about every tag ------------------------------------
Write-Step 'reading tags from origin'
$Raw = & git ls-remote --tags origin
if ($LASTEXITCODE -ne 0) {
    Write-Output 'RESULT: could not reach origin (network?). Nothing was changed.'
    Pop-Location
    exit 1
}

$Remote = @{}
foreach ($Line in $Raw) {
    if ([string]::IsNullOrWhiteSpace($Line)) { continue }
    $Parts = $Line -split "`t"
    if ($Parts.Count -lt 2) { continue }
    $Sha = $Parts[0].Trim()
    $Ref = $Parts[1].Trim()
    # skip the peeled lines "refs/tags/vX^{}"; the plain ref is what we compare
    if ($Ref.EndsWith('^{}')) { continue }
    if (-not $Ref.StartsWith('refs/tags/')) { continue }
    $Name = $Ref.Substring('refs/tags/'.Length)
    $Remote[$Name] = $Sha
}
Write-Output "origin has $($Remote.Count) tags"

# ---- 2. walk the local tags --------------------------------------------------
Write-Step 'comparing with local tags'
$LocalRaw = (& git tag --list | Out-String) -split "`r?`n"
$Local = @()
foreach ($L in $LocalRaw) { if (-not [string]::IsNullOrWhiteSpace($L)) { $Local += $L.Trim() } }
Write-Output "local clone has $($Local.Count) tags"

$ToFetch = @()
$ToMove = @()
$LocalOnly = @()
$Blocked = @()
$Same = 0

foreach ($Name in $Local) {
    $LocalSha = (& git rev-parse "refs/tags/$Name" | Out-String).Trim()
    if (-not $Remote.ContainsKey($Name)) {
        $LocalOnly += $Name
        continue
    }
    $RemoteSha = $Remote[$Name]
    if ($LocalSha -eq $RemoteSha) { $Same++; continue }

    # The local tag object differs. Is the local commit reachable from origin?
    $LocalCommit = (& git rev-list -n 1 "refs/tags/$Name" | Out-String).Trim()
    & git merge-base --is-ancestor $LocalCommit $RemoteSha
    if ($LASTEXITCODE -eq 0) {
        $ToMove += , @($Name, $LocalSha, $RemoteSha)
        continue
    }
    # Not an ancestor of the remote tag - check the wider history as well.
    & git merge-base --is-ancestor $LocalCommit refs/remotes/origin/main
    if ($LASTEXITCODE -eq 0) {
        $ToMove += , @($Name, $LocalSha, $RemoteSha)
    }
    else {
        $Blocked += , @($Name, $LocalCommit)
    }
}

Write-Output "identical:            $Same"
Write-Output "to move to origin's:  $($ToMove.Count)"
Write-Output "local only:           $($LocalOnly.Count)"
Write-Output "blocked (unique):     $($Blocked.Count)"

foreach ($M in $ToMove) {
    Write-Output "  MOVE $($M[0]): local $($M[1].Substring(0,7)) -> origin $($M[2].Substring(0,7))"
}
foreach ($L in $LocalOnly) {
    Write-Output "  LOCAL ONLY (not touched): $L"
}
foreach ($B in $Blocked) {
    Write-Output "  BLOCKED: $($B[0]) points at $($B[1]) which is NOT reachable from origin."
    Write-Output "           Save it first, for example:"
    Write-Output "           git branch archive/local-$($B[0]) $($B[1])"
}

# ---- 3. tags origin has but the clone does not -------------------------------
$Missing = @()
foreach ($Name in $Remote.Keys) {
    if ($Local -notcontains $Name) { $Missing += $Name }
}
Write-Output "missing locally:      $($Missing.Count)"
foreach ($Ms in $Missing) { Write-Output "  FETCH $Ms" }

if ($ToMove.Count -eq 0 -and $Missing.Count -eq 0) {
    Write-Output ''
    Write-Output 'RESULT: OK - local tags already match origin. Nothing to do.'
    Pop-Location
    exit 0
}

if ($DryRun) {
    Write-Output ''
    Write-Output 'DRY RUN: nothing was changed. Run without -DryRun to apply.'
    Pop-Location
    exit 0
}

if ($Blocked.Count -gt 0) {
    Write-Output ''
    Write-Output 'RESULT: STOPPED - save the blocked tags first (see above). Nothing moved.'
    Pop-Location
    exit 1
}

# ---- 4. apply ---------------------------------------------------------------
Write-Step 'applying'
foreach ($M in $ToMove) {
    $Name = $M[0]
    & git tag -d $Name
    if ($LASTEXITCODE -ne 0) {
        Write-Output "RESULT: could not delete the local tag $Name. Stopped."
        Pop-Location
        exit 1
    }
    & git fetch origin "refs/tags/${Name}:refs/tags/${Name}"
    if ($LASTEXITCODE -ne 0) {
        Write-Output "RESULT: could not fetch the tag $Name back. Stopped."
        Pop-Location
        exit 1
    }
    Write-Output "moved: $Name"
}

if ($Missing.Count -gt 0) {
    & git fetch --tags origin
    if ($LASTEXITCODE -ne 0) {
        Write-Output 'RESULT: the bulk tag fetch failed (network?). Run the script again.'
        Pop-Location
        exit 1
    }
}

# ---- 5. verify ---------------------------------------------------------------
Write-Step 'verify'
$Bad = 0
foreach ($Name in $Remote.Keys) {
    $Now = (& git rev-parse --quiet --verify "refs/tags/$Name" | Out-String).Trim()
    if ([string]::IsNullOrEmpty($Now)) {
        Write-Output "MISSING after the fix: $Name"
        $Bad++
        continue
    }
    if ($Now -ne $Remote[$Name]) {
        Write-Output "STILL DIFFERENT: $Name local $Now origin $($Remote[$Name])"
        $Bad++
    }
}
if ($Bad -gt 0) {
    Write-Output "RESULT: $Bad tag(s) still differ. Send this output to the agent."
    Pop-Location
    exit 1
}
Write-Output "RESULT: OK - all $($Remote.Count) origin tags are identical in the local clone."
Pop-Location
