# build-rust.ps1 — Сборка Rust библиотеки для Android
# спользование: .\build-rust.ps1
# пционально: .\build-rust.ps1 -Arch armeabi-v7a,x86_64

param(
    [string[]]$Arch = @("arm64-v8a")
)

Write-Host "=== Сборка Rust для Android ===" -ForegroundColor Yellow
Write-Host "рхитектуры: $($Arch -join ', ')"

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
        
        cargo ndk -t $target -o $jniDir build --release
        if ($LASTEXITCODE -ne 0) {
            Write-Host "❌ шибка сборки $a" -ForegroundColor Red
            exit 1
        }
    }
    
    Write-Host "`n✅ Сборка завершена:" -ForegroundColor Green
    Get-ChildItem -Path $jniDir -Recurse -Filter "libp2p_core.so" | ForEach-Object {
        Write-Host "  $($_.FullName -replace [regex]::Escape($PSScriptRoot), '.'): $($_.LastWriteTime)"
    }
} finally {
    Pop-Location
}
