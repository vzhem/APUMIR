Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepoRoot = 'C:\APUMIR-arena-test'
$DriveLetter = 'F'
$DriveRoot = 'F:\'
$ExpectedDiskNumber = 2
$ExpectedFriendlyName = 'General UDisk'
$ExpectedBusType = 'USB'
$ExpectedPartitionStyle = 'MBR'
$ExpectedOldLabel = 'SMARTBUY'
$ExpectedOldFileSystem = 'FAT32'
$ExpectedDriveType = 2
$MinimumSizeBytes = [int64](14GB)
$MaximumSizeBytes = [int64](16GB)
$NewLabel = 'APU_BACKUP'
$NewFileSystem = 'exFAT'
$StatePath = Join-Path $env:TEMP 'apu-v11.16.16-flash-format-v1.json'
$Utf8NoBom = New-Object Text.UTF8Encoding($false)

function Save-State {
    param(
        [Parameter(Mandatory = $true)][string]$Outcome,
        [AllowNull()][string]$Failure,
        [bool]$FormatCalled,
        [bool]$PostVerified
    )
    $State = [ordered]@{
        schema = 1
        purpose = 'Format explicitly authorized APU backup flash drive'
        generatedAtUtc = [DateTime]::UtcNow.ToString('o')
        outcome = $Outcome
        failure = $Failure
        driveLetter = 'F:'
        diskNumber = $ExpectedDiskNumber
        friendlyName = $ExpectedFriendlyName
        busType = $ExpectedBusType
        partitionStyle = $ExpectedPartitionStyle
        sizeRangeBytes = @($MinimumSizeBytes, $MaximumSizeBytes)
        oldLabel = $ExpectedOldLabel
        oldFileSystem = $ExpectedOldFileSystem
        newLabel = $NewLabel
        newFileSystem = $NewFileSystem
        formatCalled = $FormatCalled
        postVerified = $PostVerified
        phonesChanged = $false
        buildRepeated = $false
        apkChanged = $false
    }
    [IO.File]::WriteAllText($StatePath, ($State | ConvertTo-Json -Depth 5), $Utf8NoBom)
}

$FormatCalled = $false
$PostVerified = $false
try {
    Set-Location -LiteralPath $RepoRoot

    if (Test-Path -LiteralPath $StatePath) {
        throw "Flash format v1 already has state; do not repeat: $StatePath"
    }

    $Volume = Get-Volume -DriveLetter $DriveLetter -ErrorAction Stop
    $Partition = Get-Partition -DriveLetter $DriveLetter -ErrorAction Stop
    $Disk = $Partition | Get-Disk -ErrorAction Stop
    $LogicalDisk = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='F:'"

    $IdentityValid = $Disk.Number -eq $ExpectedDiskNumber
    $IdentityValid = $IdentityValid -and $Disk.FriendlyName.Trim() -eq $ExpectedFriendlyName
    $IdentityValid = $IdentityValid -and $Disk.BusType.ToString() -eq $ExpectedBusType
    $IdentityValid = $IdentityValid -and $Disk.PartitionStyle.ToString() -eq $ExpectedPartitionStyle
    $IdentityValid = $IdentityValid -and -not $Disk.IsSystem
    $IdentityValid = $IdentityValid -and -not $Disk.IsBoot
    $IdentityValid = $IdentityValid -and $Partition.DiskNumber -eq $ExpectedDiskNumber
    $IdentityValid = $IdentityValid -and $Partition.DriveLetter -eq $DriveLetter
    $IdentityValid = $IdentityValid -and [int]$LogicalDisk.DriveType -eq $ExpectedDriveType
    $IdentityValid = $IdentityValid -and $Volume.FileSystemLabel -eq $ExpectedOldLabel
    $IdentityValid = $IdentityValid -and $Volume.FileSystem -eq $ExpectedOldFileSystem
    $IdentityValid = $IdentityValid -and [int64]$Volume.Size -ge $MinimumSizeBytes
    $IdentityValid = $IdentityValid -and [int64]$Volume.Size -le $MaximumSizeBytes

    if (-not $IdentityValid) {
        throw 'F: identity changed after the approved read-only gate; formatting blocked'
    }

    $OtherDriveLetters = @(
        Get-Partition -DiskNumber $ExpectedDiskNumber -ErrorAction Stop |
            Where-Object { $_.DriveLetter -and $_.DriveLetter -ne $DriveLetter } |
            Select-Object -ExpandProperty DriveLetter
    )
    if ($OtherDriveLetters.Count -ne 0) {
        throw "Disk 2 has additional mounted partitions; formatting blocked: $($OtherDriveLetters -join ',')"
    }

    $FormatCalled = $true
    Format-Volume `
        -DriveLetter $DriveLetter `
        -FileSystem $NewFileSystem `
        -NewFileSystemLabel $NewLabel `
        -Force `
        -Confirm:$false `
        -ErrorAction Stop | Out-Null

    $PostVolume = $null
    for ($Attempt = 1; $Attempt -le 10; $Attempt++) {
        try {
            $PostVolume = Get-Volume -DriveLetter $DriveLetter -ErrorAction Stop
        }
        catch {
            $PostVolume = $null
        }
        if ($null -ne $PostVolume -and
            $PostVolume.FileSystem -eq $NewFileSystem -and
            $PostVolume.FileSystemLabel -eq $NewLabel) {
            break
        }
        Start-Sleep -Seconds 2
    }

    if ($null -eq $PostVolume) {
        throw 'F: did not remount after format'
    }
    if ($PostVolume.FileSystem -ne $NewFileSystem -or $PostVolume.FileSystemLabel -ne $NewLabel) {
        throw "Post-format identity mismatch: filesystem=$($PostVolume.FileSystem), label=$($PostVolume.FileSystemLabel)"
    }

    $PostPartition = Get-Partition -DriveLetter $DriveLetter -ErrorAction Stop
    $PostDisk = $PostPartition | Get-Disk -ErrorAction Stop
    if ($PostDisk.Number -ne $ExpectedDiskNumber -or
        $PostDisk.FriendlyName.Trim() -ne $ExpectedFriendlyName -or
        $PostDisk.BusType.ToString() -ne $ExpectedBusType -or
        $PostDisk.IsSystem -or
        $PostDisk.IsBoot) {
        throw 'Post-format physical disk identity mismatch'
    }

    $UnexpectedEntries = @(
        Get-ChildItem -LiteralPath $DriveRoot -Force -ErrorAction Stop |
            Where-Object { $_.Name -ne 'System Volume Information' }
    )
    if ($UnexpectedEntries.Count -ne 0) {
        throw "F: is not empty after format: $($UnexpectedEntries.Name -join ', ')"
    }

    $PostVerified = $true
    Save-State -Outcome 'PASS_FORMATTED_EMPTY' -Failure $null -FormatCalled $FormatCalled -PostVerified $PostVerified
    $StateHash = (Get-FileHash -LiteralPath $StatePath -Algorithm SHA256).Hash

    Write-Host 'F: FORMAT PASS; EXFAT; LABEL APU_BACKUP; EMPTY; READY FOR COMPACT BACKUP'
    Write-Host "State: $StatePath"
    Write-Host "State SHA-256: $StateHash"
}
catch {
    $Failure = $_.Exception.Message
    try {
        Save-State -Outcome 'INCOMPLETE_DO_NOT_REPEAT' -Failure $Failure -FormatCalled $FormatCalled -PostVerified $PostVerified
    }
    catch {
        Write-Warning "Unable to write format state: $($_.Exception.Message)"
    }
    throw
}
