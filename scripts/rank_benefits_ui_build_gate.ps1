$ErrorActionPreference="Stop"
Set-StrictMode -Version 2.0
$RepoRoot=Split-Path -Parent $PSScriptRoot;Set-Location -LiteralPath $RepoRoot
$ExpectedSource="b3c0b2ef55e3dd18744ad1015e25fa9e6afa00e9"
$ExpectedNative="95D96A416F0B8A9404D59D19AE749095ADE728B0C14BC943784DB00DA33B5D80"
$ExpectedBinding="FA0743536328C1827EDBD9D380048B90F1B9CE1C7861D01E1B7C20A02F6C4493"
$Jdk="C:\Program Files\Android\Android Studio\jbr";$Prefix=Join-Path $env:TEMP "apu-rank-benefits-ui-build";$State="$Prefix-state.json";$Log="$Prefix-gradle.log"
$Existing=@(@($State,$Log)|Where-Object{Test-Path $_});if($Existing.Count){throw "Rank UI build already attempted"}
$Head=((& git rev-parse HEAD)-join"").Trim();& git diff --quiet $ExpectedSource $Head -- android-app rust-core;if($LASTEXITCODE -ne 0){throw "Source mismatch"}
$Native="android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so";$Binding="android-app/app/src/main/java/uniffi/p2p_core/p2p_core.kt"
$Status=@(& git status --porcelain=v1 --untracked-files=all);if($Status.Count -ne 1 -or $Status[0] -ne" M $Native"){throw "Unexpected worktree"}
if((Get-FileHash $Native -Algorithm SHA256).Hash -ne $ExpectedNative -or (Get-FileHash $Binding -Algorithm SHA256).Hash -ne $ExpectedBinding){throw "Artifact mismatch"}
$Outcome="INCOMPLETE_DO_NOT_REPEAT";$Failure=$null;$ApkHash=$null;$ApkSize=$null
try{$env:JAVA_HOME=$Jdk;$env:Path="$Jdk\bin;"+$env:Path;$env:GITHUB_REF_NAME="v11.16.46";Push-Location "android-app";try{$Old=$ErrorActionPreference;$ErrorActionPreference="Continue";& .\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.vladimir.messenger.data.file.FileTransferRankPolicyTest" :app:assembleDebug *>&1|Tee-Object $Log|Out-Host;$Code=$LASTEXITCODE;$ErrorActionPreference=$Old;if($Code -ne 0){throw "Gradle rank UI build failed: $Code"}}finally{Pop-Location};$Apk="android-app/app/build/outputs/apk/debug/app-debug.apk";$ApkSize=(Get-Item $Apk).Length;$ApkHash=(Get-FileHash $Apk -Algorithm SHA256).Hash;$Outcome="PASS"}catch{$Failure=$_.Exception.Message;throw}finally{[ordered]@{schema=1;purpose="Rank benefits information UI build";outcome=$Outcome;failure=$Failure;sourceCommit=$ExpectedSource;apkSize=$ApkSize;apkSha256=$ApkHash;productionFileTransportWired=$false;adbUsed=$false;phonesChanged=$false}|ConvertTo-Json -Depth 5|Set-Content $State -Encoding UTF8;$Hash=(Get-FileHash $State -Algorithm SHA256).Hash;Write-Host "`nOutcome: $Outcome";Write-Host "State: $State";Write-Host "State SHA256: $Hash";Write-Host "APK: $ApkSize / $ApkHash";Write-Host "File transport/ADB/phones: False/False/False"}
if($Outcome -ne "PASS"){throw "Rank UI build failed"};Write-Host "RANK BENEFITS UI BUILD PASS"
