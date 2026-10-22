plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(27)
    compilerOptions {
        // The highest this Kotlin can emit; 22 or later for the FFM API (PixelSurface).
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    testImplementation(kotlin("test"))
}

val glyphsRelease = rootProject.layout.projectDirectory.dir("../android/glyphs/target/release")

sourceSets {
    main {
        // The same font files as the phone, so a note looks the same on both.
        resources.srcDir(rootProject.layout.projectDirectory.dir("../android/app/src/main/assets"))
    }
}

compose.desktop {
    application {
        mainClass = "com.syncednotes.mac.MainKt"
        jvmArgs += listOf(
            "-Djava.library.path=${glyphsRelease.asFile.absolutePath}",
            // A run from the build keeps its notes beside the real ones, never in them.
            "-Dsyncednotes.folder=SyncedNotes-Try",
            // Only honoured with the try-out folder: screenshots of the app's look.
            "-Dsyncednotes.allowCapture=true",
            // The Rust engine is loaded through JNI and painted into through the FFM API.
            "--enable-native-access=ALL-UNNAMED",
        )
        // Unlocks the made-up notes of makeTryNotes at launch, with their made-up passphrase.
        if (project.hasProperty("tryUnlock")) jvmArgs += "-Dsyncednotes.tryPassphrase=made-up try passphrase"
    }
}

tasks.test {
    useJUnitPlatform()
    // The model's tests type into the real engine, which needs its Rust half.
    dependsOn(":engine:cargoHost")
    systemProperty("java.library.path", glyphsRelease.asFile.absolutePath)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Compose makes its run task late, so reach it by type rather than by name.
tasks.withType<JavaExec>().configureEach {
    dependsOn(":engine:cargoHost")
}

// Fills the try-out folder with made-up notes that use every kind of formatting, to look at
// the app with: ./gradlew :app:makeTryNotes, then ./gradlew :app:run -PtryUnlock.
tasks.register<JavaExec>("makeTryNotes") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.syncednotes.mac.TryNotesKt"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
