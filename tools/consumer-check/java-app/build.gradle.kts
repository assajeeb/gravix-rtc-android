// Copyright 2026 Gravity Compile
// SPDX-License-Identifier: Apache-2.0
//
// A Java-only app (no Kotlin plugin) that depends on the SDK alone: the README's Java snippet compiled
// against the published artifact, so the Java API cannot silently drift.
plugins {
    id("com.android.application")
}

android {
    namespace = "com.gravixcloud.consumercheck.java"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.gravixcloud.consumercheck.java"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.gravixcloud:gravix-rtc-android:${property("gravixSdkVersion")}")
}
