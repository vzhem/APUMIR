$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

# Single-use continuation after Android Rust PASS and host bindgen dependency failure.
# Uses the minimal tools/uniffi-bindgen crate, isolated from ring/aws-lc/sqlite.

$RepoRoot=Split-Path -Parent $PSScriptRoot; Set-Location $RepoRoot
$ExpectedSource="3a287642e4251836463dec56538f525227384d3a"
$ExpectedPriorHash="92693E06A7B01F86D660810C6A8BAB1F4526351C131959AAA162A8B3259F9559"
$ExpectedSoHash="F4D2FABF4DEC037BC2E078FBD45AFACA491BF0F819B0EDF066272187DFDAF824"
$ExpectedBindingHash="313DCC2339AFE2E5EFAC8CD8649E5E715A90F969760CD759EECD5CC8643FABA0"
$AllowedBranches=@("arena/01a00674-apumir","arena/01a013d0-apumir","arena/01a0149e-apumir")
$PowerShellExe="$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
$Prefix=Join-Path $env:TEMP "apu-m8-c3-isolated-bindgen"
$State="$Prefix-state.json";$BindOut="$Prefix.stdout.log";$BindErr="$Prefix.stderr.log";$BindExit="$Prefix.exit.txt"
$GradleOut="$Prefix-gradle.stdout.log";$GradleErr="$Prefix-gradle.stderr.log";$GradleExit="$Prefix-gradle.exit.txt"
$ToolTarget=Join-Path $env:TEMP "apu-m8-c3-bindgen-tool-target"
$Evidence=@($State,$BindOut,$BindErr,$BindExit,$GradleOut,$GradleErr,$GradleExit,$ToolTarget)
$Existing=@($Evidence|Where-Object{Test-Path -LiteralPath $_})
if($Existing.Count -gt 0){throw "Isolated bindgen gate already attempted; preserve evidence: $($Existing -join ', ')"}
if($RepoRoot -notmatch '^[Cc]:\\'){throw "Gate must run from drive C: $RepoRoot"}

$Branch=((& git branch --show-current)-join "").Trim();$Head=((& git rev-parse HEAD)-join "").Trim()
if($AllowedBranches -notcontains $Branch){throw "Wrong branch: $Branch"}
& git diff --quiet $ExpectedSource $Head -- rust-core android-app tools/uniffi-bindgen .gitignore
if($LASTEXITCODE -ne 0){throw "Source differs from exact isolated-bindgen commit"}
$ExpectedStatus=" M android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$Status=@(& git status --porcelain=v1 --untracked-files=all)
if($Status.Count -ne 1 -or $Status[0] -ne $ExpectedStatus){throw "Expected only compiled .so: $($Status -join '; ')"}

$PriorState=Join-Path $env:TEMP "apu-m8-c3-source-fix-state.json";$PriorBindErr=Join-Path $env:TEMP "apu-m8-c3-source-fix-bindgen.stderr.log"
foreach($Path in @($PriorState,$PriorBindErr)){if(-not(Test-Path -LiteralPath $Path -PathType Leaf)){throw "Prior evidence missing: $Path"}}
$PriorHash=(Get-FileHash -LiteralPath $PriorState -Algorithm SHA256).Hash
if($PriorHash -ne $ExpectedPriorHash){throw "Prior state hash mismatch: $PriorHash"}
$Prior=Get-Content -LiteralPath $PriorState -Raw|ConvertFrom-Json;$PriorError=Get-Content -LiteralPath $PriorBindErr -Raw
if($Prior.outcome -ne "INCOMPLETE_DO_NOT_REPEAT" -or $Prior.generatedSoSha256 -ne $ExpectedSoHash -or
   $PriorError -notmatch 'libsqlite3-sys' -or $PriorError -notmatch 'ring v0\.17\.14'){
    throw "Prior evidence is not the exact host-native bindgen dependency failure"
}
$So=Join-Path $RepoRoot "android-app\app\src\main\jniLibs\arm64-v8a\libp2p_core.so"
$Binding=Join-Path $RepoRoot "android-app\app\src\main\java\uniffi\p2p_core\p2p_core.kt"
$Apk=Join-Path $RepoRoot "android-app\app\build\outputs\apk\debug\app-debug.apk"
if((Get-FileHash -LiteralPath $So -Algorithm SHA256).Hash -ne $ExpectedSoHash){throw "Compiled Rust .so changed"}
if((Get-FileHash -LiteralPath $Binding -Algorithm SHA256).Hash -ne $ExpectedBindingHash){throw "Binding baseline changed"}
$Manifest=Join-Path $RepoRoot "tools\uniffi-bindgen\Cargo.toml"
if(-not(Test-Path -LiteralPath $Manifest -PathType Leaf)){throw "Isolated bindgen manifest missing"}

function Invoke-Captured{
 param([string]$Label,[string]$Cwd,[string]$Command,[string]$Out,[string]$Err,[string]$Exit,[int]$Timeout)
 $Wrapped=@"
`$ErrorActionPreference='Continue'
Set-Location '$Cwd'
& { $Command }
`$Code=if(`$null -eq `$LASTEXITCODE){0}else{[int]`$LASTEXITCODE}
[IO.File]::WriteAllText('$Exit',`$Code.ToString(),[Text.Encoding]::ASCII)
"@
 $Encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Wrapped))
 $Process=Start-Process -FilePath $PowerShellExe -ArgumentList @("-NoProfile","-EncodedCommand",$Encoded) -WorkingDirectory $Cwd -RedirectStandardOutput $Out -RedirectStandardError $Err -PassThru
 if(-not $Process.WaitForExit($Timeout)){try{Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue}catch{};throw "$Label timed out"}
 $Process.WaitForExit();if(-not(Test-Path -LiteralPath $Exit -PathType Leaf)){throw "$Label lacks exit marker"}
 $Code=[int](Get-Content -LiteralPath $Exit -Raw).Trim();if($Code -ne 0){throw "$Label failed: process=$($Process.Id) exit=$Code"};return $Process.Id
}

$Outcome="INCOMPLETE_DO_NOT_REPEAT";$Failure=$null;$Started=(Get-Date).ToUniversalTime();$Timer=[Diagnostics.Stopwatch]::StartNew()
$BindProcess=$null;$GradleProcess=$null;$BindingHash=$null;$ApkHash=$null;$ApkSize=$null
try{
 $RustCore=Join-Path $RepoRoot "rust-core"
 $Command="& cargo run --manifest-path '$Manifest' --target-dir '$ToolTarget' -- generate src/lib.udl --language kotlin --config uniffi.toml --out-dir ..\android-app\app\src\main\java"
 $BindProcess=Invoke-Captured "Isolated M8-C3 bindgen" $RustCore $Command $BindOut $BindErr $BindExit 1200000
 $BindingHash=(Get-FileHash -LiteralPath $Binding -Algorithm SHA256).Hash;if($BindingHash -eq $ExpectedBindingHash){throw "Binding unchanged"}
 $Text=Get-Content -LiteralPath $Binding -Raw
 foreach($Marker in @("createEngineDurable","installRelayAtRestKey","relayCustodyMode","relayQuarantineCount")){if($Text -notmatch $Marker){throw "Binding lacks $Marker"}}
 $Android=Join-Path $RepoRoot "android-app"
 $GradleProcess=Invoke-Captured "M8-C3 debug APK build" $Android "& '.\gradlew.bat' --no-daemon :app:assembleDebug" $GradleOut $GradleErr $GradleExit 1200000
 $Lines=@((Get-Content $GradleOut),(Get-Content $GradleErr));if(@($Lines|Where-Object{$_ -match 'BUILD SUCCESSFUL'}).Count -lt 1){throw "Gradle lacks BUILD SUCCESSFUL"}
 $ApkFile=Get-Item -LiteralPath $Apk;$ApkSize=$ApkFile.Length;$ApkHash=(Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash
 $Final=@(& git status --porcelain=v1 --untracked-files=all);$Allowed=@($ExpectedStatus," M android-app/app/src/main/java/uniffi/p2p_core/p2p_core.kt")
 $Unexpected=@($Final|Where-Object{$_ -notin $Allowed});if($Final.Count -ne 2 -or $Unexpected.Count -gt 0){throw "Unexpected outputs: $($Final -join '; ')"}
 $Outcome="PASS"
}catch{$Failure=$_.Exception.Message;throw}finally{
 $Timer.Stop();$Result=[ordered]@{schema=1;purpose="M8-C3 isolated UniFFI bindgen and Gradle continuation";outcome=$Outcome;failure=$Failure
 priorStateSha256=$PriorHash;sourceCommit=$ExpectedSource;windowsHead=$Head;branch=$Branch;startedUtc=$Started.ToString("o");completedUtc=(Get-Date).ToUniversalTime().ToString("o");durationSeconds=[math]::Round($Timer.Elapsed.TotalSeconds,2)
 rustSoSha256=$ExpectedSoHash;bindgenProcessId=$BindProcess;generatedBindingSha256=$BindingHash;gradleProcessId=$GradleProcess;debugApkPath=$Apk;debugApkSize=$ApkSize;debugApkSha256=$ApkHash
 isolatedToolManifest=$Manifest;isolatedToolTarget=$ToolTarget;adbUsed=$false;phonesChanged=$false;publicTrafficSent=$false}
 $Result|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $State -Encoding UTF8;$Hash=(Get-FileHash -LiteralPath $State -Algorithm SHA256).Hash
 Write-Host "";Write-Host "Isolated-bindgen outcome: $Outcome";Write-Host "State: $State";Write-Host "State SHA256: $Hash";Write-Host "Rust .so preserved: $ExpectedSoHash"
 Write-Host "Bindgen/Gradle: $BindProcess / $GradleProcess";Write-Host "Generated binding: $BindingHash";Write-Host "Debug APK: $ApkSize bytes / $ApkHash";Write-Host "ADB/phones/traffic: False / False / False"
}
if($Outcome -ne "PASS"){throw "Isolated bindgen gate did not pass; preserve evidence"}
Write-Host "M8 A->C3 ISOLATED BINDGEN + GRADLE PASS" -ForegroundColor Green
