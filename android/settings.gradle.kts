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
        google()
        mavenCentral()
    }
}

rootProject.name = "SyncedNotesAndroid"

// The engine is a plain JVM module on purpose: it cannot import android.*, so nothing in the
// text engine can quietly lean on StaticLayout, Paint or Editable. Only :app touches Android.
include(":engine")
include(":app")
