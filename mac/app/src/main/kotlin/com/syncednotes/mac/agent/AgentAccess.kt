package com.syncednotes.mac.agent

import com.google.protobuf.ByteString
import com.syncednotes.core.Id
import java.security.SecureRandom
import java.util.prefs.Preferences

/**
 * Which folder is shared with an AI agent, the token an agent must show, and the port it finds
 * the app on: kept in the app's preferences, apart for each notes folder, so a run on the
 * try-out notes never shares the real ones. None of it opens a note: the notes stay locked
 * under the passphrase, and the server answers only while they are unlocked.
 */
class AgentAccess(notesFolderName: String) {
    private val preferences: Preferences? = runCatching { Preferences.userRoot().node("com/syncednotes/notes/agent/$notesFolderName") }.getOrNull()

    var folder: Id?
        get() = preferences?.get(FOLDER, null)?.let(::fromHex)
        set(value) {
            if (value == null) preferences?.remove(FOLDER) else preferences?.put(FOLDER, value.hex())
            preferences?.flush()
        }

    /**
     * The token, made the first time it is asked for. Kept here too, so the one shown and the
     * one checked stay the same even if the preferences cannot be written.
     */
    val token: String
        get() = remembered ?: (preferences?.get(TOKEN, null) ?: newToken()).also { remembered = it }
    private var remembered: String? = null

    var port: Int
        get() = preferences?.getInt(PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        set(value) {
            preferences?.putInt(PORT, value)
            preferences?.flush()
        }

    /** A new token, so an agent given the old one can no longer get in. */
    fun newToken(): String {
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        preferences?.put(TOKEN, token)
        preferences?.flush()
        remembered = token
        return token
    }

    private fun fromHex(hex: String): Id? = runCatching {
        ByteString.copyFrom(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    }.getOrNull()

    private companion object {
        const val FOLDER = "folder"
        const val TOKEN = "token"
        const val PORT = "port"
        /** Below the ports macOS hands out on its own for outgoing connections (49152 and up), so it stays free. */
        const val DEFAULT_PORT = 48124
    }
}
