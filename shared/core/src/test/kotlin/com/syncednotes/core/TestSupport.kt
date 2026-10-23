package com.syncednotes.core

import com.google.protobuf.ByteString
import java.io.File
import java.nio.file.Files
import java.time.Instant
import syncednotes.note.v1.Note
import syncednotes.note.v1.Paragraph

/** A made-up note of one paragraph. */
fun note(text: String): Note =
    Note.newBuilder().setFormatVersion(1).addParagraphs(Paragraph.newBuilder().setText(text)).build()

/** An ID of sixteen [byte]s, easy to tell apart in a failure. */
fun id(byte: Int): Id = ByteString.copyFrom(ByteArray(16) { byte.toByte() })

fun at(seconds: Long): Instant = Instant.ofEpochSecond(seconds)

/** A folder of its own under the system's temporary folder: never the real notes. */
fun temporaryFolder(name: String): File = Files.createTempDirectory("syncednotes-$name-").toFile()

/** A new vault and store in a temporary folder. */
fun newStore(passphrase: String = "made-up passphrase", folder: File = temporaryFolder("store")): NoteStore =
    NoteStore(folder, createVault(folder, passphrase))

fun NoteStore.save(text: String, id: Int, seconds: Long = 100) =
    save(note(text), id(id), title = text, words = text, now = at(seconds))

/** Every file under [folder], read raw. */
fun everythingOnDisk(folder: File): List<Pair<File, ByteArray>> =
    folder.walkTopDown().filter { it.isFile }.map { it to it.readBytes() }.toList()

fun ByteArray.contains(part: ByteArray): Boolean =
    (0..size - part.size).any { start -> part.indices.all { this[start + it] == part[it] } }
