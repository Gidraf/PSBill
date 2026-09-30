package com.example.psbill

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class CrashRecoveryActivity : ComponentActivity() {

    private var countdownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("CrashPrefs", MODE_PRIVATE)
        val crashMessage = prefs.getString("crash_message", "Unknown error") ?: "Unknown error"
        val crashTrace = prefs.getString("crash_trace", "") ?: ""

        setContent {
            var secondsLeft by remember { mutableStateOf(15) }

            LaunchedEffect(Unit) {
                countdownTimer = object : CountDownTimer(15_000, 1_000) {
                    override fun onTick(millisUntilFinished: Long) {
                        secondsLeft = (millisUntilFinished / 1000).toInt() + 1
                    }
                    override fun onFinish() {
                        restartApp()
                    }
                }.start()
            }

            DisposableEffect(Unit) {
                onDispose { countdownTimer?.cancel() }
            }

            CrashScreen(
                crashMessage = crashMessage,
                crashTrace = crashTrace,
                secondsLeft = secondsLeft,
                onRestart = { countdownTimer?.cancel(); restartApp() },
                onOpenSettings = { openAndroidSettings() },
                onChangeLauncher = { openLauncherChooser() }
            )
        }
    }

    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        if (intent != null) startActivity(intent)
        finish()
    }

    private fun openAndroidSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (_: Exception) {}
    }

    private fun openLauncherChooser() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (_: Exception) {
            // Fallback: open general settings
            openAndroidSettings()
        }
    }

    @Composable
    private fun CrashScreen(
        crashMessage: String,
        crashTrace: String,
        secondsLeft: Int,
        onRestart: () -> Unit,
        onOpenSettings: () -> Unit,
        onChangeLauncher: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0F19)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Icon + Title
                Text("⚠", fontSize = 48.sp, textAlign = TextAlign.Center)

                Text(
                    text = "PlayGate Crashed",
                    color = Color(0xFFEF4444),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "The app encountered an unexpected error. It will restart automatically.",
                    color = Color(0xFF94A3B8),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )

                // Countdown
                Box(
                    modifier = Modifier
                        .background(Color(0xFF161E2F), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF00E676).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 24.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = "Auto-restart in $secondsLeft seconds",
                        color = Color(0xFF00E676),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Error details
                if (crashMessage.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF161E2F), RoundedCornerShape(10.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Error",
                            color = Color(0xFFEF4444),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = crashMessage,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        if (crashTrace.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = crashTrace.lines().take(6).joinToString("\n"),
                                color = Color(0xFF64748B),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onRestart,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Restart Now", color = Color.Black, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8))
                    ) {
                        Text("Open Settings")
                    }

                    OutlinedButton(
                        onClick = onChangeLauncher,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF60A5FA))
                    ) {
                        Text("Change Launcher")
                    }
                }

                Text(
                    text = "If the app keeps crashing, tap 'Change Launcher' to switch to the stock Android TV launcher.",
                    color = Color(0xFF475569),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
