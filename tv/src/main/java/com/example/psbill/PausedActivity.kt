package com.example.psbill

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.lang.ref.WeakReference

/*
 * What HOME shows while the owner has paused PSBill on this TV (RemoteControl). Nothing is
 * locked: the buttons open TV settings, the installed apps and the home-screen choice, and,
 * when the owner asked to remove PSBill, the uninstall prompt.
 */
class PausedActivity : ComponentActivity() {

    companion object {
        private var current: WeakReference<PausedActivity>? = null
        fun closeIfOpen() { current?.get()?.let { if (!it.isFinishing) it.finish() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        try { stopLockTask() } catch (_: Exception) {}
        render()
    }

    override fun onResume() {
        super.onResume()
        if (!RemoteControl.isPaused(this)) {   // resumed while we were away
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        render()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    private fun open(action: String) {
        try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    private fun render() {
        val removing = RemoteControl.isRemoving(this)
        val msg = RemoteControl.message(this)
        setContent {
            PausedScreen(
                removing = removing,
                message = msg,
                onSettings = { open(Settings.ACTION_SETTINGS) },
                onApps = { open(Settings.ACTION_APPLICATION_SETTINGS) },
                onHome = { open(Settings.ACTION_HOME_SETTINGS) },
                onUninstall = { RemoteControl.startRemoval(this) },
            )
        }
    }
}

@Composable
private fun PausedButton(text: String, primary: Boolean = false, focus: FocusRequester? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        (if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .padding(8.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .background(if (primary) Color(0xFFDC2626) else Color(0xFF1E293B), RoundedCornerShape(14.dp))
            .border(if (focused) 3.dp else 0.dp, Color.White, RoundedCornerShape(14.dp))
            .padding(horizontal = 26.dp, vertical = 14.dp)
    ) {
        Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PausedScreen(removing: Boolean, message: String, onSettings: () -> Unit, onApps: () -> Unit,
                         onHome: () -> Unit, onUninstall: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0F19)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(48.dp)) {
            Text(if (removing) "Removing PSBill" else "PSBill is paused", color = Color.White,
                fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text(message.ifEmpty { "The owner paused PSBill on this TV. Other apps and TV settings can be used." },
                color = Color(0xFFCBD5E1), fontSize = 22.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(if (removing) "Press Uninstall and confirm. The screen is unlinked from the lounge."
                 else "The lock screen comes back by itself when the owner resumes PSBill.",
                color = Color(0xFF94A3B8), fontSize = 18.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            Row {
                if (removing) PausedButton("Uninstall PSBill", primary = true, focus = focus, onClick = onUninstall)
                PausedButton("TV settings", focus = if (removing) null else focus, onClick = onSettings)
                PausedButton("Apps", onClick = onApps)
                PausedButton("Home screen", onClick = onHome)
            }
        }
    }
}
