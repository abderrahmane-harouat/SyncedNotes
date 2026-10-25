package com.syncednotes.mac

import com.syncednotes.engine.render.Shaper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppFontsTest {
    private val family = AppFonts.family
    private val faces = listOf(family.regular, family.bold, family.italic, family.boldItalic, family.monospace)

    @Test
    fun arrowsPastedIntoANoteAreDrawnInEveryFaceRatherThanAsEmptyBoxes() {
        val arrows = "← → ↑ ↓ ↔ ⇒ ⇐ ⟶ ➜ ⬅"
        for (face in faces) {
            val run = Shaper(face).shape(arrows, 0, arrows.length, 20f)
            for (i in 0 until run.count) {
                assertTrue(run.glyphs[i] != 0, "${arrows[run.clusters[i]]} has no glyph in ${face.primary.id}")
            }
        }
    }

    @Test
    fun aStarAndAThumbsUpStayColourEmojiNowThatASymbolFontHasThemToo() {
        val emoji = family.regular.fonts.last().id
        for (text in listOf("⭐", "👍", "✅")) {
            val run = Shaper(family.regular).shape(text, 0, text.length, 20f)
            assertEquals(emoji, run.fontIds[0], text)
        }
    }
}
