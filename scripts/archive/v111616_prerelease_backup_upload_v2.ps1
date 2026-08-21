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
$NativeCaptureDir = Join-Path $env:TEMP 'apu-v11.16.16-prerelease-native-v2'
$StatePath = Join-Path $env:TEMP 'apu-v11.16.16-prerelease-backup-v2.json'
$FlashStatePath = Join-Path $MetadataBackup 'apu-v11.16.16-prerelease-backup-v2.json'
$SourceApkPath = Join-Path $env:TEMP 'apu-m3d-v11.16.16.apk'
$ReleaseApkPath = Join-Path $ReleaseBackup 'APU-v11.16.16.apk'
$ReleaseHashPath = Join-Path $ReleaseBackup 'APU-v11.16.16.apk.sha256'
$ReleaseNotesPath = Join-Path $MetadataBackup 'RELEASE_NOTES_v11.16.16.md'
$ExpectedApkBytes = [int64]22664712
$ExpectedApkSha256 = '446A1EE9254B7F57E037398E81209DB9E60C915CE2E3ADBCFA43A3FC8429DC0D'
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
    $Measure = Get-ChildItem -LiteralPath $Path -Force -File -Recurse -ErrorAction Stop |
        Measure-Object -Property Length -Sum
    if ($null -eq $Measure.Sum) {
        return [int64]0
    }
    return [int64]$Measure.Sum
}

function Invoke-NativeCaptured {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$ArgumentList,
        [Parameter(Mandatory = $true)][string]$Label,
        [int]$TimeoutMilliseconds = 120000
    )

    New-Item -ItemType Directory -Path $NativeCaptureDir -Force | Out-Null
    $StdoutPath = Join-Path $NativeCaptureDir ("{0}.stdout.log" -f $Label)
    $StderrPath = Join-Path $NativeCaptureDir ("{0}.stderr.log" -f $Label)
    $NativeProcess = Start-Process `
        -FilePath $FilePath `
        -ArgumentList $ArgumentList `
        -WorkingDirectory $RepoRoot `
        -RedirectStandardOutput $StdoutPath `
        -RedirectStandardError $StderrPath `
        -PassThru

    $Exited = $NativeProcess.WaitForExit($TimeoutMilliseconds)
    if (-not $Exited) {
        try {
            $NativeProcess.Kill()
        }
        catch {
        }
        throw "Native command timed out: $Label"
    }
    $NativeProcess.WaitForExit()
    $NativeProcess.Refresh()
    $RawExitCode = $NativeProcess.ExitCode
    if ($null -eq $RawExitCode) {
        throw "Native command exit code unavailable: $Label"
    }

    $Stdout = if (Test-Path -LiteralPath $StdoutPath) {
        [IO.File]::ReadAllText($StdoutPath)
    }
    else {
        ''
    }
    $Stderr = if (Test-Path -LiteralPath $StderrPath) {
        [IO.File]::ReadAllText($StderrPath)
    }
    else {
        ''
    }
    return [pscustomobject]@{
        ExitCode = [int]$RawExitCode
        Stdout = $Stdout
        Stderr = $Stderr
        Combined = (($Stdout.TrimEnd(), $Stderr.TrimEnd()) | Where-Object { $_ }) -join "`n"
        StdoutPath = $StdoutPath
        StderrPath = $StderrPath
    }
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
        schema = 2
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

    $script:FetchWarning = 'Remote fetch intentionally skipped in v2 after the confirmed GitHub connectivity failure; local backup proceeds without a network retry.'

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
    [int64]$RequiredFreeBytes = $RepoBytes + $GitBytes + $TempBytes + $SafetyBytes
    if ([int64]$Drive.FreeSpace -lt $RequiredFreeBytes) {
        throw "Not enough free space on $FlashDrive. Required at least $RequiredFreeBytes bytes, available $($Drive.FreeSpace) bytes"
    }

    New-Item -ItemType Directory -Path $BackupRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $EvidenceBackup -Force | Out-Null
    New-Item -ItemType Directory -Path $ReleaseBackup -Force | Out-Null
    New-Item -ItemType Directory -Path $MetadataBackup -Force | Out-Null
    New-Item -ItemType Directory -Path $RemoteVerifyBackup -Force | Out-Null

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

    $StatusAfter = (& git status --porcelain=v1 --branch 2>&1 | Out-String).TrimEnd()
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to read final Windows worktree status'
    }
    if ($StatusAfter -ne $StatusBefore) {
        throw 'Windows worktree changed during backup; refusing release upload'
    }
    Write-Utf8NoBom -Path (Join-Path $MetadataBackup 'windows-status-after.txt') -Text $StatusAfter

    $WorkingDiffResult = Invoke-NativeCaptured `
        -FilePath 'git.exe' `
        -ArgumentList @('diff', '--binary') `
        -Label 'git-diff-working'
    if ($WorkingDiffResult.ExitCode -ne 0) {
        throw "Unable to save Windows working-tree patch: $($WorkingDiffResult.Combined)"
    }
    Copy-Item -LiteralPath $WorkingDiffResult.StdoutPath -Destination (Join-Path $MetadataBackup 'windows-working-tree.patch') -Force

    $IndexDiffResult = Invoke-NativeCaptured `
        -FilePath 'git.exe' `
        -ArgumentList @('diff', '--cached', '--binary') `
        -Label 'git-diff-index'
    if ($IndexDiffResult.ExitCode -ne 0) {
        throw "Unable to save Windows index patch: $($IndexDiffResult.Combined)"
    }
    Copy-Item -LiteralPath $IndexDiffResult.StdoutPath -Destination (Join-Path $MetadataBackup 'windows-index.patch') -Force

    $BundlePath = Join-Path $MetadataBackup 'APUMIR-all-refs.bundle'
    $BundleResult = Invoke-NativeCaptured `
        -FilePath 'git.exe' `
        -ArgumentList @('bundle', 'create', $BundlePath, '--all') `
        -Label 'git-bundle-create' `
        -TimeoutMilliseconds 300000
    if ($BundleResult.ExitCode -ne 0) {
        throw "git bundle failed: $($BundleResult.Combined)"
    }
    $BundleVerifyResult = Invoke-NativeCaptured `
        -FilePath 'git.exe' `
        -ArgumentList @('bundle', 'verify', $BundlePath) `
        -Label 'git-bundle-verify' `
        -TimeoutMilliseconds 300000
    if ($BundleVerifyResult.ExitCode -ne 0) {
        throw "git bundle verification failed: $($BundleVerifyResult.Combined)"
    }
    Write-Utf8NoBom -Path (Join-Path $MetadataBackup 'git-bundle-verify.txt') -Text $BundleVerifyResult.Combined
    if (Test-Path -LiteralPath $NativeCaptureDir) {
        Invoke-RobocopyChecked `
            -Source $NativeCaptureDir `
            -Destination (Join-Path $MetadataBackup 'native-capture-v2') `
            -LogPath (Join-Path $MetadataBackup 'robocopy-native-capture-v2.log')
    }

    $CriticalManifest = @(
        $ReleaseApkPath,
        $ReleaseHashPath,
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
        Write-Host 'APU v11.16.16 BACKUP COMPLETE ON F:; NETWORK RETRY SKIPPED; DRAFT UPLOAD PENDING'
        Write-Host "State: $StatePath"
        Write-Host "Flash backup: $BackupRoot"
        exit 0
    }

    throw 'Offline v2 invariant failed: network upload must remain pending'
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
