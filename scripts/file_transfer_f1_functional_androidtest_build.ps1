$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot

$ExpectedSource = "fcf566b9a449a1773101420a648eb83e78bffea5"
$ExpectedNative = "95D96A416F0B8A9404D59D19AE749095ADE728B0C14BC943784DB00DA33B5D80"
$ExpectedBinding = "FA0743536328C1827EDBD9D380048B90F1B9CE1C7861D01E1B7C20A02F6C4493"
$ExpectedApp = "2EC36B50EB9AB14D78A9D9FCDE12FE6DAC70749D928339DA5C38E4D4C0A3930B"
$PriorState = Join-Path $env:TEMP "apu-file-transfer-f1-crypto-api-build-state.json"
$ExpectedPriorState = "D56BECDE4891FA3665BFA5CE1C04C7CE4A3B794A846633693886641A1D1043CB"
$Jdk = "C:\Program Files\Android\Android Studio\jbr"
$Prefix = Join-Path $env:TEMP "apu-file-transfer-f1-functional-androidtest-build"
$StatePath = "$Prefix-state.json"
$GradleLog = "$Prefix-gradle.log"

$Existing = @(@($StatePath, $GradleLog) | Where-Object { Test-Path $_ })
if ($Existing.Count -ne 0) { throw "Functional androidTest build already attempted; preserve evidence" }
if ((Get-FileHash $PriorState -Algorithm SHA256).Hash -ne $ExpectedPriorState) {
    throw "Prior crypto API build state mismatch"
}
$Head = ((& git rev-parse HEAD) -join "").Trim()
& git diff --quiet $ExpectedSource $Head -- android-app rust-core
if ($LASTEXITCODE -ne 0) { throw "Source mismatch" }

$NativePath = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$BindingPath = "android-app/app/src/main/java/uniffi/p2p_core/p2p_core.kt"
$AppPath = "android-app/app/build/outputs/apk/debug/app-debug.apk"
$Status = @(& git status --porcelain=v1 --untracked-files=all)
if ($Status.Count -ne 1 -or $Status[0] -ne " M $NativePath") { throw "Unexpected worktree" }
if ((Get-FileHash $NativePath -Algorithm SHA256).Hash -ne $ExpectedNative -or
    (Get-FileHash $BindingPath -Algorithm SHA256).Hash -ne $ExpectedBinding -or
    (Get-FileHash $AppPath -Algorithm SHA256).Hash -ne $ExpectedApp) {
    throw "Artifact baseline mismatch"
}

$Outcome = "INCOMPLETE_DO_NOT_REPEAT"
$Failure = $null
$TestHash = $null
$TestSize = $null
try {
    $env:JAVA_HOME = $Jdk
    $env:Path = "$Jdk\bin;" + $env:Path
    $env:GITHUB_REF_NAME = "v11.16.38"
    Push-Location (Join-Path $RepoRoot "android-app")
    try {
        $OldPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        & .\gradlew.bat --no-daemon :app:assembleDebugAndroidTest *>&1 |
            Tee-Object $GradleLog | Out-Host
        $Code = $LASTEXITCODE
        $ErrorActionPreference = $OldPreference
        if ($Code -ne 0) { throw "Gradle functional androidTest build failed: $Code" }
    } finally { Pop-Location }
    if ((Get-FileHash $AppPath -Algorithm SHA256).Hash -ne $ExpectedApp) {
        throw "App APK changed during test-only build"
    }
    $TestPath = Join-Path $RepoRoot "android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    $TestSize = (Get-Item $TestPath).Length
    $TestHash = (Get-FileHash $TestPath -Algorithm SHA256).Hash
    $Outcome = "PASS"
} catch {
    $Failure = $_.Exception.Message
    throw
} finally {
    [ordered]@{
        schema = 1
        purpose = "File F1 isolated functional Android crypto pipeline test build"
        outcome = $Outcome
        failure = $Failure
        sourceCommit = $ExpectedSource
        priorCryptoApiStateSha256 = $ExpectedPriorState
        appApkSha256 = $ExpectedApp
        testApkSize = $TestSize
        testApkSha256 = $TestHash
        keyPersistent = $false
        productionStoreTouched = $false
        transportWired = $false
        adbUsed = $false
        phonesChanged = $false
    } | ConvertTo-Json -Depth 5 | Set-Content $StatePath -Encoding UTF8
    $StateHash = (Get-FileHash $StatePath -Algorithm SHA256).Hash
    Write-Host "`nOutcome: $Outcome"
    Write-Host "State: $StatePath"
    Write-Host "State SHA256: $StateHash"
    Write-Host "App APK unchanged: $ExpectedApp"
    Write-Host "Test APK: $TestSize / $TestHash"
    Write-Host "Persistent key/production store/transport/ADB/phones: False/False/False/False/False"
}
if ($Outcome -ne "PASS") { throw "Functional Android test build failed" }
Write-Host "FILE TRANSFER F1 FUNCTIONAL ANDROIDTEST BUILD PASS"
