Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepoRoot = 'C:\APUMIR-arena-test'
$FlashDrive = 'F:'
$BackupRoot = 'F:\APU-backup-2026-08-15-v11.16.16'
$WorkspaceBackup = Join-Path $BackupRoot 'workspace-exact'
$EvidenceBackup = Join-Path $BackupRoot 'temp-evidence'
$ReleaseBackup = Join-Path $BackupRoot 'release'
$MetadataBackup = Join-Path $BackupRoot 'metadata'
$RemoteVerifyBackup = Join-Path $BackupRoot 'remote-verification'
$StatePath = Join-Path $env:TEMP 'apu-v11.16.16-prerelease-backup-v4.json'
$FlashStatePath = Join-Path $MetadataBackup 'apu-v11.16.16-prerelease-backup-v4.json'
$SourceApkPath = Join-Path $env:TEMP 'apu-m3d-v11.16.16.apk'
$ReleaseApkPath = Join-Path $ReleaseBackup 'APU-v11.16.16.apk'
$ReleaseHashPath = Join-Path $ReleaseBackup 'APU-v11.16.16.apk.sha256'
$ReleaseNotesPath = Join-Path $MetadataBackup 'RELEASE_NOTES_v11.16.16.md'
$ExpectedApkBytes = [int64]22664712
$ExpectedApkSha256 = '446A1EE9254B7F57E037398E81209DB9E60C915CE2E3ADBCFA43A3FC8429DC0D'
$LatestVersion = 'v11.16.16'
$PreviousVersion = 'v11.16.15'
$PreviousApkPath = Join-Path $env:TEMP 'apu-r4.4-dual-v11.16.15.apk'
$ExpectedPreviousApkBytes = [int64]22664716
$ExpectedPreviousApkSha256 = 'B675770D043E4ABA5D6D099275F489DB9666A9B16792DD45000A9EC2D243E9B2'
$ExpectedSignerCertificateSha256 = 'F843CBE70332BAB67A9671EBDE32FEE541E84CD904D3A508E5626346A1A4A5F7'
$LatestBuildStatePath = Join-Path $env:TEMP 'apu-m3d-v11.16.16-apk-build.json'
$ExpectedLatestBuildStateSha256 = '917077E82C25DFF9A020713BA4A391DD49D7AFD81446367F87A23B17216AFABC'
$PreviousBuildStatePath = Join-Path $env:TEMP 'apu-r4.4-dual-v11.16.15-apk-build.json'
$ExpectedPreviousBuildStateSha256 = '3D893F5A546008C3166C68026203EF2B395144EA9C072D6E9583E9FE84644078'
$VersionsRoot = Join-Path $BackupRoot 'versions'
$PreviousVersionDir = Join-Path $VersionsRoot 'previous-v11.16.15'
$LatestVersionDir = Join-Path $VersionsRoot 'latest-v11.16.16'
$PreviousVersionApkPath = Join-Path $PreviousVersionDir 'APU-v11.16.15.apk'
$LatestVersionApkPath = Join-Path $LatestVersionDir 'APU-v11.16.16.apk'
$LatestMarkerPath = Join-Path $VersionsRoot 'LATEST.txt'
$LatestManifestPath = Join-Path $VersionsRoot 'LATEST.json'
$ExpectedBranch = 'arena/01a000bc-apumir'
$ExpectedMinimumRemoteCommit = 'fad9bce43fb544a3d835912f55fa29c1ce98cf54'
$ReleaseTag = 'v11.16.16'
$Repository = 'vzhem/APUMIR'
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$script:BackupComplete = $false
$script:UploadComplete = $false
$script:RemoteVerified = $false
$script:SourceHead = $null
$script:SourceBranch = $null
$script:FetchedRemoteCommit = $null
$script:FetchWarning = $null
$script:Outcome = 'STARTED'
$script:FailureText = $null

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text
    )
    [IO.File]::WriteAllText($Path, $Text, $Utf8NoBom)
}

function Get-TreeBytes {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return [int64]0
    }
    [int64]$TotalBytes = 0
    $Files = @(Get-ChildItem -LiteralPath $Path -Force -File -Recurse -ErrorAction Stop)
    foreach ($File in $Files) {
        $TotalBytes += [int64]$File.Length
    }
    return $TotalBytes
}

function Invoke-RobocopyChecked {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination,
        [Parameter(Mandatory = $true)][string]$LogPath
    )
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    $Arguments = @(
        $Source,
        $Destination,
        '/E',
        '/COPY:DAT',
        '/DCOPY:DAT',
        '/R:2',
        '/W:2',
        '/XJ',
        '/FFT',
        '/NP',
        "/LOG:$LogPath"
    )
    & robocopy.exe @Arguments
    $RobocopyExitCode = $LASTEXITCODE
    if ($RobocopyExitCode -gt 7) {
        throw "robocopy failed for '$Source' with exit code $RobocopyExitCode. See $LogPath"
    }
}

function Save-State {
    param(
        [Parameter(Mandatory = $true)][string]$Outcome,
        [AllowNull()][string]$FailureText
    )
    $State = [ordered]@{
        schema = 4
        generatedAtUtc = [DateTime]::UtcNow.ToString('o')
        outcome = $Outcome
        failure = $FailureText
        repository = $Repository
        releaseTag = $ReleaseTag
        releaseKind = 'prerelease-draft'
        repoRoot = $RepoRoot
        sourceBranch = $script:SourceBranch
        sourceHead = $script:SourceHead
        fetchedRemoteCommit = $script:FetchedRemoteCommit
        expectedMinimumRemoteCommit = $ExpectedMinimumRemoteCommit
        fetchWarning = $script:FetchWarning
        flashDrive = $FlashDrive
        backupRoot = $BackupRoot
        backupComplete = $script:BackupComplete
        uploadComplete = $script:UploadComplete
        remoteAssetVerified = $script:RemoteVerified
        apkSource = $SourceApkPath
        apkBackup = $ReleaseApkPath
        apkBytes = $ExpectedApkBytes
        apkSha256 = $ExpectedApkSha256
        versionPolicy = 'minimum-two-previous-and-latest'
        latestVersion = $LatestVersion
        latestVersionApk = $LatestVersionApkPath
        previousVersion = $PreviousVersion
        previousVersionApk = $PreviousVersionApkPath
        previousApkSha256 = $ExpectedPreviousApkSha256
        signerCertificateSha256 = $ExpectedSignerCertificateSha256
        latestMarker = $LatestMarkerPath
        phoneCommands = $false
        buildRepeated = $false
        installRepeated = $false
        sourceDeleted = $false
    }
    $Json = $State | ConvertTo-Json -Depth 6
    Write-Utf8NoBom -Path $StatePath -Text $Json
    if (Test-Path -LiteralPath $MetadataBackup) {
        Write-Utf8NoBom -Path $FlashStatePath -Text $Json
    }
}

$ReleaseNotes = @'
# APU v11.16.16 — тестовая контрольная версия

Это предварительный тестовый релиз, а не объявление полной готовности офлайн-сети.

Проверенный APK: 22,664,712 байт; SHA-256
446A1EE9254B7F57E037398E81209DB9E60C915CE2E3ADBCFA43A3FC8429DC0D.

Проверены data-preserving install и controlled launch/readiness на трёх телефонах. Пользователь
вручную наблюдал успешную offline UI-доставку через третий телефон.

Ограничение: persistent relay custody M8 ещё не реализована. RelayQueue находится в памяти процесса,
поэтому process death/reboot до передачи может потерять custody. Полная exact message-ID chain,
receipt cleanup и eventual origin DELIVERED отдельным runtime capture ещё не доказаны.

Следующий M8: encrypted persistent custody перед sleep; restore после process death/reboot; bounded
запрос новых relay items после wake; повторная попытка старых+новых; недоставленное снова сохраняется
без сброса TTL.
'@

try {
    Set-Location -LiteralPath $RepoRoot

    if (-not (Test-Path -LiteralPath $RepoRoot -PathType Container)) {
        throw "Repository root is missing: $RepoRoot"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot '.git'))) {
        throw "Git metadata is missing under $RepoRoot"
    }
    if (-not (Test-Path -LiteralPath $SourceApkPath -PathType Leaf)) {
        throw "Verified APK is missing: $SourceApkPath"
    }

    $SourceApk = Get-Item -LiteralPath $SourceApkPath
    $SourceApkHash = (Get-FileHash -LiteralPath $SourceApkPath -Algorithm SHA256).Hash.ToUpperInvariant()
    if ([int64]$SourceApk.Length -ne $ExpectedApkBytes) {
        throw "APK size mismatch: expected $ExpectedApkBytes, got $($SourceApk.Length)"
    }
    if ($SourceApkHash -ne $ExpectedApkSha256) {
        throw "APK SHA-256 mismatch: expected $ExpectedApkSha256, got $SourceApkHash"
    }
    if (-not (Test-Path -LiteralPath $PreviousApkPath -PathType Leaf)) {
        throw "Previous verified APK is missing: $PreviousApkPath"
    }
    $PreviousApk = Get-Item -LiteralPath $PreviousApkPath
    $PreviousApkHash = (Get-FileHash -LiteralPath $PreviousApkPath -Algorithm SHA256).Hash.ToUpperInvariant()
    if ([int64]$PreviousApk.Length -ne $ExpectedPreviousApkBytes) {
        throw "Previous APK size mismatch: expected $ExpectedPreviousApkBytes, got $($PreviousApk.Length)"
    }
    if ($PreviousApkHash -ne $ExpectedPreviousApkSha256) {
        throw "Previous APK SHA-256 mismatch: expected $ExpectedPreviousApkSha256, got $PreviousApkHash"
    }
    foreach ($BuildStateSpec in @(
        [pscustomobject]@{ Path = $LatestBuildStatePath; Hash = $ExpectedLatestBuildStateSha256; Version = $LatestVersion; ApkHash = $ExpectedApkSha256 },
        [pscustomobject]@{ Path = $PreviousBuildStatePath; Hash = $ExpectedPreviousBuildStateSha256; Version = $PreviousVersion; ApkHash = $ExpectedPreviousApkSha256 }
    )) {
        if (-not (Test-Path -LiteralPath $BuildStateSpec.Path -PathType Leaf)) {
            throw "Signed APK build state is missing: $($BuildStateSpec.Path)"
        }
        $BuildStateHash = (Get-FileHash -LiteralPath $BuildStateSpec.Path -Algorithm SHA256).Hash.ToUpperInvariant()
        if ($BuildStateHash -ne $BuildStateSpec.Hash) {
            throw "Signed APK build state hash mismatch for $($BuildStateSpec.Version): $BuildStateHash"
        }
        $BuildState = Get-Content -LiteralPath $BuildStateSpec.Path -Raw | ConvertFrom-Json
        if ($BuildState.outcome -ne 'PASS' -or
            $BuildState.apkSha256 -ne $BuildStateSpec.ApkHash -or
            $BuildState.signerV2 -ne $true -or
            $BuildState.signerCertificateSha256 -ne $ExpectedSignerCertificateSha256) {
            throw "Signed APK build state content mismatch for $($BuildStateSpec.Version)"
        }
    }

    $Drive = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='$FlashDrive'"
    if ($null -eq $Drive) {
        throw "Flash drive $FlashDrive is not available"
    }
    if ($FlashDrive -eq 'C:') {
        throw 'Backup destination must not be drive C:'
    }
    if (@(2, 3) -notcontains [int]$Drive.DriveType) {
        throw "Drive $FlashDrive has unexpected DriveType=$($Drive.DriveType)"
    }

    $script:SourceBranch = (& git branch --show-current 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to read the current Windows branch'
    }
    $script:SourceHead = (& git rev-parse HEAD 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to read the current Windows HEAD'
    }
    $StatusInitial = (& git status --porcelain=v1 --branch 2>&1 | Out-String).TrimEnd()
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to read the initial Windows worktree status'
    }

    $script:FetchWarning = 'Network intentionally skipped in offline v4 after the confirmed GitHub connectivity failure.'

    $StatusBefore = (& git status --porcelain=v1 --branch 2>&1 | Out-String).TrimEnd()
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to read the pre-backup Windows worktree status'
    }

    $RepoBytes = Get-TreeBytes -Path $RepoRoot
    $GitBytes = Get-TreeBytes -Path (Join-Path $RepoRoot '.git')
    $TempItems = @(Get-ChildItem -LiteralPath $env:TEMP -Force -ErrorAction Stop |
        Where-Object { $_.Name -like 'apu-*' -and $_.FullName -ne $StatePath })
    [int64]$TempBytes = 0
    foreach ($TempItem in $TempItems) {
        if ($TempItem.PSIsContainer) {
            $TempBytes += Get-TreeBytes -Path $TempItem.FullName
        }
        else {
            $TempBytes += [int64]$TempItem.Length
        }
    }
    [int64]$SafetyBytes = 512MB
    [int64]$ExplicitVersionCopiesBytes = ($ExpectedApkBytes * 2) + $ExpectedPreviousApkBytes
    [int64]$RequiredFreeBytes = $RepoBytes + $GitBytes + $TempBytes + $ExplicitVersionCopiesBytes + $SafetyBytes
    if ([int64]$Drive.FreeSpace -lt $RequiredFreeBytes) {
        throw "Not enough free space on $FlashDrive. Required at least $RequiredFreeBytes bytes, available $($Drive.FreeSpace) bytes"
    }

    New-Item -ItemType Directory -Path $BackupRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $EvidenceBackup -Force | Out-Null
    New-Item -ItemType Directory -Path $ReleaseBackup -Force | Out-Null
    New-Item -ItemType Directory -Path $MetadataBackup -Force | Out-Null
    New-Item -ItemType Directory -Path $RemoteVerifyBackup -Force | Out-Null
    New-Item -ItemType Directory -Path $PreviousVersionDir -Force | Out-Null
    New-Item -ItemType Directory -Path $LatestVersionDir -Force | Out-Null

    Write-Utf8NoBom -Path (Join-Path $MetadataBackup 'windows-status-initial.txt') -Text $StatusInitial
    Write-Utf8NoBom -Path (Join-Path $MetadataBackup 'windows-status-before.txt') -Text $StatusBefore
    Write-Utf8NoBom -Path (Join-Path $MetadataBackup 'RELEASE_NOTES_v11.16.16.md') -Text $ReleaseNotes
    Write-Utf8NoBom -Path (Join-Path $MetadataBackup 'drive-info.json') -Text ($Drive | Select-Object DeviceID, VolumeName, FileSystem, DriveType, Size, FreeSpace | ConvertTo-Json)

    $WorkspaceLog = Join-Path $MetadataBackup 'robocopy-workspace.log'
    Invoke-RobocopyChecked -Source $RepoRoot -Destination $WorkspaceBackup -LogPath $WorkspaceLog

    foreach ($TempItem in $TempItems) {
        $Destination = Join-Path $EvidenceBackup $TempItem.Name
        if ($TempItem.PSIsContainer) {
            $TempLog = Join-Path $MetadataBackup ("robocopy-temp-{0}.log" -f $TempItem.Name)
            Invoke-RobocopyChecked -Source $TempItem.FullName -Destination $Destination -LogPath $TempLog
        }
        else {
            Copy-Item -LiteralPath $TempItem.FullName -Destination $Destination -Force
        }
    }

    Copy-Item -LiteralPath $SourceApkPath -Destination $ReleaseApkPath -Force
    $BackupApk = Get-Item -LiteralPath $ReleaseApkPath
    $BackupApkHash = (Get-FileHash -LiteralPath $ReleaseApkPath -Algorithm SHA256).Hash.ToUpperInvariant()
    if ([int64]$BackupApk.Length -ne $ExpectedApkBytes -or $BackupApkHash -ne $ExpectedApkSha256) {
        throw 'Flash APK verification failed after copy'
    }
    Write-Utf8NoBom -Path $ReleaseHashPath -Text ("{0} *APU-v11.16.16.apk`n" -f $ExpectedApkSha256)

    Copy-Item -LiteralPath $PreviousApkPath -Destination $PreviousVersionApkPath -Force
    Copy-Item -LiteralPath $SourceApkPath -Destination $LatestVersionApkPath -Force
    $PreviousVersionCopy = Get-Item -LiteralPath $PreviousVersionApkPath
    $LatestVersionCopy = Get-Item -LiteralPath $LatestVersionApkPath
    $PreviousVersionCopyHash = (Get-FileHash -LiteralPath $PreviousVersionApkPath -Algorithm SHA256).Hash.ToUpperInvariant()
    $LatestVersionCopyHash = (Get-FileHash -LiteralPath $LatestVersionApkPath -Algorithm SHA256).Hash.ToUpperInvariant()
    if ([int64]$PreviousVersionCopy.Length -ne $ExpectedPreviousApkBytes -or
        $PreviousVersionCopyHash -ne $ExpectedPreviousApkSha256) {
        throw 'Previous version verification failed after F: copy'
    }
    if ([int64]$LatestVersionCopy.Length -ne $ExpectedApkBytes -or
        $LatestVersionCopyHash -ne $ExpectedApkSha256) {
        throw 'Latest version verification failed after F: copy'
    }
    $PreviousVersionHashPath = Join-Path $PreviousVersionDir 'APU-v11.16.15.apk.sha256'
    $LatestVersionHashPath = Join-Path $LatestVersionDir 'APU-v11.16.16.apk.sha256'
    Write-Utf8NoBom -Path $PreviousVersionHashPath -Text ("{0} *APU-v11.16.15.apk`n" -f $ExpectedPreviousApkSha256)
    Write-Utf8NoBom -Path $LatestVersionHashPath -Text ("{0} *APU-v11.16.16.apk`n" -f $ExpectedApkSha256)

    $LatestManifest = [ordered]@{
        schema = 1
        generatedAtUtc = [DateTime]::UtcNow.ToString('o')
        policy = 'Keep at least previous and latest APU versions'
        latest = [ordered]@{
            version = $LatestVersion
            folder = 'latest-v11.16.16'
            apk = 'APU-v11.16.16.apk'
            bytes = $ExpectedApkBytes
            sha256 = $ExpectedApkSha256
            androidSignature = 'V2 verified'
            signerCertificateSha256 = $ExpectedSignerCertificateSha256
        }
        previous = [ordered]@{
            version = $PreviousVersion
            folder = 'previous-v11.16.15'
            apk = 'APU-v11.16.15.apk'
            bytes = $ExpectedPreviousApkBytes
            sha256 = $ExpectedPreviousApkSha256
            androidSignature = 'V2 verified'
            signerCertificateSha256 = $ExpectedSignerCertificateSha256
        }
    }
    Write-Utf8NoBom -Path $LatestManifestPath -Text ($LatestManifest | ConvertTo-Json -Depth 6)
    $LatestMarker = @(
        'LATEST APU VERSION: v11.16.16',
        'Folder: latest-v11.16.16',
        "APK SHA-256: $ExpectedApkSha256",
        "Android signer certificate SHA-256: $ExpectedSignerCertificateSha256",
        '',
        'PREVIOUS APU VERSION: v11.16.15',
        'Folder: previous-v11.16.15',
        "APK SHA-256: $ExpectedPreviousApkSha256",
        '',
        'The APK files themselves have verified Android V2 digital signatures.'
    ) -join "`r`n"
    Write-Utf8NoBom -Path $LatestMarkerPath -Text $LatestMarker

    $StatusAfter = (& git status --porcelain=v1 --branch 2>&1 | Out-String).TrimEnd()
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to read final Windows worktree status'
    }
    if ($StatusAfter -ne $StatusBefore) {
        throw 'Windows worktree changed during backup; refusing release upload'
    }
    Write-Utf8NoBom -Path (Join-Path $MetadataBackup 'windows-status-after.txt') -Text $StatusAfter

    $WorkingPatchPath = Join-Path $MetadataBackup 'windows-working-tree.patch'
    $IndexPatchPath = Join-Path $MetadataBackup 'windows-index.patch'
    $BundlePath = Join-Path $MetadataBackup 'APUMIR-all-refs.bundle'
    $SavedErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        (& git diff --binary 2>&1 | Out-File -LiteralPath $WorkingPatchPath -Encoding utf8)
        $WorkingDiffExitCode = $LASTEXITCODE
        (& git diff --cached --binary 2>&1 | Out-File -LiteralPath $IndexPatchPath -Encoding utf8)
        $IndexDiffExitCode = $LASTEXITCODE
        $BundleOutput = (& git bundle create $BundlePath --all 2>&1 | Out-String).TrimEnd()
        $BundleCreateExitCode = $LASTEXITCODE
        $BundleVerify = (& git bundle verify $BundlePath 2>&1 | Out-String).TrimEnd()
        $BundleVerifyExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $SavedErrorActionPreference
    }
    if ($WorkingDiffExitCode -ne 0 -or -not (Test-Path -LiteralPath $WorkingPatchPath)) {
        throw 'Unable to save Windows working-tree patch'
    }
    if ($IndexDiffExitCode -ne 0 -or -not (Test-Path -LiteralPath $IndexPatchPath)) {
        throw 'Unable to save Windows index patch'
    }
    if ($BundleCreateExitCode -ne 0 -or -not (Test-Path -LiteralPath $BundlePath)) {
        throw "git bundle failed: $BundleOutput"
    }
    if ($BundleVerifyExitCode -ne 0) {
        throw "git bundle verification failed: $BundleVerify"
    }
    Write-Utf8NoBom -Path (Join-Path $MetadataBackup 'git-bundle-verify.txt') -Text $BundleVerify

    $CriticalManifest = @(
        $ReleaseApkPath,
        $ReleaseHashPath,
        $PreviousVersionApkPath,
        $PreviousVersionHashPath,
        $LatestVersionApkPath,
        $LatestVersionHashPath,
        $LatestMarkerPath,
        $LatestManifestPath,
        $BundlePath,
        (Join-Path $MetadataBackup 'windows-working-tree.patch'),
        (Join-Path $MetadataBackup 'windows-index.patch'),
        $ReleaseNotesPath
    ) | ForEach-Object {
        $Item = Get-Item -LiteralPath $_
        [ordered]@{
            path = $Item.FullName.Substring($BackupRoot.Length + 1)
            bytes = [int64]$Item.Length
            sha256 = (Get-FileHash -LiteralPath $Item.FullName -Algorithm SHA256).Hash.ToUpperInvariant()
        }
    }
    Write-Utf8NoBom -Path (Join-Path $MetadataBackup 'critical-manifest.json') -Text ($CriticalManifest | ConvertTo-Json -Depth 4)

    $script:BackupComplete = $true
    $script:Outcome = 'BACKUP_COMPLETE_UPLOAD_PENDING'
    Save-State -Outcome $script:Outcome -FailureText $null

    if ($null -ne $script:FetchWarning) {
        Write-Host 'APU BACKUP COMPLETE ON F:; PREVIOUS v11.16.15 + LATEST v11.16.16 VERIFIED; DRAFT UPLOAD PENDING'
        Write-Host "State: $StatePath"
        Write-Host "Flash backup: $BackupRoot"
        exit 0
    }

    $GhCommand = Get-Command gh.exe -ErrorAction SilentlyContinue
    if ($null -eq $GhCommand) {
        Write-Host 'BACKUP COMPLETE. GitHub CLI is not installed; draft upload remains pending.'
        exit 0
    }

    $AuthOutput = (& gh auth status --hostname github.com 2>&1 | Out-String).TrimEnd()
    Write-Utf8NoBom -Path (Join-Path $MetadataBackup 'gh-auth-status.txt') -Text $AuthOutput
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'BACKUP COMPLETE. GitHub CLI is not authenticated; draft upload remains pending.'
        exit 0
    }

    $ReleaseRaw = (& gh release view $ReleaseTag --repo $Repository --json tagName,isDraft,isPrerelease,assets,url 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to read draft prerelease: $ReleaseRaw"
    }
    $Release = $ReleaseRaw | ConvertFrom-Json
    if ($Release.tagName -ne $ReleaseTag -or -not [bool]$Release.isDraft -or -not [bool]$Release.isPrerelease) {
        throw 'GitHub release is not the expected draft prerelease; refusing upload'
    }

    $UploadOutput = (& gh release upload $ReleaseTag $ReleaseApkPath $ReleaseHashPath --repo $Repository --clobber 2>&1 | Out-String).TrimEnd()
    Write-Utf8NoBom -Path (Join-Path $MetadataBackup 'gh-upload.txt') -Text $UploadOutput
    if ($LASTEXITCODE -ne 0) {
        throw "Draft asset upload failed: $UploadOutput"
    }
    $script:UploadComplete = $true

    $DownloadOutput = (& gh release download $ReleaseTag --repo $Repository --pattern 'APU-v11.16.16.apk' --dir $RemoteVerifyBackup --clobber 2>&1 | Out-String).TrimEnd()
    Write-Utf8NoBom -Path (Join-Path $MetadataBackup 'gh-download-verify.txt') -Text $DownloadOutput
    if ($LASTEXITCODE -ne 0) {
        throw "Remote asset download failed: $DownloadOutput"
    }
    $RemoteApkPath = Join-Path $RemoteVerifyBackup 'APU-v11.16.16.apk'
    $RemoteApk = Get-Item -LiteralPath $RemoteApkPath
    $RemoteApkHash = (Get-FileHash -LiteralPath $RemoteApkPath -Algorithm SHA256).Hash.ToUpperInvariant()
    if ([int64]$RemoteApk.Length -ne $ExpectedApkBytes -or $RemoteApkHash -ne $ExpectedApkSha256) {
        throw "Remote APK verification mismatch: bytes=$($RemoteApk.Length), sha256=$RemoteApkHash"
    }

    $script:RemoteVerified = $true
    $script:Outcome = 'BACKUP_COMPLETE_DRAFT_UPLOAD_VERIFIED'
    Save-State -Outcome $script:Outcome -FailureText $null
    Write-Host 'APU v11.16.16 BACKUP COMPLETE; DRAFT UPLOAD VERIFIED; NOT YET PUBLISHED'
    Write-Host "State: $StatePath"
    Write-Host "Flash backup: $BackupRoot"
}
catch {
    $script:FailureText = $_.Exception.Message
    if ($script:BackupComplete) {
        $script:Outcome = 'BACKUP_COMPLETE_UPLOAD_INCOMPLETE'
    }
    else {
        $script:Outcome = 'INCOMPLETE_BACKUP'
    }
    try {
        Save-State -Outcome $script:Outcome -FailureText $script:FailureText
    }
    catch {
        Write-Warning "Unable to write state: $($_.Exception.Message)"
    }
    throw
}
