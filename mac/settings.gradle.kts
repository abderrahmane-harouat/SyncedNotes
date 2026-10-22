pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
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

// The note canvas is the Android app's own engine, shared rather than copied: one engine
// types, selects and draws the same on the phone and on the Mac.
include(":engine")
project(":engine").projectDir = file("../android/engine")

include(":core")
include(":app")
