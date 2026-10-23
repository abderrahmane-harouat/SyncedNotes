package com.syncednotes.engine.editor

/**
 * Where the engine gets a note's pictures. A paragraph names its picture by a handle the app
 * gave out ([com.syncednotes.engine.text.ParagraphStyle.picture]); the app knows what stands
 * behind it (a locked picture in the store) and decodes it.
 */
interface PictureSource {
    /** The picture's own size in points, before it is shrunk to fit; null when unknown. */
    fun size(picture: Int): Pair<Int, Int>?

    /**
     * The picture at exactly [width] × [height] pixels, as an id from
     * [com.syncednotes.engine.render.Images.load]; null while it is still being decoded, and
     * the app asks for a frame once it is ready.
     */
    fun image(picture: Int, width: Int, height: Int): Int?
}
