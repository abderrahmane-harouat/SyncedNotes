pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

plugins {
    // Fetches the JDK the app is packaged with (see app/build.gradle.kts) when it is missing.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Compose for Desktop pulls a few androidx libraries (lifecycle, annotations) from here.
        google()
    }
}

rootProject.name = "SyncedNotesMac"

// The engine, the lock and the store are the phone's too, shared rather than copied: one
// engine types, selects and draws the same on both, and one lock and store read the same notes.
include(":engine")
project(":engine").projectDir = file("../shared/engine")
include(":core")
project(":core").projectDir = file("../shared/core")

include(":app")
