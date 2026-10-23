import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
            // The Rust engine is loaded through JNI and painted into through the FFM API.
            "--enable-native-access=ALL-UNNAMED",
            // In the app, the Rust engine sits with its resources; jpackage fills in $APPDIR.
            "-Djava.library.path=\$APPDIR/resources",
            // So does SQLite's: the sandbox refuses to load the copy the driver would unpack
            // into its temporary folder, as it refuses any code the app writes itself.
            "-Dorg.sqlite.lib.path=\$APPDIR/resources",
            "-Dorg.sqlite.lib.name=libsqlitejdbc.dylib",
        )
        // The app carries a JDK of its own, made from Temurin's: Homebrew's JDK links its font
        // libraries to Homebrew's, outside the app, which the sandbox rightly will not open.
        // Looked up only when an app is being made, so no other build waits for it.
        if (gradle.startParameter.taskNames.any { "Distributable" in it || "signApp" in it || "package" in it }) {
            javaHome = javaToolchains.launcherFor {
                languageVersion = JavaLanguageVersion.of(25)
                vendor = JvmVendorSpec.ADOPTIUM
            }.get().metadata.installationPath.asFile.absolutePath
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "SyncedNotes"
            // jpackage takes no version below 1; the one shown is set to the app's own below.
            packageVersion = "1.0.0"
            appResourcesRootDir.set(layout.buildDirectory.dir("appResources"))
            // The JDK's own parts the app needs besides the desktop: SQL for the store,
            // jdk.unsupported for Protobuf.
            modules("java.sql", "jdk.unsupported")
            macOS {
                // Not the Swift app's com.syncednotes.mac: its sandbox folder holds the notes
                // that app wrote, and this app brings them in through a backup instead.
                bundleID = "com.syncednotes.notes"
                appCategory = "public.app-category.productivity"
                iconFile.set(layout.buildDirectory.file("icon/SyncedNotes.icns"))
            }
        }
    }
}

// The app icon, from the Swift app's icon images, by macOS's own iconutil.
val appIcon = tasks.register<Exec>("appIcon") {
    val source = rootProject.layout.projectDirectory.dir("../SyncedNotes/Assets.xcassets/AppIcon.appiconset")
    val set = layout.buildDirectory.dir("icon/SyncedNotes.iconset")
    val icns = layout.buildDirectory.file("icon/SyncedNotes.icns")
    inputs.dir(source)
    outputs.file(icns)
    doFirst {
        val dir = set.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        for ((name, file) in listOf(
            "icon_16x16" to "icon-16", "icon_16x16@2x" to "icon-32", "icon_32x32" to "icon-32", "icon_32x32@2x" to "icon-64",
            "icon_128x128" to "icon-128", "icon_128x128@2x" to "icon-256", "icon_256x256" to "icon-256", "icon_256x256@2x" to "icon-512",
            "icon_512x512" to "icon-512", "icon_512x512@2x" to "icon-1024",
        )) source.file("$file.png").asFile.copyTo(File(dir, "$name.png"))
    }
    commandLine("iconutil", "-c", "icns", "-o", icns.get().asFile.absolutePath, set.get().asFile.absolutePath)
}

// The Rust engine, built for this Mac, and SQLite's library for this Mac, taken out of its
// driver, among the app's resources, where the app's signature covers them.
val glyphsResource = tasks.register<Copy>("glyphsResource") {
    dependsOn(":engine:cargoHost")
    from(glyphsRelease.file("libglyphs.dylib"))
    from({ configurations["runtimeClasspath"].filter { "sqlite-jdbc" in it.name }.map { zipTree(it) } }) {
        include("org/sqlite/native/Mac/aarch64/libsqlitejdbc.dylib")
        eachFile { path = name }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("appResources/macos-arm64"))
}

tasks.matching { it.name == "prepareAppResources" || it.name == "createDistributable" }.configureEach {
    dependsOn(glyphsResource, appIcon)
}

// Signed on this Mac only, with no Apple account (Privacy.md), and sandboxed with no network.
val appVersion = "0.2.0"
val signApp = tasks.register<Exec>("signApp") {
    dependsOn("createDistributable")
    val app = layout.buildDirectory.file("compose/binaries/main/app/SyncedNotes.app")
    doFirst {
        val plist = app.get().asFile.resolve("Contents/Info.plist").absolutePath
        for (key in listOf("CFBundleShortVersionString", "CFBundleVersion")) {
            providers.exec { commandLine("plutil", "-replace", key, "-string", appVersion, plist) }.result.get()
        }
    }
    commandLine(
        "codesign", "--force", "--deep", "--sign", "-",
        "--entitlements", rootProject.layout.projectDirectory.file("packaging/SyncedNotes.entitlements").asFile.absolutePath,
        app.get().asFile.absolutePath,
    )
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

// A run from the build: the engine from its build folder, and made-up notes beside the real
// ones, never in them, which may be captured to check the app's look. Added as the task
// starts, after Compose has set the app's own arguments: the last of each wins.
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    val tryUnlock = project.hasProperty("tryUnlock")
    val selfTest = project.findProperty("selfTest") as String?
    val library = glyphsRelease.asFile.absolutePath
    doFirst {
        (this as JavaExec).jvmArgs(
            "-Djava.library.path=$library",
            "-Dsyncednotes.folder=SyncedNotes-Try",
            "-Dsyncednotes.allowCapture=true",
        )
        // Unlocks the made-up notes of makeTryNotes at launch, with their made-up passphrase.
        if (tryUnlock) jvmArgs("-Dsyncednotes.tryPassphrase=made-up try passphrase")
        // Types into a new note through the window's own events: -PselfTest="some text".
        selfTest?.let { jvmArgs("-Dsyncednotes.selfTest=$it") }
    }
}

// Fills the try-out folder with made-up notes that use every kind of formatting, to look at
// the app with: ./gradlew :app:makeTryNotes, then ./gradlew :app:run -PtryUnlock.
tasks.register<JavaExec>("makeTryNotes") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.syncednotes.mac.TryNotesKt"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
