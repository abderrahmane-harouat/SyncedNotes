package com.syncednotes.mac

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The native steps, on the real system calls: a private pasteboard, never the clipboard. */
class KeepOnThisMacTest {
    @Test
    fun copiedTextComesBackExactlyArabicAndEmojiIncluded() {
        val board = Pasteboard.private()
        val text = "مرحبا, made-up café 🗒️ text\nsecond line"
        board.copy(text)
        assertEquals(text, board.paste())
    }

    @Test
    fun secureInputIsOnWhileHeldAndOffAgainAfter() {
        val carbon = SymbolLookup.libraryLookup("/System/Library/Frameworks/Carbon.framework/Carbon", Arena.global())
        val enabled = Linker.nativeLinker().downcallHandle(carbon.find("IsSecureEventInputEnabled").get(), FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN))
        val before = enabled.invoke() as Boolean
        SecureInput.hold(true)
        assertTrue(enabled.invoke() as Boolean)
        SecureInput.hold(false)
        assertEquals(before, enabled.invoke() as Boolean)
        // Held twice, released once: the system's count must not be left raised.
        SecureInput.hold(true)
        SecureInput.hold(true)
        SecureInput.hold(false)
        assertFalse(!before && enabled.invoke() as Boolean)
    }
}
