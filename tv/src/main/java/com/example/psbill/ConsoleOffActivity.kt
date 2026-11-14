package com.example.psbill

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.lang.ref.WeakReference

/*
 * Shown over the HDMI input while a session is running but the console is off or asleep.
 *
 * Why: the TV's own HDMI screen counts down on "no signal" and then puts the whole TV to
 * sleep (seen on the Skyworth "GTV": PowerManager "Going to sleep due to application"
 * 60 s after the no-signal window). In real standby the network goes quiet, so the
 * dashboard can no longer reach the TV. With this page in front the TV's HDMI screen is
 * stopped, it lets go of the input and there is no countdown. The page keeps the screen on.
 *
 * KioskService opens it (console off for a short while) and closes it when the console
 * gives a signal again, then switches back to the HDMI input. OK on the remote does the
 * same by hand, for TVs that only notice the signal once the input is open.
 */
class ConsoleOffActivity : ComponentActivity() {

    companion object {
        const val PREFS = "KioskPrefs"
        const val KEY_INPUT_ID = "hdmi_input_id"
        /** System.currentTimeMillis() until which the service must not reopen this page. */
        const val KEY_SNOOZE_UNTIL = "console_cover_snooze_until"
        const val SNOOZE_MS = 45_000L
        /** Staff/player pressed OK: trust them (some TVs report "standby" with a picture on screen)
         *  and do not cover again until the input's state changes. Cleared by KioskService. */
        const val KEY_TRUST_ON = "console_cover_trust_on"

        @Volatile var isResumedNow = false
            private set
        private var current: WeakReference<ConsoleOffActivity>? = null

        fun isOpen(): Boolean = current?.get()?.let { !it.isFinishing && !it.isDestroyed } == true

        fun close() {
            current?.get()?.let { if (!it.isFinishing) it.finish() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // A small card in the corner, not the whole screen (owner, 2026-11-14): the TV menu and
        // settings stay usable around it.
        window.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(android.view.Gravity.BOTTOM or android.view.Gravity.END)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        setContent { ConsoleOffScreen(onOk = ::backToGame) }
    }

    override fun onResume() {
        super.onResume()
        isResumedNow = true
    }

    override fun onPause() {
        isResumedNow = false
        super.onPause()
    }

    /** Left by Home / the TV menu / settings: do not come back over them until the console's
     *  state really changes (KioskService clears KEY_TRUST_ON then). */
    override fun onStop() {
        if (!isFinishing) dismissForNow()
        super.onStop()
    }

    private fun dismissForNow() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TRUST_ON, true).apply()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    // dispatchKeyEvent, not onKeyDown: the Compose view sees keys first and may keep OK.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_SPACE -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) backToGame()
                return true
            }
            // Back hides the card until the console's state changes
            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_UP) { dismissForNow(); finish() }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** "The console is on": open the HDMI input and give it a moment to show a picture. */
    fun backToGame() {
        if (isFinishing) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_SNOOZE_UNTIL, System.currentTimeMillis() + SNOOZE_MS)
            .putBoolean(KEY_TRUST_ON, true).apply()
        val inputId = prefs.getString(KEY_INPUT_ID, null)
        try {
            val uri = if (inputId != null) android.media.tv.TvContract.buildChannelUriForPassthroughInput(inputId)
            else Uri.parse("content://android.media.tv/passthrough")
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
        finish()
    }
}

@androidx.compose.runtime.Composable
private fun ConsoleOffScreen(onOk: () -> Unit) {
    val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier.padding(16.dp).widthIn(max = 300.dp)
            .background(Color(0xEE0B0F19), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text("Console off?", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Press the PS button. Your session is still running.",
                color = Color(0xFFCBD5E1), fontSize = 12.sp, textAlign = TextAlign.Start
            )
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.focusRequester(focus)
                    .clickable(onClick = onOk)
                    .background(Color(0xFF4F46E5), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("OK = back to game   BACK = hide", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
