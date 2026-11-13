package com.example.psbill.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Canvas = Color(0xFF0B1020)
private val Card = Color(0xFF16203A)
private val TextMain = Color(0xFFF8FAFC)
private val TextSub = Color(0xFF94A3B8)
private val Danger = Color(0xFFEF4444)
private val Warn = Color(0xFFF59E0B)

/** Download / install controls shared by the blocking screen and the banner. */
@Composable
private fun UpdateActions() {
    val context = LocalContext.current
    val st by AppUpdater.state.collectAsState()
    var needAllow by remember { mutableStateOf(false) }
    when {
        st.downloading -> {
            LinearProgressIndicator(progress = { st.progress / 100f }, modifier = Modifier.fillMaxWidth())
            Text("Downloading... ${st.progress}%", color = TextSub, fontSize = 13.sp)
        }
        st.ready -> Button(
            onClick = { if (AppUpdater.canInstall(context)) AppUpdater.install(context) else needAllow = true },
            enabled = !st.installing, modifier = Modifier.fillMaxWidth()
        ) { Text(if (st.installing) "Installing..." else "Install update") }
        else -> Button(onClick = { AppUpdater.download(context) }, modifier = Modifier.fillMaxWidth()) { Text("Download update") }
    }
    st.error?.let { Text(it, color = Danger, fontSize = 13.sp) }
    if (needAllow) {
        Text("Allow PSBill to install updates, then come back and tap Install.", color = Warn, fontSize = 13.sp)
        OutlinedButton(onClick = {
            runCatching { context.startActivity(AppUpdater.allowInstallsIntent(context)) }
            needAllow = false
        }, modifier = Modifier.fillMaxWidth()) { Text("Open settings") }
    }
}

/** Shown instead of the app while a mandatory update is not installed. */
@Composable
fun UpdateRequiredScreen() {
    val st by AppUpdater.state.collectAsState()
    Box(Modifier.fillMaxSize().background(Canvas), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Update required", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "Your business needs everyone on version ${st.versionName}. Install it to keep working.",
                color = TextSub, textAlign = TextAlign.Center,
            )
            st.hint?.optString("notes")?.takeIf { it.isNotBlank() && it != "null" }?.let {
                Text(it, color = TextSub, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().background(Card, RoundedCornerShape(10.dp)).padding(12.dp))
            }
            UpdateActions()
        }
    }
}

/** Optional update: a slim card at the top of the app. */
@Composable
fun UpdateBanner() {
    val st by AppUpdater.state.collectAsState()
    var hidden by remember(st.versionName) { mutableStateOf(false) }
    if (st.hint == null || st.mandatory || hidden) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .background(Card, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Update ${st.versionName} available", color = TextMain, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { hidden = true }) { Text("Later") }
        }
        UpdateActions()
    }
}
