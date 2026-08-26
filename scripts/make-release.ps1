param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$ExpectedCommit = '',
    [switch]$DryRun
)

# ============================================================================
# make-release.ps1 - tag the current commit so GitHub Actions publishes a build.
# ASCII only on purpose: PowerShell 5.1 misreads UTF-8 no-BOM.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\make-release.ps1 -Version v11.19.0 -DryRun
#   powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\make-release.ps1 -Version v11.19.0
#
# What happens after the tag is pushed (.github/workflows/build-release.yml):
#   the workflow builds rust-core for three ABIs with cargo-ndk, rewrites
#   versionName from the tag, runs :app:assembleRelease signed with
#   android-app/p2p-release.jks, and publishes a GitHub Release named
#   "Release <version>" with app-release.apk. It is published as a PRERELEASE,
#   exactly like v11.18.0 was; promoting it to a full release is a separate
#   manual step on GitHub.
#
# Before tagging this script re-runs the whole groups gate, because a tag is
# public and cannot be taken back cleanly once Actions has published a release.
# ============================================================================

$ErrorActionPreference = 'Stop'

$RepoRoot = 'C:\APU-M8'
$GateScript = Join-Path $PSScriptRoot 'groups-build-gate.ps1'

function Write-Step { param([string]$Text) Write-Output ''; Write-Output "===== $Text =====" }

# ---- version format: the same rules build.gradle.kts applies ----------------
Write-Step "release $Version"
if ($Version -notmatch '^v([0-9]+)\.([0-9]+)(?:\.([0-9]+))?$') {
    Write-Output "FATAL: '$Version' is not vMAJOR.MINOR or vMAJOR.MINOR.PATCH."
    Write-Output 'versionCodeFromName in app/build.gradle.kts would reject it and the'
    Write-Output 'build would fall back to v11.16.'
    exit 1
}
$Parts = $Version.Substring(1).Split('.')
$Minor = [int]$Parts[1]
$Patch = 0
if ($Parts.Count -eq 3) { $Patch = [int]$Parts[2] }
if ($Minor -gt 999 -or $Patch -gt 999) {
    Write-Output 'FATAL: minor and patch must be 999 or less (versionCode encoding).'
    exit 1
}
$NewTuple = @([int]$Parts[0], $Minor, $Patch)

# ---- repo state -------------------------------------------------------------
Write-Step 'repo state'
Push-Location $RepoRoot
try {
    $Head = (& git rev-parse HEAD | Out-String).Trim()
    $Branch = (& git rev-parse --abbrev-ref HEAD | Out-String).Trim()
    Write-Output "HEAD:   $Head"
    Write-Output "branch: $Branch"
    if ($ExpectedCommit -ne '' -and $Head -ne $ExpectedCommit) {
        Write-Output "FATAL: HEAD does not match $ExpectedCommit."
        exit 1
    }
    $Dirty = (& git status --porcelain | Out-String).Trim()
    if ($Dirty -ne '') {
        Write-Output 'FATAL: the working tree is not clean, uncommitted work would be left out:'
        Write-Output $Dirty
        exit 1
    }
    $Unpushed = (& git log --oneline "@{u}..HEAD" 2>&1 | Out-String).Trim()
    if ($Unpushed -ne '' -and $Unpushed -notmatch 'no upstream|unknown revision') {
        Write-Output 'FATAL: there are commits that are not on origin yet:'
        Write-Output $Unpushed
        exit 1
    }

    # ---- tag checks ---------------------------------------------------------
    Write-Step 'tags'
    & git fetch --tags origin | Out-Null
    $Existing = (& git tag --list 'v*' | Out-String).Trim() -split "`r?`n" | Where-Object { $_ -ne '' }
    if ($Existing -contains $Version) {
        Write-Output "FATAL: tag $Version already exists."
        exit 1
    }
    $Highest = $null
    foreach ($Tag in $Existing) {
        if ($Tag -notmatch '^v([0-9]+)\.([0-9]+)(?:\.([0-9]+))?$') { continue }
        $Tuple = @([int]$Matches[1], [int]$Matches[2], $(if ($Matches[3]) { [int]$Matches[3] } else { 0 }))
        if ($null -eq $Highest) { $Highest = $Tuple; $HighestName = $Tag; continue }
        for ($i = 0; $i -lt 3; $i++) {
            if ($Tuple[$i] -gt $Highest[$i]) { $Highest = $Tuple; $HighestName = $Tag; break }
            if ($Tuple[$i] -lt $Highest[$i]) { break }
        }
    }
    if ($null -ne $Highest) {
        Write-Output "highest existing tag: $HighestName"
        $Newer = $false
        for ($i = 0; $i -lt 3; $i++) {
            if ($NewTuple[$i] -gt $Highest[$i]) { $Newer = $true; break }
            if ($NewTuple[$i] -lt $Highest[$i]) { break }
        }
        if (-not $Newer) {
            Write-Output "FATAL: $Version is not higher than $HighestName. Installing it over"
            Write-Output 'a newer build would need a downgrade flag.'
            exit 1
        }
    }
    Write-Output "tag to create: $Version on $Head"

    # ---- gate ---------------------------------------------------------------
    Write-Step 'groups gate (unit tests, compile, assemble, androidTest, schema)'
    if (-not (Test-Path -LiteralPath $GateScript)) {
        Write-Output "FATAL: gate script not found at $GateScript"
        exit 1
    }
    & powershell -NoProfile -ExecutionPolicy Bypass -File $GateScript -ExpectedCommit $Head
    $GateExit = $LASTEXITCODE
    Write-Output "gate exit code: $GateExit"
    if ($GateExit -ne 0) {
        Write-Output 'RESULT: GATE FAILED - no tag was created.'
        exit $GateExit
    }

    if ($DryRun) {
        Write-Step 'DRY RUN - nothing was pushed'
        Write-Output "Would run: git tag -a $Version -m '...' && git push origin $Version"
        exit 0
    }

    # ---- tag and push -------------------------------------------------------
    Write-Step "pushing tag $Version"
    $Message = "Release $Version"
    & git tag -a $Version -m $Message
    $TagExit = $LASTEXITCODE
    if ($TagExit -ne 0) { Write-Output 'RESULT: FAILED to create the tag.'; exit $TagExit }
    & git push origin $Version
    $PushExit = $LASTEXITCODE
    if ($PushExit -ne 0) {
        Write-Output 'RESULT: the tag exists locally but was NOT pushed. Push it manually or'
        Write-Output "delete it with: git tag -d $Version"
        exit $PushExit
    }
    Write-Output ''
    Write-Output "RESULT: tag $Version pushed. GitHub Actions is building app-release.apk."
    Write-Output 'It will be published as a PRERELEASE named "Release ' + $Version + '".'
    Write-Output 'Promoting it to a full release is a separate manual step on GitHub.'
    exit 0
}
finally {
    Pop-Location
}
