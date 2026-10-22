plugins {
    id("com.android.application")
}

android {
    namespace = "com.syncednotes.android"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.syncednotes.android"
        // 28 because the emulator that runs Samsung Notes for comparison is Android 9.
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(27)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":engine"))
}

// The Rust half (android/glyphs) is built with the NDK's linker for each ABI we ship, into a
// directory AGP packages as native libraries.
abstract class CargoAndroid : DefaultTask() {
    @get:InputFiles abstract val sources: ConfigurableFileCollection
    @get:Internal abstract val crateDir: DirectoryProperty
    @get:Input abstract val toolchainBin: Property<String>
    @get:Input abstract val abis: MapProperty<String, String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        abis.get().forEach { (triple, abi) ->
            val linkerEnv = "CARGO_TARGET_${triple.uppercase().replace('-', '_')}_LINKER"
            exec.exec {
                workingDir = crateDir.get().asFile
                commandLine("cargo", "build", "--release", "--target", triple)
                environment(linkerEnv, "${toolchainBin.get()}/${triple}28-clang")
            }
            val dest = outputDir.get().dir(abi).asFile
            dest.mkdirs()
            crateDir.get().file("target/$triple/release/libglyphs.so").asFile.copyTo(File(dest, "libglyphs.so"), overwrite = true)
        }
    }
}

val glyphsDir = rootProject.layout.projectDirectory.dir("glyphs")
val cargoAndroid = tasks.register<CargoAndroid>("cargoAndroid") {
    sources.from(glyphsDir.dir("src"), glyphsDir.file("Cargo.toml"))
    crateDir.set(glyphsDir)
    val sdk = providers.environmentVariable("ANDROID_HOME").orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    toolchainBin.set(sdk.map { "$it/ndk/${android.ndkVersion}/toolchains/llvm/prebuilt/darwin-x86_64/bin" })
    abis.set(mapOf("aarch64-linux-android" to "arm64-v8a", "x86_64-linux-android" to "x86_64"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoAndroid, CargoAndroid::outputDir)
    }
}
