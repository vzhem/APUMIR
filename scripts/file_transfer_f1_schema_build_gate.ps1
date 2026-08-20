$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot

$ExpectedSource = "eade81c122035b3ce4f6a092b8b402a94df84d66"
$ExpectedNative = "1D6478B21FDE3D4856E439A575268D0314BA078C79D842AC0D039512B3554B23"
$ExpectedBinding = "A697399686E60292D5C2EEFBE207D143602E0443782ABE8FFFFF91B8F93325E7"
$Jdk = "C:\Program Files\Android\Android Studio\jbr"
$Prefix = Join-Path $env:TEMP "apu-file-transfer-f1-schema-build"
$StatePath = "$Prefix-state.json"
$GradleLog = "$Prefix-gradle.log"

$Existing = @(@($StatePath, $GradleLog) | Where-Object { Test-Path $_ })
if ($Existing.Count -ne 0) { throw "File F1 schema build already attempted; preserve evidence" }
$Head = ((& git rev-parse HEAD) -join "").Trim()
& git diff --quiet $ExpectedSource $Head -- android-app rust-core
if ($LASTEXITCODE -ne 0) { throw "Source mismatch" }

$NativePath = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$BindingPath = "android-app/app/src/main/java/uniffi/p2p_core/p2p_core.kt"
$Status = @(& git status --porcelain=v1 --untracked-files=all)
if ($Status.Count -ne 1 -or $Status[0] -ne " M $NativePath") { throw "Unexpected worktree" }
if ((Get-FileHash $NativePath -Algorithm SHA256).Hash -ne $ExpectedNative) { throw "Native mismatch" }
if ((Get-FileHash $BindingPath -Algorithm SHA256).Hash -ne $ExpectedBinding) { throw "Binding mismatch" }

$Outcome = "INCOMPLETE_DO_NOT_REPEAT"
$Failure = $null
$AppHash = $null
$AppSize = $null
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
        & .\gradlew.bat --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest *>&1 |
            Tee-Object $GradleLog | Out-Host
        $Code = $LASTEXITCODE
        $ErrorActionPreference = $OldPreference
        if ($Code -ne 0) { throw "Gradle schema/test build failed: $Code" }
    } finally { Pop-Location }

    $App = Join-Path $RepoRoot "android-app/app/build/outputs/apk/debug/app-debug.apk"
    $Test = Join-Path $RepoRoot "android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    $AppSize = (Get-Item $App).Length
    $AppHash = (Get-FileHash $App -Algorithm SHA256).Hash
    $TestSize = (Get-Item $Test).Length
    $TestHash = (Get-FileHash $Test -Algorithm SHA256).Hash
    $Outcome = "PASS"
} catch {
    $Failure = $_.Exception.Message
    throw
} finally {
    [ordered]@{
        schema = 1
        purpose = "Secure File Transfer F1 additive Room schema and migration test build"
        outcome = $Outcome
        failure = $Failure
        sourceCommit = $ExpectedSource
        databaseFromVersion = 5
        databaseToVersion = 6
        appApkSize = $AppSize
        appApkSha256 = $AppHash
        testApkSize = $TestSize
        testApkSha256 = $TestHash
        adbUsed = $false
        phonesChanged = $false
    } | ConvertTo-Json -Depth 5 | Set-Content $StatePath -Encoding UTF8
    $StateHash = (Get-FileHash $StatePath -Algorithm SHA256).Hash
    Write-Host "`nOutcome: $Outcome"
    Write-Host "State: $StatePath"
    Write-Host "State SHA256: $StateHash"
    Write-Host "App APK: $AppSize / $AppHash"
    Write-Host "Test APK: $TestSize / $TestHash"
    Write-Host "ADB/phones: False/False"
}
if ($Outcome -ne "PASS") { throw "File F1 schema build failed" }
Write-Host "FILE TRANSFER F1 SCHEMA BUILD PASS"
