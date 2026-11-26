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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
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
import com.example.psbill.core.PhoneLock
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

/** What one alert kind looks like and what its button does (kinds come from staff_alerts.py on the server). */
private data class AlertLook(val icon: String, val action: String, val screen: String)

private fun lookOf(kind: String): AlertLook = when {
    kind == "SMS_UNSENT" -> AlertLook("✉️", "Buy SMS bundle", "sms")
    kind == "METER_READING" || kind == "DAILY_METER" -> AlertLook("💧", "Read the meter", "stock")
    kind == "READY_LOW" -> AlertLook("🚰", "Refill now", "stock")
    kind == "STORAGE_LOW" -> AlertLook("📦", "Restock", "stock")
    kind == "NEGATIVE_STOCK" -> AlertLook("⚠️", "Fix the stock", "stock")
    kind == "DAILY_CLOSE" -> AlertLook("💵", "Count the cash", "stock")
    kind == "VALUE_PLAN_DELIVER" -> AlertLook("🚚", "See deliveries", "orders")
    kind == "VALUE_APPROVE" || kind == "VALUE_REPLY" -> AlertLook("💬", "Check messages", "customers")
    kind == "VALUE_REWARD" -> AlertLook("🎁", "Give the reward", "customers")
    kind.startsWith("VALUE_") -> AlertLook("🤝", "Open customers", "customers")
    kind.startsWith("WA_") -> AlertLook("📱", "Check WhatsApp", "overview")
    else -> AlertLook("🔔", "Open", StaffAlertNotifier.screenFor(kind))
}

private fun sevRank(a: JSONObject) = when (a.optString("severity")) { "CRITICAL" -> 0; "WARNING" -> 1; else -> 2 }

/**
 * Things to do now (buy SMS bundle, read the meter, refill, restock...). One slim bar on every screen: the most urgent
 * item and how many there are; tap it for a sheet of cards, each with a plain button that goes to the fix.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsBanner(onOpen: (String) -> Unit) {
    val agent by DeviceAgent.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    val alerts = remember(agent.alerts) { agent.alerts.sortedBy(::sevRank) }
    if (alerts.isEmpty()) return
    val top = alerts.first()
    val critical = sevRank(top) == 0
    val tint = if (critical) AjiriwaColors.Danger else AjiriwaColors.Warning
    fun go(a: JSONObject) {
        ActivityLog.log("alert_open", detail = JSONObject().put("kind", a.optString("kind")))
        open = false
        onOpen(lookOf(a.optString("kind")).screen)
    }
    Surface(
        color = tint.copy(alpha = 0.16f), shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clickable { open = true },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(lookOf(top.optString("kind")).icon, fontSize = 22.sp)
            Column(Modifier.weight(1f)) {
                Text(top.optString("title"), color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (alerts.size == 1) "Tap to fix" else "+${alerts.size - 1} more to do · tap to see all",
                    color = AjiriwaColors.TextSecondary, fontSize = 12.sp)
            }
            Surface(color = tint, shape = RoundedCornerShape(50)) {
                Text("${alerts.size}", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold,
                    fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
            }
        }
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }, containerColor = AjiriwaColors.SurfaceAlt) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (alerts.size == 1) "1 thing to do" else "${alerts.size} things to do", color = AjiriwaColors.TextPrimary,
                    fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Most urgent first. Tap the button to go and fix it.", color = AjiriwaColors.TextMuted, fontSize = 12.sp)
                alerts.take(20).forEach { a ->
                    val look = lookOf(a.optString("kind"))
                    val c = when (sevRank(a)) { 0 -> AjiriwaColors.Danger; 1 -> AjiriwaColors.Warning; else -> AjiriwaColors.Primary }
                    Card(colors = CardDefaults.cardColors(containerColor = AjiriwaColors.Surface), shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.height(IntrinsicSize.Min)) {
                            Box(Modifier.width(5.dp).fillMaxHeight().background(c))
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(look.icon, fontSize = 20.sp)
                                    Text(a.optString("title"), color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp, modifier = Modifier.weight(1f))
                                    if (sevRank(a) == 0) Text("URGENT", color = c, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                                a.optString("message").takeIf { it.isNotBlank() }?.let {
                                    Text(it, color = AjiriwaColors.TextSecondary, fontSize = 13.sp)
                                }
                                Button(onClick = { go(a) }, colors = ButtonDefaults.buttonColors(containerColor = c),
                                    modifier = Modifier.fillMaxWidth().height(44.dp)) {
                                    Text(look.action, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
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

/** One line of the phone set-up card: done or not, and a button when it is not. */
@Composable
private fun SetupRow(done: Boolean, label: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(if (done) "✅" else "⭕", fontSize = 16.sp)
        Text(label, color = if (done) AjiriwaColors.TextPrimary else AjiriwaColors.TextSecondary, fontSize = 14.sp,
            modifier = Modifier.weight(1f).padding(start = 8.dp))
        if (!done && action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/** Owner: make this the business phone (like the TV app): Ajiriwa is Home + the SMS app, only allowed apps open. */
@Composable
private fun PhoneSetupCard(onChanged: () -> Unit) {
    val context = LocalContext.current
    var v by remember { mutableIntStateOf(0) }
    val refresh = { v++; onChanged() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) v++ }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    val owner = remember(v) { PhoneLock.isDeviceOwner(context) }
    val home = remember(v) { PhoneLock.isHome(context) }
    val sms = remember(v) { PhoneLock.isDefaultSms(context) }
    val locked = remember(v) { PhoneLock.locked(context) }
    val appsLocked = remember(v) { PhoneLock.appsLocked(context) }
    var msg by remember { mutableStateOf<String?>(null) }
    var confirmRelease by remember { mutableStateOf(false) }
    val launch = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { refresh() }
    fun open(i: Intent?) { i?.let { runCatching { launch.launch(it) }.onFailure { e -> msg = e.message } } }

    Card(colors = CardDefaults.cardColors(containerColor = AjiriwaColors.Surface), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Business phone", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                val tag = when { locked && appsLocked -> "Fully locked"; locked -> "Locked"; else -> "Open" }
                Surface(color = if (locked) AjiriwaColors.Success.copy(alpha = 0.2f) else AjiriwaColors.SurfaceHigh, shape = RoundedCornerShape(50)) {
                    Text(tag, color = if (locked) AjiriwaColors.Success else AjiriwaColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
                }
            }
            Text("Like the TV app: Home always opens Ajiriwa, SMS only go through Ajiriwa, and staff open only the apps you allow.",
                color = AjiriwaColors.TextMuted, fontSize = 12.sp)
            SetupRow(home, "Ajiriwa is the home screen", "Set") { open(PhoneLock.askHomeRole(context)) }
            SetupRow(sms, "Ajiriwa is the SMS app", "Set") { open(PhoneLock.askSmsRole(context)) }
            SetupRow(owner, "Full control (device owner)")
            if (!owner) {
                Text("For full control (Home and SMS cannot be changed, no uninstall, other apps blocked), connect the phone to a " +
                    "computer once and run the line below. Remove all accounts (Google, WhatsApp backup accounts) from the phone first.",
                    color = AjiriwaColors.TextSecondary, fontSize = 12.sp)
                Surface(color = AjiriwaColors.Canvas, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().clickable {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", PhoneLock.ADB_COMMAND)); msg = "Copied"
                }) {
                    Text(PhoneLock.ADB_COMMAND, color = AjiriwaColors.Primary, fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(10.dp))
                }
            } else {
                if (!locked) Button(onClick = {
                    val p = PhoneLock.lock(context); msg = if (p.isEmpty()) "Phone locked to Ajiriwa" else p.joinToString("\n"); refresh()
                }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Lock this phone to Ajiriwa") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Only allowed apps open", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Tap the star on an app below to allow it. Others are greyed out.", color = AjiriwaColors.TextMuted, fontSize = 12.sp)
                    }
                    Switch(checked = appsLocked, onCheckedChange = { on ->
                        val p = PhoneLock.applyAppLock(context, on); msg = p.takeIf { it.isNotEmpty() }?.joinToString("\n"); refresh()
                    })
                }
                if (locked || appsLocked) OutlinedButton(onClick = { confirmRelease = true }, modifier = Modifier.fillMaxWidth()) { Text("Unlock the phone") }
            }
            msg?.let { Text(it, color = AjiriwaColors.Warning, fontSize = 12.sp) }
        }
    }
    if (confirmRelease) AlertDialog(
        onDismissRequest = { confirmRelease = false },
        title = { Text("Unlock this phone?") },
        text = { Text("Other home screens, SMS apps and every app will work again until you lock it again.") },
        confirmButton = { TextButton(onClick = { PhoneLock.release(context); confirmRelease = false; msg = "Unlocked"; refresh() }) { Text("Unlock") } },
        dismissButton = { TextButton(onClick = { confirmRelease = false }) { Text("Cancel") } },
    )
}

/**
 * The phone's apps, opened through Ajiriwa so every launch is logged (works as the Home screen). The owner sees the
 * business-phone set-up and stars the apps staff may use; an attendant sees only the starred apps.
 */
@Composable
fun AppsScreen(owner: Boolean = true) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<PhoneApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var allowed by remember { mutableStateOf(PhoneLock.allowed(context)) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
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
        if (owner) PhoneSetupCard(onChanged = { allowed = PhoneLock.allowed(context); reload++ })
        else Text("Apps you can use", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search apps") }, modifier = Modifier.fillMaxWidth())
        val shown = apps.filter { (owner || it.pkg in allowed) && (query.isBlank() || it.label.contains(query, true)) }
            .sortedByDescending { it.pkg in allowed }
        if (!owner && shown.isEmpty()) Text("No apps allowed on this phone yet. Ask the owner.", color = AjiriwaColors.TextMuted, fontSize = 13.sp)
        LazyVerticalGrid(columns = GridCells.Adaptive(84.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(shown, key = { it.pkg + it.activity }) { app ->
                val ok = app.pkg in allowed
                Box {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            ActivityLog.log("app_launch", detail = JSONObject().put("package", app.pkg).put("label", app.label), context = context)
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                                    .setClassName(app.pkg, app.activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }.padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val dim = if (owner && !ok && PhoneLock.appsLocked(context)) 0.35f else 1f
                        if (app.icon != null) Image(app.icon, app.label, Modifier.size(48.dp), alpha = dim)
                        else Surface(shape = RoundedCornerShape(12.dp), color = AjiriwaColors.SurfaceHigh, modifier = Modifier.size(48.dp)) {}
                        Text(app.label, color = AjiriwaColors.TextSecondary, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                    }
                    if (owner) Text(if (ok) "★" else "☆", color = if (ok) AjiriwaColors.Warning else AjiriwaColors.TextMuted, fontSize = 18.sp,
                        modifier = Modifier.align(Alignment.TopEnd).clickable {
                            allowed = if (ok) allowed - app.pkg else allowed + app.pkg
                            PhoneLock.setAllowed(context, allowed)
                        }.padding(4.dp))
                }
            }
        }
    }
}
