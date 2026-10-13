// Copyright 2026 Gravity Compile
// SPDX-License-Identifier: Apache-2.0
//
// A minimal customer app that consumes the SDK exactly as a customer would, from a Maven repository.
// The ONLY repositories are Google and Maven Central, plus Maven Local restricted to com.gravixcloud
// (the development build of this SDK). No JitPack: if any transitive dependency of the SDK is not on
// Google/Maven Central, dependency resolution fails and so does tools/consumer-check/run.sh.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal {
            content { includeGroup("com.gravixcloud") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "gravix-consumer-check"
include(":app", ":kotlin-minimal", ":java-app")
