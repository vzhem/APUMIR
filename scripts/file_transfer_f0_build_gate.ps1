$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot

$ExpectedSource = "b4140d8e276ea47763fb4cd969c22294c260a43d"
$OldNative = "836B009E903E8BAF6A4E5F478954B6A46723DDE18FF0B83DF8D8AD03F8BB391A"
$ExpectedBinding = "B123242009C7E3D1387BE7EEC982FA4EF1971F99BAD42D97F068E478E971F9AA"
$Jdk = "C:\Program Files\Android\Android Studio\jbr"
$Prefix = Join-Path $env:TEMP "apu-file-transfer-f0-build"
$StatePath = "$Prefix-state.json"
$CheckLog = "$Prefix-check.log"
$RustLog = "$Prefix-rust.log"
$GradleLog = "$Prefix-gradle.log"

$Existing = @(@($StatePath, $CheckLog, $RustLog, $GradleLog) | Where-Object { Test-Path $_ })
if ($Existing.Count -ne 0) { throw "File F0 build already attempted; preserve evidence" }
$Head = ((& git rev-parse HEAD) -join "").Trim()
& git diff --quiet $ExpectedSource $Head -- rust-core android-app
if ($LASTEXITCODE -ne 0) { throw "Source mismatch" }

$NativePath = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$BindingPath = "android-app/app/src/main/java/uniffi/p2p_core/p2p_core.kt"
$Status = @(& git status --porcelain=v1 --untracked-files=all)
if ($Status.Count -ne 1 -or $Status[0] -ne " M $NativePath") { throw "Unexpected worktree" }
if ((Get-FileHash $NativePath -Algorithm SHA256).Hash -ne $OldNative) { throw "Native baseline mismatch" }
if ((Get-FileHash $BindingPath -Algorithm SHA256).Hash -ne $ExpectedBinding) { throw "Binding mismatch" }

$Outcome = "INCOMPLETE_DO_NOT_REPEAT"
$Failure = $null
$NewNative = $null
$ApkHash = $null
$ApkSize = $null
try {
    $Jni = Join-Path $RepoRoot "android-app/app/src/main/jniLibs"
    Push-Location (Join-Path $RepoRoot "rust-core")
    try {
        $OldPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        & cargo ndk -t arm64-v8a check --tests --features mqtt-dual-broker *>&1 |
            Tee-Object $CheckLog | Out-Host
        $CheckCode = $LASTEXITCODE
        $ErrorActionPreference = $OldPreference
        if ($CheckCode -ne 0) { throw "Rust Android test typecheck failed: $CheckCode" }

        $OldPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        & cargo ndk -t arm64-v8a -o $Jni build --release --features mqtt-dual-broker *>&1 |
            Tee-Object $RustLog | Out-Host
        $RustCode = $LASTEXITCODE
        $ErrorActionPreference = $OldPreference
        if ($RustCode -ne 0) { throw "Rust release build failed: $RustCode" }
    } finally { Pop-Location }

    $NewNative = (Get-FileHash $NativePath -Algorithm SHA256).Hash
    if ($NewNative -eq $OldNative) { throw "Expected native change missing" }

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
        purpose = "Secure File Transfer F0 canonical manifest and chunk AEAD build"
        outcome = $Outcome
        failure = $Failure
        sourceCommit = $ExpectedSource
        testsTypecheckedForAndroid = $true
        oldNativeSha256 = $OldNative
        newNativeSha256 = $NewNative
        bindingSha256 = $ExpectedBinding
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
    Write-Host "Native: $NewNative"
    Write-Host "APK: $ApkSize / $ApkHash"
    Write-Host "Transport/ADB/phones: False/False/False"
}
if ($Outcome -ne "PASS") { throw "File transfer F0 build failed" }
Write-Host "FILE TRANSFER F0 BUILD PASS"
