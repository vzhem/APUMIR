$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

# M8-E slice 1 Windows compile gate: Gradle only (Rust source/bindings unchanged).
$RepoRoot=Split-Path -Parent $PSScriptRoot;Set-Location $RepoRoot
$ExpectedSource="329ae3d65a1e4e1fea0df9b56818885ef3e18449"
$ExpectedSoHash="F4D2FABF4DEC037BC2E078FBD45AFACA491BF0F819B0EDF066272187DFDAF824"
$ExpectedBindingHash="982308D9ABAA5F43FC9ADA524676D9C6B4218E3BCC3EEB3277ECFEE8DA2596D3"
$Jdk="C:\Program Files\Android\Android Studio\jbr"
$AllowedBranches=@("arena/01a013d0-apumir","arena/01a0149e-apumir")
$PowerShellExe="$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
$Prefix=Join-Path $env:TEMP "apu-m8e-slice1-compile"
$State="$Prefix-state.json";$Stdout="$Prefix.stdout.log";$Stderr="$Prefix.stderr.log";$ExitMarker="$Prefix.exit.txt"
$Existing=@(@($State,$Stdout,$Stderr,$ExitMarker)|Where-Object{Test-Path -LiteralPath $_})
if($Existing.Count -gt 0){throw "M8-E slice1 gate already attempted; preserve evidence: $($Existing -join ', ')"}

$Branch=((& git branch --show-current)-join "").Trim();$Head=((& git rev-parse HEAD)-join "").Trim()
if($AllowedBranches -notcontains $Branch){throw "Wrong branch: $Branch"}
& git diff --quiet $ExpectedSource $Head -- android-app rust-core tools/uniffi-bindgen
if($LASTEXITCODE -ne 0){throw "Application source differs from exact M8-E slice1 commit"}
$ExpectedStatus=" M android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$Status=@(& git status --porcelain=v1 --untracked-files=all)
if($Status.Count -ne 1 -or $Status[0] -ne $ExpectedStatus){throw "Expected only generated .so: $($Status -join '; ')"}

$Failed=@(Get-Disk -ErrorAction SilentlyContinue|Where-Object{$_.SerialNumber -and $_.SerialNumber.Trim() -eq "Z9AM7JYH"})
if($Failed.Count -gt 1 -or ($Failed.Count -eq 1 -and -not $Failed[0].IsOffline)){throw "Failed HDD is not absent/offline"}
$FailedState=if($Failed.Count -eq 0){"physically-absent"}else{"offline"}
$Pagefiles=@(Get-CimInstance Win32_PageFileUsage)
if(@($Pagefiles|Where-Object{$_.Name -like "D:\*"}).Count -gt 0 -or @($Pagefiles|Where-Object{$_.Name -like "C:\*"}).Count -eq 0){throw "Pagefile safety gate failed"}

$Java=Join-Path $Jdk "bin\java.exe";$Javac=Join-Path $Jdk "bin\javac.exe"
foreach($Path in @($Java,$Javac)){if(-not(Test-Path -LiteralPath $Path -PathType Leaf)){throw "JDK input missing: $Path"}}
$LocalProperties=Join-Path $RepoRoot "android-app\local.properties"
if(-not(Test-Path -LiteralPath $LocalProperties -PathType Leaf)){throw "local.properties missing"}
$So=Join-Path $RepoRoot "android-app\app\src\main\jniLibs\arm64-v8a\libp2p_core.so";$Binding=Join-Path $RepoRoot "android-app\app\src\main\java\uniffi\p2p_core\p2p_core.kt"
$Apk=Join-Path $RepoRoot "android-app\app\build\outputs\apk\debug\app-debug.apk"
if((Get-FileHash $So -Algorithm SHA256).Hash -ne $ExpectedSoHash){throw "Rust .so changed"}
if((Get-FileHash $Binding -Algorithm SHA256).Hash -ne $ExpectedBindingHash){throw "Binding changed"}

# Static bounded-wake contract before invoking Gradle.
$AppText=Get-Content -LiteralPath (Join-Path $RepoRoot "android-app\app\src\main\java\com\vladimir\messenger\MessengerApplication.kt") -Raw
$BridgeText=Get-Content -LiteralPath (Join-Path $RepoRoot "android-app\app\src\main\java\com\vladimir\messenger\data\RustBridge.kt") -Raw
$WorkerText=Get-Content -LiteralPath (Join-Path $RepoRoot "android-app\app\src\main\java\com\vladimir\messenger\worker\RelayWakeWorker.kt") -Raw
$Checks=[ordered]@{uniquePeriodic=$AppText -match 'enqueueUniquePeriodicWork';workerScheduled=$AppText -match 'RelayWakeWorker';connected=$AppText -match 'NetworkType.CONNECTED';battery=$AppText -match 'setRequiresBatteryNotLow\(true\)';boundedApi=$BridgeText -match 'runBoundedRelayWake';max30s=$BridgeText -match 'MAX_RELAY_WAKE_WINDOW_MILLIS = 30_000L';shutdownFinally=$BridgeText -match '(?s)runBoundedRelayWake.*finally\s*\{\s*shutdown\(\)';window25s=$WorkerText -match 'ACTIVE_WINDOW_MS = 25_000L';noImmediateRetry=$WorkerText -notmatch 'Result\.retry\(\)'}
$FailedChecks=@($Checks.Keys|Where-Object{-not $Checks[$_]});if($FailedChecks.Count -gt 0){throw "Static wake contract failed: $($FailedChecks -join ', ')"}

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
 $Final=@(& git status --porcelain=v1 --untracked-files=all);if($Final.Count -ne 1 -or $Final[0] -ne $ExpectedStatus){throw "Unexpected outputs: $($Final -join '; ')"};$Outcome="PASS"
}catch{$Failure=$_.Exception.Message;throw}finally{
 $Timer.Stop();$Result=[ordered]@{schema=1;purpose="M8-E slice1 bounded WorkManager wake compile gate";outcome=$Outcome;failure=$Failure;sourceCommit=$ExpectedSource;windowsHead=$Head;branch=$Branch;startedUtc=$Started.ToString("o");completedUtc=(Get-Date).ToUniversalTime().ToString("o");durationSeconds=[math]::Round($Timer.Elapsed.TotalSeconds,2);javaHome=$Jdk;failedDiskState=$FailedState;pagefileOnC=$true;rustRebuilt=$false;bindgenRun=$false;rustSoSha256=$ExpectedSoHash;bindingSha256=$ExpectedBindingHash;gradleProcessId=$ProcessId;debugApkPath=$Apk;debugApkSize=$ApkSize;debugApkSha256=$ApkHash;adbUsed=$false;phonesChanged=$false;publicTrafficSent=$false}
 $Result|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $State -Encoding UTF8;$Hash=(Get-FileHash $State -Algorithm SHA256).Hash
 Write-Host "";Write-Host "M8-E slice1 outcome: $Outcome";Write-Host "State: $State";Write-Host "State SHA256: $Hash";Write-Host "Gradle process: $ProcessId";Write-Host "Debug APK: $ApkSize bytes / $ApkHash";Write-Host "Rust/bindgen/ADB/phones: False / False / False / False"
}
if($Outcome -ne "PASS"){throw "M8-E slice1 compile gate did not pass; preserve evidence"}
Write-Host "M8-E SLICE1 BOUNDED WAKE COMPILE PASS" -ForegroundColor Green
