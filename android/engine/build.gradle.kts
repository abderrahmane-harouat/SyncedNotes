plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(26)
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

val glyphsDir = rootProject.layout.projectDirectory.dir("glyphs")

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
    systemProperty("syncednotes.fonts", rootProject.layout.projectDirectory.dir("app/src/main/assets/fonts").asFile.absolutePath)
}
