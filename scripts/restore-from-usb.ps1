# restore-from-usb.ps1 - rebuild a working APUMIR checkout on a NEW PC from a
# flash-drive copy made by scripts\backup-to-usb.ps1.
#
# It does five things a plain "git clone" would miss:
#   1. Checks every file in the backup against MANIFEST.txt (sha256), so a
#      half-copied or corrupted drive is caught before it wastes an hour.
#   2. Clones from repo\apumir-mirror.git, which holds all branches and all tags.
#   3. Repoints "origin" at GitHub. Left alone, origin would be the flash drive
#      and the first "git push" would write back to the stick.
#   4. Recreates the local branches, because a clone of a mirror checks out
#      nothing when the mirror's HEAD refers to a ref it does not have.
#   5. Restores android-app\local.properties, and warns that its sdk.dir is a
#      path from the OLD PC and almost certainly has to be rewritten.
#
# Usage (drive mounted as E:):
#   powershell -NoProfile -ExecutionPolicy Bypass -File E:\...\restore-from-usb.ps1 -BackupDir E:\APUMIR-backup-20260830-183000
#   add -RestoreToolchain to also put back the Gradle distribution and adb
#   add -Force to install over an existing non-empty directory
#
# After it finishes the PC still needs a JDK 17 and the Android SDK (compileSdk
# 35, build-tools, platform-tools). See docs\RESTORE_ON_NEW_PC.md.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BackupDir,

    [string]$Target = 'C:\APU-M8',

    # Put the Gradle wrapper distribution and platform-tools back where the
    # build expects them, so no download is needed.
    [switch]$RestoreToolchain,

    # Skip the sha256 pass over the backup. Not recommended.
    [switch]$SkipVerify,

    # Install over an existing non-empty $Target.
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$OriginUrl = 'https://github.com/vzhem/APUMIR.git'

function Write-Step([string]$Message) {
    Write-Output ''
    Write-Output "===== $Message ====="
}

function Fail([string]$Message) {
    Write-Output ''
    Write-Output "FATAL: $Message"
    exit 1
}

if (-not (Test-Path $BackupDir)) { Fail "no such backup directory: $BackupDir" }
$BackupDir = (Resolve-Path $BackupDir).Path
Write-Output "backup:  $BackupDir"
Write-Output "target:  $Target"

# ---------------------------------------------------------------- 1. integrity

Write-Step '1. verifying the backup against MANIFEST.txt'
$Manifest = Join-Path $BackupDir 'MANIFEST.txt'
if (-not (Test-Path $Manifest)) {
    Fail 'MANIFEST.txt is missing, so this backup cannot be verified. Re-run backup-to-usb.ps1.'
}

if ($SkipVerify) {
    Write-Output 'SKIPPED on request. A corrupted backup will fail later and less clearly.'
} else {
    $Entries = Get-Content $Manifest | Where-Object { $_ -match '^[0-9A-F]{64}\s+\d+\s+\S' }
    if (-not $Entries) { Fail 'MANIFEST.txt has no file entries.' }
    $Checked = 0
    $Bad = @()
    foreach ($line in $Entries) {
        $Parts = $line -split '\s+', 3
        $Expected = $Parts[0]
        $Rel = $Parts[2].Trim()
        $Full = Join-Path $BackupDir $Rel
        if (-not (Test-Path $Full)) { $Bad += "MISSING  $Rel"; continue }
        $Actual = (Get-FileHash -Path $Full -Algorithm SHA256).Hash
        if ($Actual -ne $Expected) { $Bad += "CORRUPT  $Rel" }
        $Checked++
    }
    if ($Bad.Count -gt 0) {
        $Bad | ForEach-Object { Write-Output $_ }
        Fail "$($Bad.Count) of $Checked files did not match MANIFEST.txt. Copy the backup again."
    }
    Write-Output "all $Checked files match their sha256."
}

# ---------------------------------------------------------------- 2. what is in there

Write-Step '2. reading the recorded repository state'
$StateFile = Join-Path $BackupDir 'repo-state.txt'
$WantBranch = 'main'
$WantHead = ''
if (Test-Path $StateFile) {
    foreach ($line in (Get-Content $StateFile)) {
        if ($line -match '^branch:\s+(.+)$') { $WantBranch = $Matches[1].Trim() }
        if ($line -match '^HEAD:\s+([0-9a-f]{40})') { $WantHead = $Matches[1] }
    }
    Get-Content $StateFile | ForEach-Object { Write-Output "  $_" }
} else {
    Write-Output 'repo-state.txt not found - falling back to the branch named main.'
}
Write-Output "branch to check out: $WantBranch"

$Mirror = Join-Path $BackupDir 'repo\apumir-mirror.git'
$Bundle = Join-Path $BackupDir 'repo\apumir-all.bundle'
if (-not (Test-Path $Mirror) -and -not (Test-Path $Bundle)) {
    Fail "neither repo\apumir-mirror.git nor repo\apumir-all.bundle is present in $BackupDir."
}

# ---------------------------------------------------------------- 3. clone

Write-Step '3. cloning the history'
if (Test-Path $Target) {
    $Existing = Get-ChildItem $Target -Force -ErrorAction SilentlyContinue
    if ($Existing -and -not $Force) {
        Fail "$Target is not empty. Move it aside, choose another -Target, or pass -Force."
    }
    if ($Existing -and $Force) {
        Write-Output "-Force given: removing the existing $Target."
        Remove-Item -Recurse -Force $Target
    }
}

$Source = if (Test-Path $Mirror) { $Mirror } else { $Bundle }
Write-Output "source: $Source"
$Parent = Split-Path $Target -Parent
if ($Parent -and -not (Test-Path $Parent)) { New-Item -ItemType Directory -Path $Parent -Force | Out-Null }

# --no-checkout because a mirror whose HEAD points at a ref it does not carry
# leaves the clone with no working tree at all.
& git clone --no-checkout $Source $Target 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail 'git clone from the backup failed.' }

# ---------------------------------------------------------------- 4. origin and branches

Write-Step '4. repointing origin at GitHub and recreating branches'
& git -C $Target remote set-url origin $OriginUrl
Write-Output "origin is now $OriginUrl (it was the flash drive)"

$RemoteBranches = (& git -C $Target for-each-ref --format='%(refname:short)' refs/remotes/origin)
if (-not $RemoteBranches) { Fail 'the clone has no remote branches; the backup is incomplete.' }
foreach ($b in $RemoteBranches) { Write-Output "  available: $b" }

$CheckoutRef = "origin/$WantBranch"
if ($RemoteBranches -notcontains $CheckoutRef) {
    Write-Output "origin/$WantBranch is not in the backup."
    $Fallback = $RemoteBranches | Where-Object { $_ -ne 'origin/HEAD' } | Select-Object -First 1
    if (-not $Fallback) { Fail 'no branch to check out.' }
    $CheckoutRef = $Fallback
    $WantBranch = $Fallback -replace '^origin/', ''
    Write-Output "using $CheckoutRef instead."
}
& git -C $Target checkout -B $WantBranch $CheckoutRef 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "could not check out $WantBranch from $CheckoutRef." }
Write-Output "checked out $WantBranch at $((& git -C $Target rev-parse --short HEAD).Trim())"

# Create a local branch for every other remote branch, so nothing is only a ref.
foreach ($b in $RemoteBranches) {
    if ($b -eq 'origin/HEAD') { continue }
    $Name = $b -replace '^origin/', ''
    if ($Name -eq $WantBranch) { continue }
    & git -C $Target branch -f $Name $b 2>&1 | Out-Null
    Write-Output "  local branch created: $Name"
}

$RestoredHead = (& git -C $Target rev-parse HEAD).Trim()
$RestoredTags = ((& git -C $Target tag | Measure-Object -Line).Lines)
Write-Output "HEAD: $RestoredHead   tags: $RestoredTags"
if ($WantHead -and $RestoredHead -ne $WantHead) {
    Write-Output "WARNING: repo-state.txt recorded HEAD $WantHead but the clone is at $RestoredHead."
    Write-Output '         That means the backup was taken from a different commit than its own'
    Write-Output '         record says. Check git log before building anything.'
}

# ---------------------------------------------------------------- 5. machine-local

Write-Step '5. machine-local files'
$LocalPropsBackup = Join-Path $BackupDir 'machine-local\local.properties'
$LocalPropsTarget = Join-Path $Target 'android-app\local.properties'
if (Test-Path $LocalPropsBackup) {
    Copy-Item $LocalPropsBackup $LocalPropsTarget -Force
    Write-Output 'android-app\local.properties restored.'
    Write-Output 'WARNING: sdk.dir in it is a path from the OLD PC. Open the file and point it'
    Write-Output '         at this machine''s Android SDK, or delete the file and let Android'
    Write-Output '         Studio regenerate it.'
} else {
    Write-Output 'no local.properties in the backup. Create android-app\local.properties with:'
    Write-Output '  sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk'
}

foreach ($e in @('.env', '.dev.vars')) {
    $Src = Join-Path $BackupDir "machine-local\$e"
    if (Test-Path $Src) {
        Copy-Item $Src (Join-Path $Target $e) -Force
        Write-Output "restored secret file: $e"
    }
}

# ---------------------------------------------------------------- 6. toolchain

if ($RestoreToolchain) {
    Write-Step '6. toolchain caches'
    $ToolSrc = Join-Path $BackupDir 'toolchain'
    if (-not (Test-Path $ToolSrc)) {
        Write-Output 'the backup has no toolchain\ directory (it was made without -IncludeToolchainCaches).'
    } else {
        $Dists = Join-Path $ToolSrc 'gradle-wrapper-dists'
        if (Test-Path $Dists) {
            $Dest = Join-Path $env:USERPROFILE '.gradle\wrapper\dists'
            New-Item -ItemType Directory -Path (Split-Path $Dest -Parent) -Force | Out-Null
            Copy-Item $Dists $Dest -Recurse -Force
            Write-Output "Gradle distributions restored to $Dest"
        }
        $Pt = Join-Path $ToolSrc 'platform-tools'
        if (Test-Path $Pt) {
            Write-Output "platform-tools is in $Pt"
            Write-Output 'Copy it into your Android SDK folder (...\Android\Sdk\platform-tools) and put'
            Write-Output 'that folder on PATH so adb works.'
        }
    }
} else {
    Write-Step '6. toolchain caches skipped'
    Write-Output 'Pass -RestoreToolchain if the backup was made with -IncludeToolchainCaches.'
}

# ---------------------------------------------------------------- 7. verify

Write-Step '7. verifying the restored tree'
$Checks = @(
    @{ Name = 'release keystore'; Path = 'android-app\app\p2p-release.jks' },
    @{ Name = 'arm64 native lib'; Path = 'android-app\app\src\main\jniLibs\arm64-v8a\libp2p_core.so' },
    @{ Name = 'armv7 native lib'; Path = 'android-app\app\src\main\jniLibs\armeabi-v7a\libp2p_core.so' },
    @{ Name = 'gradle wrapper'; Path = 'android-app\gradlew.bat' },
    @{ Name = 'build gate script'; Path = 'scripts\groups-build-gate.ps1' },
    @{ Name = 'collaboration notes'; Path = 'docs\AI_COLLABORATION_NOTES.md' }
)
$Missing = 0
foreach ($c in $Checks) {
    $p = Join-Path $Target $c.Path
    if (Test-Path $p) {
        $Size = (Get-Item $p).Length
        Write-Output ("  ok       {0,-22} {1} bytes" -f $c.Name, $Size)
    } else {
        Write-Output ("  MISSING  {0,-22} {1}" -f $c.Name, $c.Path)
        $Missing++
    }
}
if ($Missing -gt 0) { Fail "$Missing expected files are missing from the restored tree." }

# ---------------------------------------------------------------- next steps

Write-Step 'restored - what is left is not in the backup'
Write-Output "repository:  $Target on branch $WantBranch at $($RestoredHead.Substring(0, [math]::Min(12, $RestoredHead.Length)))"
Write-Output ''
Write-Output 'Still to install on this PC, in this order:'
Write-Output '  1. JDK 17 (the build targets Java 17).'
Write-Output '  2. Android SDK: platform 35, build-tools, platform-tools. minSdk is 26.'
Write-Output '  3. Fix sdk.dir in android-app\local.properties (see the warning above).'
Write-Output '  4. GitHub CLI, then "gh auth login" - credentials are never put on the drive.'
Write-Output '  5. USB debugging enabled on each phone, and adb drivers for them.'
Write-Output ''
Write-Output 'Then, to prove the machine really builds:'
Write-Output "  cd $Target"
Write-Output '  git fetch --tags'
Write-Output "  powershell -NoProfile -ExecutionPolicy Bypass -File $Target\scripts\groups-build-gate.ps1 -ExpectedCommit $($RestoredHead.Substring(0, [math]::Min(12, $RestoredHead.Length)))"
Write-Output ''
Write-Output 'The gate must print tests=157 with a non-zero "executed" count. A run that'
Write-Output 'reports "up-to-date" for every task did not actually run the tests.'
exit 0
