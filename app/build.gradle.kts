plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Base64

// The debug key is committed as base64 text (debug.keystore.b64) because the
// push tooling only handles text files. Decode it to debug.keystore at
// configuration time so the signing config below always finds it. Without a
// fixed key, every CI build is signed with a fresh random key, Android
// refuses to update the app, and the user must uninstall first — which wipes
// all settings every time.
// NOTE: the import above is required — a fully-qualified java.util.Base64
// fails in Kotlin DSL scripts because `java` resolves to the Java plugin
// extension accessor instead of the java package ("Unresolved reference:
// util", run 21).
run {
    val ksFile = file("debug.keystore")
    val b64File = file("debug.keystore.b64")
    if (!ksFile.exists() && b64File.exists()) {
        ksFile.writeBytes(Base64.getDecoder().decode(b64File.readText().trim()))
    }
}

android {
    namespace = "com.powerlock.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.powerlock.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 24
        versionName = "3.0"
    }

    signingConfigs {
        // Fixed debug key (decoded from debug.keystore.b64 above).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
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
}

dependencies {
    implementation("androidx.work:work-runtime:2.9.0")
    // FileProvider for sharing the focus card image (androidx.core)
    implementation("androidx.core:core-ktx:1.13.1")
    // NOTE: the map is the online WebView + MapLibre GL JS version (build 67) —
    // no native map SDK dependency. The map JS loads from the unpkg CDN at runtime.
}
