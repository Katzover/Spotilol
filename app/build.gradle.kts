import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val keystorePropertiesFile = rootProject.file("keystore/keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

// Hebrew locale fix: on this device/API the resource matcher resolves Hebrew with
// the legacy "iw" language code (framework-res and libraries ship values-iw, never
// values-he), so a values-he-only string never matches at runtime and all Hebrew
// UI falls back to the default (English) resources. Mirror values-he as values-iw
// so both the legacy ("iw") and standard ("he") matchers find the strings.
val iwLocaleResDir = layout.buildDirectory.dir("generated/iwLocaleRes").get().asFile
File(iwLocaleResDir, "values-iw").mkdirs()
file("src/main/res/values-he").listFiles()?.forEach {
    if (it.isFile) it.copyTo(File(iwLocaleResDir, "values-iw/${it.name}"), overwrite = true)
}


android {
    namespace = "com.project.lol"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.project.lol"
        minSdk = 28
        targetSdk = 36
        versionCode = 16
        versionName = "1.1.6"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets.getByName("main").res.srcDir(iwLocaleResDir)

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/${keystoreProperties.getProperty("storeFile")}")
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.media)
    implementation(libs.bouncyprov)
    implementation(libs.bouncypkix)
    implementation(libs.security.crypto)

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:34.16.0"))
    implementation("com.google.firebase:firebase-analytics") {
        exclude(group = "com.google.firebase", module = "protolite-well-known-types")
    }
    implementation("com.google.firebase:firebase-crashlytics") {
        exclude(group = "com.google.firebase", module = "protolite-well-known-types")
    }
    implementation("com.google.firebase:firebase-perf") {
        exclude(group = "com.google.firebase", module = "protolite-well-known-types")
    }

    // Jetpack Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.tabler.icons)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Ktor + serialization (YouTube InnerTube client)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.encoding)
    implementation(libs.ktor.serialization.json)
    implementation(libs.serialization.json)

    // NewPipe + YouTube streaming
    implementation(libs.newpipeextractor)
    implementation(libs.brotli)
    implementation(libs.okhttp)

    // Audio downloads: opus decoding, mp3 encoding
    implementation(project(":lame"))
    implementation(project(":opus"))

    // Core library desugaring (required by NewPipeExtractor)
    coreLibraryDesugaring(libs.desugaring)
}