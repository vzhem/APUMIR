$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$ApkPath = Join-Path $env:TEMP "apu-r4.3-observe-v11.16.14-build2.apk"
$ApkStatePath = Join-Path $env:TEMP "apu-r4.3-observe-v11.16.14-apk-build2.json"
$Runtime13StatePath = Join-Path $env:TEMP "apu-r4.2-r1b4-v11.16.13-launch-analysis-recovery.json"
$StatePath = Join-Path $env:TEMP "apu-r4.3-observe-v11.16.14-anna-runtime2.json"
$EvidenceDir = Join-Path $env:TEMP "apu-r4.3-observe-v11.16.14-anna-runtime2-evidence"
$ExpectedApkStateHash = "01A649477F975B312D1CAB4797B217ACA5E6C68071E0AAC8736426FD9B492FBF"
$ExpectedApkSize = 22615564
$ExpectedApkHash = "6C4D29DA78EB914C172376955B4A802473C26EB7595E25BEB51373D0CCE13F5C"
$ExpectedCertHash = "F843CBE70332BAB67A9671EBDE32FEE541E84CD904D3A508E5626346A1A4A5F7"
$ExpectedRuntime13Hash = "7FDAF9BD2634A322186AECB704A393F132D9B66D9A891C568A9DB3E041C3A89F"
$OldRuntimeStatePath = Join-Path $env:TEMP "apu-r4.3-observe-v11.16.14-anna-runtime.json"
$ExpectedOldRuntimeStateHash = "D33273ACC3A728ED2DB883CC6D096D8D9BB1200B4D803E5177A6B81193137EDD"
$PackageName = "com.vladimir.messenger"
$EarlyWaitSeconds = 15
$LateWaitSeconds = 135

$Devices = @(
    [pscustomobject]@{ Name="Anna"; Serial="AUYF6R5923006121"; Uid=10425; FirstInstall="2026-08-08 11:40:39"; ExpectedPid="10945" },
    [pscustomobject]@{ Name="Zhenya"; Serial="3B665800EES00000"; Uid=10395; FirstInstall="2026-08-08 17:31:18"; ExpectedPid="20562" },
    [pscustomobject]@{ Name="Stas"; Serial="11567254BK001192"; Uid=10387; FirstInstall="2026-08-10 12:41:10"; ExpectedPid="23149" }
)

if (Test-Path $StatePath) { throw "R4.3 ANNA RUNTIME ALREADY ATTEMPTED - DO NOT REPEAT" }
if (Test-Path $EvidenceDir) { throw "R4.3 ANNA EVIDENCE ALREADY EXISTS - DO NOT REPEAT" }
foreach ($Path in @($Adb,$ApkPath,$ApkStatePath,$Runtime13StatePath,$OldRuntimeStatePath)) {
    if (-not (Test-Path $Path)) { throw "Required input missing: $Path" }
}

$OldRuntimeStateHash = (Get-FileHash $OldRuntimeStatePath -Algorithm SHA256).Hash
if ($OldRuntimeStateHash -ne $ExpectedOldRuntimeStateHash) { throw "Old runtime state hash mismatch" }
$OldRuntimeState = Get-Content $OldRuntimeStatePath -Raw | ConvertFrom-Json
if ($OldRuntimeState.outcome -ne "INCOMPLETE_DO_NOT_REPEAT" -or $OldRuntimeState.installStarted -ne $false -or $OldRuntimeState.launchStarted -ne $false) {
    throw "Old runtime state does not prove a pre-ADB stop"
}

$ApkStateHash = (Get-FileHash $ApkStatePath -Algorithm SHA256).Hash
$Runtime13Hash = (Get-FileHash $Runtime13StatePath -Algorithm SHA256).Hash
if ($ApkStateHash -ne $ExpectedApkStateHash) { throw "APK state hash mismatch" }
if ($Runtime13Hash -ne $ExpectedRuntime13Hash) { throw "Runtime13 state hash mismatch" }
$ApkState = Get-Content $ApkStatePath -Raw | ConvertFrom-Json
$Runtime13 = Get-Content $Runtime13StatePath -Raw | ConvertFrom-Json
if ($ApkState.outcome -ne "PASS" -or $ApkState.versionCode -ne 11016014 -or
    $ApkState.signerCertificateSha256 -ne $ExpectedCertHash -or $Runtime13.RuntimePassCount -ne 3) {
    throw "Artifact/runtime input state mismatch"
}
$ApkFile = Get-Item $ApkPath
$ApkHash = (Get-FileHash $ApkPath -Algorithm SHA256).Hash
if ($ApkFile.Length -ne $ExpectedApkSize -or $ApkHash -ne $ExpectedApkHash) { throw "APK identity mismatch" }

New-Item -ItemType Directory -Path $EvidenceDir | Out-Null

function Invoke-Captured {
    param([string]$File,[string[]]$ArgumentList,[string]$Label)
    $Out = Join-Path $EvidenceDir "$Label.stdout.log"
    $Err = Join-Path $EvidenceDir "$Label.stderr.log"
    $P = Start-Process -FilePath $File -ArgumentList $ArgumentList -WorkingDirectory $RepoRoot `
        -RedirectStandardOutput $Out -RedirectStandardError $Err -PassThru
    if (-not $P.WaitForExit(120000)) { throw "$Label PID $($P.Id) timeout" }
    $P.WaitForExit(); $P.Refresh()
    [pscustomobject]@{ Exit=[int]$P.ExitCode; Out=@(Get-Content $Out); Err=@(Get-Content $Err); OutPath=$Out; ErrPath=$Err }
}

function Get-Snapshot {
    param([object]$Device,[string]$Phase)
    $N=$Device.Name.ToLowerInvariant()
    $U=Invoke-Captured $Adb @("-s",$Device.Serial,"shell","cmd","package","list","packages","-U",$PackageName) "$N-$Phase-uid"
    $D=Invoke-Captured $Adb @("-s",$Device.Serial,"shell","dumpsys","package",$PackageName) "$N-$Phase-package"
    $P=Invoke-Captured $Adb @("-s",$Device.Serial,"shell","pidof",$PackageName) "$N-$Phase-pid"
    if ($U.Exit -ne 0 -or $D.Exit -ne 0 -or $P.Exit -notin @(0,1)) { throw "$($Device.Name) snapshot command failed" }
    $UT=$U.Out -join ""; $DT=$D.Out -join "`n"
    $UM=[regex]::Match($UT,"uid:(\d+)"); $VN=[regex]::Match($DT,"versionName=([^\s\r\n]+)")
    $VC=[regex]::Match($DT,"versionCode=(\d+)"); $FI=[regex]::Match($DT,"firstInstallTime=([^\r\n]+)")
    $DD=[regex]::Match($DT,"dataDir=([^\s\r\n]+)")
    if (-not $UM.Success -or -not $VN.Success -or -not $VC.Success -or -not $FI.Success -or -not $DD.Success) { throw "$($Device.Name) snapshot parse failed" }
    $Ids=@($P.Out|ForEach-Object{$_ -split "\s+"}|Where-Object{$_ -match "^\d+$"})
    [pscustomobject]@{ Uid=[int]$UM.Groups[1].Value; VersionName=$VN.Groups[1].Value.Trim(); VersionCode=[int64]$VC.Groups[1].Value; FirstInstall=$FI.Groups[1].Value.Trim(); DataDir=$DD.Groups[1].Value.Trim(); ProcessIds=@($Ids); Running=($Ids.Count -gt 0); PidExit=$P.Exit }
}

function Device-Epoch {
    param([object]$Device)
    $R=Invoke-Captured $Adb @("-s",$Device.Serial,"shell","date","+%s") "anna-launch-epoch"
    $T=($R.Out -join "").Trim(); if ($R.Exit -ne 0 -or $T -notmatch "^\d+$") { throw "Anna epoch failed" }; [int64]$T
}

function Native-Lines {
    param([object]$Device,[string]$AndroidProcessId,[int64]$Epoch,[string]$Phase)
    $R=Invoke-Captured $Adb @("-s",$Device.Serial,"logcat","-d","-v","epoch","p2p_core:V","*:S") "anna-$Phase-p2p"
    if ($R.Exit -ne 0) { throw "Anna p2p log failed" }
    $Result=[System.Collections.Generic.List[string]]::new(); $Pattern="^\s*(\d+\.\d+)\s+{0}\s+" -f [regex]::Escape($AndroidProcessId)
    foreach($L in $R.Out){$M=[regex]::Match($L,$Pattern);if(-not $M.Success){continue};$E=[double]::Parse($M.Groups[1].Value,[Globalization.CultureInfo]::InvariantCulture);if($E -ge [double]$Epoch){$Result.Add($L)}}
    @($Result)
}

function Count-Text { param([string[]]$Lines,[string]$Text); @($Lines|Where-Object{$_.Contains($Text)}).Count }

$Outcome="INCOMPLETE_DO_NOT_REPEAT";$Failure=$null;$InstallStarted=$false;$LaunchStarted=$false
$Pre=[ordered]@{};$PostInstall=$null;$NewPid=$null;$Metrics=$null
try {
    $Dev=Invoke-Captured $Adb @("devices") "adb-devices"; if($Dev.Exit -ne 0){throw "adb devices failed"}
    $Connected=@($Dev.Out|Select-Object -Skip 1|Where-Object{$_ -match "\S"})
    foreach($D in $Devices){$Pat="^{0}\s+device$" -f [regex]::Escape($D.Serial);if(@($Connected|Where-Object{$_ -match $Pat}).Count -ne 1){throw "$($D.Name) not connected"}}
    foreach($D in $Devices){$S=Get-Snapshot $D "pre";$Pre[$D.Name]=$S;if($S.Uid -ne $D.Uid -or $S.VersionCode -ne 11016013 -or $S.FirstInstall -ne $D.FirstInstall -or $S.DataDir -ne "/data/user/0/$PackageName" -or $S.ProcessIds.Count -ne 1 -or [string]$S.ProcessIds[0] -ne $D.ExpectedPid){throw "$($D.Name) precheck failed"}}

    $Anna=$Devices[0];$InstallStarted=$true
    $I=Invoke-Captured $Adb @("-s",$Anna.Serial,"install","-r",$ApkPath) "anna-install"
    $ILines=@($I.Out)+@($I.Err);$Success=@($ILines|Where-Object{$_.Trim() -eq "Success"}).Count
    if($I.Exit -ne 0 -or $Success -ne 1){throw "Anna install failed exit=$($I.Exit) success=$Success"}
    $PostInstall=Get-Snapshot $Anna "postinstall"
    if($PostInstall.Uid -ne $Anna.Uid -or $PostInstall.VersionCode -ne 11016014 -or $PostInstall.VersionName -ne "v11.16.14" -or $PostInstall.FirstInstall -ne $Anna.FirstInstall -or $PostInstall.DataDir -ne "/data/user/0/$PackageName" -or $PostInstall.Running){throw "Anna postinstall gate failed"}

    $Epoch=Device-Epoch $Anna;$LaunchStarted=$true
    $L=Invoke-Captured $Adb @("-s",$Anna.Serial,"shell","am","start","-W","-n","$PackageName/.MainActivity") "anna-launch"
    if($L.Exit -ne 0 -or @($L.Out|Where-Object{$_.Trim() -eq "Status: ok"}).Count -ne 1){throw "Anna launch failed"}
    $Started=Get-Snapshot $Anna "started";if($Started.ProcessIds.Count -ne 1){throw "Anna PID missing"};$NewPid=[string]$Started.ProcessIds[0]
    Start-Sleep $EarlyWaitSeconds;$Early=@(Native-Lines $Anna $NewPid $Epoch "early")
    Start-Sleep $LateWaitSeconds;$Late=@(Native-Lines $Anna $NewPid $Epoch "late")
    $Final=Get-Snapshot $Anna "final";if($Final.ProcessIds.Count -ne 1 -or [string]$Final.ProcessIds[0] -ne $NewPid){throw "Anna PID unstable"}
    foreach($D in @($Devices[1],$Devices[2])){$S=Get-Snapshot $D "final";if($S.VersionCode -ne 11016013 -or $S.ProcessIds.Count -ne 1 -or [string]$S.ProcessIds[0] -ne $D.ExpectedPid){throw "$($D.Name) primary peer changed"}}

    $All=@(@($Early)+@($Late)|Sort-Object -Unique)
    $Metrics=[ordered]@{
        secondarySupervisor=Count-Text $All "MQTT SECONDARY SUPERVISOR: feature=enabled mode=observe_only"
        secondaryStarting=Count-Text $All "MQTT SECONDARY STATUS: broker=emqx state=starting mode=observe_only subscriptions=0 publishes=0"
        secondaryConnected=@($All|Where-Object{$_.Contains("MQTT SECONDARY STATUS: broker=emqx state=connected") -and $_.Contains("subscriptions=0 publishes=0")}).Count
        secondaryErrors=Count-Text $All "MQTT SECONDARY STATUS: broker=emqx state=backoff"
        primaryReady=Count-Text $All "MQTT SESSION READY:"
        primaryHeartbeat=Count-Text $All "MQTT LIVENESS HEARTBEAT:"
        primaryIncoming=Count-Text $All "MQTT IN:"
        primaryErrors=Count-Text $All "MQTT error:"
        stalls=Count-Text $All "MQTT LIVENESS STALLED:"
        restarts=Count-Text $All "MQTT SESSION RESTART"
        requestErrors=Count-Text $All "MQTT REQUEST ERROR:"
        requestTimeouts=Count-Text $All "MQTT REQUEST TIMEOUT:"
    }
    if($Metrics.secondarySupervisor -ne 1 -or $Metrics.secondaryStarting -ne 1 -or $Metrics.secondaryConnected -lt 1 -or $Metrics.secondaryErrors -ne 0 -or $Metrics.primaryReady -lt 1 -or $Metrics.primaryHeartbeat -lt 1 -or $Metrics.primaryIncoming -lt 1 -or $Metrics.primaryErrors -ne 0 -or $Metrics.stalls -ne 0 -or $Metrics.restarts -ne 0 -or $Metrics.requestErrors -ne 0 -or $Metrics.requestTimeouts -ne 0){throw "Anna r4.3 status matrix incomplete: $($Metrics|ConvertTo-Json -Compress)"}
    $Outcome="PASS"
} catch {$Failure=$_.Exception.Message;throw} finally {
    $State=[ordered]@{schema=2;purpose="corrected r4.3 Anna-only observe runtime2";outcome=$Outcome;failure=$Failure;completedUtc=(Get-Date).ToUniversalTime().ToString("o");userApproved=$true;oldRuntimeState=$OldRuntimeStatePath;oldRuntimeStateSha256=$OldRuntimeStateHash;apkState=$ApkStatePath;apkStateSha256=$ApkStateHash;apkSha256=$ApkHash;runtime13State=$Runtime13StatePath;runtime13StateSha256=$Runtime13Hash;installStarted=$InstallStarted;launchStarted=$LaunchStarted;annaPre=$Pre["Anna"];annaPostInstall=$PostInstall;annaNewPid=$NewPid;metrics=$Metrics;evidenceDirectory=$EvidenceDir;uninstalled=$false;dataCleared=$false;forceStopped=$false;logcatCleared=$false;networkChanged=$false;userPayloadPublished=$false;secondarySubscriptions=0;secondaryPublishes=0;automaticRetry=$false}
    $State|ConvertTo-Json -Depth 10|Set-Content $StatePath -Encoding UTF8;$Hash=(Get-FileHash $StatePath -Algorithm SHA256).Hash
    Write-Host "";Write-Host "Outcome: $Outcome";Write-Host "State: $StatePath";Write-Host "State SHA256: $Hash";Write-Host "Install/launch: $InstallStarted / $LaunchStarted";Write-Host "Anna PID: $NewPid";Write-Host "Secondary subscriptions/publishes: 0 / 0"
}
if($Outcome -ne "PASS"){throw "r4.3 Anna runtime incomplete; see state"}
Write-Host "R4.3 ANNA OBSERVE-ONLY RUNTIME PASS";$Metrics|ConvertTo-Json -Depth 4
