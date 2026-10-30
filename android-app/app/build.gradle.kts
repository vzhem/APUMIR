import java.util.Locale
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

fun versionCodeFromName(versionName: String): Int? {
    val match = Regex("^v([0-9]+)\\.([0-9]+)(?:\\.([0-9]+))?$").matchEntire(versionName)
        ?: return null
    val major = match.groupValues[1].toLongOrNull() ?: return null
    val minor = match.groupValues[2].toLongOrNull() ?: return null
    val patch = match.groupValues[3].ifEmpty { "0" }.toLongOrNull() ?: return null
    if (minor > 999 || patch > 999) return null

    val versionCode = major * 1_000_000 + minor * 1_000 + patch
    return versionCode.takeIf { it in 1L..2_100_000_000L }?.toInt()
}

// Локальная сборка (debug на ПК владельца) не знает ни -PreleaseVersionName,
// ни GITHUB_REF_NAME, поэтому раньше попадала в fallback "v11.16" и телефоны
// показывали v11.16, хотя выпущен v11.23.0. Хуже того: UpdateChecker сравнивал
// v11.16 с v11.23.0 и предлагал «обновление», которое поверх debug не
// ставится (разные ключи подписи).
//
// Теперь версия берётся из ближайшего тега git. На GitHub Actions этот код не
// влияет на релиз: там всегда задан GITHUB_REF_NAME (имя тега), он имеет
// приоритет. Суффикс "-debug" добавляется только вне Actions, чтобы debug
// никогда не выдавал себя за релиз; UpdateChecker.parse() отбрасывает хвост
// после "-", а isVersionNewer() при равных числах возвращает false, поэтому
// лишнее окно обновления не появится.
// ВНИМАНИЕ, две грабли Gradle, обе пойманы гейтом владельца:
// 1. Gradle 9 УДАЛИЛ Project.exec и скриптовый exec (deprecated с 8.11) —
//    `exec { }` в .kts даёт `Unresolved reference 'exec'`. Поэтому здесь
//    java.lang.ProcessBuilder, а не exec { } и не providers.exec.
// 2. В скрипте Gradle имя `java` в ВЫРАЖЕНИИ — это не пакет, а accessor
//    расширения JavaPluginExtension, поэтому `java.util.concurrent.TimeUnit`
//    падает с `Unresolved reference 'util'`. Полное имя JDK безопасно только в
//    позиции ТИПА (`ProcessBuilder(...)`, `catch (e: Exception)`), для значений
//    нужен import в начале файла. Таймаут здесь сознательно не используется:
//    перегрузка waitFor требует TimeUnit, а readText() и так блокируется, пока
//    git не закроет stdout, поэтому waitFor() возвращается сразу.
fun gitDescribeOrNull(): String? = try {
    val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    val git = if (isWindows) "git.exe" else "git"
    val process = ProcessBuilder(git, "describe", "--tags")
        .directory(rootDir)
        .redirectErrorStream(false)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    if (process.waitFor() != 0) {
        null
    } else {
        output.ifEmpty { null }
    }
} catch (e: Exception) {
    null
}

val gitTagVersionName: String? = providers.gradleProperty("releaseVersionName")
    .orElse(providers.environmentVariable("GITHUB_REF_NAME"))
    .orNull
    ?.takeIf { versionCodeFromName(it) != null }
    ?: gitDescribeOrNull()?.let { described ->
        // describe даёт "v11.23.0" (HEAD ровно на теге) или
        // "v11.23.0-23-g1c7e54b" (23 коммита после тега). Во втором случае
        // отрезаем хвост по частям: substringBeforeLast дал бы "v11.23.0-23",
        // а это не версия.
        var candidate = described
        while (candidate.isNotEmpty() && versionCodeFromName(candidate) == null) {
            val cut = candidate.substringBeforeLast('-', "")
            if (cut.isEmpty() || cut == candidate) {
                candidate = ""
            } else {
                candidate = cut
            }
        }
        candidate.ifEmpty { null }
    }

val ciVersionFromEnvironment = providers.environmentVariable("GITHUB_REF_NAME").orNull
    ?.takeIf { versionCodeFromName(it) != null }
val releaseVersionName = gitTagVersionName?.let { tag ->
    if (ciVersionFromEnvironment != null) tag else "$tag-debug"
} ?: "v11.16"
val releaseVersionCode = providers.gradleProperty("releaseVersionCode")
    .orNull
    ?.toIntOrNull()
    ?: versionCodeFromName(gitTagVersionName ?: releaseVersionName)
    ?: 11_016_000

android {
    namespace = "com.vladimir.messenger"
    compileSdk = libs.versions.compileSdk.get().toInt()

    // JVM unit tests exercise transport code that reports through android.util.Log; without
    // this the JVM stub throws "Method ... not mocked" and green paths turn into failures.
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    defaultConfig {
        applicationId = "com.vladimir.messenger"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("p2p-release.jks")
            storePassword = "p2p2026release"
            keyAlias = "p2p"
            keyPassword = "p2p2026release"
        }
    }

    // ---- какие ABI попадают в APK -------------------------------------------
    // Аудит 2026-10-26, P1-4 (размер APK) предлагал убрать эмуляторную ABI из
    // релиза ради размера. ОТМЕНЕНО 2026-10-28 по слову владельца: «приложение
    // тоже ставится на компьютер через лаунчер» — а лаунчер на ПК это эмулятор
    // x86_64. Без этой копии APK на компьютере не устанавливается вовсе, и
    // размер в 232 МБ тут не аргумент: релиз остаётся УНИВЕРСАЛЬНЫМ.
    //
    // Почему не ABI-сплиты (отдельный APK на каждую ABI) и не AAB — см.
    // docs/AUDIT_2026-10-26.md, P1-4 «Почему не сплиты»: приложение
    // распространяется не через Google Play, а файлом с GitHub и роем
    // APK-раздачи. В пакете роя нет поля ABI (`ApkSeederModels.kt`: версия,
    // sha256, размер), поэтому сосед с другой ABI скачал бы неставимый APK.
    val releaseAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            ndk {
                abiFilters += releaseAbis
            }
            // P1-4 аудита: APK вырос с 96 до 229 МБ. R8 + вырезание ресурсов
            // дают обычно 25–40 %, но включать их вслепую нельзя: ядро
            // (uniffi → JNA), Vosk и ML Kit общаются через рефлексию, и
            // пропущенное keep-правило выглядит как падение на старте уже у
            // людей. Поэтому сокращение включается ЯВНО:
            //   ./gradlew :app:assembleRelease -Papu.r8=true
            // и только после проверки на телефоне (proguard-rules.pro,
            // docs/AUDIT_2026-10-26.md P1-4).
            val r8 = providers.gradleProperty("apu.r8").orNull?.toBoolean() == true
            isMinifyEnabled = r8
            isShrinkResources = r8
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
            // Debug и релиз собираются с одним набором ABI: что проверили в
            // эмуляторе, то и уедет людям (в том числе на ПК через лаунчер).
            ndk {
                abiFilters += releaseAbis
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Служебные файлы зависимостей: в APK они нужны только для чтения
            // человеком, а тянутся из каждой библиотеки.
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            excludes += "/META-INF/*.version"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

kapt {
    correctErrorTypes = true
}

dependencies {
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // ZXing QR Code Scanner
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Карта выбора точки (OpenStreetMap, без ключей Google): экран «Поделиться координатами».
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("com.google.zxing:core:3.5.3")
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    kapt(libs.hilt.compiler)

    // WorkManager + HiltWorker
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    kapt("androidx.hilt:hilt-compiler:1.2.0")


    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.coil.compose)
    // Анимированные GIF в сообщениях (декодер для Coil).
    implementation(libs.coil.gif)
    implementation(libs.zxing.core)
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    implementation(libs.eddsa)

    // Перевод входящих сообщений на устройстве (см. data/translate/MessageTranslator.kt).
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")

    // Распознавание лица для AR-фильтров видеоистории (data/story/StoryFaceTracker.kt).
    implementation("com.google.mlkit:face-detection:16.1.7")

    // Офлайн-распознавание речи голосовых сообщений в текст (data/voice/VoiceTranscriber.kt).
    // Модель языка маленькая и скачивается один раз при первом переводе, в APK её нет.
    implementation("com.alphacephei:vosk-android:0.3.47")

    // Выделение человека для смены фона в видеозвонке и видеосториях (data/background/).
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// ---- состав релизного APK по ABI -------------------------------------------
// Аудит 2026-10-26, P1-4: размер APK — первое, что видит человек на странице
// релиза, но узнать его состав можно было только скачав файл (232 МБ) и
// распаковав. Здесь после сборки в лог печатается таблица: сколько занимает
// каждая ABI и сколько — всё остальное. Так видно и результат правок
// по ABI (arm64-v8a, armeabi-v7a, x86_64 — набор у релиза и debug один) и
// сколько занимает всё остальное: так видно, что именно раздувает архив.
// Ошибка разбора не должна валить релиз: лог не важнее APK, но молчать о ней
// нельзя (AGENTS.md, п. 10) — печатаем причину.
tasks.matching { it.name == "assembleRelease" }.configureEach {
    doLast {
        val dir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val apk = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".apk") }
            ?.maxByOrNull { it.length() }
        if (apk == null) {
            logger.lifecycle("[apu] APK в ${dir.path} не найден — состав не печатаю")
            return@doLast
        }
        runCatching {
            val perAbi = sortedMapOf<String, Long>()
            var other = 0L
            ZipFile(apk).use { zip ->
                for (entry in zip.entries()) {
                    val abi = Regex("^lib/([^/]+)/").find(entry.name)?.groupValues?.get(1)
                    if (abi != null) {
                        perAbi[abi] = (perAbi[abi] ?: 0L) + entry.compressedSize
                    } else {
                        other += entry.compressedSize
                    }
                }
            }
            val mb = { bytes: Long -> String.format(Locale.ROOT, "%.1f МБ", bytes / 1048576.0) }
            logger.lifecycle("[apu] APK ${apk.name}: ${mb(apk.length())}")
            perAbi.forEach { (abi, bytes) -> logger.lifecycle("[apu]   lib/$abi: ${mb(bytes)}") }
            logger.lifecycle("[apu]   остальное (dex, ресурсы, assets): ${mb(other)}")
        }.onFailure { failure ->
            logger.lifecycle("[apu] состав APK не разобран: ${failure.javaClass.simpleName}: ${failure.message}")
        }
    }
}
