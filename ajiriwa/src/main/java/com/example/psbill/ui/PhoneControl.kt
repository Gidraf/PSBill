package com.example.psbill.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.psbill.core.ActivityLog
import com.example.psbill.core.AppUpdater
import com.example.psbill.core.DeviceAgent
import com.example.psbill.core.StaffAlertNotifier
import com.example.psbill.ui.theme.AjiriwaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

// ── In-app update ───────────────────────────────────────────────────────────

/** Download / install controls shared by the blocking screen and the banner. */
@Composable
private fun UpdateActions() {
    val context = LocalContext.current
    val st by AppUpdater.state.collectAsState()
    var needAllow by remember { mutableStateOf(false) }
    when {
        st.downloading -> {
            LinearProgressIndicator(progress = { st.progress / 100f }, modifier = Modifier.fillMaxWidth())
            Text("Downloading… ${st.progress}%", color = AjiriwaColors.TextSecondary, fontSize = 13.sp)
        }
        st.ready -> Button(onClick = {
            if (AppUpdater.canInstall(context)) AppUpdater.install(context) else needAllow = true
        }, enabled = !st.installing, modifier = Modifier.fillMaxWidth()) { Text(if (st.installing) "Installing…" else "Install update") }
        else -> Button(onClick = { AppUpdater.download(context) }, modifier = Modifier.fillMaxWidth()) { Text("Download update") }
    }
    st.error?.let { Text(it, color = AjiriwaColors.Danger, fontSize = 13.sp) }
    if (needAllow) {
        Text("Allow Ajiriwa to install updates, then come back and tap Install.", color = AjiriwaColors.Warning, fontSize = 13.sp)
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
    Surface(color = AjiriwaColors.Canvas, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Update required", color = AjiriwaColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "Your business needs everyone on version ${st.versionName}. Install it to keep working — your data stays on the phone and uploads after the update.",
                color = AjiriwaColors.TextSecondary, textAlign = TextAlign.Center,
            )
            st.hint?.optString("notes")?.takeIf { it.isNotBlank() && it != "null" }?.let {
                Card(colors = CardDefaults.cardColors(containerColor = AjiriwaColors.Surface), modifier = Modifier.fillMaxWidth()) {
                    Text(it, color = AjiriwaColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                }
            }
            UpdateActions()
        }
    }
}

/** Optional update: a slim card at the top of every screen. */
@Composable
fun UpdateBanner() {
    val st by AppUpdater.state.collectAsState()
    var hidden by remember(st.versionName) { mutableStateOf(false) }
    if (st.hint == null || st.mandatory || hidden) return
    Card(colors = CardDefaults.cardColors(containerColor = AjiriwaColors.SurfaceAlt),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Update ${st.versionName} available", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { hidden = true }) { Text("Later") }
            }
            UpdateActions()
        }
    }
}

// ── Attendant alerts ────────────────────────────────────────────────────────

/** Open alerts (buy SMS bundle, read the meter, pump, restock); tap to go fix it. */
@Composable
fun AlertsBanner(onOpen: (String) -> Unit) {
    val agent by DeviceAgent.state.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val alerts = agent.alerts
    if (alerts.isEmpty()) return
    val critical = alerts.any { it.optString("severity") == "CRITICAL" }
    Surface(color = if (critical) AjiriwaColors.ErrorContainer else androidx.compose.ui.graphics.Color(0xFF4A3410),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(
                if (alerts.size == 1) alerts[0].optString("title") else "${alerts.size} things need attention" + if (expanded) "" else " — tap to see",
                color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (expanded || alerts.size == 1) {
                alerts.take(8).forEach { a ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            if (alerts.size > 1) Text(a.optString("title"), color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(a.optString("message"), color = androidx.compose.ui.graphics.Color(0xFFFFE0B2), fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            ActivityLog.log("alert_open", detail = JSONObject().put("kind", a.optString("kind")))
                            onOpen(StaffAlertNotifier.screenFor(a.optString("kind")))
                        }) { Text("Fix", color = androidx.compose.ui.graphics.Color.White) }
                    }
                }
            }
        }
    }
}

// ── Launcher mode ───────────────────────────────────────────────────────────

object LauncherMode {
    private fun alias(context: Context) = ComponentName(context, "com.example.psbill.LauncherAlias")

    fun enabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(alias(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** Turn the Home role on/off; the user then picks Ajiriwa as the home app. */
    fun set(context: Context, on: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            alias(context),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        ActivityLog.log(if (on) "launcher_on" else "launcher_off", context = context)
        runCatching { context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
}

private data class PhoneApp(val label: String, val pkg: String, val activity: String, val icon: ImageBitmap?)

private fun Drawable.toBitmapSafe(): ImageBitmap? = runCatching {
    if (this is BitmapDrawable && bitmap != null) return@runCatching bitmap.asImageBitmap()
    val w = intrinsicWidth.takeIf { it > 0 } ?: 96
    val h = intrinsicHeight.takeIf { it > 0 } ?: 96
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    setBounds(0, 0, c.width, c.height)
    draw(c)
    b.asImageBitmap()
}.getOrNull()

/** The phone's apps, opened through Ajiriwa so every launch is logged (works as the Home screen). */
@Composable
fun AppsScreen() {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<PhoneApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var launcherOn by remember { mutableStateOf(LauncherMode.enabled(context)) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val list = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(main, PackageManager.ResolveInfoFlags.of(0)) else pm.queryIntentActivities(main, 0)
            list.filter { it.activityInfo.packageName != context.packageName }
                .map { PhoneApp(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name, runCatching { it.loadIcon(pm).toBitmapSafe() }.getOrNull()) }
                .sortedBy { it.label.lowercase() }
        }
    }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = AjiriwaColors.Surface), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use Ajiriwa as the home screen", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("The phone opens Ajiriwa on Home; apps open from here and every operation is logged (offline too).",
                        color = AjiriwaColors.TextMuted, fontSize = 12.sp)
                }
                Switch(checked = launcherOn, onCheckedChange = { LauncherMode.set(context, it); launcherOn = it })
            }
        }
        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search apps") }, modifier = Modifier.fillMaxWidth())
        val shown = apps.filter { query.isBlank() || it.label.contains(query, true) }
        LazyVerticalGrid(columns = GridCells.Adaptive(84.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(shown, key = { it.pkg + it.activity }) { app ->
                Column(
                    Modifier.clickable {
                        ActivityLog.log("app_launch", detail = JSONObject().put("package", app.pkg).put("label", app.label), context = context)
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                                .setClassName(app.pkg, app.activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (app.icon != null) Image(app.icon, app.label, Modifier.size(48.dp))
                    else Surface(shape = RoundedCornerShape(12.dp), color = AjiriwaColors.SurfaceHigh, modifier = Modifier.size(48.dp)) {}
                    Text(app.label, color = AjiriwaColors.TextSecondary, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
