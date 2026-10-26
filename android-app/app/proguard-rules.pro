# Правила R8 для релизной сборки APU.
#
# ВКЛЮЧАЕТСЯ ТОЛЬКО ЯВНО: ./gradlew :app:assembleRelease -Papu.r8=true
# По умолчанию release собирается без сокращения (isMinifyEnabled = false),
# потому что проверить сокращённую сборку можно только установкой на телефон,
# а у ядра, JNA и Vosk всё общение идёт через рефлексию: пропущенное keep-правило
# выглядит как «приложение падает на старте» уже у людей.
#
# Порядок включения (docs/AUDIT_2026-10-26.md, P1-4):
#   1. собрать с -Papu.r8=true;
#   2. поставить на телефон поверх текущей версии;
#   3. проверить: старт ядра (диагностика «ядро запущено»), отправку и приём
#      сообщения, голосовое сообщение (Vosk/JNA), историю, QR, обновление;
#   4. только после этого ставить isMinifyEnabled = true по умолчанию.
#
# Это НЕ полный список: правила дополняются по мере падений на устройстве.

# ── UniFFI + JNA ────────────────────────────────────────────────────────────
# Мост uniffi/p2p_core/p2p_core.kt — интерфейс JNA: методы вызываются по имени
# через рефлексию, поэтому имена обязаны остаться.
-keep class uniffi.** { *; }
-keep interface uniffi.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# ── Vosk (офлайн-распознавание речи) ────────────────────────────────────────
-keep class org.vosk.** { *; }
-keep class com.alphacephei.** { *; }
-dontwarn org.vosk.**

# ── ML Kit ──────────────────────────────────────────────────────────────────
# Библиотеки поставляют consumer-правила, но модели грузятся по имени класса.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

# ── Карта OpenStreetMap ─────────────────────────────────────────────────────
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# ── Room / Hilt / Compose ───────────────────────────────────────────────────
# У этих библиотек есть consumer-правила; здесь только страховка на наши
# сущности и DAO, которые Room инстанцирует по имени.
-keep class com.vladimir.messenger.data.local.entity.** { *; }
-keep interface com.vladimir.messenger.data.local.dao.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { <init>(); }

# Kotlin-метаданные нужны kotlinx-корутинам и рефлексии Hilt.
-keep class kotlin.Metadata { *; }

# ── Диагностика ────────────────────────────────────────────────────────────
# Стектрейсы падений читаются в отчёте: без имён классов и номеров строк он
# бесполезен. Цена — размер, поэтому только имена и строки, без локальных.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
