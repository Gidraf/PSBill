package com.example.psbill

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.example.psbill.ui.theme.PSBillTheme
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : ComponentActivity() {

    companion object {
        var isMainActivityVisible = false
    }

    private val client = OkHttpClient()
    private val allowedApps = mutableStateListOf<JSONObject>()
    private var fetchAppsHandler: Handler? = null
    private var fetchAppsRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val token = getAuthToken()

        if (token.isEmpty()) {
            startActivity(Intent(this, DeviceAuthActivity::class.java))
            finish()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            // Need overlay permission before kiosk service can run
            showSetupScreen()
            return
        }

        if (!isDefaultLauncher()) {
            showLauncherSetupScreen()
            return
        }

        // Start KioskService if not running
        val serviceIntent = Intent(this, KioskService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // If device is locked (no active session), go straight to LockActivity
        val status = KioskService.activeDeviceStatus
        if (status != "ACTIVE" && status != "OVERTIME") {
            startActivity(Intent(this, LockActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            finish()
            return
        }

        // Active session — show a blocking "Session in Progress" screen
        // (KioskService timer overlay will be on top of this anyway)
        showSessionActiveBlocker()
    }

    override fun onResume() {
        super.onResume()
        isMainActivityVisible = true

        // Re-check permissions/launcher status on every resume (e.g. returning from Settings)
        if (!Settings.canDrawOverlays(this)) { showSetupScreen(); return }
        if (!isDefaultLauncher()) { showLauncherSetupScreen(); return }

        // If state changed to locked while we were in background, go to LockActivity
        val status = KioskService.activeDeviceStatus
        if (status != "ACTIVE" && status != "OVERTIME") {
            startActivity(Intent(this, LockActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            finish()
        } else {
            startPeriodicAppsFetch()
        }
    }

    override fun onPause() {
        super.onPause()
        isMainActivityVisible = false
        stopPeriodicAppsFetch()
    }

    override fun onDestroy() {
        super.onDestroy()
        isMainActivityVisible = false
        stopPeriodicAppsFetch()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // During active session, block only keys that escape the app
        if (KioskService.activeDeviceStatus == "ACTIVE" || KioskService.activeDeviceStatus == "OVERTIME") {
            val code = event.keyCode
            val blocked = when (code) {
                KeyEvent.KEYCODE_HOME,
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_APP_SWITCH,
                KeyEvent.KEYCODE_SEARCH,
                KeyEvent.KEYCODE_ASSIST,
                KeyEvent.KEYCODE_TV,
                KeyEvent.KEYCODE_GUIDE,
                KeyEvent.KEYCODE_DVR,
                KeyEvent.KEYCODE_SETTINGS -> true
                else -> false
            }
            if (blocked) return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ─── Screen Builders ──────────────────────────────────────────────────────

    private fun isDefaultLauncher(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        val info = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return info?.activityInfo?.packageName == packageName
    }

    private fun showLauncherSetupScreen() {
        setContent { PSBillTheme { LauncherSetupScreen() } }
    }

    private fun showSessionActiveBlocker() {
        setContent {
            PSBillTheme {
                SessionActiveBlocker()
            }
        }
    }

    private fun showSetupScreen() {
        setContent {
            PSBillTheme {
                SetupScreen()
            }
        }
    }

    // ─── Composables ──────────────────────────────────────────────────────────

    @Composable
    private fun SessionActiveBlocker() {
        // Displayed when HOME is pressed during an active session.
        // The KioskService timer overlay will render on top.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF000000)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "SESSION IN PROGRESS",
                    color = Color(0xFF00E676),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 3.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Please wait for your session to end.",
                    color = Color(0xFF64748B),
                    fontSize = 14.sp
                )
            }
        }
    }

    @Composable
    private fun SetupScreen() {
        var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(this)) }
        val context = this

        LaunchedEffect(Unit) {
            while (!overlayGranted) {
                kotlinx.coroutines.delay(1000)
                overlayGranted = Settings.canDrawOverlays(context)
                if (overlayGranted) {
                    val svcIntent = Intent(context, KioskService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        context.startForegroundService(svcIntent)
                    else context.startService(svcIntent)
                    startActivity(Intent(context, LockActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                    finish()
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xFF0B0F19)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.width(480.dp)
            ) {
                Text("PlayGate Setup", color = Color(0xFF00E676), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Text("Overlay permission is required for the kiosk timer to display.", color = Color(0xFF94A3B8), fontSize = 13.sp, textAlign = TextAlign.Center)

                Button(
                    onClick = {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("Grant Overlay Permission", color = Color.White, fontWeight = FontWeight.Bold)
                }

                Text(
                    text = "MAC: ${getMacAddress()}",
                    color = Color(0xFF475569),
                    fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        }
    }

    @Composable
    private fun LauncherSetupScreen() {
        val context = this

        LaunchedEffect(Unit) {
            // Poll every second; once we become default, continue normal boot
            while (true) {
                kotlinx.coroutines.delay(1000)
                if (isDefaultLauncher()) {
                    // Re-run full onCreate logic
                    val svcIntent = Intent(context, KioskService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        context.startForegroundService(svcIntent)
                    else context.startService(svcIntent)
                    val status = KioskService.activeDeviceStatus
                    if (status == "ACTIVE" || status == "OVERTIME") {
                        showSessionActiveBlocker()
                    } else {
                        startActivity(Intent(context, LockActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                        finish()
                    }
                    break
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xFF0B0F19)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.width(520.dp)
            ) {
                Text(
                    "One More Step",
                    color = Color(0xFF00E676),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    "PlayGate needs to be set as the default Home app so it can lock the TV during sessions and block the HOME button.",
                    color = Color(0xFF94A3B8),
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF161E2F), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("How to set PlayGate as your launcher:", color = Color(0xFFF1F5F9), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("1. Tap the button below to open Home app settings", color = Color(0xFF94A3B8), fontSize = 13.sp)
                    Text("2. Select \"PlayGate\" from the list", color = Color(0xFF94A3B8), fontSize = 13.sp)
                    Text("3. Press HOME — this screen will disappear automatically", color = Color(0xFF94A3B8), fontSize = 13.sp)
                }

                Button(
                    onClick = {
                        try {
                            startActivity(Intent(Settings.ACTION_HOME_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        } catch (_: Exception) {
                            startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Home App Settings", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                }

                Text(
                    "MAC: ${getMacAddress()}",
                    color = Color(0xFF334155),
                    fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        }
    }

    // ─── Allowed Apps ─────────────────────────────────────────────────────────

    private fun startPeriodicAppsFetch() {
        stopPeriodicAppsFetch()
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                fetchAllowedApps()
                handler.postDelayed(this, 30_000)
            }
        }
        fetchAppsHandler = handler
        fetchAppsRunnable = runnable
        handler.post(runnable)
    }

    private fun stopPeriodicAppsFetch() {
        fetchAppsRunnable?.let { fetchAppsHandler?.removeCallbacks(it) }
        fetchAppsHandler = null
        fetchAppsRunnable = null
    }

    internal fun fetchAllowedApps() {
        val prefs = getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
        val server = prefs.getString("server_domain", "api.ajiriwa.gidraf.dev") ?: "api.ajiriwa.gidraf.dev"
        val token = getAuthToken()
        if (token.isEmpty()) return

        val request = Request.Builder()
            .url("https://$server/api/v1/kiosk/allowed-apps")
            .header("Authorization", "Bearer $token")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w("MainActivity", "Failed to fetch allowed apps: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return
                    val body = it.body?.string() ?: return
                    try {
                        val arr = JSONArray(body)
                        runOnUiThread {
                            allowedApps.clear()
                            for (i in 0 until arr.length()) allowedApps.add(arr.getJSONObject(i))
                        }
                    } catch (_: Exception) {}
                }
            }
        })
    }

    // ─── Composable: Allowed Apps Grid (used by LockActivity admin panel) ────

    @Composable
    fun AllowedAppsGrid(apps: List<JSONObject>) {
        if (apps.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No apps configured. Add apps from the web dashboard.",
                    color = Color(0xFF64748B),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
            return
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.heightIn(max = 280.dp)
        ) {
            items(apps) { app ->
                AppTile(app)
            }
        }
    }

    @Composable
    private fun AppTile(app: JSONObject) {
        val packageName = app.optString("package_name", "")
        val appName = app.optString("app_name", packageName)
        var isFocused by remember { mutableStateOf(false) }

        val isInstalled = remember(packageName) {
            try {
                packageManager.getPackageInfo(packageName, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) { false }
        }

        val icon: Bitmap? = remember(packageName) {
            if (!isInstalled) return@remember null
            try {
                val drawable = packageManager.getApplicationIcon(packageName)
                if (drawable is BitmapDrawable) {
                    drawable.bitmap
                } else {
                    val bmp = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bmp
                }
            } catch (_: Exception) { null }
        }

        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isFocused) Color(0xFF1E293B) else Color(0xFF0F172A)
                )
                .border(
                    1.dp,
                    if (isFocused) Color(0xFF00E676) else Color(0xFF1E293B),
                    RoundedCornerShape(12.dp)
                )
                .clickable(enabled = isInstalled) {
                    launchAllowedApp(packageName)
                }
                .onFocusChanged { isFocused = it.isFocused }
                .focusable()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon.asImageBitmap(),
                    contentDescription = appName,
                    modifier = Modifier.size(52.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(Color(0xFF1E293B), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("?", color = Color(0xFF64748B), fontSize = 22.sp)
                }
            }
            Text(
                text = appName,
                color = if (isInstalled) Color.White else Color(0xFF64748B),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
            if (!isInstalled) {
                Text("Not installed", color = Color(0xFFEF4444), fontSize = 9.sp)
            }
        }
    }

    private fun launchAllowedApp(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
                ?: packageManager.getLeanbackLaunchIntentForPackage(packageName)
            if (intent != null) {
                startActivity(intent)
            } else {
                Toast.makeText(this, "Cannot launch $packageName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Launch failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun getAuthToken(): String {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            val securePrefs = EncryptedSharedPreferences.create(
                "SecureKioskPrefs", masterKeyAlias, this,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            securePrefs.getString("auth_token", "") ?: ""
        } catch (_: Exception) { "" }
    }

    private fun getMacAddress(): String {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .firstOrNull { it.name.equals("wlan0", ignoreCase = true) }
                ?.hardwareAddress
                ?.joinToString(":") { "%02X".format(it) }
                ?: "02:00:00:00:00:00"
        } catch (_: Exception) { "02:00:00:00:00:00" }
    }
}
