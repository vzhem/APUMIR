$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

# Single-use final M8-C3 compile continuation after Rust and isolated bindgen PASS.
# Runs Gradle only. Requires failed D: offline and pagefile active only on C:.

$RepoRoot=Split-Path -Parent $PSScriptRoot; Set-Location $RepoRoot
$ExpectedSource="f7d4a3080c45fee36a30917bc4a39252703cafe2"
$ExpectedPriorHash="89DC3F9E4935AE76667E17DCEA849E7B2FC0BDA5902FC7AC64109568C7C9687F"
$ExpectedSoHash="F4D2FABF4DEC037BC2E078FBD45AFACA491BF0F819B0EDF066272187DFDAF824"
$ExpectedBindingHash="31BD55059AF96604B4B0B1AB2F970BA7E583C39B2BA0C5D523055AECE7B610F2"
$ExpectedSdk="C:\Users\User\AppData\Local\Android\Sdk"
$AllowedBranches=@("arena/01a00674-apumir","arena/01a013d0-apumir","arena/01a0149e-apumir")
$PowerShellExe="$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
$State=Join-Path $env:TEMP "apu-m8-c3-gradle-only-state.json"
$Stdout=Join-Path $env:TEMP "apu-m8-c3-gradle-only.stdout.log"
$Stderr=Join-Path $env:TEMP "apu-m8-c3-gradle-only.stderr.log"
$ExitMarker=Join-Path $env:TEMP "apu-m8-c3-gradle-only.exit.txt"
$Existing=@(@($State,$Stdout,$Stderr,$ExitMarker)|Where-Object{Test-Path -LiteralPath $_})
if($Existing.Count -gt 0){throw "Gradle-only gate already attempted; preserve evidence: $($Existing -join ', ')"}

$Branch=((& git branch --show-current)-join "").Trim();$Head=((& git rev-parse HEAD)-join "").Trim()
if($AllowedBranches -notcontains $Branch){throw "Wrong branch: $Branch"}
& git diff --quiet $ExpectedSource $Head -- rust-core android-app tools/uniffi-bindgen .gitignore
if($LASTEXITCODE -ne 0){throw "Source differs from exact Gradle-only continuation source"}
$AllowedStatus=@(" M android-app/app/src/main/java/uniffi/p2p_core/p2p_core.kt"," M android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so")
$Status=@(& git status --porcelain=v1 --untracked-files=all);$Unexpected=@($Status|Where-Object{$_ -notin $AllowedStatus})
if($Status.Count -ne 2 -or $Unexpected.Count -gt 0){throw "Expected only successful .so/binding outputs: $($Status -join '; ')"}

$PriorState=Join-Path $env:TEMP "apu-m8-c3-isolated-bindgen-state.json"
if(-not(Test-Path -LiteralPath $PriorState -PathType Leaf)){throw "Prior isolated-bindgen state missing"}
$PriorHash=(Get-FileHash -LiteralPath $PriorState -Algorithm SHA256).Hash
if($PriorHash -ne $ExpectedPriorHash){throw "Prior state hash mismatch: $PriorHash"}
$Prior=Get-Content -LiteralPath $PriorState -Raw|ConvertFrom-Json
if($Prior.outcome -ne "INCOMPLETE_DO_NOT_REPEAT" -or $Prior.rustSoSha256 -ne $ExpectedSoHash -or $Prior.generatedBindingSha256 -ne $ExpectedBindingHash){throw "Prior state is not exact Rust+bindgen success / Gradle incomplete evidence"}

$FailedDisk=Get-Disk -Number 1
if($FailedDisk.SerialNumber.Trim() -ne "Z9AM7JYH" -or -not $FailedDisk.IsOffline){throw "Failed D: disk is not safely offline"}
$Pagefiles=@(Get-CimInstance Win32_PageFileUsage)
if(@($Pagefiles|Where-Object{$_.Name -like "D:\*"}).Count -gt 0 -or @($Pagefiles|Where-Object{$_.Name -like "C:\*"}).Count -eq 0){throw "Pagefile is not active only away from D:"}

$LocalProperties=Join-Path $RepoRoot "android-app\local.properties"
if(-not(Test-Path -LiteralPath $LocalProperties -PathType Leaf)){throw "local.properties missing"}
$SdkLines=@(Get-Content -LiteralPath $LocalProperties|Where-Object{$_ -match '^\s*sdk\.dir\s*='})
$ExpectedSdkLine='sdk.dir=C\:\\Users\\User\\AppData\\Local\\Android\\Sdk'
if($SdkLines.Count -ne 1 -or $SdkLines[0] -ne $ExpectedSdkLine){throw "local.properties SDK path mismatch: $($SdkLines -join '; ')"}
foreach($SdkInput in @($ExpectedSdk,(Join-Path $ExpectedSdk "platform-tools\adb.exe"),(Join-Path $ExpectedSdk "platforms"))){if(-not(Test-Path -LiteralPath $SdkInput)){throw "SDK input missing: $SdkInput"}}

$So=Join-Path $RepoRoot "android-app\app\src\main\jniLibs\arm64-v8a\libp2p_core.so"
$Binding=Join-Path $RepoRoot "android-app\app\src\main\java\uniffi\p2p_core\p2p_core.kt"
$Apk=Join-Path $RepoRoot "android-app\app\build\outputs\apk\debug\app-debug.apk"
if((Get-FileHash $So -Algorithm SHA256).Hash -ne $ExpectedSoHash){throw "Rust .so changed"}
if((Get-FileHash $Binding -Algorithm SHA256).Hash -ne $ExpectedBindingHash){throw "Binding changed"}

$Outcome="INCOMPLETE_DO_NOT_REPEAT";$Failure=$null;$Started=(Get-Date).ToUniversalTime();$Timer=[Diagnostics.Stopwatch]::StartNew();$ProcessId=$null;$ApkSize=$null;$ApkHash=$null
try{
 $Android=Join-Path $RepoRoot "android-app"
 $Wrapped=@"
`$ErrorActionPreference='Continue'
Set-Location '$Android'
& '.\gradlew.bat' --no-daemon :app:assembleDebug
`$Code=if(`$null -eq `$LASTEXITCODE){0}else{[int]`$LASTEXITCODE}
[IO.File]::WriteAllText('$ExitMarker',`$Code.ToString(),[Text.Encoding]::ASCII)
"@
 $Encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Wrapped))
 $Process=Start-Process -FilePath $PowerShellExe -ArgumentList @("-NoProfile","-EncodedCommand",$Encoded) -WorkingDirectory $Android -RedirectStandardOutput $Stdout -RedirectStandardError $Stderr -PassThru
 $ProcessId=$Process.Id
 if(-not $Process.WaitForExit(1200000)){try{Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue}catch{};throw "Gradle timed out"}
 $Process.WaitForExit();if(-not(Test-Path -LiteralPath $ExitMarker -PathType Leaf)){throw "Gradle lacks exit marker"}
 $Code=[int](Get-Content -LiteralPath $ExitMarker -Raw).Trim();if($Code -ne 0){throw "Gradle failed: process=$ProcessId exit=$Code"}
 $Lines=@((Get-Content $Stdout),(Get-Content $Stderr));if(@($Lines|Where-Object{$_ -match 'BUILD SUCCESSFUL'}).Count -lt 1){throw "Gradle lacks BUILD SUCCESSFUL"}
 $ApkFile=Get-Item -LiteralPath $Apk;if($ApkFile.LastWriteTimeUtc -lt $Started.AddMinutes(-1)){throw "Debug APK was not produced by this gate"}
 $ApkSize=$ApkFile.Length;$ApkHash=(Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash
 $Final=@(& git status --porcelain=v1 --untracked-files=all);$FinalUnexpected=@($Final|Where-Object{$_ -notin $AllowedStatus})
 if($Final.Count -ne 2 -or $FinalUnexpected.Count -gt 0){throw "Unexpected outputs: $($Final -join '; ')"}
 $Outcome="PASS"
}catch{$Failure=$_.Exception.Message;throw}finally{
 $Timer.Stop();$Result=[ordered]@{schema=1;purpose="M8-C3 final Gradle-only compile continuation";outcome=$Outcome;failure=$Failure;priorStateSha256=$PriorHash
 sourceCommit=$ExpectedSource;windowsHead=$Head;branch=$Branch;startedUtc=$Started.ToString("o");completedUtc=(Get-Date).ToUniversalTime().ToString("o");durationSeconds=[math]::Round($Timer.Elapsed.TotalSeconds,2)
 rustSoSha256=$ExpectedSoHash;bindingSha256=$ExpectedBindingHash;gradleProcessId=$ProcessId;debugApkPath=$Apk;debugApkSize=$ApkSize;debugApkSha256=$ApkHash
 failedDiskOffline=$true;pagefileOnC=$true;adbUsed=$false;phonesChanged=$false;publicTrafficSent=$false}
 $Result|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $State -Encoding UTF8;$Hash=(Get-FileHash -LiteralPath $State -Algorithm SHA256).Hash
 Write-Host "";Write-Host "Gradle-only outcome: $Outcome";Write-Host "State: $State";Write-Host "State SHA256: $Hash";Write-Host "Gradle process: $ProcessId"
 Write-Host "Rust .so: $ExpectedSoHash";Write-Host "Binding: $ExpectedBindingHash";Write-Host "Debug APK: $ApkSize bytes / $ApkHash";Write-Host "ADB/phones/traffic: False / False / False"
}
if($Outcome -ne "PASS"){throw "M8-C3 Gradle-only gate did not pass; preserve evidence"}
Write-Host "M8 A->C3 WINDOWS COMPILE GATE PASS" -ForegroundColor Green
