package com.syncednotes.core

import com.syncednotes.engine.editor.PictureSource
import com.syncednotes.engine.render.Images
import java.util.concurrent.Executors
import syncednotes.note.v1.Picture

/**
 * The engine names a picture by a number ([com.syncednotes.engine.text.ParagraphStyle.picture]);
 * this is what each number stands for: the note format's [Picture], with its ID, type and size.
 * One per process, so a picture keeps its number from one note to the next.
 */
object PictureHandles {
    private val byId = HashMap<Id, Int>()
    private val pictures = HashMap<Int, Picture>()
    private var next = 1

    @Synchronized
    fun handle(picture: Picture): Int {
        val handle = byId.getOrPut(picture.id) { next++ }
        pictures[handle] = picture
        return handle
    }

    @Synchronized
    fun picture(handle: Int): Picture? = pictures[handle]
}

/**
 * Turns a picture file's bytes into premultiplied RGBA at exactly [width] × [height] pixels:
 * Android's decoder on the phone, the JDK's ImageIO on the Mac. Null for bytes it cannot read.
 */
fun interface PictureDecoder {
    fun decode(bytes: ByteArray, width: Int, height: Int): ByteArray?
}

/**
 * The engine's pictures for the notes in [store]: each opened from its locked file and decoded
 * at the size it is shown, away from the drawing thread, then kept until it is shown at another
 * size or the notes lock. [onReady] asks for a frame once one is ready.
 */
class PictureCache(
    private val store: () -> NoteStore?,
    private val decoder: PictureDecoder,
    private val onReady: () -> Unit,
) : PictureSource {
    private class Shown(val width: Int, val height: Int, val image: Int)

    private val shown = HashMap<Int, Shown>()
    private val pending = HashSet<Int>()
    /** When each picture last could not be read: it may not have arrived from the other device yet. */
    private val missing = HashMap<Int, Long>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "SyncedNotes pictures").apply { isDaemon = true } }

    override fun size(picture: Int): Pair<Int, Int>? =
        PictureHandles.picture(picture)?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width to it.height }

    @Synchronized
    override fun image(picture: Int, width: Int, height: Int): Int? {
        shown[picture]?.let { if (it.width == width && it.height == height) return it.image }
        // Until the new size is ready the frame stays empty: the old size would spill out of it.
        if (picture in pending || width <= 0 || height <= 0) return null
        missing[picture]?.let { if (System.currentTimeMillis() - it < RETRY_MILLIS) return null }
        val id = PictureHandles.picture(picture)?.id ?: return null
        pending += picture
        worker.execute {
            val rgba = runCatching { store()?.picture(id)?.let { decoder.decode(it, width, height) } }.getOrNull()
            val image = rgba?.let { Images.load(it, width, height) }?.takeIf { it > 0 }
            synchronized(this) {
                pending -= picture
                if (image != null) {
                    shown.put(picture, Shown(width, height, image))?.let { Images.free(it.image) }
                    missing -= picture
                } else {
                    missing[picture] = System.currentTimeMillis()
                }
            }
            onReady()
        }
        return null
    }

    /** Lets every picture that could not be read be tried again: pictures came in from the other device. */
    @Synchronized
    fun retryMissing() {
        missing.clear()
        onReady()
    }

    /** Forgets every decoded picture: the notes locked. */
    @Synchronized
    fun clear() {
        for (s in shown.values) Images.free(s.image)
        shown.clear()
        missing.clear()
    }

    companion object {
        private const val RETRY_MILLIS = 3_000L
    }
}

/**
 * Keeps a picture the user inserted: locked in the store under a new ID, and described for the
 * note. [bytes] are already what is to be kept: the platform re-encodes a picture before this,
 * which also drops what a camera writes into it (where and when it was taken).
 */
fun NoteStore.addPicture(bytes: ByteArray, width: Int, height: Int): Picture {
    val id = newId()
    storePicture(bytes, id)
    return Picture.newBuilder().setId(id).setType(pictureType(bytes)).setWidth(width).setHeight(height).build()
}

/** The picture file's type as a Uniform Type Identifier, from its first bytes. */
fun pictureType(bytes: ByteArray): String = when {
    bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() -> "public.png"
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "public.jpeg"
    else -> "public.data"
}
