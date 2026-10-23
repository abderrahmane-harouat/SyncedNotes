package com.syncednotes.mac

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.prefs.Preferences

/**
 * How wide the sidebar and the list of notes are, and whether the sidebar is shown: changed by
 * hand, and kept between launches with the appearance, in the app's own preferences.
 */
class WindowLayout {
    private val preferences: Preferences? = runCatching { Preferences.userRoot().node("com/syncednotes/notes") }.getOrNull()

    var sidebarWidth by mutableFloatStateOf(read("sidebarWidth", 214f).coerceIn(SIDEBAR_MIN, SIDEBAR_MAX))
    var listWidth by mutableFloatStateOf(read("listWidth", 272f).coerceIn(LIST_MIN, LIST_MAX))
    var sidebarShown by mutableStateOf(preferences?.getBoolean("sidebarShown", true) ?: true)
        private set

    fun toggleSidebar() {
        sidebarShown = !sidebarShown
        runCatching { preferences?.putBoolean("sidebarShown", sidebarShown) }
    }

    /** After a drag: the widths as they now are. */
    fun save() {
        runCatching {
            preferences?.putFloat("sidebarWidth", sidebarWidth)
            preferences?.putFloat("listWidth", listWidth)
        }
    }

    private fun read(key: String, default: Float) = preferences?.getFloat(key, default) ?: default

    companion object {
        const val SIDEBAR_MIN = 160f
        const val SIDEBAR_MAX = 400f
        const val LIST_MIN = 200f
        const val LIST_MAX = 480f
    }
}
