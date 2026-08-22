Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepoRoot = 'C:\APUMIR-arena-test'
$Repository = 'vzhem/APUMIR'
$Tag = 'v11.16.16'
$ApkPath = 'F:\APU_PORTABLE\versions\latest-v11.16.16\APU-v11.16.16.apk'
$ApkHashPath = 'F:\APU_PORTABLE\versions\latest-v11.16.16\APU-v11.16.16.apk.sha256'
$BackupCompletePath = 'F:\APU_PORTABLE\BACKUP_COMPLETE.json'
$BackupStatePath = Join-Path $env:TEMP 'apu-v11.16.16-compact-flash-backup-resume-v4.json'
$ExpectedBackupStateSha256 = 'A96500612DD1AC80D908F1F49ADE9536931E512D387C2FD0EDA8CB82772D2483'
$ExpectedApkBytes = [int64]22664712
$ExpectedApkSha256 = '446A1EE9254B7F57E037398E81209DB9E60C915CE2E3ADBCFA43A3FC8429DC0D'
$RemoteVerifyDir = Join-Path $env:TEMP 'apu-v11.16.16-draft-upload-remote-verify-v1'
$StatePath = Join-Path $env:TEMP 'apu-v11.16.16-draft-upload-v1.json'
$Utf8NoBom = New-Object Text.UTF8Encoding($false)
$script:UploadCalled = $false
$script:UploadComplete = $false
$script:RemoteVerified = $false

function Invoke-ApuGhCommandExact {
    param([string]$GhPath, [string[]]$Arguments, [string]$Label)
    $SavedErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $Output = @(& $GhPath @Arguments 2>&1)
        $ExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $SavedErrorActionPreference
    }
    return [pscustomobject]@{
        Label = $Label
        ExitCode = [int]$ExitCode
        Text = (($Output | ForEach-Object { $_.ToString() }) -join "`n")
    }
}

function Save-ApuDraftUploadStateExact {
    param([string]$Outcome, [AllowNull()][string]$Failure)
    $State = [ordered]@{
        schema = 1
        generatedAtUtc = [DateTime]::UtcNow.ToString('o')
        outcome = $Outcome
        failure = $Failure
        repository = $Repository
        tag = $Tag
        apkPath = $ApkPath
        apkBytes = $ExpectedApkBytes
        apkSha256 = $ExpectedApkSha256
        uploadCalled = $script:UploadCalled
        uploadComplete = $script:UploadComplete
        remoteVerified = $script:RemoteVerified
        releasePublished = $false
        phonesChanged = $false
        flashFormatted = $false
    }
    [IO.File]::WriteAllText($StatePath, ($State | ConvertTo-Json -Depth 5), $Utf8NoBom)
}

function Assert-ApuFileIdentityExact {
    param([string]$Path, [int64]$Bytes, [string]$Sha256, [string]$Label)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$Label missing: $Path" }
    $Item = Get-Item -LiteralPath $Path
    $Hash = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
    if ([int64]$Item.Length -ne $Bytes -or $Hash -ne $Sha256) {
        throw "$Label mismatch: bytes=$($Item.Length), sha256=$Hash"
    }
}

try {
    Set-Location -LiteralPath $RepoRoot
    if (Test-Path -LiteralPath $StatePath) { throw "Draft upload v1 already has state; do not repeat: $StatePath" }
    if (Test-Path -LiteralPath $RemoteVerifyDir) { throw "Remote verify directory already exists: $RemoteVerifyDir" }
    if (-not (Test-Path -LiteralPath $BackupCompletePath -PathType Leaf)) { throw 'Portable BACKUP_COMPLETE marker missing' }
    if (-not (Test-Path -LiteralPath $BackupStatePath -PathType Leaf)) { throw 'Portable backup state missing' }
    $BackupStateHash = (Get-FileHash -LiteralPath $BackupStatePath -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($BackupStateHash -ne $ExpectedBackupStateSha256) { throw "Portable backup state hash mismatch: $BackupStateHash" }
    $BackupState = Get-Content -LiteralPath $BackupStatePath -Raw | ConvertFrom-Json
    if ($BackupState.outcome -ne 'PASS_COMPACT_PORTABLE_BACKUP' -or $BackupState.formatRepeated -ne $false) {
        throw 'Portable backup state content mismatch'
    }
    Assert-ApuFileIdentityExact $ApkPath $ExpectedApkBytes $ExpectedApkSha256 'Latest APK on flash'
    if (-not (Test-Path -LiteralPath $ApkHashPath -PathType Leaf)) { throw 'Latest APK checksum file missing' }
    $ChecksumText = Get-Content -LiteralPath $ApkHashPath -Raw
    if ($ChecksumText -notmatch [regex]::Escape($ExpectedApkSha256)) { throw 'Latest APK checksum file content mismatch' }

    $GhCommand = Get-Command gh.exe -ErrorAction SilentlyContinue
    if ($null -eq $GhCommand) { throw 'GitHub CLI gh.exe is not installed on Windows' }
    $GhPath = $GhCommand.Source
    $Auth = Invoke-ApuGhCommandExact $GhPath @('auth','status','--hostname','github.com') 'gh-auth'
    if ($Auth.ExitCode -ne 0) { throw "GitHub CLI is not authenticated: $($Auth.Text)" }

    $View = Invoke-ApuGhCommandExact $GhPath @('release','view',$Tag,'--repo',$Repository,'--json','tagName,isDraft,isPrerelease,assets,url') 'gh-view-draft'
    if ($View.ExitCode -ne 0) { throw "Unable to read draft release: $($View.Text)" }
    $Release = $View.Text | ConvertFrom-Json
    if ($Release.tagName -ne $Tag -or $Release.isDraft -ne $true -or $Release.isPrerelease -ne $true) {
        throw 'Release is not the expected draft prerelease'
    }
    if (@($Release.assets).Count -ne 0) { throw 'Draft already has assets; inspect before upload' }

    $script:UploadCalled = $true
    $Upload = Invoke-ApuGhCommandExact $GhPath @('release','upload',$Tag,$ApkPath,$ApkHashPath,'--repo',$Repository) 'gh-upload-assets'
    if ($Upload.ExitCode -ne 0) { throw "Draft upload failed: $($Upload.Text)" }
    $script:UploadComplete = $true

    New-Item -ItemType Directory -Path $RemoteVerifyDir | Out-Null
    $Download = Invoke-ApuGhCommandExact $GhPath @('release','download',$Tag,'--repo',$Repository,'--pattern','APU-v11.16.16.apk','--dir',$RemoteVerifyDir) 'gh-download-verify'
    if ($Download.ExitCode -ne 0) { throw "Remote APK download failed: $($Download.Text)" }
    $RemoteApkPath = Join-Path $RemoteVerifyDir 'APU-v11.16.16.apk'
    Assert-ApuFileIdentityExact $RemoteApkPath $ExpectedApkBytes $ExpectedApkSha256 'Remote draft APK'

    $RemoteView = Invoke-ApuGhCommandExact $GhPath @('release','view',$Tag,'--repo',$Repository,'--json','isDraft,isPrerelease,assets,url') 'gh-view-after-upload'
    if ($RemoteView.ExitCode -ne 0) { throw 'Unable to verify draft after upload' }
    $RemoteRelease = $RemoteView.Text | ConvertFrom-Json
    $AssetNames = @($RemoteRelease.assets | ForEach-Object { $_.name })
    if ($AssetNames -notcontains 'APU-v11.16.16.apk' -or $AssetNames -notcontains 'APU-v11.16.16.apk.sha256') {
        throw "Expected remote assets missing: $($AssetNames -join ',')"
    }
    if ($RemoteRelease.isDraft -ne $true -or $RemoteRelease.isPrerelease -ne $true) {
        throw 'Draft changed publication state unexpectedly'
    }

    $script:RemoteVerified = $true
    Save-ApuDraftUploadStateExact 'PASS_DRAFT_UPLOAD_REMOTE_VERIFIED' $null
    $StateHash = (Get-FileHash -LiteralPath $StatePath -Algorithm SHA256).Hash.ToUpperInvariant()
    Write-Host 'APU v11.16.16 DRAFT ASSET UPLOAD PASS; REMOTE APK HASH VERIFIED; NOT YET PUBLISHED'
    Write-Host "State: $StatePath"
    Write-Host "State SHA-256: $StateHash"
}
catch {
    $Failure = $_.Exception.Message
    try { Save-ApuDraftUploadStateExact 'INCOMPLETE_DO_NOT_REPEAT' $Failure } catch {}
    throw
}
