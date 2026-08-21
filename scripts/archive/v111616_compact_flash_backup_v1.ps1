Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepoRoot = 'C:\APUMIR-arena-test'
$FlashRoot = 'F:\'
$BackupRoot = 'F:\APU_PORTABLE'
$StatePath = Join-Path $env:TEMP 'apu-v11.16.16-compact-flash-backup-v1.json'
$NativeCaptureDir = Join-Path $env:TEMP 'apu-v11.16.16-compact-native-v1'
$VerifyClonePath = Join-Path $env:TEMP 'apu-v11.16.16-compact-restore-verify-v1'
$FormatStatePath = Join-Path $env:TEMP 'apu-v11.16.16-flash-format-v1.json'
$ExpectedFormatStateSha256 = 'A75443F8D302B8D856237F63C2122ABA4C676A6456078066125EF1455E1FACFF'

$ExpectedDiskNumber = 2
$ExpectedFriendlyName = 'General UDisk'
$ExpectedBusType = 'USB'
$ExpectedFileSystem = 'exFAT'
$ExpectedLabel = 'APU_BACKUP'
$ExpectedWindowsBranch = 'arena/01a000bc-apumir'
$ExpectedWindowsHead = '8cea566e50f439810e29fb1dc4ac14dc69b5fbc6'
$RemoteRunbookCommit = '1fdb73a512db97ccecca8990a79410b095290acd'

$LatestVersion = 'v11.16.16'
$LatestApkSource = Join-Path $env:TEMP 'apu-m3d-v11.16.16.apk'
$LatestApkBytes = [int64]22664712
$LatestApkSha256 = '446A1EE9254B7F57E037398E81209DB9E60C915CE2E3ADBCFA43A3FC8429DC0D'
$LatestBuildStatePath = Join-Path $env:TEMP 'apu-m3d-v11.16.16-apk-build.json'
$LatestBuildStateSha256 = '917077E82C25DFF9A020713BA4A391DD49D7AFD81446367F87A23B17216AFABC'

$PreviousVersion = 'v11.16.15'
$PreviousApkSource = Join-Path $env:TEMP 'apu-r4.4-dual-v11.16.15.apk'
$PreviousApkBytes = [int64]22664716
$PreviousApkSha256 = 'B675770D043E4ABA5D6D099275F489DB9666A9B16792DD45000A9EC2D243E9B2'
$PreviousBuildStatePath = Join-Path $env:TEMP 'apu-r4.4-dual-v11.16.15-apk-build.json'
$PreviousBuildStateSha256 = '3D893F5A546008C3166C68026203EF2B395144EA9C072D6E9583E9FE84644078'

$SignerCertificateSha256 = 'F843CBE70332BAB67A9671EBDE32FEE541E84CD904D3A508E5626346A1A4A5F7'
$NativeSource = Join-Path $RepoRoot 'android-app\app\src\main\jniLibs\arm64-v8a\libp2p_core.so'
$NativeBytes = [int64]7263416
$NativeSha256 = '27B9D4DC87CA7046D9F862F9ED153FDDD48C26E4053B620FE46986D25D1FD26C'
$KeystoreSource = Join-Path $RepoRoot 'android-app\app\p2p-release.jks'
$Utf8NoBom = New-Object Text.UTF8Encoding($false)

$script:CopyStarted = $false
$script:CopyComplete = $false
$script:SourceFileCount = 0
$script:SourceBytes = [int64]0
$script:FinalFileCount = 0
$script:FinalBytes = [int64]0
$script:WindowsHead = $null
$script:WindowsBranch = $null

function Write-Utf8NoBom {
    param([string]$Path, [AllowEmptyString()][string]$Text)
    [IO.File]::WriteAllText($Path, $Text, $Utf8NoBom)
}

function Invoke-NativeCaptured {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$ArgumentList,
        [Parameter(Mandatory = $true)][string]$Label,
        [int]$TimeoutMilliseconds = 300000
    )
    New-Item -ItemType Directory -Path $NativeCaptureDir -Force | Out-Null
    $StdoutPath = Join-Path $NativeCaptureDir ("{0}.stdout.log" -f $Label)
    $StderrPath = Join-Path $NativeCaptureDir ("{0}.stderr.log" -f $Label)
    $NativeProcess = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList `
        -WorkingDirectory $RepoRoot -RedirectStandardOutput $StdoutPath `
        -RedirectStandardError $StderrPath -PassThru
    $Exited = $NativeProcess.WaitForExit($TimeoutMilliseconds)
    if (-not $Exited) {
        try { $NativeProcess.Kill() } catch {}
        throw "Native command timed out: $Label"
    }
    $NativeProcess.WaitForExit()
    $NativeProcess.Refresh()
    if ($null -eq $NativeProcess.ExitCode) { throw "Native exit code unavailable: $Label" }
    $Stdout = if (Test-Path -LiteralPath $StdoutPath) { [IO.File]::ReadAllText($StdoutPath) } else { '' }
    $Stderr = if (Test-Path -LiteralPath $StderrPath) { [IO.File]::ReadAllText($StderrPath) } else { '' }
    return [pscustomobject]@{
        ExitCode = [int]$NativeProcess.ExitCode
        Stdout = $Stdout
        Stderr = $Stderr
        Combined = (($Stdout.TrimEnd(), $Stderr.TrimEnd()) | Where-Object { $_ }) -join "`n"
        StdoutPath = $StdoutPath
        StderrPath = $StderrPath
    }
}

function Assert-FileIdentity {
    param([string]$Path, [int64]$Bytes, [string]$Sha256, [string]$Label)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$Label missing: $Path" }
    $Item = Get-Item -LiteralPath $Path
    $Hash = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
    if ([int64]$Item.Length -ne $Bytes -or $Hash -ne $Sha256) {
        throw "$Label identity mismatch: bytes=$($Item.Length), sha256=$Hash"
    }
}

function Test-PortableRelativePath {
    param([string]$RelativePath)
    $Normalized = $RelativePath.Replace('\', '/')
    if ([IO.Path]::IsPathRooted($Normalized) -or $Normalized -match '(^|/)\.\.(/|$)') { return $false }
    $Segments = @($Normalized.Split('/'))
    $ForbiddenSegments = @('.git', '.gradle', 'build', 'target', '.idea', '.kotlin', '.cxx',
        '.externalNativeBuild', 'node_modules', 'coverage', 'dist', 'out', '__pycache__', 'logs')
    foreach ($Segment in $Segments) {
        if ($ForbiddenSegments -contains $Segment) { return $false }
    }
    $Leaf = $Segments[-1]
    if ($Leaf -match '^(?i:hs_err_pid.*\.log|temp_core\.txt|local\.properties|\.env|Thumbs\.db|\.DS_Store)$') { return $false }
    if ($Leaf -match '(?i:\.log$|\.pyc$|\.iml$|\.jks$|\.keystore$|\.tmp$)') { return $false }
    return $true
}

function Save-State {
    param([string]$Outcome, [AllowNull()][string]$Failure)
    $State = [ordered]@{
        schema = 1
        generatedAtUtc = [DateTime]::UtcNow.ToString('o')
        outcome = $Outcome
        failure = $Failure
        purpose = 'Compact portable APU flash backup; never format flash'
        flashRoot = $FlashRoot
        backupRoot = $BackupRoot
        diskNumber = $ExpectedDiskNumber
        flashLabel = $ExpectedLabel
        flashFileSystem = $ExpectedFileSystem
        formatRepeated = $false
        windowsBranch = $script:WindowsBranch
        windowsHead = $script:WindowsHead
        remoteRunbookCommit = $RemoteRunbookCommit
        sourceFileCount = $script:SourceFileCount
        sourceBytes = $script:SourceBytes
        finalFileCount = $script:FinalFileCount
        finalBytes = $script:FinalBytes
        previousVersion = $PreviousVersion
        previousApkSha256 = $PreviousApkSha256
        latestVersion = $LatestVersion
        latestApkSha256 = $LatestApkSha256
        signerCertificateSha256 = $SignerCertificateSha256
        copyStarted = $script:CopyStarted
        copyComplete = $script:CopyComplete
        excludedBuildCaches = $true
        copiedTempEvidenceWildcard = $false
        phonesChanged = $false
        networkUsed = $false
        releasePublished = $false
    }
    [IO.File]::WriteAllText($StatePath, ($State | ConvertTo-Json -Depth 6), $Utf8NoBom)
}

$IncompletePath = Join-Path $BackupRoot 'INCOMPLETE.json'
$CompletePath = Join-Path $BackupRoot 'BACKUP_COMPLETE.json'

try {
    Set-Location -LiteralPath $RepoRoot
    if (Test-Path -LiteralPath $StatePath) { throw "Compact backup v1 already has state; do not repeat: $StatePath" }
    if (Test-Path -LiteralPath $BackupRoot) { throw "Backup root already exists; inspect, do not overwrite: $BackupRoot" }
    if (Test-Path -LiteralPath $VerifyClonePath) { throw "Restore verify path already exists: $VerifyClonePath" }

    if (-not (Test-Path -LiteralPath $FormatStatePath -PathType Leaf)) { throw 'Accepted format state missing' }
    $FormatStateHash = (Get-FileHash -LiteralPath $FormatStatePath -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($FormatStateHash -ne $ExpectedFormatStateSha256) { throw "Format state hash mismatch: $FormatStateHash" }
    $FormatState = Get-Content -LiteralPath $FormatStatePath -Raw | ConvertFrom-Json
    $FormatStateValid = $FormatState.outcome -eq 'PASS_FORMATTED_EMPTY'
    $FormatStateValid = $FormatStateValid -and $FormatState.formatCalled -eq $true
    $FormatStateValid = $FormatStateValid -and $FormatState.postVerified -eq $true
    if (-not $FormatStateValid) { throw 'Format state content mismatch' }

    $Volume = Get-Volume -DriveLetter F -ErrorAction Stop
    $Partition = Get-Partition -DriveLetter F -ErrorAction Stop
    $Disk = $Partition | Get-Disk -ErrorAction Stop
    $FlashIdentityValid = $Disk.Number -eq $ExpectedDiskNumber
    $FlashIdentityValid = $FlashIdentityValid -and $Disk.FriendlyName.Trim() -eq $ExpectedFriendlyName
    $FlashIdentityValid = $FlashIdentityValid -and $Disk.BusType.ToString() -eq $ExpectedBusType
    $FlashIdentityValid = $FlashIdentityValid -and -not $Disk.IsSystem
    $FlashIdentityValid = $FlashIdentityValid -and -not $Disk.IsBoot
    $FlashIdentityValid = $FlashIdentityValid -and $Volume.FileSystem -eq $ExpectedFileSystem
    $FlashIdentityValid = $FlashIdentityValid -and $Volume.FileSystemLabel -eq $ExpectedLabel
    if (-not $FlashIdentityValid) { throw 'F: identity changed; compact copy blocked; never format automatically' }
    $UnexpectedRootEntries = @(Get-ChildItem -LiteralPath $FlashRoot -Force -ErrorAction Stop | Where-Object { $_.Name -ne 'System Volume Information' })
    if ($UnexpectedRootEntries.Count -ne 0) { throw "F: is not empty before first compact backup: $($UnexpectedRootEntries.Name -join ', ')" }

    Assert-FileIdentity $LatestApkSource $LatestApkBytes $LatestApkSha256 'Latest APK'
    Assert-FileIdentity $PreviousApkSource $PreviousApkBytes $PreviousApkSha256 'Previous APK'
    Assert-FileIdentity $LatestBuildStatePath (Get-Item $LatestBuildStatePath).Length $LatestBuildStateSha256 'Latest build state'
    Assert-FileIdentity $PreviousBuildStatePath (Get-Item $PreviousBuildStatePath).Length $PreviousBuildStateSha256 'Previous build state'
    Assert-FileIdentity $NativeSource $NativeBytes $NativeSha256 'Latest native library'
    if (-not (Test-Path -LiteralPath $KeystoreSource -PathType Leaf)) { throw "Signing keystore missing: $KeystoreSource" }

    foreach ($BuildStateSpec in @(
        [pscustomobject]@{ Path=$LatestBuildStatePath; Apk=$LatestApkSha256; Version=$LatestVersion },
        [pscustomobject]@{ Path=$PreviousBuildStatePath; Apk=$PreviousApkSha256; Version=$PreviousVersion }
    )) {
        $BuildState = Get-Content -LiteralPath $BuildStateSpec.Path -Raw | ConvertFrom-Json
        $BuildStateValid = $BuildState.outcome -eq 'PASS'
        $BuildStateValid = $BuildStateValid -and $BuildState.versionName -eq $BuildStateSpec.Version
        $BuildStateValid = $BuildStateValid -and $BuildState.apkSha256 -eq $BuildStateSpec.Apk
        $BuildStateValid = $BuildStateValid -and $BuildState.signerV2 -eq $true
        $BuildStateValid = $BuildStateValid -and $BuildState.signerCertificateSha256 -eq $SignerCertificateSha256
        if (-not $BuildStateValid) { throw "Signed build state mismatch: $($BuildStateSpec.Version)" }
    }

    $BranchResult = Invoke-NativeCaptured 'git.exe' @('branch','--show-current') 'git-branch'
    $HeadResult = Invoke-NativeCaptured 'git.exe' @('rev-parse','HEAD') 'git-head'
    if ($BranchResult.ExitCode -ne 0 -or $HeadResult.ExitCode -ne 0) { throw 'Unable to read Git identity' }
    $script:WindowsBranch = $BranchResult.Stdout.Trim()
    $script:WindowsHead = $HeadResult.Stdout.Trim()
    if ($script:WindowsBranch -ne $ExpectedWindowsBranch -or $script:WindowsHead -ne $ExpectedWindowsHead) {
        throw "Unexpected Windows Git identity: $($script:WindowsBranch) / $($script:WindowsHead)"
    }

    $ListResult = Invoke-NativeCaptured 'git.exe' @('ls-files','--cached','--others','--exclude-standard') 'git-portable-files'
    if ($ListResult.ExitCode -ne 0) { throw "git ls-files failed: $($ListResult.Combined)" }
    $AllListed = @($ListResult.Stdout -split '\r?\n' | Where-Object { $_ })
    $PortableRelativeFiles = @($AllListed | Where-Object { Test-PortableRelativePath $_ } | Sort-Object -Unique)
    $UntrackedResult = Invoke-NativeCaptured 'git.exe' @('ls-files','--others','--exclude-standard') 'git-untracked-files'
    if ($UntrackedResult.ExitCode -ne 0) { throw 'Unable to list essential untracked files' }
    $PortableUntracked = @($UntrackedResult.Stdout -split '\r?\n' | Where-Object { $_ -and (Test-PortableRelativePath $_) } | Sort-Object -Unique)

    $RequiredPortableFiles = @('build-rust.ps1','android-app/gradlew.bat','android-app/app/build.gradle.kts',
        'rust-core/src/engine/core.rs','rust-core/src/network/relay_queue.rs',
        'android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so')
    foreach ($Required in $RequiredPortableFiles) {
        if ($PortableRelativeFiles -notcontains $Required) { throw "Required portable source missing from allowlist: $Required" }
    }

    $PlannedFiles = @()
    [int64]$PlannedSourceBytes = 0
    foreach ($Relative in $PortableRelativeFiles) {
        $Source = Join-Path $RepoRoot ($Relative.Replace('/','\'))
        if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) { throw "Listed source missing: $Relative" }
        $Length = [int64](Get-Item -LiteralPath $Source).Length
        $PlannedSourceBytes += $Length
        $PlannedFiles += [pscustomobject]@{ Relative=$Relative; Source=$Source; Bytes=$Length }
    }
    $script:SourceFileCount = $PlannedFiles.Count
    $script:SourceBytes = $PlannedSourceBytes
    if ($script:SourceFileCount -lt 100 -or $script:SourceFileCount -gt 2000) { throw "Unexpected portable source file count: $($script:SourceFileCount)" }
    if ($script:SourceBytes -gt 512MB) { throw "Portable source unexpectedly large: $($script:SourceBytes) bytes" }

    [int64]$KeystoreBytes = [int64](Get-Item $KeystoreSource).Length
    [int64]$PlannedMinimumBytes = $script:SourceBytes + $LatestApkBytes + $PreviousApkBytes + $NativeBytes + $KeystoreBytes + 256MB
    if ([int64]$Volume.SizeRemaining -lt $PlannedMinimumBytes) { throw 'Not enough free space for compact backup' }
    $PlanText = "COMPACT PLAN PASS: sourceFiles={0}, sourceMiB={1}, minimumMiB={2}" -f $script:SourceFileCount, [math]::Round($script:SourceBytes/1MB,2), [math]::Round($PlannedMinimumBytes/1MB,2)
    Write-Host $PlanText
    $PlannedFiles | Sort-Object Bytes -Descending | Select-Object -First 10 Relative,Bytes | Format-Table -AutoSize

    $VersionsRoot = Join-Path $BackupRoot 'versions'
    $PreviousDir = Join-Path $VersionsRoot 'previous-v11.16.15'
    $LatestDir = Join-Path $VersionsRoot 'latest-v11.16.16'
    $SourceRoot = Join-Path $BackupRoot 'source'
    $PortableSourceRoot = Join-Path $SourceRoot 'APUMIR-portable-source'
    $NativeRoot = Join-Path $BackupRoot 'native'
    $SigningRoot = Join-Path $BackupRoot 'signing-private'
    $RestoreRoot = Join-Path $BackupRoot 'restore'
    $ProvenanceRoot = Join-Path $BackupRoot 'provenance'
    foreach ($Directory in @($BackupRoot,$VersionsRoot,$PreviousDir,$LatestDir,$SourceRoot,$PortableSourceRoot,
        $NativeRoot,$SigningRoot,$RestoreRoot,$ProvenanceRoot)) {
        New-Item -ItemType Directory -Path $Directory -Force | Out-Null
    }
    $script:CopyStarted = $true
    Write-Utf8NoBom $IncompletePath (([ordered]@{ outcome='INCOMPLETE'; startedUtc=[DateTime]::UtcNow.ToString('o'); neverFormat=$true }) | ConvertTo-Json)

    foreach ($Planned in $PlannedFiles) {
        $Destination = Join-Path $PortableSourceRoot ($Planned.Relative.Replace('/','\'))
        New-Item -ItemType Directory -Path (Split-Path -Parent $Destination) -Force | Out-Null
        Copy-Item -LiteralPath $Planned.Source -Destination $Destination -Force
    }

    $PreviousApkDestination = Join-Path $PreviousDir 'APU-v11.16.15.apk'
    $LatestApkDestination = Join-Path $LatestDir 'APU-v11.16.16.apk'
    Copy-Item $PreviousApkSource $PreviousApkDestination -Force
    Copy-Item $LatestApkSource $LatestApkDestination -Force
    Assert-FileIdentity $PreviousApkDestination $PreviousApkBytes $PreviousApkSha256 'Copied previous APK'
    Assert-FileIdentity $LatestApkDestination $LatestApkBytes $LatestApkSha256 'Copied latest APK'
    Write-Utf8NoBom (Join-Path $PreviousDir 'APU-v11.16.15.apk.sha256') ("$PreviousApkSha256 *APU-v11.16.15.apk`n")
    Write-Utf8NoBom (Join-Path $LatestDir 'APU-v11.16.16.apk.sha256') ("$LatestApkSha256 *APU-v11.16.16.apk`n")

    $LatestJson = [ordered]@{
        schema=1; latest=$LatestVersion; previous=$PreviousVersion; signerCertificateSha256=$SignerCertificateSha256
        latestApk=[ordered]@{ path='latest-v11.16.16/APU-v11.16.16.apk'; bytes=$LatestApkBytes; sha256=$LatestApkSha256; signature='Android V2 verified' }
        previousApk=[ordered]@{ path='previous-v11.16.15/APU-v11.16.15.apk'; bytes=$PreviousApkBytes; sha256=$PreviousApkSha256; signature='Android V2 verified' }
        rotationRule='Never format. Verify incoming latest, rotate old latest to previous, then delete versions older than previous.'
    }
    Write-Utf8NoBom (Join-Path $VersionsRoot 'LATEST.json') ($LatestJson | ConvertTo-Json -Depth 6)
    Write-Utf8NoBom (Join-Path $VersionsRoot 'LATEST.txt') ("LATEST APU VERSION: v11.16.16`r`nPREVIOUS APU VERSION: v11.16.15`r`nNEVER FORMAT THIS FLASH DRIVE`r`n")

    $BundlePath = Join-Path $SourceRoot 'APUMIR-all-refs.bundle'
    $BundleCreate = Invoke-NativeCaptured 'git.exe' @('bundle','create',$BundlePath,'--all') 'git-bundle-create' 600000
    if ($BundleCreate.ExitCode -ne 0) { throw "Bundle create failed: $($BundleCreate.Combined)" }
    $BundleVerify = Invoke-NativeCaptured 'git.exe' @('bundle','verify',$BundlePath) 'git-bundle-verify' 600000
    if ($BundleVerify.ExitCode -ne 0) { throw "Bundle verify failed: $($BundleVerify.Combined)" }
    Write-Utf8NoBom (Join-Path $SourceRoot 'BUNDLE_VERIFY.txt') $BundleVerify.Combined

    $WorkingDiff = Invoke-NativeCaptured 'git.exe' @('diff','--binary') 'git-diff-working'
    $IndexDiff = Invoke-NativeCaptured 'git.exe' @('diff','--cached','--binary') 'git-diff-index'
    $StatusResult = Invoke-NativeCaptured 'git.exe' @('status','--porcelain=v1','--branch') 'git-status'
    if ($WorkingDiff.ExitCode -ne 0 -or $IndexDiff.ExitCode -ne 0 -or $StatusResult.ExitCode -ne 0) { throw 'Git provenance capture failed' }
    Copy-Item $WorkingDiff.StdoutPath (Join-Path $SourceRoot 'WORKTREE.patch') -Force
    Copy-Item $IndexDiff.StdoutPath (Join-Path $SourceRoot 'INDEX.patch') -Force
    Write-Utf8NoBom (Join-Path $SourceRoot 'WORKTREE_STATUS.txt') $StatusResult.Stdout
    Write-Utf8NoBom (Join-Path $SourceRoot 'UNTRACKED_ESSENTIAL.txt') (($PortableUntracked -join "`r`n") + "`r`n")

    $SourceManifestEntries = @($PlannedFiles | ForEach-Object {
        $Copied = Join-Path $PortableSourceRoot ($_.Relative.Replace('/','\'))
        [ordered]@{ path=$_.Relative; bytes=[int64](Get-Item $Copied).Length; sha256=(Get-FileHash $Copied -Algorithm SHA256).Hash.ToUpperInvariant() }
    })
    Write-Utf8NoBom (Join-Path $SourceRoot 'SOURCE_MANIFEST.json') (([ordered]@{
        schema=1; windowsBranch=$script:WindowsBranch; windowsHead=$script:WindowsHead;
        remoteRunbookCommit=$RemoteRunbookCommit; fileCount=$SourceManifestEntries.Count;
        excluded='build,.gradle,target,.git,logs,temp evidence,caches'; files=$SourceManifestEntries
    }) | ConvertTo-Json -Depth 6)

    $NativeDestination = Join-Path $NativeRoot 'libp2p_core-arm64-v8a.so'
    Copy-Item $NativeSource $NativeDestination -Force
    Assert-FileIdentity $NativeDestination $NativeBytes $NativeSha256 'Copied native library'
    Write-Utf8NoBom (Join-Path $NativeRoot 'libp2p_core-arm64-v8a.so.sha256') ("$NativeSha256 *libp2p_core-arm64-v8a.so`n")

    $KeystoreDestination = Join-Path $SigningRoot 'p2p-release.jks'
    Copy-Item $KeystoreSource $KeystoreDestination -Force
    $KeystoreHash = (Get-FileHash $KeystoreDestination -Algorithm SHA256).Hash.ToUpperInvariant()
    Write-Utf8NoBom (Join-Path $SigningRoot 'PRIVATE_DO_NOT_SHARE.txt') ("PRIVATE APU SIGNING MATERIAL`r`nDo not upload this folder to GitHub or share it.`r`nKeystore SHA-256: $KeystoreHash`r`nAndroid signer certificate SHA-256: $SignerCertificateSha256`r`n")

    Copy-Item $LatestBuildStatePath (Join-Path $ProvenanceRoot 'latest-apk-build-state.json') -Force
    Copy-Item $PreviousBuildStatePath (Join-Path $ProvenanceRoot 'previous-apk-build-state.json') -Force
    Copy-Item $FormatStatePath (Join-Path $ProvenanceRoot 'one-time-format-state-never-repeat.json') -Force

    $VerifyScript = @'
param([string]$BackupRoot = (Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference = 'Stop'
$ManifestPath = Join-Path $BackupRoot 'SHA256SUMS.json'
$Entries = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json
$Failures = 0
foreach ($Entry in $Entries) {
    $Path = Join-Path $BackupRoot $Entry.path
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { $Failures++; Write-Host "MISSING: $($Entry.path)"; continue }
    $Hash = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($Hash -ne $Entry.sha256 -or [int64](Get-Item $Path).Length -ne [int64]$Entry.bytes) { $Failures++; Write-Host "FAILED: $($Entry.path)" }
}
if ($Failures -ne 0) { throw "Backup verification failures: $Failures" }
Write-Host "ALL PORTABLE BACKUP HASHES PASSED: $($Entries.Count) files"
'@
    Write-Utf8NoBom (Join-Path $RestoreRoot 'VERIFY_BACKUP.ps1') $VerifyScript

    $RestoreScript = @'
param([string]$Destination = 'C:\APU-restored')
$ErrorActionPreference = 'Stop'
$BackupRoot = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'VERIFY_BACKUP.ps1') -BackupRoot $BackupRoot
if (Test-Path -LiteralPath $Destination) {
    if (@(Get-ChildItem -LiteralPath $Destination -Force).Count -ne 0) { throw "Destination is not empty: $Destination" }
} else { New-Item -ItemType Directory -Path $Destination | Out-Null }
$Bundle = Join-Path $BackupRoot 'source\APUMIR-all-refs.bundle'
$Clone = Start-Process -FilePath 'git.exe' -ArgumentList @('clone','--no-checkout',$Bundle,$Destination) -Wait -PassThru
if ($Clone.ExitCode -ne 0) { throw "Bundle clone failed: $($Clone.ExitCode)" }
$Portable = Join-Path $BackupRoot 'source\APUMIR-portable-source'
Get-ChildItem -LiteralPath $Portable -File -Recurse | ForEach-Object {
    $Relative = $_.FullName.Substring($Portable.Length + 1)
    $Target = Join-Path $Destination $Relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $Target) -Force | Out-Null
    Copy-Item -LiteralPath $_.FullName -Destination $Target -Force
}
$PrivateKey = Join-Path $BackupRoot 'signing-private\p2p-release.jks'
$KeyTarget = Join-Path $Destination 'android-app\app\p2p-release.jks'
New-Item -ItemType Directory -Path (Split-Path -Parent $KeyTarget) -Force | Out-Null
Copy-Item -LiteralPath $PrivateKey -Destination $KeyTarget -Force
Write-Host "APU source restored to $Destination"
Write-Host 'Do not checkout/reset: the portable tree contains required tested Windows overlays.'
Write-Host 'Open INSTALL_TOOLS_FROM_INTERNET.md and download build tools from the internet.'
'@
    Write-Utf8NoBom (Join-Path $RestoreRoot 'RESTORE_APU.ps1') $RestoreScript
    $Tokens = $null; $ParserErrors = $null
    [Management.Automation.Language.Parser]::ParseFile((Join-Path $RestoreRoot 'VERIFY_BACKUP.ps1'),[ref]$Tokens,[ref]$ParserErrors) | Out-Null
    if (@($ParserErrors).Count -ne 0) { throw 'VERIFY_BACKUP parser failed' }
    $Tokens = $null; $ParserErrors = $null
    [Management.Automation.Language.Parser]::ParseFile((Join-Path $RestoreRoot 'RESTORE_APU.ps1'),[ref]$Tokens,[ref]$ParserErrors) | Out-Null
    if (@($ParserErrors).Count -ne 0) { throw 'RESTORE_APU parser failed' }

    $ToolsDocument = @'
# Install tools on a new Windows PC

The flash intentionally contains no SDK/JDK/Rust/Gradle caches. With internet access install:
Git, JDK 17, Android Studio + SDK/Build-Tools/Platform-Tools/NDK, Rustup and cargo-ndk.
Then open the restored source. For Rust use only `build-rust.ps1`; do not auto-build before restore
hashes pass. APU is Android software: install the signed APK from `versions/latest-*` on a phone.
'@
    Write-Utf8NoBom (Join-Path $RestoreRoot 'INSTALL_TOOLS_FROM_INTERNET.md') $ToolsDocument
    $ReadmeDocument = @'
APU PORTABLE RECOVERY BACKUP

1. NEVER FORMAT THIS FLASH DRIVE AGAIN.
2. Keep exactly previous and latest verified signed APK versions.
3. Run restore\VERIFY_BACKUP.ps1 before use.
4. Run restore\RESTORE_APU.ps1 on a new Windows PC with internet.
5. Toolchains/dependencies download from the internet; caches are intentionally absent.
6. signing-private is confidential and must never be uploaded or shared.
'@
    Write-Utf8NoBom (Join-Path $BackupRoot 'README_FIRST.txt') $ReadmeDocument

    $Milestone = [ordered]@{
        schema=1; product='APU'; createdAtUtc=[DateTime]::UtcNow.ToString('o');
        latestVersion=$LatestVersion; previousVersion=$PreviousVersion; windowsBranch=$script:WindowsBranch;
        windowsHead=$script:WindowsHead; remoteRunbookCommit=$RemoteRunbookCommit;
        latestApkSha256=$LatestApkSha256; previousApkSha256=$PreviousApkSha256;
        signerCertificateSha256=$SignerCertificateSha256; nativeSha256=$NativeSha256;
        knownLimitation='M8 persistent relay custody after process death/reboot is not implemented; prerelease only.';
        policy='Never format. Rotate verified latest to previous; delete only versions older than previous.'
    }
    Write-Utf8NoBom (Join-Path $BackupRoot 'MILESTONE.json') ($Milestone | ConvertTo-Json -Depth 5)

    $ForbiddenFound = @(Get-ChildItem -LiteralPath $PortableSourceRoot -Force -Recurse | Where-Object {
        $ForbiddenDirectory = $_.PSIsContainer -and @('.git','.gradle','build','target','.idea','.kotlin','.cxx','.externalNativeBuild','node_modules','logs') -contains $_.Name
        $ForbiddenFile = -not $_.PSIsContainer -and $_.Name -match '(?i:\.log$|\.pyc$|\.jks$|local\.properties$|^temp_core\.txt$|^hs_err_pid)'
        $ForbiddenDirectory -or $ForbiddenFile
    })
    if ($ForbiddenFound.Count -ne 0) { throw "Forbidden portable source entries found: $($ForbiddenFound.FullName -join ', ')" }

    $ManifestJsonPath = Join-Path $BackupRoot 'SHA256SUMS.json'
    $ManifestTextPath = Join-Path $BackupRoot 'SHA256SUMS.txt'
    $HashEntries = @(Get-ChildItem -LiteralPath $BackupRoot -File -Recurse | Where-Object {
        $_.FullName -notin @($IncompletePath,$CompletePath,$ManifestJsonPath,$ManifestTextPath)
    } | Sort-Object FullName | ForEach-Object {
        [ordered]@{ path=$_.FullName.Substring($BackupRoot.Length+1).Replace('\','/'); bytes=[int64]$_.Length; sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToUpperInvariant() }
    })
    Write-Utf8NoBom $ManifestJsonPath ($HashEntries | ConvertTo-Json -Depth 4)
    Write-Utf8NoBom $ManifestTextPath (($HashEntries | ForEach-Object { "$($_.sha256)  $($_.path)" }) -join "`r`n")

    & (Join-Path $RestoreRoot 'VERIFY_BACKUP.ps1') -BackupRoot $BackupRoot
    $FinalBundleVerify = Invoke-NativeCaptured 'git.exe' @('bundle','verify',$BundlePath) 'git-bundle-final-verify' 600000
    if ($FinalBundleVerify.ExitCode -ne 0) { throw 'Final bundle verify failed' }

    $CloneResult = Invoke-NativeCaptured 'git.exe' @('clone','--no-checkout',$BundlePath,$VerifyClonePath) 'git-restore-rehearsal' 600000
    if ($CloneResult.ExitCode -ne 0) { throw "Restore rehearsal clone failed: $($CloneResult.Combined)" }
    $CommitResult = Invoke-NativeCaptured 'git.exe' @('-C',$VerifyClonePath,'cat-file','-e',("{0}^{commit}" -f $ExpectedWindowsHead)) 'git-restore-commit'
    if ($CommitResult.ExitCode -ne 0) { throw 'Expected Windows HEAD missing from restored bundle' }
    Remove-Item -LiteralPath $VerifyClonePath -Recurse -Force

    $script:FinalFileCount = @(Get-ChildItem -LiteralPath $BackupRoot -File -Recurse).Count
    $script:FinalBytes = [int64]((Get-ChildItem -LiteralPath $BackupRoot -File -Recurse | Measure-Object Length -Sum).Sum)
    $Complete = [ordered]@{
        schema=1; outcome='PASS_COMPACT_PORTABLE_BACKUP'; completedAtUtc=[DateTime]::UtcNow.ToString('o');
        neverFormat=$true; previous=$PreviousVersion; latest=$LatestVersion; fileCount=$script:FinalFileCount;
        totalBytes=$script:FinalBytes; manifestEntries=$HashEntries.Count; bundleVerified=$true;
        restoreRehearsal=$true; forbiddenEntries=0
    }
    Write-Utf8NoBom $CompletePath ($Complete | ConvertTo-Json -Depth 5)
    Remove-Item -LiteralPath $IncompletePath -Force
    $script:CopyComplete = $true
    Save-State 'PASS_COMPACT_PORTABLE_BACKUP' $null
    $StateHash = (Get-FileHash $StatePath -Algorithm SHA256).Hash.ToUpperInvariant()
    Write-Host 'APU COMPACT FLASH BACKUP PASS; PREVIOUS+LATEST VERIFIED; NO CACHES; RESTORE REHEARSAL PASS'
    Write-Host "Backup: $BackupRoot"
    Write-Host "Files/bytes: $($script:FinalFileCount) / $($script:FinalBytes)"
    Write-Host "State: $StatePath"
    Write-Host "State SHA-256: $StateHash"
}
catch {
    $Failure = $_.Exception.Message
    try { Save-State 'INCOMPLETE_DO_NOT_REPEAT' $Failure } catch {}
    throw
}
