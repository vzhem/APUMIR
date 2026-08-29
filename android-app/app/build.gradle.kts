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
// ВНИМАНИЕ: Gradle 9 УДАЛИЛ Project.exec и скриптовый exec (deprecated с 8.11),
// поэтому здесь обычный java.lang.ProcessBuilder, а не exec { } и не
// providers.exec. Проверено на живом JVM: команда и разбор вывода те же.
fun gitDescribeOrNull(): String? = try {
    val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    val git = if (isWindows) "git.exe" else "git"
    val process = ProcessBuilder(git, "describe", "--tags")
        .directory(rootDir)
        .redirectErrorStream(false)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    val finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        null
    } else if (process.exitValue() != 0) {
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

        ndk {
            abiFilters += listOf("x86_64", "arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("p2p-release.jks")
            storePassword = "p2p2026release"
            keyAlias = "p2p"
            keyPassword = "p2p2026release"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
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

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // ZXing QR Code Scanner
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
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
    implementation(libs.zxing.core)
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

