package com.syncednotes.mac

import java.util.prefs.Preferences

/**
 * Light or dark: the Mac's own choice by default, or one picked in the app, kept between
 * launches. Only this word is stored, in the app's own preferences inside its sandbox.
 */
enum class Appearance(val title: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark");

    fun save() = runCatching { preferences().put(KEY, name) }.let { }

    /** Whether to draw dark, given what the Mac is set to. */
    fun dark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        private const val KEY = "appearance"

        private fun preferences(): Preferences = Preferences.userRoot().node("com/syncednotes/notes")

        fun load(): Appearance = runCatching { valueOf(preferences().get(KEY, SYSTEM.name)) }.getOrDefault(SYSTEM)
    }
}
