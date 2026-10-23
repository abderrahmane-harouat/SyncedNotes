package com.syncednotes.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.syncednotes.core.LockException
import kotlin.concurrent.thread

/**
 * The first screen: on a new phone, pairing with the Mac or starting on the phone alone; after
 * that, the passphrase. The notes open only from here.
 */
class LockActivity : ScreenActivity() {
    private lateinit var problem: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (notes.isUnlocked) {
            openNotes()
            return
        }
        show()
    }

    override fun onResume() {
        super.onResume()
        // Back here after pairing set the notes up.
        if (notes.isUnlocked) openNotes()
    }

    private fun show() {
        if (notes.hasVault) showUnlock() else showWelcome()
    }

    private fun page(vararg children: View): View {
        val icon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(dp(88), dp(88)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16) }
        }
        val title = label("SyncedNotes", 28f, bold = true).apply { gravity = Gravity.CENTER }
        problem = label("", 14f, Palette.DANGER).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        val body = column(icon, title, *children, problem, padding = dp(28)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        problem.margins(top = dp(12))
        return ScrollView(this).apply {
            isFillViewport = true
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            })
        }
    }

    private fun showWelcome() {
        val text = label("Your notes, locked with a passphrase only you know, on this phone and your Mac. Nowhere else.", 16f, Palette.SECONDARY)
            .apply { gravity = Gravity.CENTER }
        showScreen(
            page(
                text.margins(top = dp(8), bottom = dp(32)),
                primaryButton("Pair with your Mac") { startActivity(Intent(this, SyncActivity::class.java)) },
                plainButton("Use on this phone only") { showCreate() }.margins(top = dp(8)),
            ),
        )
    }

    private fun showCreate() {
        val passphrase = privateField("Passphrase", secret = true)
        val again = privateField("Passphrase again", secret = true)
        val text = label(
            "Choose a passphrase. It cannot be recovered: without it, the notes cannot be opened. " +
                "Pairing with your Mac later uses the Mac's passphrase instead.",
            15f, Palette.SECONDARY,
        ).apply { gravity = Gravity.CENTER }
        lateinit var create: View
        create = primaryButton("Create") {
            val chosen = passphrase.text.toString()
            when {
                chosen.length < 8 -> say("Use at least 8 characters.")
                chosen != again.text.toString() -> say("The two passphrases differ.")
                else -> work(create, "Creating…") { notes.create(chosen) }
            }
        }
        showScreen(
            page(
                text.margins(top = dp(8), bottom = dp(24)),
                passphrase.margins(bottom = dp(10)),
                again.margins(bottom = dp(20)),
                create,
                plainButton("Back") { showWelcome() }.margins(top = dp(4)),
            ),
        )
        passphrase.requestFocus()
    }

    private fun showUnlock() {
        val passphrase = privateField("Passphrase", secret = true)
        lateinit var unlock: View
        unlock = primaryButton("Unlock") {
            val typed = passphrase.text.toString()
            if (typed.isNotEmpty()) work(unlock, "Unlocking…") { notes.unlock(typed) }
        }
        passphrase.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_ACTION_GO) unlock.performClick()
            true
        }
        passphrase.imeOptions = passphrase.imeOptions or EditorInfo.IME_ACTION_GO
        showScreen(page(passphrase.margins(top = dp(32), bottom = dp(16)), unlock))
        passphrase.requestFocus()
    }

    /** Argon2id takes a moment: off the main thread, with the button saying so. */
    private fun work(button: View, busy: String, task: () -> Unit) {
        val label = (button as TextView).text
        button.isEnabled = false
        button.text = busy
        problem.visibility = View.GONE
        thread {
            val failure = runCatching(task).exceptionOrNull()
            runOnUiThread {
                button.isEnabled = true
                button.text = label
                when {
                    failure == null -> openNotes()
                    failure is LockException -> say("That passphrase does not open these notes.")
                    else -> say("The notes could not be opened: ${failure.message}")
                }
            }
        }
    }

    private fun say(message: String) {
        problem.text = message
        problem.visibility = View.VISIBLE
    }

    private fun openNotes() {
        startActivity(Intent(this, NotesActivity::class.java))
        finish()
    }
}
