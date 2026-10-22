package com.syncednotes.mac

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable

/**
 * Compose's clipboard for the app's own fields (search, folder names), through [Pasteboard]:
 * what is copied there is for this Mac only too. Compose's own goes through AWT, which cannot
 * keep a copy off Universal Clipboard.
 */
@OptIn(ExperimentalComposeUiApi::class)
object MacOnlyClipboard : Clipboard, ClipboardManager {
    /** There is no AWT clipboard behind this one. */
    override val nativeClipboard: Any get() = Pasteboard.general

    override suspend fun getClipEntry(): ClipEntry? = getClip()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) = setClip(clipEntry)

    override fun getClip(): ClipEntry? = Pasteboard.paste()?.let { ClipEntry(StringSelection(it)) }

    override fun setClip(clipEntry: ClipEntry?) {
        val transferable = clipEntry?.nativeClipEntry as? Transferable ?: return
        if (!transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) return
        (transferable.getTransferData(DataFlavor.stringFlavor) as? String)?.let(Pasteboard::copy)
    }

    override fun getText(): AnnotatedString? = Pasteboard.paste()?.let(::AnnotatedString)

    override fun setText(annotatedString: AnnotatedString) = Pasteboard.copy(annotatedString.text).let { }

    override fun hasText(): Boolean = Pasteboard.paste() != null
}
