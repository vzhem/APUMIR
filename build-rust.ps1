# build-rust.ps1 — Сборка Rust библиотеки для Android
# спользование: .\build-rust.ps1
# пционально: .\build-rust.ps1 -Arch armeabi-v7a,x86_64
# Feature-gated build: .\build-rust.ps1 -Features mqtt-secondary-observe
# r4.4 policy build: .\build-rust.ps1 -Features mqtt-dual-broker

param(
    [string[]]$Arch = @("arm64-v8a", "armeabi-v7a", "x86_64"),
    [string[]]$Features = @()
)

foreach ($Feature in $Features) {
    if ($Feature -notmatch "^[A-Za-z0-9_-]+$") {
        throw "Unsafe Cargo feature name: $Feature"
    }
}

Write-Host "=== Сборка Rust для Android ===" -ForegroundColor Yellow
Write-Host "рхитектуры: $($Arch -join ', ')"
Write-Host "Cargo features: $(if ($Features.Count -gt 0) { $Features -join ',' } else { '<default>' })"

$rustDir = Join-Path $PSScriptRoot "rust-core"
$jniDir = Join-Path $PSScriptRoot "android-app\app\src\main\jniLibs"

Push-Location $rustDir
try {
    foreach ($a in $Arch) {
        Write-Host "`n--- Сборка $a ---" -ForegroundColor Cyan
        $target = switch ($a) {
            "arm64-v8a"   { "arm64-v8a" }
            "armeabi-v7a" { "armeabi-v7a" }
            "x86_64"      { "x86_64" }
            default       { $a }
        }

        $CargoArguments = @("ndk", "-t", $target, "-o", $jniDir, "build", "--release")
        if ($Features.Count -gt 0) {
            $CargoArguments += @("--features", ($Features -join ","))
        }

        & cargo @CargoArguments
        if ($LASTEXITCODE -ne 0) {
            Write-Host "❌ шибка сборки $a" -ForegroundColor Red
            exit 1
        }
    }
    
    # UDL and native libraries must come from the same source revision. A new native
    # API with the tracked old bridge either fails Kotlin compile or ABI checks.
    & cargo run --manifest-path ../tools/uniffi-bindgen/Cargo.toml -- `
        generate src/lib.udl --language kotlin --config uniffi.toml `
        --out-dir ../android-app/app/src/main/java --no-format
    if ($LASTEXITCODE -ne 0) { throw "UniFFI generation failed; do not package the APK" }
    if ($Arch.Count -lt 3) {
        Write-Warning "Partial ABI build: do not distribute an APK containing other, stale native libraries."
    }

    Write-Host "`n✅ Сборка завершена:" -ForegroundColor Green
    Get-ChildItem -Path $jniDir -Recurse -Filter "libp2p_core.so" | ForEach-Object {
        Write-Host "  $($_.FullName -replace [regex]::Escape($PSScriptRoot), '.'): $($_.LastWriteTime)"
    }
} finally {
    Pop-Location
}
