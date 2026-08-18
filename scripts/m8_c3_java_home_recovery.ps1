$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

# Single-use final continuation after Gradle stopped before startup because
# JAVA_HOME still referenced the decommissioned D: drive. Runs Gradle only,
# with a process-local JDK 21 on C:. Does not change the system environment.

$RepoRoot=Split-Path -Parent $PSScriptRoot;Set-Location $RepoRoot
$ExpectedSource="f7d4a3080c45fee36a30917bc4a39252703cafe2"
$ExpectedPriorHash="185E997A8DA07726D64E56672AE5BE93A690F2FF1D11B8CA82DF70375FF1AB8E"
$ExpectedSoHash="F4D2FABF4DEC037BC2E078FBD45AFACA491BF0F819B0EDF066272187DFDAF824"
$ExpectedBindingHash="31BD55059AF96604B4B0B1AB2F970BA7E583C39B2BA0C5D523055AECE7B610F2"
$Jdk="C:\Program Files\Android\Android Studio\jbr"
$AllowedBranches=@("arena/01a00674-apumir","arena/01a013d0-apumir","arena/01a0149e-apumir")
$PowerShellExe="$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
$Prefix=Join-Path $env:TEMP "apu-m8-c3-java-home-recovery"
$State="$Prefix-state.json";$Stdout="$Prefix.stdout.log";$Stderr="$Prefix.stderr.log";$ExitMarker="$Prefix.exit.txt"
$Existing=@(@($State,$Stdout,$Stderr,$ExitMarker)|Where-Object{Test-Path -LiteralPath $_})
if($Existing.Count -gt 0){throw "JAVA_HOME recovery already attempted; preserve evidence: $($Existing -join ', ')"}

$Branch=((& git branch --show-current)-join "").Trim();$Head=((& git rev-parse HEAD)-join "").Trim()
if($AllowedBranches -notcontains $Branch){throw "Wrong branch: $Branch"}
& git diff --quiet $ExpectedSource $Head -- rust-core android-app tools/uniffi-bindgen .gitignore
if($LASTEXITCODE -ne 0){throw "Application source differs from exact M8-C3 source"}
$AllowedStatus=@(" M android-app/app/src/main/java/uniffi/p2p_core/p2p_core.kt"," M android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so")
$Status=@(& git status --porcelain=v1 --untracked-files=all);$Unexpected=@($Status|Where-Object{$_ -notin $AllowedStatus})
if($Status.Count -ne 2 -or $Unexpected.Count -gt 0){throw "Expected only successful .so/binding: $($Status -join '; ')"}

$PriorState=Join-Path $env:TEMP "apu-m8-c3-gradle-only-state.json";$PriorErr=Join-Path $env:TEMP "apu-m8-c3-gradle-only.stderr.log"
foreach($Path in @($PriorState,$PriorErr)){if(-not(Test-Path -LiteralPath $Path -PathType Leaf)){throw "Prior Gradle evidence missing: $Path"}}
$PriorHash=(Get-FileHash -LiteralPath $PriorState -Algorithm SHA256).Hash
if($PriorHash -ne $ExpectedPriorHash){throw "Prior state hash mismatch: $PriorHash"}
$Prior=Get-Content -LiteralPath $PriorState -Raw|ConvertFrom-Json;$PriorError=Get-Content -LiteralPath $PriorErr -Raw
if($Prior.outcome -ne "INCOMPLETE_DO_NOT_REPEAT" -or $PriorError -notmatch 'JAVA_HOME is set to an invalid directory: D:\\Android Studio\\jbr'){throw "Prior evidence is not exact pre-Gradle JAVA_HOME failure"}

$Failed=@(Get-Disk -ErrorAction SilentlyContinue|Where-Object{$_.SerialNumber -and $_.SerialNumber.Trim() -eq "Z9AM7JYH"})
if($Failed.Count -gt 1 -or ($Failed.Count -eq 1 -and -not $Failed[0].IsOffline)){throw "Failed HDD is not absent/offline"}
$FailedState=if($Failed.Count -eq 0){"physically-absent"}else{"offline"}
$Pagefiles=@(Get-CimInstance Win32_PageFileUsage)
if(@($Pagefiles|Where-Object{$_.Name -like "D:\*"}).Count -gt 0 -or @($Pagefiles|Where-Object{$_.Name -like "C:\*"}).Count -eq 0){throw "Pagefile safety gate failed"}

$Java=Join-Path $Jdk "bin\java.exe";$Javac=Join-Path $Jdk "bin\javac.exe"
foreach($Path in @($Java,$Javac)){if(-not(Test-Path -LiteralPath $Path -PathType Leaf)){throw "JDK input missing: $Path"}}
$OldPreference=$ErrorActionPreference;$ErrorActionPreference="Continue"
$VersionLines=@(& $Java -version 2>&1|ForEach-Object{$_.ToString()});$JavaExit=$LASTEXITCODE
$ErrorActionPreference=$OldPreference
if($JavaExit -ne 0 -or ($VersionLines -join "`n") -notmatch 'version "21\.'){throw "Expected working JDK 21 at $Jdk"}
$LocalProperties=Join-Path $RepoRoot "android-app\local.properties"
if(-not(Test-Path -LiteralPath $LocalProperties -PathType Leaf)){throw "local.properties missing"}
$Sdk="C:\Users\User\AppData\Local\Android\Sdk"
foreach($Path in @($Sdk,(Join-Path $Sdk "platforms"))){if(-not(Test-Path -LiteralPath $Path)){throw "SDK input missing: $Path"}}

$So=Join-Path $RepoRoot "android-app\app\src\main\jniLibs\arm64-v8a\libp2p_core.so";$Binding=Join-Path $RepoRoot "android-app\app\src\main\java\uniffi\p2p_core\p2p_core.kt"
$Apk=Join-Path $RepoRoot "android-app\app\build\outputs\apk\debug\app-debug.apk"
if((Get-FileHash $So -Algorithm SHA256).Hash -ne $ExpectedSoHash){throw "Rust .so changed"}
if((Get-FileHash $Binding -Algorithm SHA256).Hash -ne $ExpectedBindingHash){throw "Binding changed"}

$Outcome="INCOMPLETE_DO_NOT_REPEAT";$Failure=$null;$Started=(Get-Date).ToUniversalTime();$Timer=[Diagnostics.Stopwatch]::StartNew();$ProcessId=$null;$ApkSize=$null;$ApkHash=$null
try{
 $Android=Join-Path $RepoRoot "android-app"
 $Wrapped=@"
`$ErrorActionPreference='Continue'
`$env:JAVA_HOME='$Jdk'
`$env:Path='$Jdk\bin;' + `$env:Path
Set-Location '$Android'
& '.\gradlew.bat' --no-daemon :app:assembleDebug
`$Code=if(`$null -eq `$LASTEXITCODE){0}else{[int]`$LASTEXITCODE}
[IO.File]::WriteAllText('$ExitMarker',`$Code.ToString(),[Text.Encoding]::ASCII)
"@
 $Encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Wrapped));$Process=Start-Process -FilePath $PowerShellExe -ArgumentList @("-NoProfile","-EncodedCommand",$Encoded) -WorkingDirectory $Android -RedirectStandardOutput $Stdout -RedirectStandardError $Stderr -PassThru;$ProcessId=$Process.Id
 if(-not $Process.WaitForExit(1200000)){try{Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue}catch{};throw "Gradle timed out"};$Process.WaitForExit()
 if(-not(Test-Path -LiteralPath $ExitMarker -PathType Leaf)){throw "Gradle lacks exit marker"};$Code=[int](Get-Content -LiteralPath $ExitMarker -Raw).Trim();if($Code -ne 0){throw "Gradle failed: process=$ProcessId exit=$Code"}
 $Lines=@((Get-Content $Stdout),(Get-Content $Stderr));if(@($Lines|Where-Object{$_ -match 'BUILD SUCCESSFUL'}).Count -lt 1){throw "Gradle lacks BUILD SUCCESSFUL"}
 $ApkFile=Get-Item -LiteralPath $Apk;if($ApkFile.LastWriteTimeUtc -lt $Started.AddMinutes(-1)){throw "Debug APK not produced by this gate"};$ApkSize=$ApkFile.Length;$ApkHash=(Get-FileHash $Apk -Algorithm SHA256).Hash
 $Final=@(& git status --porcelain=v1 --untracked-files=all);$FinalUnexpected=@($Final|Where-Object{$_ -notin $AllowedStatus});if($Final.Count -ne 2 -or $FinalUnexpected.Count -gt 0){throw "Unexpected outputs: $($Final -join '; ')"};$Outcome="PASS"
}catch{$Failure=$_.Exception.Message;throw}finally{
 $Timer.Stop();$Result=[ordered]@{schema=1;purpose="M8-C3 JDK21 Gradle-only recovery";outcome=$Outcome;failure=$Failure;priorStateSha256=$PriorHash;sourceCommit=$ExpectedSource;windowsHead=$Head;branch=$Branch
 startedUtc=$Started.ToString("o");completedUtc=(Get-Date).ToUniversalTime().ToString("o");durationSeconds=[math]::Round($Timer.Elapsed.TotalSeconds,2);javaHome=$Jdk;javaVersion=$VersionLines
 failedDiskState=$FailedState;pagefileOnC=$true;rustSoSha256=$ExpectedSoHash;bindingSha256=$ExpectedBindingHash;gradleProcessId=$ProcessId;debugApkPath=$Apk;debugApkSize=$ApkSize;debugApkSha256=$ApkHash;adbUsed=$false;phonesChanged=$false;publicTrafficSent=$false}
 $Result|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $State -Encoding UTF8;$Hash=(Get-FileHash $State -Algorithm SHA256).Hash
 Write-Host "";Write-Host "JAVA_HOME recovery outcome: $Outcome";Write-Host "State: $State";Write-Host "State SHA256: $Hash";Write-Host "JDK: $Jdk";Write-Host "Gradle process: $ProcessId";Write-Host "Debug APK: $ApkSize bytes / $ApkHash";Write-Host "Rust/bindgen/ADB/phones: False / False / False / False"
}
if($Outcome -ne "PASS"){throw "M8-C3 JAVA_HOME recovery did not pass; preserve evidence"}
Write-Host "M8 A->C3 WINDOWS COMPILE GATE PASS" -ForegroundColor Green
