package com.syncednotes.core

import com.google.protobuf.ByteString
import syncednotes.note.v1.Embedded
import syncednotes.note.v1.Note
import syncednotes.note.v1.Paragraph

// What the list and search need from a note, worked out from the stored note alone. One
// definition, used both by the editor when it saves and by an import, so the two can never
// name or index a note differently. The Swift app's NoteSummary.swift, unchanged in meaning.

/** Every paragraph's text, table cells included, without the U+FFFC placeholders. */
val Note.words: List<String> get() = paragraphsList.flatMap(::wordsOf)

/** The first line with any words: the note's name in the list. */
val Note.title: String
    get() = words.asSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(120) ?: ""

/** Whether there is nothing in it at all: no words, no picture, table or divider. */
val Note.isEmpty: Boolean
    get() = title.isEmpty() && paragraphsList.none { p -> p.runsList.any { it.hasEmbedded() } }

/** The IDs of the pictures it shows, table cells included. */
val Note.pictureIds: List<ByteString> get() = paragraphsList.flatMap(::pictureIdsIn)

private fun wordsOf(paragraph: Paragraph): List<String> {
    val own = paragraph.text.replace('￼', ' ')
    val cells = paragraph.runsList.flatMap { run ->
        if (run.embedded.kindCase != Embedded.KindCase.TABLE) emptyList()
        else run.embedded.table.rowsList.flatMap { row -> row.cellsList.flatMap { cell -> cell.paragraphsList.flatMap(::wordsOf) } }
    }
    return listOf(own) + cells
}

private fun pictureIdsIn(paragraph: Paragraph): List<ByteString> = paragraph.runsList.flatMap { run ->
    when (run.embedded.kindCase) {
        Embedded.KindCase.PICTURE -> listOf(run.embedded.picture.id)
        Embedded.KindCase.TABLE -> run.embedded.table.rowsList.flatMap { row -> row.cellsList.flatMap { cell -> cell.paragraphsList.flatMap(::pictureIdsIn) } }
        else -> emptyList()
    }
}
