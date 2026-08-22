Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepoRoot = 'C:\APUMIR-arena-test'
$BackupRoot = 'F:\APU_PORTABLE'
$IncompletePath = Join-Path $BackupRoot 'INCOMPLETE.json'
$CompletePath = Join-Path $BackupRoot 'BACKUP_COMPLETE.json'
$ManifestPath = Join-Path $BackupRoot 'SHA256SUMS.json'
$VerifyScriptPath = Join-Path $BackupRoot 'restore\VERIFY_BACKUP.ps1'
$BundlePath = Join-Path $BackupRoot 'source\APUMIR-all-refs.bundle'
$PreviousApkPath = Join-Path $BackupRoot 'versions\previous-v11.16.15\APU-v11.16.15.apk'
$LatestApkPath = Join-Path $BackupRoot 'versions\latest-v11.16.16\APU-v11.16.16.apk'
$V2StatePath = Join-Path $env:TEMP 'apu-v11.16.16-compact-flash-backup-v2.json'
$StatePath = Join-Path $env:TEMP 'apu-v11.16.16-compact-flash-backup-resume-v3.json'
$VerifyClonePath = Join-Path $env:TEMP 'apu-v11.16.16-compact-restore-verify-v2'
$NativeCaptureDir = Join-Path $env:TEMP 'apu-v11.16.16-compact-resume-native-v3'
$ExpectedWindowsHead = '8cea566e50f439810e29fb1dc4ac14dc69b5fbc6'
$ExpectedDiskNumber = 2
$ExpectedPreviousApkBytes = [int64]22664716
$ExpectedPreviousApkSha256 = 'B675770D043E4ABA5D6D099275F489DB9666A9B16792DD45000A9EC2D243E9B2'
$ExpectedLatestApkBytes = [int64]22664712
$ExpectedLatestApkSha256 = '446A1EE9254B7F57E037398E81209DB9E60C915CE2E3ADBCFA43A3FC8429DC0D'
$Utf8NoBom = New-Object Text.UTF8Encoding($false)

function Write-Utf8NoBom {
    param([string]$Path, [AllowEmptyString()][string]$Text)
    [IO.File]::WriteAllText($Path, $Text, $Utf8NoBom)
}

function Invoke-NativeCaptured {
    param([string]$FilePath, [string[]]$ArgumentList, [string]$Label, [int]$TimeoutMilliseconds = 300000)
    foreach ($Argument in $ArgumentList) {
        if ($Argument -match '[\s"]') { throw "Native argument requires unsupported quoting: $Label" }
    }
    New-Item -ItemType Directory -Path $NativeCaptureDir -Force | Out-Null
    $StdoutPath = Join-Path $NativeCaptureDir ("{0}.stdout.log" -f $Label)
    $StderrPath = Join-Path $NativeCaptureDir ("{0}.stderr.log" -f $Label)
    $StartInfo = New-Object Diagnostics.ProcessStartInfo
    $StartInfo.FileName = $FilePath
    $StartInfo.Arguments = $ArgumentList -join ' '
    $StartInfo.WorkingDirectory = $RepoRoot
    $StartInfo.UseShellExecute = $false
    $StartInfo.CreateNoWindow = $true
    $StartInfo.RedirectStandardOutput = $true
    $StartInfo.RedirectStandardError = $true
    $NativeProcess = New-Object Diagnostics.Process
    $NativeProcess.StartInfo = $StartInfo
    if (-not $NativeProcess.Start()) { throw "Native command did not start: $Label" }
    $StdoutTask = $NativeProcess.StandardOutput.ReadToEndAsync()
    $StderrTask = $NativeProcess.StandardError.ReadToEndAsync()
    $Exited = $NativeProcess.WaitForExit($TimeoutMilliseconds)
    if (-not $Exited) {
        try { $NativeProcess.Kill() } catch {}
        $NativeProcess.WaitForExit()
        throw "Native command timed out: $Label"
    }
    $NativeProcess.WaitForExit()
    $Stdout = $StdoutTask.Result
    $Stderr = $StderrTask.Result
    $ExitCode = [int]$NativeProcess.ExitCode
    $NativeProcess.Dispose()
    Write-Utf8NoBom $StdoutPath $Stdout
    Write-Utf8NoBom $StderrPath $Stderr
    return [pscustomobject]@{
        ExitCode = $ExitCode
        Combined = (($Stdout.TrimEnd(), $Stderr.TrimEnd()) | Where-Object { $_ }) -join "`n"
    }
}

function Assert-FileIdentity {
    param([string]$Path, [int64]$Bytes, [string]$Sha256, [string]$Label)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$Label missing" }
    $ActualBytes = [int64](Get-Item -LiteralPath $Path).Length
    $ActualHash = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($ActualBytes -ne $Bytes -or $ActualHash -ne $Sha256) {
        throw "$Label identity mismatch: bytes=$ActualBytes, sha256=$ActualHash"
    }
}

function Save-State {
    param([string]$Outcome, [AllowNull()][string]$Failure, [bool]$Completed)
    $State = [ordered]@{
        schema = 3
        generatedAtUtc = [DateTime]::UtcNow.ToString('o')
        outcome = $Outcome
        failure = $Failure
        purpose = 'Resume compact backup after post-copy commit-format bug'
        backupRoot = $BackupRoot
        reusedCopiedFiles = $true
        filesRecopied = 0
        formatRepeated = $false
        manifestReverified = $Completed
        existingRestoreCloneReused = $Completed
        previous = 'v11.16.15'
        latest = 'v11.16.16'
        phonesChanged = $false
        networkUsed = $false
        releasePublished = $false
    }
    Write-Utf8NoBom $StatePath ($State | ConvertTo-Json -Depth 5)
}

try {
    Set-Location -LiteralPath $RepoRoot
    if (Test-Path -LiteralPath $StatePath) { throw "Resume v3 state already exists; do not repeat: $StatePath" }
    if (-not (Test-Path -LiteralPath $V2StatePath -PathType Leaf)) { throw 'Compact v2 state missing' }
    $V2State = Get-Content -LiteralPath $V2StatePath -Raw | ConvertFrom-Json
    $V2StateValid = $V2State.outcome -eq 'INCOMPLETE_DO_NOT_REPEAT'
    $V2StateValid = $V2StateValid -and $V2State.copyStarted -eq $true
    $V2StateValid = $V2StateValid -and $V2State.copyComplete -eq $false
    $V2StateValid = $V2StateValid -and $V2State.formatRepeated -eq $false
    if (-not $V2StateValid) { throw 'Compact v2 state does not prove post-copy incomplete stop' }

    $Volume = Get-Volume -DriveLetter F -ErrorAction Stop
    $Partition = Get-Partition -DriveLetter F -ErrorAction Stop
    $Disk = $Partition | Get-Disk -ErrorAction Stop
    $FlashValid = $Disk.Number -eq $ExpectedDiskNumber
    $FlashValid = $FlashValid -and $Disk.FriendlyName.Trim() -eq 'General UDisk'
    $FlashValid = $FlashValid -and $Disk.BusType.ToString() -eq 'USB'
    $FlashValid = $FlashValid -and -not $Disk.IsSystem
    $FlashValid = $FlashValid -and -not $Disk.IsBoot
    $FlashValid = $FlashValid -and $Volume.FileSystem -eq 'exFAT'
    $FlashValid = $FlashValid -and $Volume.FileSystemLabel -eq 'APU_BACKUP'
    if (-not $FlashValid) { throw 'F: identity changed; never format' }

    if (-not (Test-Path -LiteralPath $BackupRoot -PathType Container)) { throw 'Compact backup root missing' }
    if (-not (Test-Path -LiteralPath $IncompletePath -PathType Leaf)) { throw 'INCOMPLETE marker missing' }
    if (Test-Path -LiteralPath $CompletePath) { throw 'BACKUP_COMPLETE already exists; do not repeat' }
    if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) { throw 'SHA256SUMS.json missing' }
    if (-not (Test-Path -LiteralPath $VerifyScriptPath -PathType Leaf)) { throw 'VERIFY_BACKUP.ps1 missing' }
    if (-not (Test-Path -LiteralPath $BundlePath -PathType Leaf)) { throw 'Git bundle missing' }
    if (-not (Test-Path -LiteralPath (Join-Path $VerifyClonePath '.git') -PathType Container)) {
        throw 'Expected successful v2 restore rehearsal clone is missing; do not re-clone automatically'
    }

    Assert-FileIdentity $PreviousApkPath $ExpectedPreviousApkBytes $ExpectedPreviousApkSha256 'Previous APK'
    Assert-FileIdentity $LatestApkPath $ExpectedLatestApkBytes $ExpectedLatestApkSha256 'Latest APK'

    $Tokens = $null
    $ParserErrors = $null
    [Management.Automation.Language.Parser]::ParseFile($VerifyScriptPath,[ref]$Tokens,[ref]$ParserErrors) | Out-Null
    if (@($ParserErrors).Count -ne 0) { throw 'VERIFY_BACKUP parser failed during resume' }
    & $VerifyScriptPath -BackupRoot $BackupRoot

    $ManifestEntries = @(Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json)
    if ($ManifestEntries.Count -ne 278) { throw "Unexpected manifest entry count: $($ManifestEntries.Count)" }

    $BundleVerify = Invoke-NativeCaptured 'git.exe' @('bundle','verify',$BundlePath) 'bundle-resume-verify' 600000
    if ($BundleVerify.ExitCode -ne 0) { throw "Bundle resume verify failed: $($BundleVerify.Combined)" }

    $CommitSpec = $ExpectedWindowsHead + '^{commit}'
    $CommitResult = Invoke-NativeCaptured 'git.exe' @('-C',$VerifyClonePath,'cat-file','-e',$CommitSpec) 'restore-existing-commit'
    if ($CommitResult.ExitCode -ne 0) { throw 'Expected Windows HEAD missing from existing restore clone' }
    Remove-Item -LiteralPath $VerifyClonePath -Recurse -Force

    $CurrentFiles = @(Get-ChildItem -LiteralPath $BackupRoot -File -Recurse)
    [int64]$CurrentBytes = 0
    foreach ($CurrentFile in $CurrentFiles) { $CurrentBytes += [int64]$CurrentFile.Length }
    $Complete = [ordered]@{
        schema = 3
        outcome = 'PASS_COMPACT_PORTABLE_BACKUP'
        completedAtUtc = [DateTime]::UtcNow.ToString('o')
        neverFormat = $true
        previous = 'v11.16.15'
        latest = 'v11.16.16'
        manifestEntries = $ManifestEntries.Count
        fileCountBeforeMarkerSwap = $CurrentFiles.Count
        bytesBeforeMarkerSwap = $CurrentBytes
        allHashesPassed = $true
        bundleVerified = $true
        restoreRehearsal = $true
        forbiddenEntries = 0
        filesRecopiedDuringResume = 0
    }
    Write-Utf8NoBom $CompletePath ($Complete | ConvertTo-Json -Depth 5)
    Remove-Item -LiteralPath $IncompletePath -Force
    Save-State 'PASS_COMPACT_PORTABLE_BACKUP' $null $true
    $StateHash = (Get-FileHash -LiteralPath $StatePath -Algorithm SHA256).Hash.ToUpperInvariant()
    Write-Host 'APU COMPACT FLASH BACKUP PASS; 278 HASHES; PREVIOUS+LATEST; RESTORE REHEARSAL PASS; NEVER FORMAT'
    Write-Host "Backup: $BackupRoot"
    Write-Host "State: $StatePath"
    Write-Host "State SHA-256: $StateHash"
}
catch {
    $Failure = $_.Exception.Message
    try { Save-State 'INCOMPLETE_DO_NOT_REPEAT' $Failure $false } catch {}
    throw
}
