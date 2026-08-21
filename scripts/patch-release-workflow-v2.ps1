# Патч-v2 релизного workflow: надёжная сборка Rust в CI (заменяет v1-вставку целиком).
# Запускать в корне клона:  powershell -File scripts\patch-release-workflow-v2.ps1
# Затем: git add .github/workflows/build-release.yml; git commit -m "ci: robust native build"; git push

$ErrorActionPreference = "Stop"
$path = ".github/workflows/build-release.yml"
$text = Get-Content $path -Raw -Encoding UTF8

# 1) Вырезаем всю вставку v1 (от "- name: Install Android NDK" до строки перед "- name: Get version from tag")
$start = $text.IndexOf("      - name: Install Android NDK")
$endMarker = "      - name: Get version from tag"
$end = $text.IndexOf($endMarker)
if ($start -ge 0 -and $end -gt $start) {
    $text = $text.Substring(0, $start) + $text.Substring($end)
} elseif ($start -ge 0) {
    throw "v1 insert found but end marker missing - file changed upstream?"
}

# 2) Вставляем блок v2 после блока Accept Android licenses
$anchor = @'
      - name: Accept Android licenses
        run: |
          yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses || true
'@

$insert = @'
      - name: Accept Android licenses
        run: |
          yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses || true

      - name: Install Android NDK and export ANDROID_NDK_HOME
        run: |
          yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install "ndk;28.2.13676358" > /dev/null
          echo "ANDROID_NDK_HOME=$ANDROID_HOME/ndk/28.2.13676358" >> "$GITHUB_ENV"
          test -x "$ANDROID_HOME/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"

      - name: Set up Rust
        uses: dtolnay/rust-toolchain@stable
        with:
          targets: aarch64-linux-android,armv7-linux-androideabi,x86_64-linux-android

      - name: Install cargo-ndk
        run: cargo install --locked cargo-ndk

      - name: Build native core from source (all ABIs)
        working-directory: ./rust-core
        run: cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o ../android-app/app/src/main/jniLibs build --release --features mqtt-dual-broker
'@

if (-not $text.Contains($anchor)) { throw "Anchor not found - file changed upstream?" }
$text = $text.Replace($anchor, $insert)

Set-Content -Path $path -Value $text -Encoding UTF8 -NoNewline
Write-Host "Patched. Verify with: git diff .github/workflows/build-release.yml"
Write-Host "Then: git add .github/workflows/build-release.yml; git commit -m 'ci: robust native build'; git push"
