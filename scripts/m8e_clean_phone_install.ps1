$ErrorActionPreference="Stop"
Set-StrictMode -Version 2.0

# Explicitly destructive clean-install gate authorized by user on 2026-08-18.
# Uninstall removes all app data/cache on Anna, Zhenya, and Stas.
$RepoRoot=Split-Path -Parent $PSScriptRoot;Set-Location $RepoRoot
$Sdk="C:\Users\User\AppData\Local\Android\Sdk";$Jdk="C:\Program Files\Android\Android Studio\jbr";$Adb=Join-Path $Sdk "platform-tools\adb.exe";$Package="com.vladimir.messenger";$TestVersion="v11.16.18"
$ExpectedSoHash="F4D2FABF4DEC037BC2E078FBD45AFACA491BF0F819B0EDF066272187DFDAF824";$ExpectedSigner="40448024EB6A824709431EB8908176F5D08BE8EAD1A8902EEB5F0FDAA1B3A186";$ExpectedInventoryHash="15DD85AD45EDDEFEE2D433117C8719DDCE86D7512032C43BAC5D3494205E718B"
$Inventory=Join-Path $env:TEMP "apu-m8e-phone-signer-inventory-v4.json";$StatePath=Join-Path $env:TEMP "apu-m8e-clean-install-v111618-state.json";$BuildLog=Join-Path $env:TEMP "apu-m8e-clean-install-v111618-build.log"
if((Test-Path $StatePath) -or (Test-Path $BuildLog)){throw "Clean install already attempted; preserve evidence"}
$Phones=@([pscustomobject]@{Name="Anna";Serial="AUYF6R5923006121"},[pscustomobject]@{Name="Zhenya";Serial="3B665800EES00000"},[pscustomobject]@{Name="Stas";Serial="11567254BK001192"})

$So=Join-Path $RepoRoot "android-app\app\src\main\jniLibs\arm64-v8a\libp2p_core.so";if((Get-FileHash $So -Algorithm SHA256).Hash -ne $ExpectedSoHash){throw "Rust .so changed"}
if((Get-FileHash $Inventory -Algorithm SHA256).Hash -ne $ExpectedInventoryHash){throw "Signer inventory evidence changed"}
$ExpectedStatus=" M android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so";$Status=@(& git status --porcelain=v1 --untracked-files=all);if($Status.Count -ne 1 -or $Status[0] -ne $ExpectedStatus){throw "Unexpected worktree: $($Status -join '; ')"}
$Lines=@(& $Adb devices);$Visible=@{};foreach($Line in $Lines){if($Line -match "^(\S+)\s+(\S+)$"){$Visible[$Matches[1]]=$Matches[2]}};foreach($Phone in $Phones){if(-not $Visible.ContainsKey($Phone.Serial) -or $Visible[$Phone.Serial] -ne "device"){throw "$($Phone.Name) not visible/authorized"}}

$env:JAVA_HOME=$Jdk;$env:Path="$Jdk\bin;"+$env:Path;$env:GITHUB_REF_NAME=$TestVersion
$Android=Join-Path $RepoRoot "android-app";Push-Location $Android
try{$OldPreference=$ErrorActionPreference;$ErrorActionPreference="Continue";& .\gradlew.bat --stop|Out-Host;& .\gradlew.bat --no-daemon :app:assembleDebug *>&1|Tee-Object -FilePath $BuildLog|Out-Host;$GradleExit=$LASTEXITCODE;$ErrorActionPreference=$OldPreference;if($GradleExit -ne 0){throw "v11.16.18 debug build failed: exit=$GradleExit"}}finally{Pop-Location}
$Apk=Join-Path $RepoRoot "android-app\app\build\outputs\apk\debug\app-debug.apk";$ApkHash=(Get-FileHash $Apk -Algorithm SHA256).Hash
$BuildTools=Get-ChildItem (Join-Path $Sdk "build-tools") -Directory|Sort-Object Name -Descending|Where-Object{Test-Path (Join-Path $_.FullName "apksigner.bat")}|Select-Object -First 1;$ApkSigner=Join-Path $BuildTools.FullName "apksigner.bat";$Aapt=Join-Path $BuildTools.FullName "aapt2.exe"
function Get-Signer([string]$Path){$Old=$ErrorActionPreference;$ErrorActionPreference="Continue";$Out=@(& $ApkSigner verify --print-certs $Path 2>&1|ForEach-Object{$_.ToString()});$Code=$LASTEXITCODE;$ErrorActionPreference=$Old;if($Code -ne 0){throw "apksigner failed"};$Line=$Out|Where-Object{$_ -match "certificate SHA-256 digest:"}|Select-Object -First 1;$Value=(($Line -split "digest:",2)[1] -replace":","");$Value=$Value.Trim();return $Value.ToUpperInvariant()}
$Signer=Get-Signer $Apk;if($Signer -ne $ExpectedSigner){throw "Debug signer mismatch: $Signer"};$Badging=(& $Aapt dump badging $Apk|Select-String "package: name="|Select-Object -First 1).ToString();if($Badging -notmatch "versionCode='11016018'" -or $Badging -notmatch "versionName='v11.16.18'"){throw "APK version mismatch: $Badging"}

$Result=[ordered]@{schema=1;outcome="INCOMPLETE_DO_NOT_REPEAT";authorizedDestructiveAction="uninstall all app data/cache and clean install";startedUtc=(Get-Date).ToUniversalTime().ToString("o");apk=$Apk;apkSha256=$ApkHash;signerSha256=$Signer;version=$TestVersion;phones=@();uninstallUsed=$true;dataAndCacheDeleted=$true;forceStopUsed=$false;logcatCleared=$false}
$Result|ConvertTo-Json -Depth 8|Set-Content $StatePath -Encoding UTF8
try{
 foreach($Phone in $Phones){$Serial=$Phone.Serial;Write-Host "`n=== CLEAN INSTALL $($Phone.Name) ===";$Before=@(& $Adb -s $Serial shell dumpsys package $Package);$BeforeVersion=(($Before|Where-Object{$_ -match "^\s*versionName="}|Select-Object -First 1) -as [string]).Trim();$BeforeUid=(($Before|Where-Object{$_ -match "^\s*(userId|appId)="}|Select-Object -First 1) -as [string]).Trim();$BeforeFirst=(($Before|Where-Object{$_ -match "^\s*firstInstallTime="}|Select-Object -First 1) -as [string]).Trim()
  $Uninstall=((& $Adb -s $Serial uninstall $Package)-join " ").Trim();if($LASTEXITCODE -ne 0 -or $Uninstall -notmatch "Success"){throw "$($Phone.Name) uninstall failed: $Uninstall"}
  $Install=((& $Adb -s $Serial install $Apk)-join " ").Trim();if($LASTEXITCODE -ne 0 -or $Install -notmatch "Success"){throw "$($Phone.Name) install failed: $Install"}
  $Dump=@(& $Adb -s $Serial shell dumpsys package $Package);$Version=(($Dump|Where-Object{$_ -match "^\s*versionName="}|Select-Object -First 1) -as [string]).Trim();$Code=(($Dump|Where-Object{$_ -match "^\s*versionCode="}|Select-Object -First 1) -as [string]).Trim();$Uid=(($Dump|Where-Object{$_ -match "^\s*(userId|appId)="}|Select-Object -First 1) -as [string]).Trim();$First=(($Dump|Where-Object{$_ -match "^\s*firstInstallTime="}|Select-Object -First 1) -as [string]).Trim();if($Version -ne "versionName=v11.16.18"){throw "$($Phone.Name) installed version mismatch: $Version"}
  $Remote=((@(& $Adb -s $Serial shell pm path $Package)|Where-Object{$_ -match "base\.apk$"}|Select-Object -First 1)-replace"^package:","").Trim();$Pulled=Join-Path $env:TEMP "apu-m8e-$($Phone.Name)-v111618-base.apk";& $Adb -s $Serial pull $Remote $Pulled|Out-Host;$InstalledSigner=Get-Signer $Pulled;if($InstalledSigner -ne $ExpectedSigner){throw "$($Phone.Name) post-install signer mismatch"}
  $Launch=((& $Adb -s $Serial shell am start -W -n "$Package/.MainActivity")-join " ").Trim();Start-Sleep -Seconds 5;$PidText=((& $Adb -s $Serial shell pidof $Package)-join " ").Trim();if(-not $PidText){throw "$($Phone.Name) did not start"}
  $PhoneResult=[pscustomobject]@{Name=$Phone.Name;Serial=$Serial;beforeVersion=$BeforeVersion;beforeUid=$BeforeUid;beforeFirstInstall=$BeforeFirst;uninstallResult=$Uninstall;installResult=$Install;version=$Version;versionCode=$Code;uid=$Uid;firstInstallTime=$First;installedSignerSha256=$InstalledSigner;launchResult=$Launch;processId=$PidText};$Result.phones+=@($PhoneResult);$Result|ConvertTo-Json -Depth 8|Set-Content $StatePath -Encoding UTF8;Write-Host "$($Phone.Name): clean v11.16.18 installed/launched; PID=$PidText"
 }
 $Result.outcome="PASS";$Result.completedUtc=(Get-Date).ToUniversalTime().ToString("o")
}finally{$Result|ConvertTo-Json -Depth 8|Set-Content $StatePath -Encoding UTF8}
$StateHash=(Get-FileHash $StatePath -Algorithm SHA256).Hash;Write-Host "`nOutcome: $($Result.outcome)";Write-Host "State: $StatePath";Write-Host "State SHA256: $StateHash";Write-Host "APK SHA256: $ApkHash";Write-Host "Phones clean-installed/launched: $($Result.phones.Count)/3";Write-Host "Data/cache deleted: True";Write-Host "M8-E CLEAN INSTALL V11.16.18 PASS"
