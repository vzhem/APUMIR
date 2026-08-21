$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$ExpectedBranch = "arena/01a000bc-apumir"
$ExpectedApplicationCommit = "0181b3496cde81477a01e49f8d9977d7c325a2ca"
$GeneratedSoRelative = "android-app/app/src/main/jniLibs/arm64-v8a/libp2p_core.so"
$ExpectedNativeSize = 7248576
$ExpectedNativeHash = "E6C34E86F18D9F63B9A641E3FD9FAFD67D5F1B7101729B2CB3DF25163380095B"
$ParentStatePath = Join-Path $env:TEMP "apu-r4.4-mixed-v15-v13-zhenya-observe1.json"
$ExpectedParentStateHash = "90BC69ABFB5E5BE0D1A37623C13324F0D276402290AF5E6C4B385BDB78886B5F"
$StatePath = Join-Path $env:TEMP "apu-r4.4-mixed-v15-v13-identity1.json"
$EvidenceDir = Join-Path $env:TEMP "apu-r4.4-mixed-v15-v13-identity1-evidence"
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$PackageName = "com.vladimir.messenger"
$PresenceWaitSeconds = 135

$Phones = @(
    [pscustomobject]@{ Name="Anna"; Serial="AUYF6R5923006121"; Uid=10425; VersionName="v11.16.15"; VersionCode=11016015; FirstInstall="2026-08-08 11:40:39"; ProcessId="12943" },
    [pscustomobject]@{ Name="Zhenya"; Serial="3B665800EES00000"; Uid=10395; VersionName="v11.16.13"; VersionCode=11016013; FirstInstall="2026-08-08 17:31:18"; ProcessId="14811" },
    [pscustomobject]@{ Name="Stas"; Serial="11567254BK001192"; Uid=10387; VersionName="v11.16.13"; VersionCode=11016013; FirstInstall="2026-08-10 12:41:10"; ProcessId="23149" }
)

if (Test-Path -LiteralPath $StatePath) {
    throw "R4.4 MIXED IDENTITY1 ALREADY ATTEMPTED - DO NOT REPEAT: $StatePath"
}
if (Test-Path -LiteralPath $EvidenceDir) {
    throw "R4.4 MIXED IDENTITY1 EVIDENCE ALREADY EXISTS - DO NOT REPEAT: $EvidenceDir"
}
foreach ($RequiredPath in @($Adb, $ParentStatePath)) {
    if (-not (Test-Path -LiteralPath $RequiredPath -PathType Leaf)) {
        throw "Required mixed identity input missing: $RequiredPath"
    }
}

$ParentStateHash = (Get-FileHash -LiteralPath $ParentStatePath -Algorithm SHA256).Hash
if ($ParentStateHash -ne $ExpectedParentStateHash) {
    throw "Zhenya observe1 state hash mismatch: $ParentStateHash"
}
$ParentState = Get-Content -LiteralPath $ParentStatePath -Raw | ConvertFrom-Json
$ParentValid = $ParentState.outcome -eq "PASS"
$ParentValid = $ParentValid -and [string]$ParentState.observedProcessId -eq "14811"
$ParentValid = $ParentValid -and [int]$ParentState.metrics.incomingPublish -ge 1
$ParentValid = $ParentValid -and [int]$ParentState.metrics.heartbeat -ge 1
$ParentValid = $ParentValid -and [int]$ParentState.metrics.pollErrors -eq 0
$ParentValid = $ParentValid -and $ParentState.installStarted -eq $false
$ParentValid = $ParentValid -and $ParentState.launchStarted -eq $false
$ParentValid = $ParentValid -and $ParentState.networkChanged -eq $false
$ParentValid = $ParentValid -and $ParentState.userPayloadPublished -eq $false
$ParentValid = $ParentValid -and $ParentState.automaticRetry -eq $false
if (-not $ParentValid) {
    throw "Parent observe1 state does not prove exact mixed identity prerequisites"
}

$CurrentBranch = ((& git branch --show-current) -join "").Trim()
if ($LASTEXITCODE -ne 0 -or $CurrentBranch -ne $ExpectedBranch) {
    throw "Wrong branch: expected=$ExpectedBranch actual=$CurrentBranch"
}
$CurrentHead = ((& git rev-parse HEAD) -join "").Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Cannot resolve Windows HEAD"
}
& git diff --quiet $ExpectedApplicationCommit $CurrentHead -- rust-core android-app build-rust.ps1
if ($LASTEXITCODE -ne 0) {
    throw "Application source differs from exact r4.4 integration source"
}
$StatusBefore = @(& git status --porcelain=v1 --untracked-files=all)
$UnexpectedBefore = @($StatusBefore | Where-Object { $_ -ne " M $GeneratedSoRelative" })
if ($UnexpectedBefore.Count -gt 0) {
    throw "Unexpected identity worktree changes: $($UnexpectedBefore -join '; ')"
}
$SoPath = Join-Path $RepoRoot $GeneratedSoRelative
$NativeFile = Get-Item -LiteralPath $SoPath
$NativeHash = (Get-FileHash -LiteralPath $SoPath -Algorithm SHA256).Hash
if ($NativeFile.Length -ne $ExpectedNativeSize -or $NativeHash -ne $ExpectedNativeHash) {
    throw "Dual integration native mismatch: size=$($NativeFile.Length) hash=$NativeHash"
}

New-Item -ItemType Directory -Path $EvidenceDir | Out-Null

function Invoke-Captured {
    param(
        [Parameter(Mandatory = $true)][string]$File,
        [Parameter(Mandatory = $true)][string[]]$ArgumentList,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $OutPath = Join-Path $EvidenceDir "$Label.stdout.log"
    $ErrPath = Join-Path $EvidenceDir "$Label.stderr.log"
    $NativeProcess = Start-Process -FilePath $File -ArgumentList $ArgumentList -WorkingDirectory $RepoRoot `
        -RedirectStandardOutput $OutPath -RedirectStandardError $ErrPath -PassThru
    if (-not $NativeProcess.WaitForExit(120000)) { throw "$Label process $($NativeProcess.Id) timed out" }
    $NativeProcess.WaitForExit(); $NativeProcess.Refresh()
    $RawExitCode = $NativeProcess.ExitCode
    $ExitCodeAvailable = $null -ne $RawExitCode
    $NormalizedExitCode = $null
    if ($ExitCodeAvailable) { $NormalizedExitCode = [int]$RawExitCode }
    return [pscustomobject]@{
        ExitCodeAvailable=$ExitCodeAvailable; ExitCode=$NormalizedExitCode
        Out=@(Get-Content -LiteralPath $OutPath); Err=@(Get-Content -LiteralPath $ErrPath)
        OutPath=$OutPath; ErrPath=$ErrPath
    }
}

function Get-PhoneState {
    param([Parameter(Mandatory = $true)][object]$Phone,[Parameter(Mandatory = $true)][string]$Phase)
    $Safe=$Phone.Name.ToLowerInvariant()
    $Uid=Invoke-Captured $Adb @("-s",$Phone.Serial,"shell","cmd","package","list","packages","-U",$PackageName) "$Safe-$Phase-uid"
    $Dump=Invoke-Captured $Adb @("-s",$Phone.Serial,"shell","dumpsys","package",$PackageName) "$Safe-$Phase-package"
    $Proc=Invoke-Captured $Adb @("-s",$Phone.Serial,"shell","pidof",$PackageName) "$Safe-$Phase-process"
    if ($Uid.Err.Count+$Dump.Err.Count+$Proc.Err.Count -ne 0) { throw "$($Phone.Name) state returned stderr" }
    if ($Uid.ExitCodeAvailable -and $Uid.ExitCode -ne 0) { throw "$($Phone.Name) UID command failed" }
    if ($Dump.ExitCodeAvailable -and $Dump.ExitCode -ne 0) { throw "$($Phone.Name) package command failed" }
    if ($Proc.ExitCodeAvailable -and $Proc.ExitCode -notin @(0,1)) { throw "$($Phone.Name) process command failed" }
    $UT=$Uid.Out -join ""; $DT=$Dump.Out -join "`n"; $PT=($Proc.Out -join " ").Trim()
    $UM=[regex]::Match($UT,"uid:(\d+)"); $VN=[regex]::Match($DT,"versionName=([^\s\r\n]+)")
    $VC=[regex]::Match($DT,"versionCode=(\d+)"); $FI=[regex]::Match($DT,"firstInstallTime=([^\r\n]+)")
    $DD=[regex]::Match($DT,"dataDir=([^\s\r\n]+)")
    if (-not $UM.Success -or -not $VN.Success -or -not $VC.Success -or -not $FI.Success -or -not $DD.Success) {
        throw "$($Phone.Name) state parse failed"
    }
    $Ids=@()
    if (-not [string]::IsNullOrWhiteSpace($PT)) {
        $Tokens=@($PT -split "\s+"); if (@($Tokens|Where-Object{$_ -notmatch "^\d+$"}).Count -ne 0) { throw "$($Phone.Name) PID parse failed" }; $Ids=@($Tokens)
    }
    return [pscustomobject]@{Name=$Phone.Name;Uid=[int64]$UM.Groups[1].Value;VersionName=$VN.Groups[1].Value.Trim();VersionCode=[int64]$VC.Groups[1].Value;FirstInstall=$FI.Groups[1].Value.Trim();DataDir=$DD.Groups[1].Value.Trim();ProcessIds=@($Ids)}
}

function Test-PhoneState {
    param([object]$Phone,[object]$State)
    $Valid=$State.Uid -eq $Phone.Uid
    $Valid=$Valid -and $State.VersionName -eq $Phone.VersionName
    $Valid=$Valid -and $State.VersionCode -eq $Phone.VersionCode
    $Valid=$Valid -and $State.FirstInstall -eq $Phone.FirstInstall
    $Valid=$Valid -and $State.DataDir -eq "/data/user/0/$PackageName"
    $Valid=$Valid -and $State.ProcessIds.Count -eq 1
    $Valid=$Valid -and [string]$State.ProcessIds[0] -eq $Phone.ProcessId
    return $Valid
}

function Get-DeviceEpoch {
    param([object]$Phone)
    $Safe=$Phone.Name.ToLowerInvariant()
    $Result=Invoke-Captured $Adb @("-s",$Phone.Serial,"shell","date","+%s") "$Safe-baseline-epoch"
    $Text=($Result.Out -join "").Trim()
    if ($Result.Err.Count -ne 0 -or $Text -notmatch "^\d+$") { throw "$($Phone.Name) epoch parse failed" }
    if ($Result.ExitCodeAvailable -and $Result.ExitCode -ne 0) { throw "$($Phone.Name) epoch command failed" }
    return [int64]$Text
}

function Get-P2pLines {
    param([object]$Phone,[int64]$BaselineEpoch)
    $Safe=$Phone.Name.ToLowerInvariant()
    $Result=Invoke-Captured $Adb @("-s",$Phone.Serial,"logcat","-d","-v","epoch","p2p_core:V","*:S") "$Safe-identity-p2p"
    if ($Result.Err.Count -ne 0) { throw "$($Phone.Name) p2p log returned stderr" }
    if ($Result.ExitCodeAvailable -and $Result.ExitCode -ne 0) { throw "$($Phone.Name) p2p log failed" }
    $All=[System.Collections.Generic.List[string]]::new();$Fresh=[System.Collections.Generic.List[string]]::new()
    $Pattern="^\s*(\d+\.\d+)\s+{0}\s+" -f [regex]::Escape($Phone.ProcessId)
    foreach($Line in $Result.Out){$Match=[regex]::Match($Line,$Pattern);if(-not $Match.Success){continue};$All.Add($Line);$Epoch=[double]::Parse($Match.Groups[1].Value,[Globalization.CultureInfo]::InvariantCulture);if($Epoch -ge [double]$BaselineEpoch){$Fresh.Add($Line)}}
    return [pscustomobject]@{All=@($All);Fresh=@($Fresh)}
}

function Get-OwnNodeId {
    param([string[]]$Lines)
    $Ids=@(
        $Lines | ForEach-Object {
            $Match=[regex]::Match($_,"(?:Engine OK\. NodeId=|Engine started\. NodeId:\s*)(pk_[A-Za-z0-9_-]+)")
            if($Match.Success){$Match.Groups[1].Value}
        } | Sort-Object -Unique
    )
    if($Ids.Count -eq 1){return $Ids[0]}
    return $null
}

function Get-PeerIds {
    param([string[]]$Lines)
    return @(
        $Lines | ForEach-Object {
            $Match=[regex]::Match($_,"MQTT: peer online: .*\((pk_[A-Za-z0-9_-]+)\)")
            if($Match.Success){$Match.Groups[1].Value}
        } | Sort-Object -Unique
    )
}

$Outcome="INCOMPLETE_DO_NOT_REPEAT";$Failure=$null;$ObservationStarted=$false
$Baselines=[ordered]@{};$PreStates=[ordered]@{};$FinalStates=[ordered]@{}
$FullLines=[ordered]@{};$FreshPeerSets=[ordered]@{};$NodeIds=[ordered]@{}

try {
    $Devices=Invoke-Captured $Adb @("devices") "adb-devices"
    if($Devices.ExitCodeAvailable -and $Devices.ExitCode -ne 0){throw "adb devices failed"}
    $Connected=@($Devices.Out|Select-Object -Skip 1|Where-Object{$_ -match "\S"})
    foreach($Phone in $Phones){$Pattern="^{0}\s+device$" -f [regex]::Escape($Phone.Serial);if(@($Connected|Where-Object{$_ -match $Pattern}).Count -ne 1){throw "$($Phone.Name) is not connected exactly once"}}
    foreach ($Phone in $Phones) {
        $PhoneState = Get-PhoneState $Phone "pre"
        $PreStates[$Phone.Name] = $PhoneState
        if (-not (Test-PhoneState $Phone $PhoneState)) {
            throw "$($Phone.Name) pre-identity/PID gate failed"
        }
        $Baselines[$Phone.Name] = Get-DeviceEpoch $Phone
    }

    $ObservationStarted=$true
    Start-Sleep -Seconds $PresenceWaitSeconds

    foreach ($Phone in $Phones) {
        $Log = Get-P2pLines $Phone ([int64]$Baselines[$Phone.Name])
        $FullLines[$Phone.Name] = $Log.All
        $FreshPeerSets[$Phone.Name] = @(Get-PeerIds $Log.Fresh)
        $Final = Get-PhoneState $Phone "final"
        $FinalStates[$Phone.Name] = $Final
        if (-not (Test-PhoneState $Phone $Final)) {
            throw "$($Phone.Name) final identity/PID gate failed"
        }
    }

    foreach($Phone in $Phones){$Own=Get-OwnNodeId ([string[]]$FullLines[$Phone.Name]);if($null -ne $Own){$NodeIds[$Phone.Name]=$Own}}
    foreach($Phone in $Phones){
        if($NodeIds.Contains($Phone.Name)){continue}
        $OtherNames=@($Phones|Where-Object{$_.Name -ne $Phone.Name}|ForEach-Object{$_.Name})
        $OwnPeers=@($FreshPeerSets[$Phone.Name]);$A=@($FreshPeerSets[$OtherNames[0]]);$B=@($FreshPeerSets[$OtherNames[1]])
        $Candidates=@($A|Where-Object{$B -contains $_ -and $OwnPeers -notcontains $_}|Sort-Object -Unique)
        if($Candidates.Count -ne 1){throw "Cannot resolve exactly one node ID for $($Phone.Name): $($Candidates -join ',')"}
        $NodeIds[$Phone.Name]=$Candidates[0]
    }
    if(@($NodeIds.Values|Sort-Object -Unique).Count -ne 3){throw "Resolved node IDs are not unique"}
    foreach($Phone in $Phones){
        $Own=[string]$NodeIds[$Phone.Name]
        if($Own -notmatch "^pk_[A-Za-z0-9_-]+$" -or $Own.Length -gt 128){throw "Unsafe node ID for $($Phone.Name)"}
        if(@($FreshPeerSets[$Phone.Name]).Count -lt 2){throw "$($Phone.Name) did not observe two fresh peers"}
        foreach ($Other in @($Phones | Where-Object { $_.Name -ne $Phone.Name })) {
            $OtherNodeId = [string]$NodeIds[$Other.Name]
            if (@($FreshPeerSets[$Phone.Name]) -notcontains $OtherNodeId) {
                throw "$($Phone.Name) did not freshly observe $($Other.Name)"
            }
        }
    }
    $Outcome="PASS"
} catch {$Failure=$_.Exception.Message;throw} finally {
    $State=[ordered]@{schema=1;purpose="read-only mixed-version three-phone node identity and fresh common-HiveMQ visibility";outcome=$Outcome;failure=$Failure;completedUtc=(Get-Date).ToUniversalTime().ToString("o");applicationCommit=$ExpectedApplicationCommit;windowsHead=$CurrentHead;parentObserveState=$ParentStatePath;parentObserveStateSha256=$ParentStateHash;evidenceDirectory=$EvidenceDir;presenceWaitSeconds=$PresenceWaitSeconds;observationStarted=$ObservationStarted;baselines=$Baselines;preStates=$PreStates;finalStates=$FinalStates;nodeIds=$NodeIds;freshPeerSets=$FreshPeerSets;installStarted=$false;launchStarted=$false;forceStopped=$false;logcatCleared=$false;networkChanged=$false;userPayloadPublished=$false;publicTrafficInjected=$false;automaticRetry=$false}
    $State|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $StatePath -Encoding UTF8;$Hash=(Get-FileHash -LiteralPath $StatePath -Algorithm SHA256).Hash
    Write-Host "";Write-Host "Outcome:             $Outcome";Write-Host "State:               $StatePath";Write-Host "State SHA256:        $Hash";Write-Host "Node IDs:            $($NodeIds|ConvertTo-Json -Compress)";Write-Host "Fresh peer sets:     $($FreshPeerSets|ConvertTo-Json -Compress)";Write-Host "Phone changes:       False";Write-Host "Injected traffic:    False"
}
if($Outcome -ne "PASS"){throw "r4.4 mixed identity1 did not pass; see state: $StatePath"}
Write-Host "R4.4 MIXED THREE-PHONE IDENTITY1 PASS"
