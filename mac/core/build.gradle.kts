plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(27)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

sourceSets {
    main {
        // The note format's code, made from SyncedNotesCore/Proto/note.proto by protoc; never
        // edited by hand (see the README).
        java.srcDir("Generated")
    }
}

dependencies {
    // Argon2id. The JDK has AES-GCM and HKDF but no Argon2.
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    // SQLite with SQLCipher 4's format (SQLite3 Multiple Ciphers), for the database locked as a
    // whole, as the Swift app's GRDB with SQLCipher kept it.
    implementation("io.github.willena:sqlite-jdbc:3.53.4.0")
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
