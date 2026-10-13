package com.syncednotes.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Samsung's colour swatches: one continuous strip of 28dp squares, rounded only at its two
 * outer ends. The chosen colour rises out of the strip as a 32dp rounded tile with a shadow and
 * a check. Several palettes are pages of the same strip, changed by swiping, with dots below.
 */
class PaletteStripView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    /** Each page holds up to 8 colours; 0 is the "none" swatch, drawn in the page colour. */
    var pages: List<IntArray> = emptyList()
        set(value) {
            field = value
            page = page.coerceIn(0, maxOf(0, value.size - 1))
            invalidate()
        }

    /** The colour of the note page, which the "none" swatch shows. */
    var noneColor: Int = 0xFFFCFCFC.toInt()

    /** The colour in use, or null when it is in no palette (then nothing is marked). */
    var selected: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    var onPick: ((Int) -> Unit)? = null

    private var page = 0
    private val dp = resources.displayMetrics.density
    private val chip = 28 * dp
    private val radius = 6 * dp
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.5f * dp
        color = 0xFFD9D9D9.toInt()
    }
    private val raised = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // The selected tile's 2dp elevation, as a soft shadow.
        setShadowLayer(2 * dp, 0f, 1 * dp, 0x40000000)
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()
    private var downX = 0f
    private var downY = 0f

    init {
        // Shadow layers on shapes need software drawing.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /** Moves to the page holding the chosen colour, as Samsung does when the popup opens. */
    fun showSelectedPage() {
        val i = pages.indexOfFirst { p -> p.any { it == selected } }
        if (i >= 0) page = i
        invalidate()
    }

    private fun stripLeft(count: Int) = paddingLeft + (width - paddingLeft - paddingRight - count * chip) / 2f
    private val stripTop: Float get() = paddingTop + 3 * dp

    override fun onDraw(canvas: Canvas) {
        val colors = pages.getOrNull(page) ?: return
        val left = stripLeft(colors.size)
        val top = stripTop
        for (i in colors.indices) {
            val l = left + i * chip
            rect.set(l, top, l + chip, top + chip)
            val r0 = if (i == 0) radius else 0f
            val r1 = if (i == colors.size - 1) radius else 0f
            path.reset()
            path.addRoundRect(rect, floatArrayOf(r0, r0, r1, r1, r1, r1, r0, r0), Path.Direction.CW)
            val shown = shown(colors[i])
            fill.color = shown
            canvas.drawPath(path, fill)
            if (isNearWhite(shown)) canvas.drawPath(path, outline)
        }
        val i = colors.indexOfFirst { it == selected }
        if (i >= 0) {
            val l = left + i * chip - 2 * dp
            rect.set(l, top - 2 * dp, l + chip + 4 * dp, top + chip + 2 * dp)
            val shown = shown(colors[i])
            raised.color = shown
            canvas.drawRoundRect(rect, radius, radius, raised)
            if (isNearWhite(shown)) canvas.drawRoundRect(rect, radius, radius, outline)
            // A dark check on light colours, a light one elsewhere.
            tick.color = if (isLight(shown)) 0xFF4C4C4C.toInt() else 0xFFFAFAFA.toInt()
            val cx = rect.centerX()
            val cy = rect.centerY()
            path.reset()
            path.moveTo(cx - 5.5f * dp, cy + 0.2f * dp)
            path.lineTo(cx - 1.8f * dp, cy + 3.8f * dp)
            path.lineTo(cx + 5.5f * dp, cy - 3.6f * dp)
            canvas.drawPath(path, tick)
        }
        if (pages.size > 1) drawDots(canvas, top + chip + 9 * dp)
    }

    private fun drawDots(canvas: Canvas, cy: Float) {
        val size = 5 * dp
        val total = pages.size * size + (pages.size - 1) * size
        var x = (width - total) / 2f + size / 2
        for (p in pages.indices) {
            fill.color = if (p == page) 0xFF808080.toInt() else 0x33808080
            canvas.drawCircle(x, cy, size / 2, fill)
            x += 2 * size
        }
    }

    private fun shown(color: Int) = if (color == 0) noneColor else color

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                if (abs(dx) > 32 * dp && pages.size > 1) {
                    page = (page + if (dx < 0) 1 else -1).coerceIn(0, pages.size - 1)
                    invalidate()
                } else if (abs(dx) < 8 * dp && abs(event.y - downY) < 8 * dp) {
                    val colors = pages.getOrNull(page) ?: return true
                    val i = ((event.x - stripLeft(colors.size)) / chip).toInt()
                    if (i in colors.indices && event.y in stripTop - 4 * dp..stripTop + chip + 4 * dp) {
                        playSoundEffect(android.view.SoundEffectConstants.CLICK)
                        onPick?.invoke(colors[i])
                    }
                }
            }
        }
        return true
    }

    private fun hsv(color: Int) = FloatArray(3).also { Color.colorToHSV(color, it) }

    /** Samsung outlines near-white swatches so they show against the white card. */
    private fun isNearWhite(color: Int): Boolean = hsv(color).let { it[1] == 0f && it[2] >= 0.96f }

    /** Samsung's "adaptive" light colours, which get a dark check. */
    private fun isLight(color: Int): Boolean {
        val h = hsv(color)
        val s = h[1]
        val v = h[2]
        return (s < 0.1f && v >= 0.97f) || (s >= 0.1f && s < 0.3f && (v >= 0.99f || v in 0.88f..0.93f))
    }
}
