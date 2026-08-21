$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot

$BindingPath = "android-app/app/src/main/java/uniffi/p2p_core/p2p_core.kt"
$NativePath = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$AppPath = "android-app/app/build/outputs/apk/debug/app-debug.apk"
$StatePath = Join-Path $env:TEMP "apu-file-transfer-f1-crypto-api-build-state.json"
$RemoteRef = "refs/heads/arena/01a0149e-apumir"

$ExpectedState = "D56BECDE4891FA3665BFA5CE1C04C7CE4A3B794A846633693886641A1D1043CB"
$ExpectedNative = "95D96A416F0B8A9404D59D19AE749095ADE728B0C14BC943784DB00DA33B5D80"
$ExpectedBinding = "FA0743536328C1827EDBD9D380048B90F1B9CE1C7861D01E1B7C20A02F6C4493"
$ExpectedApp = "2EC36B50EB9AB14D78A9D9FCDE12FE6DAC70749D928339DA5C38E4D4C0A3930B"

if (-not (Test-Path $StatePath) -or
    (Get-FileHash $StatePath -Algorithm SHA256).Hash -ne $ExpectedState) {
    throw "File crypto API build state mismatch"
}
$State = Get-Content $StatePath -Raw | ConvertFrom-Json
if ($State.outcome -ne "PASS" -or $State.adbUsed -ne $false -or $State.phonesChanged -ne $false) {
    throw "File crypto API build is not an offline PASS"
}
if ((Get-FileHash $NativePath -Algorithm SHA256).Hash -ne $ExpectedNative -or
    (Get-FileHash $BindingPath -Algorithm SHA256).Hash -ne $ExpectedBinding -or
    (Get-FileHash $AppPath -Algorithm SHA256).Hash -ne $ExpectedApp) {
    throw "File crypto API artifact mismatch"
}

$ExpectedStatus = @(" M $BindingPath", " M $NativePath")
$Status = @(& git status --porcelain=v1 --untracked-files=all)
if ($Status.Count -ne 2 -or $Status[0] -ne $ExpectedStatus[0] -or $Status[1] -ne $ExpectedStatus[1]) {
    throw "Unexpected worktree"
}
$BindingText = Get-Content $BindingPath -Raw
foreach ($Marker in @(
    "createFileTransferManifest",
    "parseFileTransferManifest",
    "encryptFileTransferChunk",
    "decryptFileTransferChunk"
)) {
    if ($BindingText -notmatch $Marker) { throw "Generated marker missing: $Marker" }
}
& git diff --check -- $BindingPath
if ($LASTEXITCODE -ne 0) { throw "Generated binding whitespace check failed" }

& git add -- $BindingPath
if ($LASTEXITCODE -ne 0) { throw "Could not stage generated binding" }
$Cached = @(& git diff --cached --name-only)
if ($Cached.Count -ne 1 -or $Cached[0] -ne $BindingPath) {
    throw "Only generated binding may be committed"
}
& git commit -m "chore: regenerate functional file crypto binding"
if ($LASTEXITCODE -ne 0) { throw "Generated binding commit failed" }

$Head = ((& git rev-parse HEAD) -join "").Trim()
$Parent = ((& git rev-parse "HEAD^") -join "").Trim()
$OldPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$RemoteLine = git ls-remote origin $RemoteRef
$QueryCode = $LASTEXITCODE
$ErrorActionPreference = $OldPreference
if ($QueryCode -ne 0 -or [string]::IsNullOrWhiteSpace($RemoteLine)) {
    throw "GitHub unavailable; local generated binding commit is safe"
}
$RemoteBefore = ($RemoteLine.Trim() -split "\s+")[0]
if ($RemoteBefore -ne $Parent) { throw "Remote advanced; push not attempted" }

$OldPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& git push origin "HEAD:$RemoteRef"
$PushCode = $LASTEXITCODE
$ErrorActionPreference = $OldPreference
if ($PushCode -ne 0) { throw "Generated binding push failed; local commit is safe" }

$OldPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$VerifyLine = git ls-remote origin $RemoteRef
$VerifyCode = $LASTEXITCODE
$ErrorActionPreference = $OldPreference
if ($VerifyCode -ne 0 -or [string]::IsNullOrWhiteSpace($VerifyLine)) {
    throw "Push completed but remote verification unavailable"
}
$RemoteAfter = ($VerifyLine.Trim() -split "\s+")[0]
if ($RemoteAfter -ne $Head) { throw "Remote binding commit verification failed" }

Write-Host "Remote binding commit: $RemoteAfter"
Write-Host "Binding: $ExpectedBinding"
Write-Host "Native remains uncommitted: $ExpectedNative"
Write-Host "Key vault/transport/ADB/phones: False/False/False/False"
Write-Host "FILE TRANSFER F1 CRYPTO API ACCEPT PASS"
