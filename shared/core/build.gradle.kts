plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(27)
    compilerOptions {
        // Java 17, which Android runs too: this module is the phone's lock and store as well.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    main {
        // The note format's code, made from SyncedNotesCore/Proto/note.proto by protoc; never
        // edited by hand (see the README).
        java.srcDir("Generated")
    }
}

dependencies {
    // The note engine's document, which NoteFormat reads notes into and writes them from.
    api(project(":engine"))
    // Argon2id. The JDK has AES-GCM and HKDF but no Argon2.
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    // SQLite with SQLCipher 4's format (SQLite3 Multiple Ciphers), for the database locked as a
    // whole, as the Swift app's GRDB with SQLCipher kept it. Only compiled against: it has no
    // Android build, so the Mac app brings it in itself and the phone uses SQLCipher for Android.
    compileOnly("io.github.willena:sqlite-jdbc:3.53.4.0")
    testImplementation("io.github.willena:sqlite-jdbc:3.53.4.0")
    // For sync's sessions; the version Compose for Desktop already brings to the Mac app.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // Lite, matching protoc 36.2: the generated code needs no reflection.
    api("com.google.protobuf:protobuf-javalite:4.36.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
}

tasks.test {
    useJUnitPlatform()
    // The lock's test files, written by the Swift app's own lock code: what this lock must open.
    systemProperty("syncednotes.fixtures", rootProject.layout.projectDirectory.dir("../SyncedNotesCore/Fixtures/lock-v1").asFile.absolutePath)
}
