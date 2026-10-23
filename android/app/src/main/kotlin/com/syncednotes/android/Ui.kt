package com.syncednotes.android

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

// The screens around the note, built in code from ordinary Android widgets (the note itself is
// the engine's). Colours are the Mac app's light ones (mac/app ui/Theme.kt), so the two apps
// look like one.

object Palette {
    /** Set by each screen as it is made, from the appearance it was made in. */
    @Volatile var dark = false

    private fun pick(light: Int, night: Int) = if (dark) night else light

    val WINDOW get() = pick(0xFFFFFFFF.toInt(), 0xFF1E1E1E.toInt())
    val SIDEBAR get() = pick(0xFFEFEFF1.toInt(), 0xFF2A2A2C.toInt())
    val SEPARATOR get() = pick(0x1A000000, 0x33FFFFFF)
    val TEXT get() = pick(0xFF1D1D1F.toInt(), 0xFFF5F5F7.toInt())
    val SECONDARY get() = pick(0xFF86868B.toInt(), 0xFF98989D.toInt())
    val TERTIARY get() = pick(0xFFB0B0B5.toInt(), 0xFF636366.toInt())
    val ACCENT get() = pick(0xFF007AFF.toInt(), 0xFF0A84FF.toInt())
    val FIELD get() = pick(0xFFF2F2F4.toInt(), 0xFF2C2C2E.toInt())
    val CONTROL get() = pick(0x0D000000, 0x14FFFFFF)
    val PRESSED get() = pick(0x1F000000, 0x29FFFFFF)
    val SELECTED get() = pick(0x14000000, 0x1FFFFFFF)
    val DANGER get() = pick(0xFFFF3B30.toInt(), 0xFFFF453A.toInt())
    val CARD get() = pick(0xFFF5F5F7.toInt(), 0xFF2C2C2E.toInt())
}

fun Context.dp(value: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()
fun Context.dp(value: Int): Int = dp(value.toFloat())

fun Context.label(text: CharSequence, size: Float = 16f, color: Int = Palette.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

fun rounded(color: Int, radius: Float, stroke: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (stroke != null) setStroke(strokeWidth, stroke)
}

/** A background that shows a press, over [color]. */
fun Context.pressable(color: Int = 0, radius: Float = dp(10).toFloat()) =
    RippleDrawable(ColorStateList.valueOf(Palette.PRESSED), rounded(color, radius), rounded(0xFF000000.toInt(), radius))

fun Context.primaryButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
    this.text = text
    isAllCaps = false
    setTextColor(0xFFFFFFFF.toInt())
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(Palette.ACCENT, dp(12).toFloat()), null)
    stateListAnimator = null
    minHeight = dp(50)
    setOnClickListener { onClick() }
}

fun Context.plainButton(text: String, color: Int = Palette.ACCENT, onClick: () -> Unit): Button = Button(this).apply {
    this.text = text
    isAllCaps = false
    setTextColor(color)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    background = pressable()
    stateListAnimator = null
    minHeight = dp(48)
    setOnClickListener { onClick() }
}

/**
 * A text field kept to this phone: the keyboard is told not to learn from it or suggest (Gboard
 * then works in incognito), and neither autofill nor content capture may read it. [secret] hides
 * what is typed, for a passphrase.
 */
fun Context.privateField(hint: String, secret: Boolean = false): EditText = EditText(this).apply {
    this.hint = hint
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
    setTextColor(Palette.TEXT)
    setHintTextColor(Palette.TERTIARY)
    background = rounded(Palette.FIELD, dp(10).toFloat())
    setPadding(dp(14), dp(12), dp(14), dp(12))
    isSingleLine = true
    inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO
}

fun Context.column(vararg children: View, padding: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(padding, padding, padding, padding)
    children.forEach { addView(it) }
}

fun Context.row(vararg children: View): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    children.forEach { addView(it) }
}

/**
 * Room that pushes what follows to the far end of a row. Zero high: a plain View asked to wrap
 * its content takes all the height it is offered, which once filled the screen with a row.
 */
fun Context.spacer(): View = View(this).margins(width = 0, height = 0, weight = 1f)

fun Context.separator(): View = View(this).apply {
    setBackgroundColor(Palette.SEPARATOR)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(0.5f)))
}

fun View.margins(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0, width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f): View {
    layoutParams = LinearLayout.LayoutParams(width, height, weight).apply { setMargins(left, top, right, bottom) }
    return this
}

/**
 * Every screen: drawn edge to edge, kept clear of the status bar, the navigation bar and the
 * keyboard, and closed to autofill and content capture, which would hand what is on it to
 * another app or the system's intelligence.
 */
fun Activity.showScreen(content: View, background: Int = Palette.WINDOW) {
    content.setBackgroundColor(background)
    content.setOnApplyWindowInsetsListener { v, insets ->
        val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            bars.top to bars.bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetTop to insets.systemWindowInsetBottom
        }
        v.setPadding(v.paddingLeft, top, v.paddingRight, bottom)
        insets
    }
    setContentView(content)
    keepToThisPhone()
    @Suppress("DEPRECATION")
    window.statusBarColor = background
    @Suppress("DEPRECATION")
    window.navigationBarColor = background
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility =
        if (Palette.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
}

/**
 * No autofill service or content capture reads anything in this window, and neither
 * screenshots nor Recent apps show it (Privacy.md). A debug build may allow screenshots when
 * `files/allow-capture` exists, which only `adb` can write, to check the app's look on
 * made-up notes, as the Mac's try-out runs do.
 */
fun Activity.keepToThisPhone() {
    val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (!(debuggable && java.io.File(filesDir, "allow-capture").exists())) {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }
    window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    if (Build.VERSION.SDK_INT >= 30) {
        window.decorView.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
    }
}
