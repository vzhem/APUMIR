param(
    [Parameter(Mandatory = $true)][string]$ExpectedHead,
    [Parameter(Mandatory = $true)][string]$ScriptPath,
    [Parameter(Mandatory = $true)][string]$ExpectedBlob,
    [ValidateRange(1, 5)][int]$Attempts = 5
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot

$RemoteBranch = "arena/01a0149e-apumir"
$RetryDelaysSeconds = @(5, 15, 30, 60)
$RetryableNetworkError = "Failed to connect|Could not connect|Connection timed out|" +
    "Could not resolve host|Connection reset|Recv failure|TLS|schannel|HTTP/2 stream"

$Pulled = $false
for ($Attempt = 1; $Attempt -le $Attempts; $Attempt++) {
    Write-Host "GitHub pull attempt $Attempt/$Attempts"
    $OldPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $Output = ((& git -c gc.auto=0 pull --ff-only origin $RemoteBranch 2>&1 |
        ForEach-Object { $_.ToString() }) -join "`n")
    $Code = $LASTEXITCODE
    $ErrorActionPreference = $OldPreference
    if ($Output) { Write-Host $Output }

    if ($Code -eq 0) {
        $Pulled = $true
        break
    }
    if ($Output -notmatch $RetryableNetworkError) {
        throw "Git pull failed with a non-retryable error; gate not started"
    }
    if ($Attempt -eq $Attempts) { break }
    $Delay = $RetryDelaysSeconds[[Math]::Min($Attempt - 1, $RetryDelaysSeconds.Count - 1)]
    Write-Host "Transient GitHub network error; retrying in $Delay seconds"
    Start-Sleep -Seconds $Delay
}
if (-not $Pulled) { throw "GitHub remained unavailable after $Attempts attempts; gate not started" }

$Head = ((& git rev-parse HEAD) -join "").Trim()
if ($Head -ne $ExpectedHead) { throw "STOP: unexpected HEAD after verified pull" }
$NormalizedScriptPath = ($ScriptPath.Replace("\", "/") -replace "^[./]+", "")
$Blob = ((& git rev-parse "HEAD:$NormalizedScriptPath") -join "").Trim()
if ($LASTEXITCODE -ne 0 -or $Blob -ne $ExpectedBlob) {
    throw "STOP: verified gate script blob mismatch"
}
$ResolvedScript = Join-Path $RepoRoot $NormalizedScriptPath
if (-not (Test-Path $ResolvedScript -PathType Leaf)) { throw "Verified gate script missing" }

Write-Host "GitHub pull and script verification PASS. Starting gate."
& $ResolvedScript
