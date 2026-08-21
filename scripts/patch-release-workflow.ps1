# Одноразовый патч релизного workflow: CI сам собирает Rust из исходников.
# Запускать в корне клона:  powershell -File scripts\patch-release-workflow.ps1
# Затем: git add .github/workflows/build-release.yml; git commit -m "ci: build Rust from source in release workflow"; git push

$ErrorActionPreference = "Stop"
$path = ".github/workflows/build-release.yml"
$text = Get-Content $path -Raw -Encoding UTF8

$anchor = @'
      - name: Accept Android licenses
        run: |
          yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses || true
'@

$insert = @'
      - name: Accept Android licenses
        run: |
          yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses || true

      - name: Install Android NDK
        run: |
          yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "ndk;28.2.13676358" > /dev/null || true
          ls "$ANDROID_HOME/ndk"

      - name: Set up Rust
        uses: dtolnay/rust-toolchain@stable
        with:
          targets: aarch64-linux-android,armv7-linux-androideabi,x86_64-linux-android

      - name: Install cargo-ndk
        run: cargo install --locked cargo-ndk

      - name: Build native core from source (all ABIs)
        working-directory: ./rust-core
        env:
          ANDROID_NDK_HOME: ${{ env.ANDROID_HOME }}/ndk/28.2.13676358
        run: |
          cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 `
            -o ../android-app/app/src/main/jniLibs `
            build --release --features mqtt-dual-broker
'@

if ($text -notmatch [regex]::Escape("Install Android NDK")) {
    if (-not $text.Contains($anchor)) { throw "Anchor not found - file changed upstream?" }
    $text = $text.Replace($anchor, $insert)
}

if ($text -notmatch [regex]::Escape("prerelease: true")) {
    $text = $text.Replace("prerelease: false", "prerelease: true")
}

# YAML: backtick line continuations are PowerShell-only; convert them back to '\'
$text = $text.Replace("``\r`n            -o", "\`r`n            -o").Replace("``\r`n            build", "\`r`n            build")

Set-Content -Path $path -Value $text -Encoding UTF8 -NoNewline
Write-Host "Patched $path. Now commit and push:"
Write-Host "  git add .github/workflows/build-release.yml"
Write-Host "  git commit -m `"ci: build Rust from source in release workflow`""
Write-Host "  git push"
