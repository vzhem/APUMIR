$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot

$ExpectedSource = "502608e"
$OldNative = "95D96A416F0B8A9404D59D19AE749095ADE728B0C14BC943784DB00DA33B5D80"
$OldBinding = "FA0743536328C1827EDBD9D380048B90F1B9CE1C7861D01E1B7C20A02F6C4493"
$Generator = Join-Path $env:TEMP "apu-m8-c3-bindgen-tool-target\debug\apu-uniffi-bindgen.exe"
$GeneratorHash = "8D4F706D5FE3103F34E74604EEAE97642E3DCD20887BE05D7484BCF04EB73A4D"
$Jdk = "C:\Program Files\Android\Android Studio\jbr"
$Prefix = Join-Path $env:TEMP "apu-file-exchange-binding-build"
$StatePath = "$Prefix-state.json"
$RustLog = "$Prefix-rust.log"
$BindLog = "$Prefix-bindgen.log"
$GradleLog = "$Prefix-gradle.log"

$Existing = @(@($StatePath,$RustLog,$BindLog,$GradleLog) | Where-Object { Test-Path $_ })
if ($Existing.Count) { throw "File exchange binding build already attempted; preserve evidence" }
$Head = ((& git rev-parse HEAD)-join "").Trim()
& git diff --quiet $ExpectedSource $Head -- rust-core android-app
if ($LASTEXITCODE -ne 0) { throw "Source mismatch" }
$NativePath = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$BindingPath = "android-app/app/src/main/java/uniffi/p2p_core/p2p_core.kt"
$Status = @(& git status --porcelain=v1 --untracked-files=all)
if ($Status.Count -ne 1 -or $Status[0] -ne " M $NativePath") { throw "Unexpected worktree" }
if ((Get-FileHash $NativePath -Algorithm SHA256).Hash -ne $OldNative -or
    (Get-FileHash $BindingPath -Algorithm SHA256).Hash -ne $OldBinding -or
    (Get-FileHash $Generator -Algorithm SHA256).Hash -ne $GeneratorHash) { throw "Baseline mismatch" }

$Outcome="INCOMPLETE_DO_NOT_REPEAT";$Failure=$null;$NewNative=$null;$NewBinding=$null;$ApkHash=$null;$ApkSize=$null
try {
    $Jni = Join-Path $RepoRoot "android-app/app/src/main/jniLibs"
    Push-Location "rust-core"
    try {
        $Old=$ErrorActionPreference;$ErrorActionPreference="Continue"
        & cargo ndk -t arm64-v8a -o $Jni build --release --features mqtt-dual-broker *>&1 | Tee-Object $RustLog | Out-Host
        $Code=$LASTEXITCODE;$ErrorActionPreference=$Old
        if($Code -ne 0){throw "Rust build failed: $Code"}
        $Old=$ErrorActionPreference;$ErrorActionPreference="Continue"
        & $Generator generate src/lib.udl --language kotlin --config uniffi.toml --out-dir ..\android-app\app\src\main\java *>&1 | Tee-Object $BindLog | Out-Host
        $Code=$LASTEXITCODE;$ErrorActionPreference=$Old
        if($Code -ne 0){throw "Bindgen failed: $Code"}
    } finally { Pop-Location }
    $Normalize=Join-Path $env:TEMP "apu-file-exchange-normalize.py"
    @'
import pathlib,re,sys
p=pathlib.Path(sys.argv[1]);b=p.read_bytes();p.write_bytes(re.sub(rb"[ \t]+(?=\r?$)",b"",b,flags=re.MULTILINE))
'@ | Set-Content $Normalize -Encoding ASCII
    & py -3 $Normalize $BindingPath
    if($LASTEXITCODE -ne 0){throw "Binding normalization failed"}
    $NewNative=(Get-FileHash $NativePath -Algorithm SHA256).Hash
    $NewBinding=(Get-FileHash $BindingPath -Algorithm SHA256).Hash
    if($NewNative -eq $OldNative -or $NewBinding -eq $OldBinding){throw "Expected native/binding changes missing"}
    $Text=Get-Content $BindingPath -Raw
    foreach($Marker in @("createFileExchangeBinding","verifyFileExchangeBinding","fileExchangeBindingNodeId","fileExchangeBindingPublicKey")){
        if($Text -notmatch $Marker){throw "Missing binding marker: $Marker"}
    }
    $env:JAVA_HOME=$Jdk;$env:Path="$Jdk\bin;"+$env:Path;$env:GITHUB_REF_NAME="v11.16.47"
    Push-Location "android-app"
    try {
        $Old=$ErrorActionPreference;$ErrorActionPreference="Continue"
        & .\gradlew.bat --no-daemon :app:assembleDebug *>&1 | Tee-Object $GradleLog | Out-Host
        $Code=$LASTEXITCODE;$ErrorActionPreference=$Old
        if($Code -ne 0){throw "Gradle failed: $Code"}
    } finally { Pop-Location }
    $Apk="android-app/app/build/outputs/apk/debug/app-debug.apk";$ApkSize=(Get-Item $Apk).Length;$ApkHash=(Get-FileHash $Apk -Algorithm SHA256).Hash
    $Outcome="PASS"
} catch {$Failure=$_.Exception.Message;throw}
finally {
    [ordered]@{schema=1;purpose="Signed X25519 file exchange binding build";outcome=$Outcome;failure=$Failure;sourceCommit=$ExpectedSource;oldNative=$OldNative;newNative=$NewNative;oldBinding=$OldBinding;newBinding=$NewBinding;apkSize=$ApkSize;apkSha256=$ApkHash;deviceSecretStoreWired=$false;transportWired=$false;adbUsed=$false;phonesChanged=$false}|ConvertTo-Json -Depth 5|Set-Content $StatePath -Encoding UTF8
    $Hash=(Get-FileHash $StatePath -Algorithm SHA256).Hash
    Write-Host "`nOutcome: $Outcome";Write-Host "State: $StatePath";Write-Host "State SHA256: $Hash";Write-Host "Native: $NewNative";Write-Host "Binding: $NewBinding";Write-Host "APK: $ApkSize / $ApkHash";Write-Host "Secret store/transport/ADB/phones: False/False/False/False"
}
if($Outcome -ne "PASS"){throw "File exchange binding build failed"}
Write-Host "FILE EXCHANGE BINDING BUILD PASS"
