$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot

$ExpectedSource = "e22aeed52bede5748d16c36a343a8a3e1fc2f851"
$ExpectedNative = "95D96A416F0B8A9404D59D19AE749095ADE728B0C14BC943784DB00DA33B5D80"
$ExpectedBinding = "FA0743536328C1827EDBD9D380048B90F1B9CE1C7861D01E1B7C20A02F6C4493"
$Jdk = "C:\Program Files\Android\Android Studio\jbr"
$Prefix = Join-Path $env:TEMP "apu-file-transfer-f1-preparation-owner-build"
$StatePath = "$Prefix-state.json"
$GradleLog = "$Prefix-gradle.log"

$Existing = @(@($StatePath, $GradleLog) | Where-Object { Test-Path $_ })
if ($Existing.Count -ne 0) { throw "Preparation owner build already attempted; preserve evidence" }
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
try {
    $env:JAVA_HOME = $Jdk
    $env:Path = "$Jdk\bin;" + $env:Path
    $env:GITHUB_REF_NAME = "v11.16.40"
    Push-Location (Join-Path $RepoRoot "android-app")
    try {
        $OldPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        & .\gradlew.bat --no-daemon `
            :app:testDebugUnitTest `
            --tests "com.vladimir.messenger.data.file.FileTransferChunkStoreTest" `
            --tests "com.vladimir.messenger.data.file.FileTransferSourceInspectorTest" `
            --tests "com.vladimir.messenger.data.file.FileTransferKeyEnvelopeTest" `
            :app:assembleDebug *>&1 | Tee-Object $GradleLog | Out-Host
        $Code = $LASTEXITCODE
        $ErrorActionPreference = $OldPreference
        if ($Code -ne 0) { throw "Gradle preparation owner test/build failed: $Code" }
    } finally { Pop-Location }
    $App = Join-Path $RepoRoot "android-app/app/build/outputs/apk/debug/app-debug.apk"
    $AppSize = (Get-Item $App).Length
    $AppHash = (Get-FileHash $App -Algorithm SHA256).Hash
    $Outcome = "PASS"
} catch {
    $Failure = $_.Exception.Message
    throw
} finally {
    [ordered]@{
        schema = 1
        purpose = "File F1 outgoing local encrypted preparation owner build"
        outcome = $Outcome
        failure = $Failure
        sourceCommit = $ExpectedSource
        chunkStoreJvmTestsRun = $true
        sourceInspectorJvmTestsRun = $true
        keyEnvelopeJvmTestsRun = $true
        appApkSize = $AppSize
        appApkSha256 = $AppHash
        productionPreparationInvoked = $false
        uiEnabled = $false
        transportWired = $false
        adbUsed = $false
        phonesChanged = $false
    } | ConvertTo-Json -Depth 5 | Set-Content $StatePath -Encoding UTF8
    $StateHash = (Get-FileHash $StatePath -Algorithm SHA256).Hash
    Write-Host "`nOutcome: $Outcome"
    Write-Host "State: $StatePath"
    Write-Host "State SHA256: $StateHash"
    Write-Host "App APK: $AppSize / $AppHash"
    Write-Host "Preparation/UI/transport/ADB/phones: False/False/False/False/False"
}
if ($Outcome -ne "PASS") { throw "File F1 preparation owner build failed" }
Write-Host "FILE TRANSFER F1 PREPARATION OWNER BUILD PASS"
