$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot

$ExpectedSource = "6581850ff2c75a69d4294e3a50706eb85c4eb0eb"
$OldNative = "1D6478B21FDE3D4856E439A575268D0314BA078C79D842AC0D039512B3554B23"
$OldBinding = "A697399686E60292D5C2EEFBE207D143602E0443782ABE8FFFFF91B8F93325E7"
$Generator = Join-Path $env:TEMP "apu-m8-c3-bindgen-tool-target\debug\apu-uniffi-bindgen.exe"
$GeneratorHash = "8D4F706D5FE3103F34E74604EEAE97642E3DCD20887BE05D7484BCF04EB73A4D"
$Jdk = "C:\Program Files\Android\Android Studio\jbr"
$Prefix = Join-Path $env:TEMP "apu-file-transfer-f1-crypto-api-build"
$StatePath = "$Prefix-state.json"
$RustLog = "$Prefix-rust.log"
$BindLog = "$Prefix-bindgen.log"
$GradleLog = "$Prefix-gradle.log"

$Existing = @(@($StatePath, $RustLog, $BindLog, $GradleLog) | Where-Object { Test-Path $_ })
if ($Existing.Count -ne 0) { throw "File crypto API build already attempted; preserve evidence" }
$Head = ((& git rev-parse HEAD) -join "").Trim()
& git diff --quiet $ExpectedSource $Head -- rust-core android-app
if ($LASTEXITCODE -ne 0) { throw "Source mismatch" }
$NativePath = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$BindingPath = "android-app/app/src/main/java/uniffi/p2p_core/p2p_core.kt"
$Status = @(& git status --porcelain=v1 --untracked-files=all)
if ($Status.Count -ne 1 -or $Status[0] -ne " M $NativePath") { throw "Unexpected worktree" }
if ((Get-FileHash $NativePath -Algorithm SHA256).Hash -ne $OldNative -or
    (Get-FileHash $BindingPath -Algorithm SHA256).Hash -ne $OldBinding -or
    (Get-FileHash $Generator -Algorithm SHA256).Hash -ne $GeneratorHash) {
    throw "Artifact/generator baseline mismatch"
}

$Outcome = "INCOMPLETE_DO_NOT_REPEAT"
$Failure = $null
$NewNative = $null
$NewBinding = $null
$AppHash = $null
$AppSize = $null
try {
    $Jni = Join-Path $RepoRoot "android-app/app/src/main/jniLibs"
    Push-Location (Join-Path $RepoRoot "rust-core")
    try {
        $OldPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        & cargo ndk -t arm64-v8a -o $Jni build --release --features mqtt-dual-broker *>&1 |
            Tee-Object $RustLog | Out-Host
        $RustCode = $LASTEXITCODE
        $ErrorActionPreference = $OldPreference
        if ($RustCode -ne 0) { throw "Rust production build failed: $RustCode" }

        $OldPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        & $Generator generate src/lib.udl --language kotlin --config uniffi.toml `
            --out-dir ..\android-app\app\src\main\java *>&1 | Tee-Object $BindLog | Out-Host
        $BindCode = $LASTEXITCODE
        $ErrorActionPreference = $OldPreference
        if ($BindCode -ne 0) { throw "UniFFI generation failed: $BindCode" }
    } finally { Pop-Location }

    $Normalize = Join-Path $env:TEMP "apu-file-f1-api-normalize.py"
    @'
import pathlib,re,sys
p=pathlib.Path(sys.argv[1]);b=p.read_bytes();p.write_bytes(re.sub(rb"[ \t]+(?=\r?$)",b"",b,flags=re.MULTILINE))
'@ | Set-Content $Normalize -Encoding ASCII
    & py -3 $Normalize $BindingPath
    if ($LASTEXITCODE -ne 0) { throw "Binding normalization failed" }

    $NewNative = (Get-FileHash $NativePath -Algorithm SHA256).Hash
    $NewBinding = (Get-FileHash $BindingPath -Algorithm SHA256).Hash
    if ($NewNative -eq $OldNative -or $NewBinding -eq $OldBinding) {
        throw "Expected native/binding changes missing"
    }
    $BindingText = Get-Content $BindingPath -Raw
    foreach ($Marker in @(
        "createFileTransferManifest",
        "parseFileTransferManifest",
        "encryptFileTransferChunk",
        "decryptFileTransferChunk"
    )) {
        if ($BindingText -notmatch $Marker) { throw "Generated binding marker missing: $Marker" }
    }

    $env:JAVA_HOME = $Jdk
    $env:Path = "$Jdk\bin;" + $env:Path
    $env:GITHUB_REF_NAME = "v11.16.38"
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
        purpose = "File F1 bounded functional manifest and chunk crypto UniFFI build"
        outcome = $Outcome
        failure = $Failure
        sourceCommit = $ExpectedSource
        oldNativeSha256 = $OldNative
        newNativeSha256 = $NewNative
        oldBindingSha256 = $OldBinding
        newBindingSha256 = $NewBinding
        appApkSize = $AppSize
        appApkSha256 = $AppHash
        keyVaultWired = $false
        transportWired = $false
        adbUsed = $false
        phonesChanged = $false
    } | ConvertTo-Json -Depth 5 | Set-Content $StatePath -Encoding UTF8
    $StateHash = (Get-FileHash $StatePath -Algorithm SHA256).Hash
    Write-Host "`nOutcome: $Outcome"
    Write-Host "State: $StatePath"
    Write-Host "State SHA256: $StateHash"
    Write-Host "Native: $NewNative"
    Write-Host "Binding: $NewBinding"
    Write-Host "App APK: $AppSize / $AppHash"
    Write-Host "Key vault/transport/ADB/phones: False/False/False/False"
}
if ($Outcome -ne "PASS") { throw "File F1 crypto API build failed" }
Write-Host "FILE TRANSFER F1 CRYPTO API BUILD PASS"
