# ============================================================================
# make_update_patch.ps1 - compact update patch (APUBSP1) on the OWNER'S PC.
#
# Why this exists: the release APKs cannot be downloaded from the Arena
# sandbox (release-assets.githubusercontent.com and workers.dev are blocked
# there), and the Arena GitHub App may not push workflow files, so there is
# no CI step either. The owner's PC reaches GitHub normally and has gh CLI
# (see promote-release.ps1), so the per-release patch is generated HERE.
#
# ASCII only on purpose: PowerShell 5.1 misreads UTF-8 no-BOM (same rule as
# make-release.ps1). No Russian text in this file.
#
# Format APUBSP1 (all numbers big-endian), byte-identical to the python
# reference tools/ci/make_apk_patch.py:
#   magic "APUBSP1" (7 bytes)
#   u8    blockSizeBits (12 = 4096)
#   u64   newFileSize
#   u32   oldBlockCount
#   u32   newBlockCount
#   oldBlockCount * 16 bytes: first 16 bytes of sha256 of each OLD block
#   newBlockCount * 5 bytes:  u8 kind (1=REF old block index, 2=RAW) +
#                             u32 (REF: index; RAW: deflate stream length)
#   then raw DEFLATE streams of all RAW blocks, concatenated
# The phone-side applier is android-app .../data/update/ApkDiffPatch.kt.
#
# Usage (from the repo root, gh must be logged in):
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\ci\make_update_patch.ps1 -FromTag v11.74.28 -ToTag v11.74.29 -Upload
#
# Offline mode (files already on disk):
#   ... -OldPath old.apk -NewPath new.apk -OutFile patch.bspatch
# Verify an existing patch against a pair of files:
#   ... -Apply -PatchFile patch.bspatch -OldPath old.apk -OutFile rebuilt.apk
#
# Safety: before uploading (or before exiting, even without -Upload) the
# script APPLIES the patch it just made onto the old APK and requires the
# result to be byte-identical (sha256) to the new APK. A mismatch is FATAL.
# ============================================================================

param(
    [string]$FromTag = '',
    [string]$ToTag = '',
    [string]$Repo = '',
    [string]$OldPath = '',
    [string]$NewPath = '',
    [string]$PatchFile = '',
    [string]$OutFile = '',
    [switch]$Apply,
    [switch]$Upload
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression | Out-Null

$BlockBits = 12
$BlockSize = 4096

function Write-U32BE([System.IO.MemoryStream]$Stream, [uint32]$Value) {
    # Shift as [long]: PowerShell's -shl/-shr on int/uint32 wrap at 32 bits
    # (unchecked C# semantics), and a wrapped negative int cannot be cast to
    # uint32. Long shifts stay positive and exact.
    $v = [long]$Value
    $b = [byte[]]@(
        [byte](($v -shr 24) -band 0xFF),
        [byte](($v -shr 16) -band 0xFF),
        [byte](($v -shr 8) -band 0xFF),
        [byte]($v -band 0xFF)
    )
    $Stream.Write($b, 0, 4)
}

function Read-U32BE([byte[]]$Data, [int]$Offset) {
    return [uint32](
        (([long]$Data[$Offset] -shl 24) -bor
         ([long]$Data[$Offset + 1] -shl 16) -bor
         ([long]$Data[$Offset + 2] -shl 8) -bor
         ([long]$Data[$Offset + 3]))
    )
}

function Read-U64BE([byte[]]$Data, [int]$Offset) {
    $hi = [uint64](Read-U32BE $Data $Offset)
    $lo = [uint64](Read-U32BE $Data ($Offset + 4))
    return [uint64]($hi * [uint64]4294967296 + $lo)
}

function Get-Sha256Hex([byte[]]$Data) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return [BitConverter]::ToString($sha.ComputeHash($Data)).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Deflate-RawBlock([byte[]]$Data) {
    $ms = New-Object System.IO.MemoryStream
    # leaveOpen = $true so $ms stays readable after Dispose.
    $ds = New-Object System.IO.Compression.DeflateStream(
        $ms, [System.IO.Compression.CompressionLevel]::Optimal, $true)
    try {
        $ds.Write($Data, 0, $Data.Length)
    } finally {
        $ds.Dispose()
    }
    return $ms.ToArray()
}

function Inflate-RawBlock([byte[]]$Data, [int]$Start, [int]$Length, [int]$Expected) {
    $chunk = New-Object byte[] $Length
    [Array]::Copy($Data, $Start, $chunk, 0, $Length)
    $src = New-Object System.IO.MemoryStream(, $chunk)
    $ds = New-Object System.IO.Compression.DeflateStream(
        $src, [System.IO.Compression.CompressionMode]::Decompress)
    try {
        $out = New-Object byte[] $Expected
        $total = 0
        while ($total -lt $Expected) {
            $n = $ds.Read($out, $total, $Expected - $total)
            if ($n -le 0) { throw 'APUBSP1: deflate stream ended before a full block' }
            $total += $n
        }
        return $out
    } finally {
        $ds.Dispose()
    }
}

function Get-BlockDigest([System.Security.Cryptography.SHA256]$Sha, [byte[]]$Source, [int]$Offset, [int]$Length) {
    $blk = New-Object byte[] $Length
    [Array]::Copy($Source, $Offset, $blk, 0, $Length)
    return , $Sha.ComputeHash($blk)
}

function Make-Patch([byte[]]$Old, [byte[]]$New, [string]$OutPath) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        # Pass 1: digest index of the OLD file (first block with a given
        # digest wins, matching python's setdefault).
        $digestMap = @{}
        $digestRows = New-Object System.IO.MemoryStream
        $oldCount = [uint32]0
        for ($off = 0; $off -lt $Old.Length; $off += $BlockSize) {
            $len = [Math]::Min($BlockSize, $Old.Length - $off)
            $h = Get-BlockDigest $Sha $Old $off $len
            $key = [Convert]::ToBase64String($h, 0, 16)
            if (-not $digestMap.ContainsKey($key)) { $digestMap[$key] = $oldCount }
            $digestRows.Write($h, 0, 16)
            $oldCount++
        }

        # Pass 2: descriptors for the NEW file.
        $desc = New-Object System.IO.MemoryStream
        $raw = New-Object System.IO.MemoryStream
        $newCount = [uint32]0
        $refCount = 0
        $rawCount = 0
        for ($off = 0; $off -lt $New.Length; $off += $BlockSize) {
            $len = [Math]::Min($BlockSize, $New.Length - $off)
            $h = Get-BlockDigest $Sha $New $off $len
            $key = [Convert]::ToBase64String($h, 0, 16)
            if ($digestMap.ContainsKey($key)) {
                $desc.WriteByte([byte]1)
                Write-U32BE $desc ([uint32]$digestMap[$key])
                $refCount++
            } else {
                $blk = New-Object byte[] $len
                [Array]::Copy($New, $off, $blk, 0, $len)
                $packed = Deflate-RawBlock $blk
                $desc.WriteByte([byte]2)
                Write-U32BE $desc ([uint32]$packed.Length)
                $raw.Write($packed, 0, $packed.Length)
                $rawCount++
            }
            $newCount++
        }

        $out = New-Object System.IO.MemoryStream
        $magic = [System.Text.Encoding]::ASCII.GetBytes('APUBSP1')
        $out.Write($magic, 0, 7)
        $out.WriteByte([byte]$BlockBits)

        # u64 newFileSize, big-endian (length is far below 2^32, but keep the
        # full u64 form for exactness).
        $fileLen = [uint64]$New.Length
        $hi = [uint32][Math]::Floor($fileLen / [uint64]4294967296)
        $lo = [uint32]($fileLen - [uint64]$hi * [uint64]4294967296)
        Write-U32BE $out $hi
        Write-U32BE $out $lo
        Write-U32BE $out $oldCount
        Write-U32BE $out $newCount

        $digestRows.WriteTo($out)
        $desc.WriteTo($out)
        $raw.WriteTo($out)

        $bytes = $out.ToArray()
        [System.IO.File]::WriteAllBytes($OutPath, $bytes)
    } finally {
        $sha.Dispose()
    }

    Write-Output (
        'APUBSP1: old {0:N1} MB, new {1:N1} MB -> patch {2:N0} KB ({3:N0}% of new); blocks: {4} reused, {5} raw' -f
        ($Old.Length / 1MB), ($New.Length / 1MB), ((Get-Item $OutPath).Length / 1KB),
        (100.0 * (Get-Item $OutPath).Length / $New.Length), $refCount, $rawCount)
}

function Apply-Patch([byte[]]$Patch, [byte[]]$Old, [string]$OutPath) {
    if ($Patch.Length -lt 24) { throw 'APUBSP1: patch too small' }
    $magic = [System.Text.Encoding]::ASCII.GetString($Patch, 0, 7)
    if ($magic -ne 'APUBSP1') { throw "APUBSP1: bad magic '$magic'" }
    $bits = [int]$Patch[7]
    if ($bits -ne $BlockBits) { throw "APUBSP1: unsupported block size bits $bits" }
    $bs = 1 -shl $bits
    $newFileSize = [int64](Read-U64BE $Patch 8)
    $oldCount = [int64](Read-U32BE $Patch 16)
    $newCount = [int64](Read-U32BE $Patch 20)

    $digestTable = @{}
    $off = 24
    for ($i = [int64]0; $i -lt $oldCount; $i++) {
        $d = New-Object byte[] 16
        [Array]::Copy($Patch, [int]($off + $i * 16), $d, 0, 16)
        $digestTable[[Convert]::ToBase64String($d)] = $i
    }
    $off += [int]($oldCount * 16)

    # The phone verifies the installed file against these digests; here we do
    # the same against the old file we were given.
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        for ($i = [int64]0; $i -lt $oldCount; $i++) {
            $o = [int]($i * $bs)
            if ($o -ge $Old.Length) { throw 'APUBSP1: old file shorter than the patch expects' }
            $len = [Math]::Min($bs, $Old.Length - $o)
            $h = Get-BlockDigest $Sha $Old $o $len
            $key = [Convert]::ToBase64String($h, 0, 16)
            if (-not $digestTable.ContainsKey($key)) {
                throw "APUBSP1: old file does not match the patch (block $i)"
            }
        }
    } finally {
        $sha.Dispose()
    }

    $out = New-Object System.IO.MemoryStream
    $rawPos = [int]$off + [int]($newCount * 5)
    $written = [int64]0
    for ($i = [int64]0; $i -lt $newCount; $i++) {
        $dOff = [int]($off + $i * 5)
        $kind = [int]$Patch[$dOff]
        $value = [int64](Read-U32BE $Patch ($dOff + 1))
        $expected = [int][Math]::Min([int64]$bs, $newFileSize - $written)
        if ($expected -le 0) { throw 'APUBSP1: more blocks than newFileSize allows' }
        if ($kind -eq 1) {
            $o = [int]($value * $bs)
            if ($o -ge $Old.Length) { throw "APUBSP1: REF beyond old file (block $i)" }
            $len = [Math]::Min($expected, $Old.Length - $o)
            if ($len -ne $expected) { throw "APUBSP1: REF block $i truncated" }
            $seg = New-Object byte[] $expected
            [Array]::Copy($Old, $o, $seg, 0, $expected)
            $out.Write($seg, 0, $expected)
        } elseif ($kind -eq 2) {
            $seg = Inflate-RawBlock $Patch $rawPos ([int]$value) $expected
            $out.Write($seg, 0, $expected)
            $rawPos += [int]$value
        } else {
            throw "APUBSP1: unknown descriptor kind $kind"
        }
        $written += $expected
    }
    if ($written -ne $newFileSize) { throw "APUBSP1: size mismatch ($written vs $newFileSize)" }
    $bytes = $out.ToArray()
    [System.IO.File]::WriteAllBytes($OutPath, $bytes)
    return $bytes
}

function Download-ReleaseApk([string]$Tag, [string]$Dir) {
    New-Item -ItemType Directory -Force -Path $Dir | Out-Null
    # gh prints progress to stderr; PowerShell 5.1 turns that into a
    # NativeCommandError when $ErrorActionPreference is 'Stop', so soften
    # EAP around native calls (same reason promote-release.ps1 uses cmd /c).
    $eap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & gh release download $Tag --repo $Repo --pattern '*.apk' --dir $Dir --clobber 2>&1 |
            ForEach-Object { "$_" } | Write-Output
    } finally {
        $ErrorActionPreference = $eap
    }
    if ($LASTEXITCODE -ne 0) { throw "gh release download $Tag failed" }
    $apk = Get-ChildItem -Path $Dir -Filter '*.apk' | Sort-Object Length -Descending | Select-Object -First 1
    if ($null -eq $apk) { throw "no .apk asset on release $Tag" }
    return $apk.FullName
}

# ---- mode: apply (verify a patch) -------------------------------------------
if ($Apply) {
    if ($PatchFile -eq '' -or $OldPath -eq '' -or $OutFile -eq '') {
        Write-Output 'FATAL: -Apply needs -PatchFile, -OldPath and -OutFile.'
        exit 2
    }
    $patch = [System.IO.File]::ReadAllBytes($PatchFile)
    $old = [System.IO.File]::ReadAllBytes($OldPath)
    $rebuilt = Apply-Patch $patch $old $OutFile
    Write-Output ("REBUILT ok: {0} bytes, sha256 {1}" -f $rebuilt.Length, (Get-Sha256Hex $rebuilt))
    exit 0
}

# ---- mode: generate (offline from files, or by downloading both releases) ---
if ($OldPath -eq '' -or $NewPath -eq '') {
    if ($FromTag -eq '' -or $ToTag -eq '') {
        Write-Output 'FATAL: give -FromTag and -ToTag (add -Upload to publish), or'
        Write-Output '-OldPath/-NewPath for offline generation, or -Apply to verify a patch.'
        exit 2
    }
    if ($Repo -eq '') { $Repo = 'vzhem/APUMIR' }
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("apu-patch-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    Write-Output "Downloading release APKs ($FromTag, $ToTag)..."
    $OldPath = Download-ReleaseApk $FromTag (Join-Path $tmp 'old')
    $NewPath = Download-ReleaseApk $ToTag (Join-Path $tmp 'new')
}

if ($OutFile -eq '') {
    if ($FromTag -eq '' -or $ToTag -eq '') {
        Write-Output 'FATAL: offline mode needs -OutFile.'
        exit 2
    }
    $from = $FromTag.TrimStart('v')
    $to = $ToTag.TrimStart('v')
    $OutFile = "patch-$from-to-$to.bspatch"
}

if ($Repo -eq '') { $Repo = 'vzhem/APUMIR' }

if (-not (Test-Path $OldPath)) { Write-Output "FATAL: no old file $OldPath"; exit 2 }
if (-not (Test-Path $NewPath)) { Write-Output "FATAL: no new file $NewPath"; exit 2 }

Write-Output "Generating $OutFile ..."
$oldBytes = [System.IO.File]::ReadAllBytes($OldPath)
$newBytes = [System.IO.File]::ReadAllBytes($NewPath)
Make-Patch $oldBytes $newBytes $OutFile

# Mandatory self-check: the patch must rebuild the new APK byte-for-byte.
Write-Output 'Self-check: applying the patch onto the old APK...'
$rebuiltPath = $OutFile + '.rebuilt'
$rebuilt = Apply-Patch ([System.IO.File]::ReadAllBytes($OutFile)) $oldBytes $rebuiltPath
$newSha = Get-Sha256Hex $newBytes
$rebuiltSha = Get-Sha256Hex $rebuilt
Remove-Item $rebuiltPath -ErrorAction SilentlyContinue
if ($rebuiltSha -ne $newSha) {
    Write-Output "FATAL: rebuilt sha256 $rebuiltSha != new sha256 $newSha - patch NOT uploaded."
    exit 1
}
Write-Output "Self-check ok: sha256 $newSha"

if ($Upload) {
    $eap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & gh release upload $ToTag $OutFile --repo $Repo --clobber 2>&1 |
            ForEach-Object { "$_" } | Write-Output
    } finally {
        $ErrorActionPreference = $eap
    }
    if ($LASTEXITCODE -ne 0) { throw 'gh release upload failed' }
    Write-Output "Uploaded $OutFile to release $ToTag."
} else {
    Write-Output "Not uploaded. To upload: gh release upload $ToTag $OutFile --clobber"
}
