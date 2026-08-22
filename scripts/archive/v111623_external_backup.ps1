$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$Repo = "C:\APU-M8"
$BackupRoot = "E:\APU_BACKUP_v11.16.23_20260819"
$ExpectedUsbSerial = "2240331293279315997"
$ExpectedReleaseCommit = "ddbaabd03be774b7129eae2745b67a75a85f41aa"
$ExpectedTagTarget = "09f7f52fe3822c154b4dcfb7cbdf524d529e17b6"
$ExpectedApkHash = "85480D5CAF57B9318986BA9E61F9A2A68B38DDD814C683B5949D8E22E7EA9A68"
$ExpectedApkSize = 22796416
$ExpectedNativeHash = "6C3C12EA5AADB06A1DF69F0A1B377B7BB6EC6A49456A9082BF4BBDFEA8B36F78"
$ReleaseStaging = "C:\APU-RELEASE-v11.16.23"
$ReleaseState = Join-Path $env:TEMP "apu-v111623-stable-release-build-state.json"
$PreviousBackup = "E:\APU_RECOVERY_20260818_M8C3"
$Rehearsal = Join-Path $env:TEMP "apu-v111623-backup-rehearsal"

if (Test-Path -LiteralPath $BackupRoot) { throw "Latest backup already exists; do not overwrite: $BackupRoot" }
if (Test-Path -LiteralPath $Rehearsal) { throw "Restore rehearsal path already exists: $Rehearsal" }

$Disk = Get-Disk | Where-Object { $_.SerialNumber -and $_.SerialNumber.Trim() -eq $ExpectedUsbSerial }
if (@($Disk).Count -ne 1 -or $Disk.BusType -ne "USB" -or $Disk.IsOffline -or $Disk.IsReadOnly) { throw "Verified USB disk unavailable" }
$Partition = Get-Partition -DriveLetter E
$Volume = Get-Volume -DriveLetter E
if ($Partition.DiskNumber -ne $Disk.Number -or $Volume.FileSystemLabel -ne "APU_RECOVERY" -or $Volume.FileSystem -ne "NTFS" -or $Volume.HealthStatus -ne "Healthy") { throw "E: identity/health mismatch" }
if (-not (Test-Path -LiteralPath $PreviousBackup -PathType Container)) { throw "Previous verified backup missing" }
$PreviousState = Join-Path $PreviousBackup "metadata\backup-state.json"
$PreviousStateHash = (Get-FileHash -LiteralPath $PreviousState -Algorithm SHA256).Hash
if ($PreviousStateHash -ne "C8DC7311F049E17C1F75D63AF28A6841B608CCAEDB24E205ED5F6F736D4BEAAA") { throw "Previous backup state changed" }

Set-Location $Repo
& git merge-base --is-ancestor $ExpectedReleaseCommit HEAD
if ($LASTEXITCODE -ne 0) { throw "Publication commit is not in Windows HEAD" }
$Status = @(& git status --porcelain=v1 --untracked-files=all)
if ($Status.Count -ne 1 -or $Status[0] -ne " M android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so") { throw "Unexpected source worktree" }
$Native = Join-Path $Repo "android-app\app\src\main\jniLibs\arm64-v8a\libp2p_core.so"
if ((Get-FileHash $Native -Algorithm SHA256).Hash -ne $ExpectedNativeHash) { throw "Tested native changed" }
$Apk = Join-Path $ReleaseStaging "APU-v11.16.23.apk"
$Checksum = Join-Path $ReleaseStaging "APU-v11.16.23.apk.sha256"
if ((Get-Item $Apk).Length -ne $ExpectedApkSize -or (Get-FileHash $Apk -Algorithm SHA256).Hash -ne $ExpectedApkHash) { throw "Release APK mismatch" }
if (-not (Test-Path $Checksum) -or -not (Test-Path $ReleaseState)) { throw "Release checksum/state missing" }

# Fetch only the immutable published tag, then prove its target.
& git fetch origin "refs/tags/v11.16.23:refs/tags/v11.16.23"
if ($LASTEXITCODE -ne 0) { throw "Cannot fetch published tag" }
if ((& git rev-parse "v11.16.23^{commit}").Trim() -ne $ExpectedTagTarget) { throw "Published tag target mismatch" }

$Dirs = @("release", "source", "evidence", "metadata")
New-Item -ItemType Directory -Path $BackupRoot | Out-Null
foreach ($Dir in $Dirs) { New-Item -ItemType Directory -Path (Join-Path $BackupRoot $Dir) | Out-Null }

Copy-Item $Apk (Join-Path $BackupRoot "release\APU-v11.16.23.apk")
Copy-Item $Checksum (Join-Path $BackupRoot "release\APU-v11.16.23.apk.sha256")
Copy-Item $ReleaseState (Join-Path $BackupRoot "metadata\release-build-state.json")
Copy-Item $Native (Join-Path $BackupRoot "source\libp2p_core-arm64-v8a.so")

$SourceZip = Join-Path $BackupRoot "source\APUMIR-v11.16.23-source.zip"
$Bundle = Join-Path $BackupRoot "source\APUMIR-v11.16.23-all-refs.bundle"
& git archive --format=zip --output=$SourceZip HEAD
if ($LASTEXITCODE -ne 0) { throw "git archive failed" }
& git bundle create $Bundle --all
if ($LASTEXITCODE -ne 0) { throw "git bundle failed" }
$OldPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$BundleVerify = @(& git bundle verify $Bundle 2>&1 | ForEach-Object { $_.ToString() })
$BundleExit = $LASTEXITCODE
$ErrorActionPreference = $OldPreference
$BundleVerify | Set-Content (Join-Path $BackupRoot "metadata\git-bundle-verify.txt") -Encoding UTF8
if ($BundleExit -ne 0) { throw "git bundle verify failed" }

& git clone --quiet $Bundle $Rehearsal
if ($LASTEXITCODE -ne 0) { throw "bundle restore rehearsal clone failed" }
$RehearsalTag = (& git -C $Rehearsal rev-parse "v11.16.23^{commit}").Trim()
if ($RehearsalTag -ne $ExpectedTagTarget) { throw "restore rehearsal tag mismatch" }

& git status --porcelain=v1 --untracked-files=all | Set-Content (Join-Path $BackupRoot "metadata\windows-git-status.txt") -Encoding UTF8
& git log -30 --oneline --decorate | Set-Content (Join-Path $BackupRoot "metadata\git-log.txt") -Encoding UTF8
@{
    tag = "v11.16.23"; type = "stable"; latest = $true; draft = $false; prerelease = $false
    tagTarget = $ExpectedTagTarget; publicationCommit = $ExpectedReleaseCommit
    url = "https://github.com/vzhem/APUMIR/releases/tag/v11.16.23"
    apkSize = $ExpectedApkSize; apkSha256 = $ExpectedApkHash
    githubApkDigest = "sha256:85480d5caf57b9318986ba9e61f9a2a68b38ddd814c683b5949d8e22e7ea9a68"
    checksumAssetSize = 86; githubChecksumDigest = "sha256:c83d9d3825c125baac6471141b0819f7813487519283bd6a1870bfe09ba2800c"
} | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $BackupRoot "metadata\github-release.json") -Encoding UTF8

# Copy M8/release evidence except two huge compiler caches already retained by previous backup.
$ExcludedEvidence = @(
    "apu-m8-c3-stale-target-lnk1207",
    "apu-m8-c3-bindgen-tool-target",
    "apu-v111623-backup-rehearsal"
)
$EvidenceItems = @(Get-ChildItem $env:TEMP -Force | Where-Object {
    ($_.Name -like "apu-m8-*" -or $_.Name -like "apu-v111623-*") -and $_.Name -notin $ExcludedEvidence
})
foreach ($Item in $EvidenceItems) {
    $Destination = Join-Path (Join-Path $BackupRoot "evidence") $Item.Name
    if ($Item.PSIsContainer) {
        & robocopy $Item.FullName $Destination /E /COPY:DAT /DCOPY:DAT /R:1 /W:1 /XJ /NFL /NDL /NP | Out-Null
        if ($LASTEXITCODE -gt 7) { throw "Evidence robocopy failed: $($Item.Name) exit=$LASTEXITCODE" }
    } else {
        Copy-Item $Item.FullName $Destination
    }
}

$BackupStatePath = Join-Path $BackupRoot "metadata\backup-state.json"
$BackupState = [ordered]@{
    schema = 1; outcome = "COMPLETE_PENDING_FINAL_VERIFY"; completedUtc = (Get-Date).ToUniversalTime().ToString("o")
    version = "v11.16.23"; releaseType = "stable/Latest"
    usbDiskNumber = $Disk.Number; usbSerial = $ExpectedUsbSerial; usbLabel = $Volume.FileSystemLabel
    previousBackup = $PreviousBackup; previousBackupStateSha256 = $PreviousStateHash
    sourceHead = (& git rev-parse HEAD).Trim(); tagTarget = $ExpectedTagTarget
    apkSha256 = $ExpectedApkHash; apkSize = $ExpectedApkSize; nativeSha256 = $ExpectedNativeHash
    evidenceTopLevelItems = $EvidenceItems.Count; excludedCachesAlreadyInPrevious = $ExcludedEvidence
    gitBundleVerified = $true; restoreRehearsalTagTarget = $RehearsalTag
    previousBackupDeleted = $false; diskFormatted = $false
}
$BackupState | ConvertTo-Json -Depth 8 | Set-Content $BackupStatePath -Encoding UTF8

# Hash every deliverable except the manifest itself, then verify every row.
$ManifestPath = Join-Path $BackupRoot "metadata\sha256-manifest.csv"
$Files = @(Get-ChildItem $BackupRoot -File -Recurse | Where-Object { $_.FullName -ne $ManifestPath })
$Manifest = foreach ($File in $Files) {
    [pscustomobject]@{
        RelativePath = $File.FullName.Substring($BackupRoot.Length).TrimStart("\")
        Length = $File.Length
        SHA256 = (Get-FileHash $File.FullName -Algorithm SHA256).Hash
    }
}
$Manifest | Export-Csv $ManifestPath -NoTypeInformation -Encoding UTF8
$Failures = @()
foreach ($Row in (Import-Csv $ManifestPath)) {
    $Path = Join-Path $BackupRoot $Row.RelativePath
    if (-not (Test-Path $Path) -or (Get-Item $Path).Length -ne [long]$Row.Length -or (Get-FileHash $Path -Algorithm SHA256).Hash -ne $Row.SHA256) { $Failures += $Row.RelativePath }
}
if ($Failures.Count -gt 0) { throw "Backup hash verification failed: $($Failures -join '; ')" }

# Finalize state, regenerate the manifest row for its changed bytes, and verify again.
$BackupState.outcome = "PASS"
$BackupState.completedUtc = (Get-Date).ToUniversalTime().ToString("o")
$BackupState | ConvertTo-Json -Depth 8 | Set-Content $BackupStatePath -Encoding UTF8
$Files = @(Get-ChildItem $BackupRoot -File -Recurse | Where-Object { $_.FullName -ne $ManifestPath })
$Manifest = foreach ($File in $Files) {
    [pscustomobject]@{
        RelativePath = $File.FullName.Substring($BackupRoot.Length).TrimStart("\")
        Length = $File.Length
        SHA256 = (Get-FileHash $File.FullName -Algorithm SHA256).Hash
    }
}
$Manifest | Export-Csv $ManifestPath -NoTypeInformation -Encoding UTF8
$Failures = @()
foreach ($Row in (Import-Csv $ManifestPath)) {
    $Path = Join-Path $BackupRoot $Row.RelativePath
    if (-not (Test-Path $Path) -or (Get-Item $Path).Length -ne [long]$Row.Length -or (Get-FileHash $Path -Algorithm SHA256).Hash -ne $Row.SHA256) { $Failures += $Row.RelativePath }
}
if ($Failures.Count -gt 0) { throw "Final backup hash verification failed: $($Failures -join '; ')" }

$BackupStateHash = (Get-FileHash $BackupStatePath -Algorithm SHA256).Hash
$ManifestHash = (Get-FileHash $ManifestPath -Algorithm SHA256).Hash
$ApkBackup = Join-Path $BackupRoot "release\APU-v11.16.23.apk"
if ((Get-FileHash $ApkBackup -Algorithm SHA256).Hash -ne $ExpectedApkHash) { throw "Backup APK final mismatch" }

Write-Host "`n=== V11.16.23 EXTERNAL BACKUP RESULT ==="
Write-Host "Outcome: PASS"
Write-Host "Path: $BackupRoot"
Write-Host "Files verified: $($Manifest.Count)"
Write-Host "Evidence items: $($EvidenceItems.Count)"
Write-Host "APK SHA256: $ExpectedApkHash"
Write-Host "Bundle verified/rehearsed: True/True"
Write-Host "Previous retained: True"
Write-Host "Backup state SHA256: $BackupStateHash"
Write-Host "Manifest SHA256: $ManifestHash"
Get-Volume -DriveLetter E | Select-Object DriveLetter,FileSystemLabel,FileSystem,HealthStatus,OperationalStatus,@{Name="FreeGB";Expression={[math]::Round($_.SizeRemaining/1GB,2)}} | Format-List
fsutil dirty query E:
Write-Host "APU V11.16.23 EXTERNAL BACKUP PASS"
