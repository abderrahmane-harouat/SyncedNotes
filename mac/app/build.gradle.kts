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
            // The Rust engine is loaded through JNI and painted into through the FFM API.
            "--enable-native-access=ALL-UNNAMED",
        )
    }
}

tasks.test {
    useJUnitPlatform()
}

// Compose makes its run task late, so reach it by type rather than by name.
tasks.withType<JavaExec>().configureEach {
    dependsOn(":engine:cargoHost")
}
