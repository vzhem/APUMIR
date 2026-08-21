$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot

$ExpectedSource = "1ce7c13594c247f717139756492882d2a8c0361c"
$ExpectedNative = "1D6478B21FDE3D4856E439A575268D0314BA078C79D842AC0D039512B3554B23"
$ExpectedBinding = "A697399686E60292D5C2EEFBE207D143602E0443782ABE8FFFFF91B8F93325E7"
$ExpectedApp = "616E5CDC6AAA86F8A82C19BCCD2A297AD2A4C5775C7082F14ED44343B095E5E7"
$FailedState = Join-Path $env:TEMP "apu-file-transfer-f1-stas-migration-phone-state.json"
$FailedLog = Join-Path $env:TEMP "apu-file-transfer-f1-stas-migration-phone.log"
$ExpectedFailedState = "05927DDB3A8F83F211ACDE50E249BC59DCCAD7468C6E771D25B4B3BD56D40CAE"
$ExpectedFailedLog = "9D6A2EACCDD0D07DACEF927488204AE1452DB9EE09ABFC4E47015F33C7FA51AC"
$Jdk = "C:\Program Files\Android\Android Studio\jbr"
$Prefix = Join-Path $env:TEMP "apu-file-transfer-f1-identity-recovery-build"
$StatePath = "$Prefix-state.json"
$GradleLog = "$Prefix-gradle.log"

$Existing = @(@($StatePath, $GradleLog) | Where-Object { Test-Path $_ })
if ($Existing.Count -ne 0) { throw "Identity recovery build already attempted; preserve evidence" }
if ((Get-FileHash $FailedState -Algorithm SHA256).Hash -ne $ExpectedFailedState -or
    (Get-FileHash $FailedLog -Algorithm SHA256).Hash -ne $ExpectedFailedLog) {
    throw "Failed migration evidence mismatch"
}
$FailureText = Get-Content $FailedLog -Raw
if ($FailureText -notmatch "Expected identity hash: 1600df8ca0acdcfc6be40910e2e5eee0" -or
    $FailureText -notmatch "found: 451378b646892da8c31c499c2f93fff5") {
    throw "Unexpected migration failure"
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
    $env:GITHUB_REF_NAME = "v11.16.35"
    Push-Location (Join-Path $RepoRoot "android-app")
    try {
        $OldPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        & .\gradlew.bat --no-daemon :app:assembleDebugAndroidTest *>&1 |
            Tee-Object $GradleLog | Out-Host
        $Code = $LASTEXITCODE
        $ErrorActionPreference = $OldPreference
        if ($Code -ne 0) { throw "Gradle identity recovery build failed: $Code" }
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
        purpose = "File F1 Room identity recovery instrumentation build"
        outcome = $Outcome
        failure = $Failure
        sourceCommit = $ExpectedSource
        failedStateSha256 = $ExpectedFailedState
        failedLogSha256 = $ExpectedFailedLog
        appApkSha256 = $ExpectedApp
        testApkSize = $TestSize
        testApkSha256 = $TestHash
        adbUsed = $false
        phonesChanged = $false
    } | ConvertTo-Json -Depth 5 | Set-Content $StatePath -Encoding UTF8
    $StateHash = (Get-FileHash $StatePath -Algorithm SHA256).Hash
    Write-Host "`nOutcome: $Outcome"
    Write-Host "State: $StatePath"
    Write-Host "State SHA256: $StateHash"
    Write-Host "App APK unchanged: $ExpectedApp"
    Write-Host "Test APK: $TestSize / $TestHash"
    Write-Host "ADB/phones: False/False"
}
if ($Outcome -ne "PASS") { throw "Identity recovery build failed" }
Write-Host "FILE TRANSFER F1 IDENTITY RECOVERY BUILD PASS"
