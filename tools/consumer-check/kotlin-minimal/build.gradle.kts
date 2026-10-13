// Copyright 2026 Gravity Compile
// SPDX-License-Identifier: Apache-2.0
//
// A Kotlin app that depends on the SDK ALONE (no lifecycle-ktx, no coroutines, no kotlinx-serialization of
// its own): every type it touches must come from the SDK's published compile scope. run.sh checks that the
// compile classpath has no kotlinx-serialization before building it, so a public SDK signature that names an
// implementation-only type fails this build.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gravixcloud.consumercheck.minimal"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.gravixcloud.consumercheck.minimal"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
    implementation("com.gravixcloud:gravix-rtc-android:${property("gravixSdkVersion")}")
}
