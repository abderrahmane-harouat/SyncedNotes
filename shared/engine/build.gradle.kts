plugins {
    kotlin("jvm")
}

// Built by both the Mac's and the phone's builds: each into a folder of its own, so one never
// rewrites the classes the other is running from.
layout.buildDirectory = layout.projectDirectory.dir("build/${rootProject.name}")

kotlin {
    jvmToolchain(27)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(kotlin("test"))
}

// Relative to the engine, not the root: the Mac app's build includes this module too.
val glyphsDir = layout.projectDirectory.dir("../glyphs")

// The engine's tests run on this Mac against the same Rust code the phone runs, built for the
// host instead of for Android.
val cargoHost = tasks.register<Exec>("cargoHost") {
    workingDir = glyphsDir.asFile
    commandLine("cargo", "build", "--release")
    inputs.dir(glyphsDir.dir("src"))
    inputs.file(glyphsDir.file("Cargo.toml"))
    outputs.dir(glyphsDir.dir("target/release"))
}

tasks.test {
    dependsOn(cargoHost)
    useJUnitPlatform()
    systemProperty("java.library.path", glyphsDir.dir("target/release").asFile.absolutePath)
    systemProperty("syncednotes.fonts", layout.projectDirectory.dir("../assets/fonts").asFile.absolutePath)
}
