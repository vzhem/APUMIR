# APU app icon source and exports

This directory is the canonical home for APU launcher-icon artwork.

## Source of truth

Place the exact user-supplied original at:

```text
source/apu-icon-original.png
```

Do not overwrite or recompress that file. Record its pixel dimensions, byte size and SHA-256 in
`SOURCE_PROVENANCE.md` when it is available. Confirm that APU has permission to use the artwork.

The image supplied on 2026-08-15 shows a neon purple speech bubble around a cyan/magenta P2P mesh.
It is a landscape composition, so it must not be copied directly into Android mipmaps. Preserve the
original and make a separate square master crop centered on the speech bubble and mesh globe.

## Planned derived files

```text
working/apu-icon-master-1024.png
working/apu-icon-foreground-432.png
working/apu-icon-monochrome.svg
export/play-store/apu-icon-512.png
export/android/mipmap-mdpi/ic_launcher.webp       # 48 px
export/android/mipmap-hdpi/ic_launcher.webp       # 72 px
export/android/mipmap-xhdpi/ic_launcher.webp      # 96 px
export/android/mipmap-xxhdpi/ic_launcher.webp     # 144 px
export/android/mipmap-xxxhdpi/ic_launcher.webp    # 192 px
```

Round exports use the same master with a mask-safe crop. Modern Android uses separate adaptive
foreground/background resources and a simple monochrome themed icon. Keep critical details inside
the adaptive safe zone and verify at 16, 24 and 48 px, on light/dark backgrounds and in grayscale.

## Integration gate

Do not replace `android-app/app/src/main/res/mipmap-*` or launcher drawable XML during the active
r4.4 mixed-version acceptance. Icon integration changes the APK and therefore requires a fresh
versioned Android artifact build, exact signer/embedded-native verification, install/upgrade check
and launcher appearance test. Integrate after the current network gate is closed.

The chat attachment was visible but was not mounted in the agent workspace. The owner therefore
placed the exact original at the canonical path in the Windows clone. Windows verification PASS:
1,980,451 bytes, 1664×928, `Format24bppRgb`, SHA-256
`F2638C88A3EAB243766B8F4755183C89A3E1FFCB72B45A0BBC5F3D398C83ACA9`. Its Git transfer remains
pending; see `SOURCE_PROVENANCE.md`. Do not recreate it from a screenshot or AI approximation.
