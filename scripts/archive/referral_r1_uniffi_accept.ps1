$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot

$BindingPath = "android-app/app/src/main/java/uniffi/p2p_core/p2p_core.kt"
$NativePath = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$ApkPath = "android-app/app/build/outputs/apk/debug/app-debug.apk"
$StatePath = Join-Path $env:TEMP "apu-referral-r1-uniffi-build-state.json"

$ExpectedStateHash = "F21D66C5566EEE12C753C49997AC9A288C3239E14791522F7D111BECB1F9B811"
$ExpectedNativeHash = "836B009E903E8BAF6A4E5F478954B6A46723DDE18FF0B83DF8D8AD03F8BB391A"
$ExpectedBindingHash = "B123242009C7E3D1387BE7EEC982FA4EF1971F99BAD42D97F068E478E971F9AA"
$ExpectedApkHash = "96DA15A9C5840CB2C4ACD74B09E806247AE3F37ECADC6ECB73ECA618D9687721"

if (-not (Test-Path $StatePath)) { throw "R1 build state missing" }
if ((Get-FileHash $StatePath -Algorithm SHA256).Hash -ne $ExpectedStateHash) {
    throw "R1 build state mismatch"
}
$State = Get-Content $StatePath -Raw | ConvertFrom-Json
if ($State.outcome -ne "PASS" -or $State.adbUsed -ne $false -or $State.phonesChanged -ne $false) {
    throw "R1 build state is not an offline PASS"
}
if ((Get-FileHash $NativePath -Algorithm SHA256).Hash -ne $ExpectedNativeHash) {
    throw "Native artifact mismatch"
}
if ((Get-FileHash $BindingPath -Algorithm SHA256).Hash -ne $ExpectedBindingHash) {
    throw "Generated binding mismatch"
}
if ((Get-FileHash $ApkPath -Algorithm SHA256).Hash -ne $ExpectedApkHash) {
    throw "APK mismatch"
}

$ExpectedStatus = @(
    " M $BindingPath",
    " M $NativePath"
)
$Status = @(& git status --porcelain=v1 --untracked-files=all)
if ($Status.Count -ne $ExpectedStatus.Count) { throw "Unexpected worktree item count" }
for ($Index = 0; $Index -lt $ExpectedStatus.Count; $Index++) {
    if ($Status[$Index] -ne $ExpectedStatus[$Index]) { throw "Unexpected worktree" }
}

$BindingText = Get-Content $BindingPath -Raw
foreach ($Marker in @(
    "createReferralInviteToken",
    "verifyReferralInviteToken",
    "verifiedReferralInviterNodeId"
)) {
    if ($BindingText -notmatch $Marker) { throw "Generated binding marker missing: $Marker" }
}

& git diff --check -- $BindingPath
if ($LASTEXITCODE -ne 0) { throw "Generated binding whitespace check failed" }
& git add -- $BindingPath
if ($LASTEXITCODE -ne 0) { throw "Could not stage generated binding" }

$Cached = @(& git diff --cached --name-only)
if ($Cached.Count -ne 1 -or $Cached[0] -ne $BindingPath) {
    throw "Only generated Kotlin binding may be committed"
}

& git commit -m "chore: regenerate referral token UniFFI binding"
if ($LASTEXITCODE -ne 0) { throw "Generated binding commit failed" }
& git push origin arena/01a0149e-apumir
if ($LASTEXITCODE -ne 0) { throw "Generated binding push failed" }

Write-Host "Committed binding: $ExpectedBindingHash"
Write-Host "Native remains uncommitted: $ExpectedNativeHash"
Write-Host "ADB/phones: False/False"
Write-Host "REFERRAL R1 UNIFFI ACCEPT PASS"
