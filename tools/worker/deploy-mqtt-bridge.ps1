# ============================================================================
# deploy-mqtt-bridge.ps1 - deploy Cloudflare worker "p2p-relay" with the
# MqttBridge Durable Object (WSS bridge for MQTT in restricted mobile
# networks) via the official Cloudflare REST API.
#
# The dashboard cannot create the class namespace (needs a deploy
# migration); this script can. Node.js / npm / wrangler NOT required.
#
# Requirements: an API token (template "Edit Cloudflare Workers"),
# Windows 10/11 with built-in curl.exe.
#
# Run:
#   powershell -ExecutionPolicy Bypass -File .\deploy.ps1
#
# Steps:
#   1) ask for the API token and verify it;
#   2) find the three KV namespaces (APU_VAULT, REGISTRY, RELAY);
#   3) download worker.js from the v11.74.2 release tag (size check);
#   4) deploy with migration new_sqlite_classes MqttBridge and the
#      MQTT_BRIDGE binding (KV bindings preserved).
# ============================================================================

param([string]$Token = "")

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Account   = "caf1ba3a42dc32ef36978d9ca2bce5ad"
$Script    = "p2p-relay"
$WorkerUrl = "https://raw.githubusercontent.com/vzhem/APUMIR/v11.74.7/tools/worker/p2p_relay_worker.js"

Write-Host "=== 1/5 Token ===" -ForegroundColor Cyan
if ([string]::IsNullOrWhiteSpace($Token)) {
    $Token = Read-Host "Paste your Cloudflare API token (Edit Cloudflare Workers template) and press Enter"
}
$Token = $Token.Trim()
$H = @{ "Authorization" = "Bearer $Token" }

Write-Host "=== 2/5 Verifying token ===" -ForegroundColor Cyan
try {
    $verify = Invoke-RestMethod -Uri "https://api.cloudflare.com/client/v4/user/tokens/verify" -Headers $H
    if (-not $verify.success) { throw "API returned success=false" }
    Write-Host ("  OK: token is valid (" + $verify.result.status + ")") -ForegroundColor Green
} catch {
    Write-Host "ERROR: token rejected: $_" -ForegroundColor Red
    exit 1
}

Write-Host "=== 3/5 Looking up KV namespaces ===" -ForegroundColor Cyan
$kv = Invoke-RestMethod -Uri "https://api.cloudflare.com/client/v4/accounts/$Account/storage/kv/namespaces?per_page=100" -Headers $H
function Pick-Kv($needle) {
    $ns = $kv.result | Where-Object { $_.title -like "*$needle*" } | Select-Object -First 1
    if ($ns) {
        Write-Host ("  $needle -> " + $ns.id + "  [" + $ns.title + "]") -ForegroundColor Gray
        return $ns.id
    }
    Write-Host "  NOT FOUND: $needle" -ForegroundColor Red
    return $null
}
$VaultId    = Pick-Kv "apu-identity-vault"
$RegistryId = Pick-Kv "P2P_REGISTRY"
$RelayId    = Pick-Kv "p2p-relay-kv"
if (-not $VaultId -or -not $RegistryId -or -not $RelayId) {
    Write-Host "All KV namespaces of this account (send a screenshot if this looks wrong):" -ForegroundColor Yellow
    $kv.result | ForEach-Object { Write-Host ("  " + $_.title + " = " + $_.id) }
    exit 1
}

Write-Host "=== 4/5 Downloading worker.js ===" -ForegroundColor Cyan
Invoke-WebRequest -Uri $WorkerUrl -OutFile "worker.js"
$lines = (Get-Content "worker.js").Count
Write-Host ("  worker.js: $lines lines (expected about 792)")
if ($lines -lt 700) {
    Write-Host "ERROR: file looks truncated, download failed." -ForegroundColor Red
    exit 1
}

$bindings = @(
    @{ type = "kv_namespace"; name = "APU_VAULT"; namespace_id = $VaultId },
    @{ type = "kv_namespace"; name = "REGISTRY";  namespace_id = $RegistryId },
    @{ type = "kv_namespace"; name = "RELAY";     namespace_id = $RelayId },
    @{ type = "durable_object_namespace"; name = "MQTT_BRIDGE"; class_name = "MqttBridge" }
)
$metadata = @{
    main_module        = "worker.js"
    compatibility_date = "2025-09-15"
    workers_dev        = $true
    bindings           = $bindings
    migrations         = @{ new_tag = "v1"; new_sqlite_classes = @("MqttBridge") }
}
[IO.File]::WriteAllText("$PWD\metadata.json", ($metadata | ConvertTo-Json -Depth 6), (New-Object System.Text.UTF8Encoding($false)))

if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) {
    Write-Host "ERROR: curl.exe not found (it is built into Windows 10 1803+)." -ForegroundColor Red
    exit 1
}

Write-Host "=== 5/5 Deploying ===" -ForegroundColor Cyan
function Deploy-WithMetadata($metadataFile) {
    curl.exe -s -X PUT "https://api.cloudflare.com/client/v4/accounts/$Account/workers/scripts/$Script" `
        -H "Authorization: Bearer $Token" `
        -F "metadata=@$metadataFile;type=application/json" `
        -F 'worker.js=@worker.js;type=application/javascript+module'
}

# First attempt: with the class migration (fresh accounts). If the migration
# tag is already applied (error 10079) - retry WITHOUT migrations: the class
# already exists, we only update the script and bindings.
$raw = Deploy-WithMetadata "metadata.json"
$resp = $raw | ConvertFrom-Json
if (-not $resp.success -and ($resp.errors | ForEach-Object { $_.code }) -contains 10079) {
    Write-Host "  migration v1 already applied - retrying without migrations (class exists)" -ForegroundColor Yellow
    $metadataNoMig = @{
        main_module        = "worker.js"
        compatibility_date = "2025-09-15"
        workers_dev        = $true
        bindings           = $bindings
    }
    [IO.File]::WriteAllText("$PWD\metadata2.json", ($metadataNoMig | ConvertTo-Json -Depth 6), (New-Object System.Text.UTF8Encoding($false)))
    $raw = Deploy-WithMetadata "metadata2.json"
    $resp = $raw | ConvertFrom-Json
}
if ($resp.success) {
    Write-Host ""
    Write-Host "SUCCESS: deployed. Class MqttBridge and binding MQTT_BRIDGE are live." -ForegroundColor Green
    Write-Host "Test - open browser console (F12) on any page, paste and press Enter:"
    Write-Host '  const w = new WebSocket("wss://p2p-relay.1985vzhem.workers.dev/mqtt","mqtt"); w.onopen=()=>console.log("OPEN",w.protocol); w.onclose=e=>console.log("CLOSE",e.code,e.reason);'
    Write-Host "Expected in 1-2 seconds: OPEN mqtt"
} else {
    Write-Host "DEPLOY ERROR (send a screenshot):" -ForegroundColor Red
    $resp | ConvertTo-Json -Depth 6
}
