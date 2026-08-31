# backup-to-usb.ps1 - write a flash-drive copy from which a NEW PC can continue
# working, with no network and no access to GitHub.
#
# What it takes and why:
#   1. git clone --mirror   - every ref: all branches, all tags, remote-tracking
#                             refs. A plain "git clone <dir>" restores it.
#   2. git bundle           - the same history as ONE file, so the backup can be
#                             copied anywhere. Verified by a real test clone
#                             (see the note below), not by "git bundle verify".
#   3. Uncommitted work     - a patch plus a copy of untracked files, because
#                             neither is reachable from any ref.
#   4. Machine-local files  - android-app/local.properties (sdk.dir) and any .env
#                             are gitignored, so they are NOT in the clone.
#   5. Built APKs           - build outputs are gitignored.
#   6. -IncludeToolchainCaches copies the Gradle wrapper distribution and
#                             platform-tools, so a new PC can build with no
#                             download. Opt-in: it costs hundreds of MB.
#
# The keystore android-app/app/p2p-release.jks IS tracked by git, so it travels
# inside the history and needs no special handling. Without it a release build
# cannot be signed and installed phones would reject the upgrade, which is why
# the verification step below checks for it explicitly.
#
# "git bundle verify" prints "The bundle records a complete history" even for a
# bundle made from a SHALLOW clone, and that bundle then fails to clone with
# "Failed to traverse parents of commit ...". So this script refuses to run on a
# shallow clone AND re-clones the bundle into a temp dir to prove it works.
#
# Usage (from any directory). With no -Drive the stick is found by its label
# APU_BACKUP, so the letter does not matter:
#   powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\backup-to-usb.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\backup-to-usb.ps1 -IncludeToolchainCaches
#   powershell -NoProfile -ExecutionPolicy Bypass -File C:\APU-M8\scripts\backup-to-usb.ps1 -Drive E:
#
# Restore on the new PC: see docs\RESTORE_ON_NEW_PC.md or scripts\restore-from-usb.ps1.
#
# Relationship to scripts\backup-to-flash.ps1: that older script (round 46)
# mirrors the working tree with robocopy into APU-BACKUP\APUMIR and rewrites
# APU-BACKUP\apumir-full.bundle. It is still valid and still the fastest way to
# refresh a plain folder copy. This one writes a timestamped, self-verifying
# backup with a sha256 manifest, the machine-local files git ignores, any
# uncommitted work, and a restore script - use it before travelling or before
# retiring a PC. docs\PC_TRANSFER.md describes both.

[CmdletBinding()]
param(
    # Optional. With no -Drive the stick is found by its volume label, the way
    # scripts\backup-to-flash.ps1 has done since round 46.
    [string]$Drive,

    # Defaults to the repository the script lives in.
    [string]$RepoPath,

    # Copy the Gradle wrapper distribution and adb/platform-tools so the new PC
    # can build and flash phones without downloading anything.
    [switch]$IncludeToolchainCaches
)

# 'Continue', not 'Stop': git reports progress on stderr even when it succeeds
# ("Cloning into bare repository..."). Under 'Stop' PowerShell turns that first
# stderr line into a terminating error and the script dies on its own success.
# Every git call below is checked by $LASTEXITCODE instead, and the file copies
# are checked by the verification pass at the end.
$ErrorActionPreference = 'Continue'

function Write-Step([string]$Message) {
    Write-Output ''
    Write-Output "===== $Message ====="
}

function Fail([string]$Message) {
    Write-Output ''
    Write-Output "FATAL: $Message"
    # Do not leave a half-written backup on the drive: it looks like a good copy
    # and would be trusted later. The timestamped folder is ours alone.
    if ($script:Root -and (Test-Path $script:Root)) {
        Write-Output "removing the incomplete backup at $($script:Root)"
        Remove-Item -Recurse -Force $script:Root
        if (Test-Path $script:Root) {
            Write-Output "WARNING: could not remove $($script:Root) - delete it by hand."
        }
    }
    exit 1
}

# ---------------------------------------------------------------- drive checks

# Without -Drive the stick is found by its volume label, using the same call
# that scripts\backup-to-flash.ps1 has used successfully on the owner's machine
# since round 46. The letter may differ between PCs; the label does not.
if (-not $Drive) {
    $Vol = Get-Volume -FileSystemLabel 'APU_BACKUP' -ErrorAction SilentlyContinue
    if ($null -eq $Vol -or [string]::IsNullOrEmpty($Vol.DriveLetter)) {
        Fail 'no drive labelled APU_BACKUP is mounted. Insert the white flash drive, or pass -Drive F: explicitly.'
    }
    $Drive = $Vol.DriveLetter + ':'
    Write-Output "flash found by label APU_BACKUP: $Drive"
}

$DriveLetter = ($Drive -replace '[\\:]', '')
if ($DriveLetter.Length -ne 1) { Fail "expected a drive letter such as E: but got '$Drive'." }
$DriveLetter = $DriveLetter.ToUpperInvariant()
$DriveRoot = "${DriveLetter}:\"
if (-not (Test-Path $DriveRoot)) { Fail "no drive $DriveRoot is mounted." }

$DriveInfo = Get-PSDrive -Name $DriveLetter -ErrorAction SilentlyContinue
if (-not $DriveInfo) { Fail "drive $DriveLetter is not a filesystem drive." }
$FreeGB = [math]::Round($DriveInfo.Free / 1GB, 2)
Write-Output "target drive: $DriveRoot ($FreeGB GB free)"
if ($DriveInfo.Free -lt 500MB) { Fail "less than 500 MB free on $DriveRoot." }

# ---------------------------------------------------------------- repo checks

if (-not $RepoPath) { $RepoPath = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path }
$RepoPath = (Resolve-Path $RepoPath).Path
Set-Location $RepoPath

$TopLevel = (& git rev-parse --show-toplevel 2>$null)
if ($LASTEXITCODE -ne 0 -or -not $TopLevel) { Fail "'$RepoPath' is not a git repository." }
Write-Output "repository:   $RepoPath"

$Head = (& git rev-parse HEAD).Trim()
$Branch = (& git rev-parse --abbrev-ref HEAD).Trim()
Write-Output "HEAD:         $($Head.Substring(0, [math]::Min(12, $Head.Length))) on branch $Branch"

# A shallow clone produces a bundle that verifies clean and then fails to clone.
$IsShallow = (& git rev-parse --is-shallow-repository).Trim()
if ($IsShallow -eq 'true') {
    Fail "this clone is shallow, so its history is truncated. Run 'git fetch --unshallow origin' first."
}

$CommitCount = ((& git rev-list --count HEAD) | Out-String).Trim()
$TagCount = ((& git tag | Measure-Object -Line).Lines)
Write-Output "history:      $CommitCount commits, $TagCount tags"

# ---------------------------------------------------------------- target dir

$Stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$Root = Join-Path $DriveRoot "APUMIR-backup-$Stamp"
# Publish it so Fail() can clean up a half-written backup.
$script:Root = $Root
New-Item -ItemType Directory -Path $Root -Force | Out-Null
$RepoDir = Join-Path $Root 'repo'
$LocalDir = Join-Path $Root 'machine-local'
$ApkDir = Join-Path $Root 'apks'
New-Item -ItemType Directory -Path $RepoDir, $LocalDir, $ApkDir -Force | Out-Null
Write-Output ''
Write-Output "backup root:  $Root"

# ---------------------------------------------------------------- 1. mirror

Write-Step '1. git clone --mirror (all branches, all tags, remote refs)'
$Mirror = Join-Path $RepoDir 'apumir-mirror.git'
& git clone --mirror $RepoPath $Mirror 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail 'git clone --mirror failed.' }
$MirrorRefs = (& git --git-dir=$Mirror for-each-ref --format='%(refname)' | Measure-Object -Line).Lines
$MirrorTags = (& git --git-dir=$Mirror tag | Measure-Object -Line).Lines
Write-Output "mirror refs:  $MirrorRefs (of which $MirrorTags tags)"
if ($MirrorTags -lt $TagCount) { Fail "the mirror holds $MirrorTags tags but the repository has $TagCount." }

# A clone of a mirror maps refs/heads/* to refs/remotes/origin/* and drops
# refs/remotes/* entirely. So a LOCAL branch that lags behind its origin
# counterpart is what a restored PC would get, and the newer commits would be
# nowhere in the restored clone. Reconcile here, where both are visible:
# fast-forward when origin is strictly ahead, never discard anything else.
# Full refnames, never %(refname:short): the short form of
# refs/remotes/origin/HEAD is "origin/HEAD" on git 2.39 but plain "origin" on
# newer git. The first version of this loop compared against 'origin/HEAD' only,
# so on the owner's machine it fell through and created a branch literally
# named "origin". Full refnames are not rewritten by git.
$RemoteRefs = (& git --git-dir=$Mirror for-each-ref --format='%(refname)' refs/remotes/origin)
foreach ($rr in $RemoteRefs) {
    if (-not $rr) { continue }
    $Name = $rr -replace '^refs/remotes/origin/', ''
    if (-not $Name -or $Name -eq 'HEAD' -or $Name -eq 'origin') { continue }
    $LocalSha = (& git --git-dir=$Mirror rev-parse --verify --quiet "refs/heads/$Name" 2>$null)
    $RemoteSha = (& git --git-dir=$Mirror rev-parse --verify --quiet $rr)
    if (-not $RemoteSha) { continue }
    if (-not $LocalSha) {
        & git --git-dir=$Mirror update-ref "refs/heads/$Name" $RemoteSha
        Write-Output "  branch '$Name' existed only as a remote ref - added to the mirror."
        continue
    }
    if ($LocalSha -eq $RemoteSha) { continue }
    & git --git-dir=$Mirror merge-base --is-ancestor $LocalSha $RemoteSha
    $LocalBehind = ($LASTEXITCODE -eq 0)
    & git --git-dir=$Mirror merge-base --is-ancestor $RemoteSha $LocalSha
    $RemoteBehind = ($LASTEXITCODE -eq 0)
    if ($LocalBehind -and -not $RemoteBehind) {
        & git --git-dir=$Mirror update-ref "refs/heads/$Name" $RemoteSha
        Write-Output "  branch '$Name' lagged origin - fast-forwarded in the mirror."
    } elseif ($RemoteBehind -and -not $LocalBehind) {
        # Local is ahead of the remote-tracking ref. Normal after a commit that
        # has not been fetched back; the local sha is the newer one, keep it.
        Write-Output "  branch '$Name' is ahead of the recorded origin/$Name - keeping the local sha."
    } else {
        Write-Output "  WARNING: local '$Name' ($($LocalSha.Substring(0,7))) and origin/$Name"
        Write-Output "           ($($RemoteSha.Substring(0,7))) have really diverged. The mirror keeps"
        Write-Output '           the LOCAL one; reconcile with origin before you rely on this backup.'
    }
}

# ---------------------------------------------------------------- 2. bundle

Write-Step '2. git bundle (single file) plus a test clone of it'
$Bundle = Join-Path $RepoDir 'apumir-all.bundle'
# --branches --tags --remotes, because plain --all skips remote-tracking refs and
# a branch that exists only as origin/<name> would be missing from the bundle.
& git bundle create $Bundle --branches --tags --remotes 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail 'git bundle create failed.' }
& git bundle verify $Bundle 2>&1 | Select-Object -Last 1 | ForEach-Object { Write-Output "verify:       $_" }

# "git bundle verify" says "complete history" even for a broken bundle, so the
# bundle is cloned back and inspected.
#
# The probe deliberately does NOT check out a working tree. Cloning a bundle
# leaves its refs under refs\remotes\origin\* with no HEAD, so "rev-parse HEAD"
# fails and every file test comes back false even for a perfectly good bundle -
# which is how the first version of this check printed "keystore in the clone:
# False" and killed a valid run. Verification is done on the object database
# instead; restore-from-usb.ps1 performs the real checkout.
$Probe = Join-Path $env:TEMP "apumir-bundle-probe-$Stamp"
& git clone --quiet --no-checkout $Bundle $Probe 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    if (Test-Path $Probe) { Remove-Item -Recurse -Force $Probe }
    Fail 'the bundle could not be cloned back. Do not trust this backup.'
}

# Depending on how git mapped the bundle, the branch lands under
# refs\remotes\origin\ or refs\heads\ - accept either.
$ProbeSha = ''
$ProbeRef = ''
foreach ($cand in @("refs/remotes/origin/$Branch", "refs/heads/$Branch")) {
    $found = (& git -C $Probe rev-parse --verify --quiet $cand 2>$null)
    if ($found) { $ProbeSha = "$found".Trim(); $ProbeRef = $cand; break }
}
$ProbeTags = (& git -C $Probe tag | Measure-Object -Line).Lines
$ProbeCommits = ''
if ($ProbeSha) { $ProbeCommits = ((& git -C $Probe rev-list --count $ProbeSha) | Out-String).Trim() }
& git -C $Probe cat-file -e "${Head}:android-app/app/p2p-release.jks" 2>$null
$ProbeKeystore = ($LASTEXITCODE -eq 0)

Write-Output "test clone:   $ProbeRef = $($ProbeSha.Substring(0, [math]::Min(12, $ProbeSha.Length))), $ProbeCommits commits, $ProbeTags tags"
Write-Output "keystore in the bundle: $ProbeKeystore"
Remove-Item -Recurse -Force $Probe
if (-not $ProbeSha) { Fail "the bundle has no ref for branch '$Branch'." }
if ($ProbeSha -ne $Head) { Fail "the bundle's $Branch ($ProbeSha) differs from the repository HEAD ($Head)." }
if ($ProbeTags -lt $TagCount) { Fail "the bundle holds $ProbeTags tags but the repository has $TagCount." }
if (-not $ProbeKeystore) { Fail 'the release keystore did not come back out of the bundle.' }

# ---------------------------------------------------------------- 3. dirty tree

Write-Step '3. uncommitted work'
$Dirty = (& git status --porcelain)
$DirtyFile = Join-Path $Root 'repo-state.txt'
$DirtyText = @()
$DirtyText += "captured:     $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
$DirtyText += "repository:   $RepoPath"
$DirtyText += "HEAD:         $Head"
$DirtyText += "branch:       $Branch"
$DirtyText += "commits:      $CommitCount"
$DirtyText += "tags:         $TagCount"
$DirtyText += "shallow:      $IsShallow"
$DirtyText += "git:          $((& git --version) -join '')"

if ($Dirty) {
    Write-Output 'the working tree is NOT clean - saving a patch and the untracked files.'
    $Patch = Join-Path $Root 'uncommitted-changes.patch'
    # Not "> $Patch": PowerShell 5.1 redirects as UTF-16, which git apply
    # refuses. WriteAllLines writes UTF-8 with no BOM, which git accepts.
    $DiffText = & git diff HEAD
    if ($DiffText) {
        [System.IO.File]::WriteAllLines($Patch, [string[]]$DiffText)
        Write-Output "patch written: uncommitted-changes.patch ($(($DiffText | Measure-Object).Count) lines)"
    } else {
        [System.IO.File]::WriteAllLines($Patch, [string[]]@('(no tracked changes - untracked files only)'))
        Write-Output 'no tracked changes, only untracked files.'
    }
    $UntrackedDir = Join-Path $Root 'untracked'
    New-Item -ItemType Directory -Path $UntrackedDir -Force | Out-Null
    $Untracked = (& git ls-files --others --exclude-standard)
    foreach ($f in $Untracked) {
        if (-not $f) { continue }
        $Src = Join-Path $RepoPath $f
        $Dst = Join-Path $UntrackedDir $f
        $DstParent = Split-Path $Dst -Parent
        if (-not (Test-Path $DstParent)) { New-Item -ItemType Directory -Path $DstParent -Force | Out-Null }
        Copy-Item $Src $Dst -Force
    }
    $UntrackedCount = ($Untracked | Where-Object { $_ }).Count
    $DirtyText += "state:        DIRTY - see uncommitted-changes.patch and untracked\"
    $DirtyText += "untracked:    $UntrackedCount files"
    Write-Output "untracked files copied: $UntrackedCount"
} else {
    $DirtyText += 'state:        clean'
    Write-Output 'the working tree is clean.'
}

# ---------------------------------------------------------------- 4. machine-local

Write-Step '4. machine-local files that git ignores'
$LocalProps = Join-Path $RepoPath 'android-app\local.properties'
if (Test-Path $LocalProps) {
    Copy-Item $LocalProps (Join-Path $LocalDir 'local.properties') -Force
    $SdkLine = (Get-Content $LocalProps | Where-Object { $_ -match '^sdk\.dir' } | Select-Object -First 1)
    Write-Output "local.properties copied (for reference). $SdkLine"
    Write-Output 'NOTE: sdk.dir is a path on THIS PC. On the new PC rewrite it, do not trust it.'
} else {
    Write-Output 'android-app\local.properties not found - the new PC will create it.'
}

$EnvFiles = @()
$EnvFiles += (Get-ChildItem -Path $RepoPath -Filter '.env' -File -ErrorAction SilentlyContinue)
$EnvFiles += (Get-ChildItem -Path (Join-Path $RepoPath 'cloudflare-worker') -Filter '.env' -File -ErrorAction SilentlyContinue)
$EnvFiles += (Get-ChildItem -Path (Join-Path $RepoPath 'cloudflare-worker') -Filter '.dev.vars' -File -ErrorAction SilentlyContinue)
foreach ($e in $EnvFiles) {
    if ($e -and (Test-Path $e.FullName)) {
        Copy-Item $e.FullName (Join-Path $LocalDir $e.Name) -Force
        Write-Output "copied secret file: $($e.Name)"
    }
}
if (-not ($EnvFiles | Where-Object { $_ })) { Write-Output 'no .env / .dev.vars files found.' }
Write-Output 'WARNING: the drive now holds the release keystore and any .env files.'
Write-Output '         Keep it physically safe; do not leave it in a shared place.'

# ---------------------------------------------------------------- 5. APKs

Write-Step '5. built APKs (gitignored build outputs)'
$ApkOut = Join-Path $RepoPath 'android-app\app\build\outputs\apk'
$Apks = @()
if (Test-Path $ApkOut) { $Apks = Get-ChildItem -Path $ApkOut -Recurse -Filter '*.apk' -File -ErrorAction SilentlyContinue }
if ($Apks) {
    foreach ($a in $Apks) {
        Copy-Item $a.FullName (Join-Path $ApkDir $a.Name) -Force
        Write-Output ("{0}  {1} bytes  built {2}" -f $a.Name, $a.Length, $a.LastWriteTime)
    }
} else {
    Write-Output 'no APK on disk. That is fine - the new PC can build one, or download the'
    Write-Output 'release from GitHub releases once it is online.'
}
Write-Output 'An APK size is not proof of freshness; the built: timestamp above is.'

# ---------------------------------------------------------------- 6. toolchain

if ($IncludeToolchainCaches) {
    Write-Step '6. toolchain caches (optional, large)'
    $ToolDir = Join-Path $Root 'toolchain'
    New-Item -ItemType Directory -Path $ToolDir -Force | Out-Null

    $GradleDists = Join-Path $env:USERPROFILE '.gradle\wrapper\dists'
    if (Test-Path $GradleDists) {
        $Size = (Get-ChildItem $GradleDists -Recurse -File -ErrorAction SilentlyContinue |
            Measure-Object -Property Length -Sum).Sum
        Write-Output ("gradle wrapper dists: {0} MB - copying" -f [math]::Round($Size / 1MB, 1))
        Copy-Item $GradleDists (Join-Path $ToolDir 'gradle-wrapper-dists') -Recurse -Force
    } else {
        Write-Output "no $GradleDists - the new PC will download Gradle 9.5.0 on first build."
    }

    $SdkDir = $null
    if (Test-Path $LocalProps) {
        $SdkDir = ((Get-Content $LocalProps | Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1) -replace '^sdk\.dir=', '')
        if ($SdkDir) { $SdkDir = $SdkDir.Replace('\\', '\').Replace('\:', ':') }
    }
    $PlatformTools = if ($SdkDir) { Join-Path $SdkDir 'platform-tools' } else { $null }
    if ($PlatformTools -and (Test-Path $PlatformTools)) {
        $PtSize = (Get-ChildItem $PlatformTools -Recurse -File -ErrorAction SilentlyContinue |
            Measure-Object -Property Length -Sum).Sum
        Write-Output ("platform-tools: {0} MB - copying" -f [math]::Round($PtSize / 1MB, 1))
        Copy-Item $PlatformTools (Join-Path $ToolDir 'platform-tools') -Recurse -Force
    } else {
        Write-Output 'platform-tools not found - install it with the Android SDK on the new PC.'
    }
} else {
    Write-Step '6. toolchain caches skipped'
    Write-Output 'Pass -IncludeToolchainCaches to also copy the Gradle distribution and'
    Write-Output 'adb, so the new PC needs no download to build and flash a phone.'
}

# ---------------------------------------------------------------- 7. manifest

Write-Step '7. manifest'
$DirtyText | Out-File -FilePath $DirtyFile -Encoding utf8
$RestoreDoc = Join-Path $RepoPath 'docs\RESTORE_ON_NEW_PC.md'
if (Test-Path $RestoreDoc) {
    Copy-Item $RestoreDoc (Join-Path $Root 'RESTORE-ON-NEW-PC.md') -Force
    Write-Output 'RESTORE-ON-NEW-PC.md copied to the backup root.'
} else {
    Write-Output 'WARNING: docs\RESTORE_ON_NEW_PC.md is missing from this repository.'
}

# The restore script has to be INSIDE the backup: on the new PC there is no
# repository yet, so a script that only lives in scripts\ would be unreachable.
$RestoreScript = Join-Path $RepoPath 'scripts\restore-from-usb.ps1'
if (Test-Path $RestoreScript) {
    Copy-Item $RestoreScript (Join-Path $Root 'restore-from-usb.ps1') -Force
    Write-Output 'restore-from-usb.ps1 copied to the backup root.'
} else {
    Fail 'scripts\restore-from-usb.ps1 is missing, so the backup could not be restored on a clean PC.'
}

$Manifest = Join-Path $Root 'MANIFEST.txt'
$Lines = @()
$Lines += "APUMIR flash-drive backup"
$Lines += "created:  $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
$Lines += "source:   $RepoPath"
$Lines += "HEAD:     $Head"
$Lines += "branch:   $Branch"
$Lines += "tags:     $TagCount"
$Lines += ''
foreach ($f in (Get-ChildItem -Path $Root -Recurse -File | Sort-Object FullName)) {
    if ($f.FullName -eq $Manifest) { continue }
    $Hash = (Get-FileHash -Path $f.FullName -Algorithm SHA256).Hash
    $Rel = $f.FullName.Substring($Root.Length + 1)
    $Lines += ("{0}  {1,12}  {2}" -f $Hash, $f.Length, $Rel)
}
$Lines | Out-File -FilePath $Manifest -Encoding utf8
$FileCount = (Get-ChildItem -Path $Root -Recurse -File | Measure-Object).Count
$TotalMB = [math]::Round(((Get-ChildItem -Path $Root -Recurse -File |
    Measure-Object -Property Length -Sum).Sum) / 1MB, 1)
Write-Output "MANIFEST.txt written with sha256 for $FileCount files."

# ---------------------------------------------------------------- 8. verify

# With ErrorActionPreference set to 'Continue' a failed copy no longer aborts
# the script, so the run ends here instead: everything that has to be on the
# drive is checked to actually be there.
Write-Step '8. verifying what was written to the drive'
$Required = @(
    @{ Name = 'mirror'; Path = (Join-Path $RepoDir 'apumir-mirror.git') },
    @{ Name = 'bundle'; Path = $Bundle },
    @{ Name = 'repo state'; Path = $DirtyFile },
    @{ Name = 'manifest'; Path = $Manifest },
    @{ Name = 'restore script'; Path = (Join-Path $Root 'restore-from-usb.ps1') },
    @{ Name = 'restore guide'; Path = (Join-Path $Root 'RESTORE-ON-NEW-PC.md') }
)
$MissingRequired = 0
foreach ($r in $Required) {
    if (Test-Path $r.Path) {
        Write-Output ("  ok       {0,-16} {1}" -f $r.Name, $r.Path.Substring($Root.Length + 1))
    } else {
        Write-Output ("  MISSING  {0,-16} {1}" -f $r.Name, $r.Path)
        $MissingRequired++
    }
}
$MirrorKeystore = & git --git-dir=$Mirror cat-file -e "${Head}:android-app/app/p2p-release.jks" 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Output '  MISSING  release keystore is not in the mirrored history'
    $MissingRequired++
} else {
    Write-Output '  ok       release keystore is in the mirrored history'
}
if ($MissingRequired -gt 0) {
    Fail "$MissingRequired required items did not make it onto the drive."
}

# ---------------------------------------------------------------- summary

Write-Step 'done'
Write-Output "backup:       $Root"
Write-Output "size:         $TotalMB MB in $FileCount files"
Write-Output "history:      $CommitCount commits, $TagCount tags, HEAD $Head"
Write-Output "keystore:     verified inside the bundle and the mirror"
Write-Output ''
Write-Output 'On the new PC, with this drive mounted as (say) E: :'
Write-Output "  powershell -NoProfile -ExecutionPolicy Bypass -File $Root\restore-from-usb.ps1 -BackupDir $Root"
Write-Output 'The restore script and RESTORE-ON-NEW-PC.md are both at the root of the backup.'
Write-Output ''
Write-Output 'The drive holds the release keystore. Losing the drive is survivable (the'
Write-Output 'keystore is in git on GitHub); letting it fall into the wrong hands is not.'
exit 0
