package com.syncednotes.android

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import java.io.File

/**
 * Light, dark, or as the phone is set: the Mac's View > Appearance, chosen on each device. Kept
 * in a file of the app's own, outside the locked notes, since it is needed before they open
 * and says nothing about them.
 */
enum class Appearance(val title: String) {
    SYSTEM("Same as the Phone"), LIGHT("Light"), DARK("Dark");

    companion object {
        private fun file(context: Context) = File(context.filesDir, "appearance")

        fun load(context: Context): Appearance =
            runCatching { valueOf(file(context).readText().trim()) }.getOrDefault(SYSTEM)

        fun save(context: Context, choice: Appearance) = file(context).writeText(choice.name)
    }
}

/** Whether this context draws dark, after [Appearance] has had its say. */
val Context.isDark: Boolean
    get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/**
 * Every screen: the chosen appearance applied to its resources, so the toolbar's colours (in
 * values-night) follow the choice as well as the phone's own setting; and drawn again when the
 * choice changes while it is in the background.
 */
abstract class ScreenActivity : Activity() {
    private var darkWhenMade = false

    override fun attachBaseContext(base: Context) {
        val choice = Appearance.load(base)
        if (choice == Appearance.SYSTEM) return super.attachBaseContext(base)
        val config = Configuration(base.resources.configuration)
        val night = if (choice == Appearance.DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        darkWhenMade = isDark
        Palette.dark = darkWhenMade
        setTheme(if (darkWhenMade) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        Palette.dark = isDark
        if (isDark != darkWhenMade) recreate()
    }
}
