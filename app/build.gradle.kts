import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Tek surum kaynagi: proje kokundeki VERSION.txt (ornek: 1.0.43)
val appVersionName: String = rootProject.file("VERSION.txt").readText().trim().ifEmpty { "1.0.0" }
val appVersionCode: Int = run {
    val parts = appVersionName.split('.').map { it.toLongOrNull() }
    require(parts.size == 3 && parts.all { it != null && it >= 0L }) {
        "VERSION.txt gecersiz: '$appVersionName' (beklenen: 1.0.43)"
    }
    val (major, minor, patch) = parts.filterNotNull()
    require(major in 0L..2100L && minor < 1000L && patch < 1000L) {
        "VERSION.txt icinde major 0..2100, minor ve patch 0..999 araliginda olmali"
    }
    val code = major * 1_000_000L + minor * 1_000L + patch
    require(code in 1L..2_100_000_000L) { "VERSION.txt versionCode sinirini asiyor" }
    code.toInt()
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun localProp(name: String) = localProps.getProperty(name)?.trim().orEmpty()
val releaseStore = localProp("RELEASE_STORE_FILE")
val hasReleaseKey = releaseStore.isNotEmpty() &&
    localProp("RELEASE_STORE_PASSWORD").isNotEmpty() &&
    localProp("RELEASE_KEY_ALIAS").isNotEmpty() &&
    localProp("RELEASE_KEY_PASSWORD").isNotEmpty() &&
    rootProject.file(releaseStore).isFile

android {
    namespace = "com.makay.remote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.makay.remote"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATE_GITHUB_OWNER", "\"makaydestek\"")
        buildConfigField("String", "UPDATE_GITHUB_REPO", "\"Makay-Uzak-Baglanti\"")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(releaseStore)
                storePassword = localProp("RELEASE_STORE_PASSWORD")
                keyAlias = localProp("RELEASE_KEY_ALIAS")
                keyPassword = localProp("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Mevcut kurulumlar debug anahtariyla guncelleniyor. Uretim anahtari
            // local.properties'te yoksa bu zinciri kirmamak icin debug imza kalir.
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
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

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
                "**/kotlin-tooling-metadata.json"
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    // ABI split kapali: tek sideload APK (tum mimariler)
    splits {
        abi {
            isEnable = false
        }
    }
}

// Teslimat yalnizca proje/APK. Ara kopyalar APK/ disinda birakilmaz.
fun deleteDeliveryArtifactsIn(dir: java.io.File) {
    if (!dir.exists()) return
    dir.walkTopDown().forEach { f ->
        if (!f.isFile) return@forEach
        val n = f.name.lowercase()
        if (n.endsWith(".apk") || n.endsWith(".apk.sha256") || n.endsWith(".aab")) {
            f.delete()
        }
    }
}

fun deleteStrayDeliveryApks(keepDir: java.io.File) {
    val root = rootProject.layout.projectDirectory.asFile
    deleteDeliveryArtifactsIn(layout.buildDirectory.dir("outputs/apk").get().asFile)
    deleteDeliveryArtifactsIn(layout.buildDirectory.dir("outputs/bundle").get().asFile)
    deleteDeliveryArtifactsIn(root.resolve("app/release"))
    deleteDeliveryArtifactsIn(root.resolve("release"))
    root.listFiles()?.forEach { f ->
        if (!f.isFile) return@forEach
        val n = f.name.lowercase()
        if (n.endsWith(".apk") || n.endsWith(".apk.sha256") || n.endsWith(".aab")) {
            f.delete()
        }
    }
    check(keepDir.exists())
}

tasks.register("publishReleaseApk") {
    group = "distribution"
    description = "Release APK + SHA-256'yi yalnizca APK/ klasorune yazar; baska klasordeki kopyalari siler"
    doLast {
        val src = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        check(src.exists()) { "APK bulunamadi: ${src.absolutePath}" }
        val apkDir = rootProject.layout.projectDirectory.dir("APK").asFile
        apkDir.mkdirs()
        val ver = rootProject.file("VERSION.txt").readText().trim()
        val named = apkDir.resolve("MakayRemote_v$ver.apk")
        val shaFile = apkDir.resolve("MakayRemote_v$ver.apk.sha256")
        val staging = apkDir.resolve(".staging-app-release.apk")

        src.copyTo(staging, overwrite = true)
        apkDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name != staging.name) f.delete()
        }
        staging.copyTo(named, overwrite = true)
        staging.delete()

        val ps = listOf(
            "powershell", "-NoProfile", "-Command",
            "\$h=(Get-FileHash -Algorithm SHA256 -Path '${named.absolutePath.replace("'", "''")}').Hash.ToLower(); " +
                "Set-Content -Path '${shaFile.absolutePath.replace("'", "''")}' -Value (\$h + '  MakayRemote_v$ver.apk') -Encoding ascii; " +
                "Write-Output \$h"
        )
        val proc = ProcessBuilder(ps).redirectErrorStream(true).start()
        val hex = proc.inputStream.bufferedReader().readText().trim()
        check(proc.waitFor() == 0 && hex.length == 64) { "SHA-256 olusturulamadi: $hex" }

        deleteStrayDeliveryApks(apkDir)

        check(named.exists()) { "Teslim APK APK/ klasorunde yok: ${named.absolutePath}" }
        println("Published: ${named.absolutePath}")
        println("SHA-256  : ${shaFile.absolutePath}")
        println("Version  : $ver (versionCode=$appVersionCode)")
        println("Fingerprint: $hex")
        println("Teslimat: yalnizca ${apkDir.absolutePath}")
    }
}

afterEvaluate {
    tasks.named("assembleRelease").configure {
        finalizedBy("publishReleaseApk")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.code.gson:gson:2.10.1")

    // Uzaktan baglanti cekirdegi
    // libwebrtc (Google) Android derlemesi - DataChannel + video/audio track
    implementation("io.github.webrtc-sdk:android:125.6422.07")
    // MQTT over WSS - bulut eslesme (Windows ile ayni protokol)
    implementation("org.eclipse.paho:org.eclipse.paho.mqttv5.client:1.2.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
}