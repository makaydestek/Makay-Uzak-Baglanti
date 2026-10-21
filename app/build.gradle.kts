import java.security.MessageDigest
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
            /* 1.0.33: R8 minify gecici kapali — native JNI strip / allow-sonrasi
               ani kapanma A/B. Sonraki surumde minify + guclendirilmis keep ile acilacak. */
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Yalnizca ARM cihaz hedefi: x86/x86_64 yalnizca emülatör/Chromebook'ta
            // calisir ve tek dosya APK'yi ~23 MB buyutur. Debug'da tutulur ki
            // emülatör testleri bozulmasin. (libjingle_peerconnection_so.so ~10 MB/mimari)
            ndk {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            }
            // Uretim imzasi zorunlu — debug anahtarla release ship edilmez.
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
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

    testOptions {
        unitTests.isReturnDefaultValues = true
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

// Dosyanin SHA-256'si (kucuk harf hex). Windows PowerShell'e gerek kalmaz.
fun sha256Hex(f: java.io.File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

tasks.register("publishReleaseApk") {
    group = "distribution"
    description = "Release APK'yi, SHA-256'sini ve R8 haritasini yalnizca APK/ klasorune yazar"
    doLast {
        val apkDir = rootProject.layout.projectDirectory.dir("APK").asFile
        apkDir.mkdirs()
        val ver = rootProject.file("VERSION.txt").readText().trim()

        // (hedef ad, ham APK, R8 haritasi)
        // Uygulama TEK APK olarak teslim edilir: paket hicbir ozel izin
        // (AccessibilitService dahil) bildirmez, bu yuzden yandan yuklemede
        // Play Protect ayni Makay uygulamalari gibi sorunsuz kurar.
        val items = listOf(
            Triple(
                "MakayRemote_v$ver.apk",
                layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile,
                layout.buildDirectory.file("outputs/mapping/release/mapping.txt").get().asFile
            )
        )
        items.forEach { (_, src, _) ->
            check(src.exists()) { "APK bulunamadi: ${src.absolutePath}" }
        }

        // 1) Ham ciktilari staging'e tasi. deleteStrayDeliveryApks APK/ disindaki
        //    TUM .apk dosyalarini sildigi icin kaynaklar orada kalamaz.
        val staged = items.map { (name, src, mapping) ->
            val st = apkDir.resolve(".staging-$name")
            src.copyTo(st, overwrite = true)
            Triple(name, st, mapping)
        }

        // 2) APK/ klasorunu (staging disinda) bosalt — eski surumler temizlenir.
        apkDir.listFiles()?.forEach { f ->
            if (f.isFile && !f.name.startsWith(".staging-")) f.delete()
        }

        // 3) Yerlestir: APK + R8 haritasi + SHA-256
        val delivered = staged.map { (name, st, mapping) ->
            val named = apkDir.resolve(name)
            st.copyTo(named, overwrite = true)

            // R8 obfuscation haritasi — APK ile ayni klasorde (surumli adla) ve
            // GitHub Release asset'i olarak yayinlanir.
            val mapOut = apkDir.resolve(name.removeSuffix(".apk") + "-mapping.txt")
            if (mapping.exists()) mapping.copyTo(mapOut, overwrite = true) else mapOut.delete()

            Triple(name, named, sha256Hex(named))
        }

        deleteStrayDeliveryApks(apkDir)
        staged.forEach { (_, st, _) -> if (st.exists()) st.delete() }

        delivered.forEach { (name, named, hex) ->
            check(named.exists()) { "Teslim APK APK/ klasorunde yok: ${named.absolutePath}" }
            // sha256sum uyumlu bicim: "<hash>  <dosya>"
            apkDir.resolve("$name.sha256").writeText("$hex  $name")
            println("Published: ${named.absolutePath}")
            println("SHA-256  : $hex")
        }
        println("Version  : $ver (versionCode=$appVersionCode)")
        println("Teslimat: yalnizca ${apkDir.absolutePath}")
    }
}

afterEvaluate {
    tasks.named("assembleRelease").configure {
        finalizedBy("publishReleaseApk")
    }
    listOf("packageRelease", "assembleRelease", "publishReleaseApk").forEach { name ->
        tasks.matching { it.name == name }.configureEach {
            doFirst {
                check(hasReleaseKey) {
                    "Release imza yok. local.properties icinde RELEASE_STORE_FILE / PASSWORD / KEY_ALIAS / KEY_PASSWORD tanimlayin."
                }
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
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