$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot

$ExpectedSource = "00bdf4dbbb74d6d3004d128f601d0f46b664c931"
$ExpectedNative = "95D96A416F0B8A9404D59D19AE749095ADE728B0C14BC943784DB00DA33B5D80"
$ExpectedBinding = "FA0743536328C1827EDBD9D380048B90F1B9CE1C7861D01E1B7C20A02F6C4493"
$ExpectedApp = "33CFC80617269318C711632F04E299511254CD75598C4C68061AFD7023582B52"
$FailedState = Join-Path $env:TEMP "apu-file-transfer-f2-anna-stas-recovery-01-state.json"
$FailedCleanup = Join-Path $env:TEMP "apu-file-transfer-f2-anna-stas-recovery-01-cleanup.log"
$FailedSender = Join-Path $env:TEMP "apu-file-transfer-f2-anna-stas-recovery-01-sender.log"
$FailedReceiver = Join-Path $env:TEMP "apu-file-transfer-f2-anna-stas-recovery-01-receiver.stdout.log"
$ExpectedFailedState = "EA0F77BB57788B63937519FC7E1BD54E48CCFC830905D84C9C2FCAF3C1DF2114"
$ExpectedFailedCleanup = "4CE66DE73EFBB306F96AF67429C25DAEC1064462F4BB396BAF531B8DE9B94E33"
$ExpectedFailedSender = "EBAEA87A4B3E34D591436FB4429F29B7AC1C85621A59484C04E94176739D1435"
$ExpectedFailedReceiver = "05C4950AF4D81A8AD6602E55DB52FDF9626FC8FCA4663CFEC93FEEC140A7214A"
$Jdk = "C:\Program Files\Android\Android Studio\jbr"
$Prefix = Join-Path $env:TEMP "apu-file-transfer-f2-direct-tunnel-build"
$StatePath = "$Prefix-state.json"
$GradleLog = "$Prefix-gradle.log"

$Existing = @(@($StatePath, $GradleLog) | Where-Object { Test-Path $_ })
if ($Existing.Count -ne 0) { throw "Direct tunnel build already attempted; preserve evidence" }
if ((Get-FileHash $FailedState -Algorithm SHA256).Hash -ne $ExpectedFailedState -or
    (Get-FileHash $FailedCleanup -Algorithm SHA256).Hash -ne $ExpectedFailedCleanup -or
    (Get-FileHash $FailedSender -Algorithm SHA256).Hash -ne $ExpectedFailedSender -or
    (Get-FileHash $FailedReceiver -Algorithm SHA256).Hash -ne $ExpectedFailedReceiver) {
    throw "Recovery-01 evidence mismatch"
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
    $env:GITHUB_REF_NAME = "v11.16.41"
    Push-Location (Join-Path $RepoRoot "android-app")
    try {
        $OldPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        & .\gradlew.bat --no-daemon :app:assembleDebugAndroidTest *>&1 |
            Tee-Object $GradleLog | Out-Host
        $Code = $LASTEXITCODE
        $ErrorActionPreference = $OldPreference
        if ($Code -ne 0) { throw "Gradle direct tunnel test build failed: $Code" }
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
        purpose = "File F2 Anna-Stas ADB-tunneled direct TCP harness build"
        outcome = $Outcome
        failure = $Failure
        sourceCommit = $ExpectedSource
        recovery01StateSha256 = $ExpectedFailedState
        appApkSha256 = $ExpectedApp
        testApkSize = $TestSize
        testApkSha256 = $TestHash
        productionNetworkPath = $false
        productionKeyExchange = $false
        adbUsed = $false
        phonesChanged = $false
    } | ConvertTo-Json -Depth 5 | Set-Content $StatePath -Encoding UTF8
    $StateHash = (Get-FileHash $StatePath -Algorithm SHA256).Hash
    Write-Host "`nOutcome: $Outcome"
    Write-Host "State: $StatePath"
    Write-Host "State SHA256: $StateHash"
    Write-Host "App APK unchanged: $ExpectedApp"
    Write-Host "Test APK: $TestSize / $TestHash"
    Write-Host "Production network/key exchange/ADB/phones: False/False/False/False"
}
if ($Outcome -ne "PASS") { throw "Direct tunnel build failed" }
Write-Host "FILE TRANSFER F2 DIRECT TUNNEL BUILD PASS"
