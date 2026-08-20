$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot

$BindingPath = "android-app/app/src/main/java/uniffi/p2p_core/p2p_core.kt"
$NativePath = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$AppPath = "android-app/app/build/outputs/apk/debug/app-debug.apk"
$TestPath = "android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
$StatePath = Join-Path $env:TEMP "apu-file-transfer-f0-android-runtime-build-state.json"
$RemoteRef = "refs/heads/arena/01a0149e-apumir"

$ExpectedState = "8D556A999089C239A82CA696F1D318491EB6062D12EC3CA723550944E9B195F2"
$ExpectedNative = "1D6478B21FDE3D4856E439A575268D0314BA078C79D842AC0D039512B3554B23"
$ExpectedBinding = "A697399686E60292D5C2EEFBE207D143602E0443782ABE8FFFFF91B8F93325E7"
$ExpectedApp = "3888C58DB43992E18C5EFDDA909377DD10EEA0ACE54A1F471D2D7A708A3D0245"
$ExpectedTest = "C7637E4CC609B55EF910AE5DB87442D6D79DAF5AFDE572E0A66FECE88C270D5D"

if (-not (Test-Path $StatePath) -or
    (Get-FileHash $StatePath -Algorithm SHA256).Hash -ne $ExpectedState) {
    throw "File runtime build state mismatch"
}
$State = Get-Content $StatePath -Raw | ConvertFrom-Json
if ($State.outcome -ne "PASS" -or $State.adbUsed -ne $false -or $State.phonesChanged -ne $false) {
    throw "File runtime build is not an offline PASS"
}
if ((Get-FileHash $NativePath -Algorithm SHA256).Hash -ne $ExpectedNative -or
    (Get-FileHash $BindingPath -Algorithm SHA256).Hash -ne $ExpectedBinding -or
    (Get-FileHash $AppPath -Algorithm SHA256).Hash -ne $ExpectedApp -or
    (Get-FileHash $TestPath -Algorithm SHA256).Hash -ne $ExpectedTest) {
    throw "File runtime artifact mismatch"
}

$ExpectedStatus = @(" M $BindingPath", " M $NativePath")
$Status = @(& git status --porcelain=v1 --untracked-files=all)
if ($Status.Count -ne 2 -or $Status[0] -ne $ExpectedStatus[0] -or $Status[1] -ne $ExpectedStatus[1]) {
    throw "Unexpected worktree"
}
$BindingText = Get-Content $BindingPath -Raw
if ($BindingText -notmatch "fileTransferCryptoSelfTest") { throw "Generated marker missing" }
& git diff --check -- $BindingPath
if ($LASTEXITCODE -ne 0) { throw "Generated binding whitespace check failed" }

& git add -- $BindingPath
if ($LASTEXITCODE -ne 0) { throw "Could not stage generated binding" }
$Cached = @(& git diff --cached --name-only)
if ($Cached.Count -ne 1 -or $Cached[0] -ne $BindingPath) {
    throw "Only generated binding may be committed"
}
& git commit -m "chore: regenerate file transfer UniFFI binding"
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
Write-Host "Transport/ADB/phones: False/False/False"
Write-Host "FILE TRANSFER F0 UNIFFI ACCEPT PASS"
