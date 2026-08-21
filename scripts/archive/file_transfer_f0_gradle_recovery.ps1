$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot

$ExpectedSource = "b4140d8e276ea47763fb4cd969c22294c260a43d"
$ExpectedNative = "836B009E903E8BAF6A4E5F478954B6A46723DDE18FF0B83DF8D8AD03F8BB391A"
$ExpectedBinding = "B123242009C7E3D1387BE7EEC982FA4EF1971F99BAD42D97F068E478E971F9AA"
$PriorState = Join-Path $env:TEMP "apu-file-transfer-f0-production-recovery-01-state.json"
$PriorRustLog = Join-Path $env:TEMP "apu-file-transfer-f0-production-recovery-01-rust.log"
$ExpectedPriorState = "AD01EB9E44EC154A8B1B75DF31507069701DDE8A2ABA41A32CB9C0DA806D68FB"
$Jdk = "C:\Program Files\Android\Android Studio\jbr"
$Prefix = Join-Path $env:TEMP "apu-file-transfer-f0-gradle-recovery-02"
$StatePath = "$Prefix-state.json"
$GradleLog = "$Prefix-gradle.log"

$Existing = @(@($StatePath, $GradleLog) | Where-Object { Test-Path $_ })
if ($Existing.Count -ne 0) { throw "File F0 Gradle recovery already attempted; preserve evidence" }
if (-not (Test-Path $PriorState) -or -not (Test-Path $PriorRustLog)) {
    throw "Prior production compile evidence missing"
}
if ((Get-FileHash $PriorState -Algorithm SHA256).Hash -ne $ExpectedPriorState) {
    throw "Prior production state mismatch"
}
$Prior = Get-Content $PriorState -Raw | ConvertFrom-Json
$RustText = Get-Content $PriorRustLog -Raw
if ($Prior.outcome -ne "INCOMPLETE_DO_NOT_REPEAT" -or
    $Prior.newNativeSha256 -ne $ExpectedNative -or
    $Prior.adbUsed -ne $false -or
    $RustText -notmatch 'Finished `release` profile' -or
    $RustText -notmatch "Compiling p2p-core") {
    throw "Prior Rust production compile markers mismatch"
}
$PriorRustLogHash = (Get-FileHash $PriorRustLog -Algorithm SHA256).Hash

$Head = ((& git rev-parse HEAD) -join "").Trim()
& git diff --quiet $ExpectedSource $Head -- rust-core android-app
if ($LASTEXITCODE -ne 0) { throw "Source mismatch" }
$NativePath = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$BindingPath = "android-app/app/src/main/java/uniffi/p2p_core/p2p_core.kt"
$Status = @(& git status --porcelain=v1 --untracked-files=all)
if ($Status.Count -ne 1 -or $Status[0] -ne " M $NativePath") { throw "Unexpected worktree" }
if ((Get-FileHash $NativePath -Algorithm SHA256).Hash -ne $ExpectedNative) { throw "Native mismatch" }
if ((Get-FileHash $BindingPath -Algorithm SHA256).Hash -ne $ExpectedBinding) { throw "Binding mismatch" }

$Outcome = "INCOMPLETE_DO_NOT_REPEAT"
$Failure = $null
$ApkHash = $null
$ApkSize = $null
try {
    $env:JAVA_HOME = $Jdk
    $env:Path = "$Jdk\bin;" + $env:Path
    $env:GITHUB_REF_NAME = "v11.16.33"
    Push-Location (Join-Path $RepoRoot "android-app")
    try {
        $OldPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        & .\gradlew.bat --no-daemon :app:assembleDebug *>&1 |
            Tee-Object $GradleLog | Out-Host
        $GradleCode = $LASTEXITCODE
        $ErrorActionPreference = $OldPreference
        if ($GradleCode -ne 0) { throw "Gradle build failed: $GradleCode" }
    } finally { Pop-Location }

    $ApkPath = Join-Path $RepoRoot "android-app/app/build/outputs/apk/debug/app-debug.apk"
    $ApkSize = (Get-Item $ApkPath).Length
    $ApkHash = (Get-FileHash $ApkPath -Algorithm SHA256).Hash
    $Outcome = "PASS"
} catch {
    $Failure = $_.Exception.Message
    throw
} finally {
    [ordered]@{
        schema = 1
        purpose = "Secure File Transfer F0 Gradle recovery after successful stripped Rust compile"
        outcome = $Outcome
        failure = $Failure
        sourceCommit = $ExpectedSource
        priorStateSha256 = $ExpectedPriorState
        priorRustLogSha256 = $PriorRustLogHash
        rustProductionCompilePassed = $true
        nativeUnchangedExpected = $true
        nativeSha256 = $ExpectedNative
        bindingSha256 = $ExpectedBinding
        hostTestsRun = $false
        apkSize = $ApkSize
        apkSha256 = $ApkHash
        transportWired = $false
        adbUsed = $false
        phonesChanged = $false
    } | ConvertTo-Json -Depth 5 | Set-Content $StatePath -Encoding UTF8
    $StateHash = (Get-FileHash $StatePath -Algorithm SHA256).Hash
    Write-Host "`nOutcome: $Outcome"
    Write-Host "State: $StatePath"
    Write-Host "State SHA256: $StateHash"
    Write-Host "Native unchanged after LTO (expected): $ExpectedNative"
    Write-Host "APK: $ApkSize / $ApkHash"
    Write-Host "Host tests/transport/ADB/phones: False/False/False/False"
}
if ($Outcome -ne "PASS") { throw "File transfer F0 Gradle recovery failed" }
Write-Host "FILE TRANSFER F0 GRADLE RECOVERY PASS"
