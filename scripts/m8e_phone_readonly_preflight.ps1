$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

# Read-only phone gate for signed M8-E v11.16.17 APK.
# No install, launch, force-stop, log clear, data clear, or phone write.
$RepoRoot=Split-Path -Parent $PSScriptRoot;Set-Location $RepoRoot
$Sdk="C:\Users\User\AppData\Local\Android\Sdk";$Jdk="C:\Program Files\Android\Android Studio\jbr"
$Adb=Join-Path $Sdk "platform-tools\adb.exe";$Apk=Join-Path $RepoRoot "android-app\app\build\outputs\apk\release\app-release.apk"
$Package="com.vladimir.messenger";$ExpectedApkHash="246ED1355F77013D8BA47AFF9D860514E137F7072CBF639AC4131FF376FFCE44";$ExpectedSigner="F843CBE70332BAB67A9671EBDE32FEE541E84CD904D3A508E5626346A1A4A5F7"
$StatePath=Join-Path $env:TEMP "apu-m8e-phone-readonly-preflight-v3.json";$CaptureRoot=Join-Path $env:TEMP "apu-m8e-installed-apk-signatures-v3"
if((Test-Path -LiteralPath $StatePath)-or(Test-Path -LiteralPath $CaptureRoot)){throw "Read-only phone gate v3 already attempted; preserve evidence"}
$Phones=@([pscustomobject]@{Name="Anna";Serial="AUYF6R5923006121"},[pscustomobject]@{Name="Zhenya";Serial="3B665800EES00000"},[pscustomobject]@{Name="Stas";Serial="11567254BK001192"})

Write-Host "=== M8-E PHONE READ-ONLY PREFLIGHT V3 ===";Write-Host "Anna/Zhenya/Stas; install/launch/force-stop/log-clear/data-clear: False"
foreach($Path in @($Adb,$Apk,(Join-Path $Jdk "bin\java.exe"))){if(-not(Test-Path -LiteralPath $Path -PathType Leaf)){throw "Required input missing: $Path"}}
$env:JAVA_HOME=$Jdk;$env:Path="$Jdk\bin;"+$env:Path
$BuildTools=Get-ChildItem -LiteralPath (Join-Path $Sdk "build-tools") -Directory|Sort-Object Name -Descending|Where-Object{Test-Path -LiteralPath (Join-Path $_.FullName "apksigner.bat")}|Select-Object -First 1
if(-not $BuildTools){throw "apksigner missing"};$ApkSigner=Join-Path $BuildTools.FullName "apksigner.bat"

function Get-Signer([string]$Path){
 $Old=$ErrorActionPreference;$ErrorActionPreference="Continue";$Output=@(& $ApkSigner verify --print-certs $Path 2>&1|ForEach-Object{$_.ToString()});$Code=$LASTEXITCODE;$ErrorActionPreference=$Old
 if($Code -ne 0){throw "apksigner failed: $Path exit=$Code output=$($Output -join ' | ')"}
 $Line=$Output|Where-Object{$_ -match "certificate SHA-256 digest:"}|Select-Object -First 1;if(-not $Line){throw "Signer digest missing: $Path"}
 $Digest=(($Line -split "digest:",2)[1] -replace ":","");$Digest=$Digest.Trim();return $Digest.ToUpperInvariant()
}

$ApkHash=(Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash;if($ApkHash -ne $ExpectedApkHash){throw "Target APK hash mismatch: $ApkHash"}
$TargetSigner=Get-Signer $Apk;if($TargetSigner -ne $ExpectedSigner){throw "Target signer mismatch: $TargetSigner"}
Write-Host "Target APK/signature: verified"

$Lines=@(& $Adb devices);$Lines|ForEach-Object{Write-Host $_};$Visible=@{}
foreach($Line in $Lines){if($Line -match "^(\S+)\s+(\S+)$"){$Visible[$Matches[1]]=$Matches[2]}}
foreach($Phone in $Phones){if(-not $Visible.ContainsKey($Phone.Serial)){throw "$($Phone.Name) absent: $($Phone.Serial)"};if($Visible[$Phone.Serial] -ne "device"){throw "$($Phone.Name) state=$($Visible[$Phone.Serial])"}}
Write-Host "All three phones visible/authorized"
New-Item -ItemType Directory -Path $CaptureRoot|Out-Null;$Results=@()

foreach($Phone in $Phones){
 $Serial=$Phone.Serial;Write-Host "`n========== $($Phone.Name) =========="
 $Boot=(& $Adb -s $Serial shell getprop sys.boot_completed).Trim();if($Boot -ne "1"){throw "$($Phone.Name) boot incomplete"}
 $Model=(& $Adb -s $Serial shell getprop ro.product.model).Trim();$Manufacturer=(& $Adb -s $Serial shell getprop ro.product.manufacturer).Trim();$Android=(& $Adb -s $Serial shell getprop ro.build.version.release).Trim();$Api=(& $Adb -s $Serial shell getprop ro.build.version.sdk).Trim()
 $Paths=@(& $Adb -s $Serial shell pm path $Package|ForEach-Object{$_.Trim()});$Base=$Paths|Where-Object{$_ -match "^package:.*base\.apk$"}|Select-Object -First 1;if(-not $Base){$Base=$Paths|Where-Object{$_ -match "^package:"}|Select-Object -First 1};if(-not $Base){throw "$($Phone.Name) package missing"}
 $Remote=$Base -replace "^package:","";$Local=Join-Path $CaptureRoot "$($Phone.Name)-installed-base.apk";& $Adb -s $Serial pull $Remote $Local|Out-Host;if($LASTEXITCODE -ne 0 -or -not(Test-Path -LiteralPath $Local -PathType Leaf)){throw "$($Phone.Name) APK read failed"}
 $Signer=Get-Signer $Local;if($Signer -ne $ExpectedSigner){throw "$($Phone.Name) signer mismatch: $Signer"};$InstalledHash=(Get-FileHash -LiteralPath $Local -Algorithm SHA256).Hash
 $Dump=@(& $Adb -s $Serial shell dumpsys package $Package)
 $VersionName=(($Dump|Where-Object{$_ -match "^\s*versionName="}|Select-Object -First 1) -as [string]).Trim();$VersionCode=(($Dump|Where-Object{$_ -match "^\s*versionCode="}|Select-Object -First 1) -as [string]).Trim();$FirstInstall=(($Dump|Where-Object{$_ -match "^\s*firstInstallTime="}|Select-Object -First 1) -as [string]).Trim();$LastUpdate=(($Dump|Where-Object{$_ -match "^\s*lastUpdateTime="}|Select-Object -First 1) -as [string]).Trim();$Uid=(($Dump|Where-Object{$_ -match "^\s*userId="}|Select-Object -First 1) -as [string]).Trim()
 $PidText=((& $Adb -s $Serial shell pidof $Package)-join " ").Trim();if(-not $PidText){$PidText="not-running"}
 Write-Host "$Manufacturer $Model Android=$Android API=$Api";Write-Host "$VersionName; $VersionCode";Write-Host "$FirstInstall; $LastUpdate; $Uid; PID=$PidText";Write-Host "Signer=$Signer"
 $Results+=[pscustomobject]@{Name=$Phone.Name;Serial=$Serial;Manufacturer=$Manufacturer;Model=$Model;Android=$Android;Api=$Api;VersionName=$VersionName;VersionCode=$VersionCode;FirstInstallTime=$FirstInstall;LastUpdateTime=$LastUpdate;Uid=$Uid;ProcessId=$PidText;RemoteBaseApk=$Remote;InstalledApkSha256=$InstalledHash;InstalledSignerSha256=$Signer}
}

$State=[ordered]@{schema=3;outcome="PASS";capturedUtc=(Get-Date).ToUniversalTime().ToString("o");targetApk=$Apk;targetApkSha256=$ApkHash;targetSignerSha256=$TargetSigner;targetVersion="v11.16.17";phones=$Results;installedApksPulledReadOnly=$true;installPerformed=$false;appLaunched=$false;forceStopUsed=$false;logcatCleared=$false;dataCleared=$false}
$State|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $StatePath -Encoding UTF8;$Hash=(Get-FileHash -LiteralPath $StatePath -Algorithm SHA256).Hash
Write-Host "`nOutcome: PASS";Write-Host "State: $StatePath";Write-Host "State SHA256: $Hash";Write-Host "Installed signers: verified 3/3";Write-Host "Install/launch/force-stop/log-clear/data-clear: False/False/False/False/False";Write-Host "PHONE READ-ONLY PREFLIGHT V3 PASS"
