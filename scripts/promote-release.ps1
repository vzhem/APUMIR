param(
    [Parameter(Mandatory = $true)][string]$Version,
    [int]$TimeoutMinutes = 25
)

# ============================================================================
# promote-release.ps1 - turn the Actions-built release into a FULL release.
# ASCII only on purpose: PowerShell 5.1 misreads UTF-8 without BOM.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\promote-release.ps1 -Version v11.20.0
#
# Why this script exists. Two things break the "download update" prompt, and
# both were hit in real life on v11.20.0:
#
#   1. build-release.yml publishes with "prerelease: true". The app asks
#      GET /releases/latest (UpdateChecker.kt), and the invite text points at
#      releases/latest/download/app-release.apk - BOTH ignore prereleases. So a
#      fresh Actions release is invisible to every phone until it is promoted.
#
#   2. Clearing the prerelease flag is NOT enough. GitHub decides which release
#      is "latest" at publish time, so /releases/latest kept returning the
#      previous tag for minutes after "gh release edit --prerelease=false"
#      (checked five times over five minutes). Publishing the release again
#      (draft, then publish) is what actually moves "latest".
#
# Needs the GitHub CLI (gh) signed in. The repository is public, so reading
# /releases/latest needs no token.
# ============================================================================

$ErrorActionPreference = 'Stop'

function Write-Step { param([string]$Text) Write-Output ''; Write-Output "===== $Text =====" }

Write-Step "promote $Version"
if ($Version -notmatch '^v[0-9]+\.[0-9]+(\.[0-9]+)?$') {
    Write-Output "FATAL: '$Version' is not vMAJOR.MINOR or vMAJOR.MINOR.PATCH."
    exit 1
}

$Gh = Get-Command gh -ErrorAction SilentlyContinue
if ($null -eq $Gh) {
    Write-Output 'FATAL: the GitHub CLI (gh) was not found on PATH.'
    Write-Output 'Install it from https://cli.github.com and run "gh auth login".'
    exit 1
}

# ---- wait for Actions to publish the release -------------------------------
Write-Step 'waiting for the release to appear'
$Deadline = (Get-Date).AddMinutes($TimeoutMinutes)
$Assets = 0
while ((Get-Date) -lt $Deadline) {
    $Json = & gh release view $Version --json isDraft,isPrerelease,assets 2>$null | Out-String
    if ($LASTEXITCODE -eq 0 -and $Json.Trim() -ne '') {
        $Parsed = $Json | ConvertFrom-Json
        $Assets = @($Parsed.assets).Count
        Write-Output ("release found: draft={0} prerelease={1} assets={2}" -f `
            $Parsed.isDraft, $Parsed.isPrerelease, $Assets)
        if ($Assets -gt 0) { break }
    } else {
        Write-Output 'release not published yet (Actions is probably still building)'
    }
    Start-Sleep -Seconds 30
}
if ($Assets -eq 0) {
    Write-Output "FATAL: no release with an APK for $Version within $TimeoutMinutes minutes."
    Write-Output 'Check the workflow run: gh run list --limit 5'
    exit 1
}

# ---- make it a full release, then publish it again --------------------------
Write-Step 'clearing the prerelease flag'
& gh release edit $Version --prerelease=false
if ($LASTEXITCODE -ne 0) { Write-Output 'RESULT: FAILED to clear the prerelease flag.'; exit 1 }

# Without this second publish /releases/latest keeps pointing at the old tag.
Write-Step 'publishing again so that /releases/latest moves'
& gh release edit $Version --draft=true
Start-Sleep -Seconds 10
& gh release edit $Version --draft=false
if ($LASTEXITCODE -ne 0) { Write-Output 'RESULT: FAILED to publish the release again.'; exit 1 }

# ---- verify the way the app will see it ------------------------------------
Write-Step 'verifying /releases/latest'
$LatestTag = ''
for ($i = 0; $i -lt 10; $i++) {
    Start-Sleep -Seconds 15
    try {
        $Latest = Invoke-RestMethod -Uri 'https://api.github.com/repos/vzhem/APUMIR/releases/latest'
        $LatestTag = $Latest.tag_name
    } catch {
        $LatestTag = ''
    }
    Write-Output "attempt $($i + 1): /releases/latest = $LatestTag"
    if ($LatestTag -eq $Version) { break }
}

Write-Output ''
if ($LatestTag -eq $Version) {
    Write-Output "RESULT: OK - $Version is the latest release."
    Write-Output 'Phones on an older version will now be offered the update, and'
    Write-Output 'releases/latest/download/app-release.apk resolves to this APK.'
    exit 0
}
Write-Output "RESULT: INCONCLUSIVE - /releases/latest still returns '$LatestTag'."
Write-Output "The release itself is published at https://github.com/vzhem/APUMIR/releases/tag/$Version"
exit 1
