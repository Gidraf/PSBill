package com.example.psbill

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class UpdateState(
    /** Newer build offered by the server: {version_code, version_name, url, notes, sha256, mandatory, auto_download}. */
    val hint: JSONObject? = null,
    val downloading: Boolean = false,
    val progress: Int = 0,
    val ready: Boolean = false,
    val installing: Boolean = false,
    val error: String? = null,
) {
    val mandatory get() = hint?.optBoolean("mandatory") == true
    val versionName get() = hint?.optString("version_name").orEmpty()
}

/**
 * Self-update for the TV (same server API as the Ajiriwa app):
 * `GET /api/v1/mobile/app-update?app=tv&version_code=N` -> newer build? download it, check its
 * SHA-256, install with PackageInstaller. A mandatory build blocks the lock screen (no new
 * sessions) until it is installed; the requirement is kept in prefs so it holds offline.
 * A running session is never interrupted: the block shows on the next lock.
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val PREF = "app_update_hint"
    private const val APP = "tv"
    private val io = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val checking = AtomicBoolean(false)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state

    /** True while the system install screen is up: the lock watchdog must not cover it. */
    @Volatile var installInProgress = false
        private set

    private fun prefs(c: Context) = c.getSharedPreferences("KioskPrefs", Context.MODE_PRIVATE)
    private fun server(c: Context) =
        (prefs(c).getString("server_domain", "api.ajiriwa.gidraf.dev") ?: "api.ajiriwa.gidraf.dev")
            .removePrefix("https://").removePrefix("http://").trimEnd('/')

    private fun token(c: Context): String = runCatching {
        val key = androidx.security.crypto.MasterKeys.getOrCreate(androidx.security.crypto.MasterKeys.AES256_GCM_SPEC)
        androidx.security.crypto.EncryptedSharedPreferences.create(
            "SecureKioskPrefs", key, c,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).getString("auth_token", "") ?: ""
    }.getOrDefault("")

    fun installedVersionCode(c: Context): Long = runCatching {
        val info = c.packageManager.getPackageInfo(c.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)

    private fun file(c: Context, code: Long) =
        File(File(c.cacheDir, "updates").apply { mkdirs() }, "update-$code.apk")

    /** Load the remembered offer (call at start). Drops it once this build is installed. */
    fun restore(context: Context) {
        val c = context.applicationContext
        val hint = prefs(c).getString(PREF, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (hint == null || hint.optLong("version_code") <= installedVersionCode(c)) {
            prefs(c).edit().remove(PREF).apply()
            File(c.cacheDir, "updates").listFiles()?.forEach { it.delete() }
            _state.value = UpdateState()
            return
        }
        val f = file(c, hint.optLong("version_code"))
        _state.value = _state.value.copy(hint = hint, ready = f.exists() && verify(f, hint.optString("sha256")))
    }

    /** Ask the server now (on start, every 30 min from KioskService, and when the lock screen opens). */
    fun checkNow(context: Context) {
        if (!checking.compareAndSet(false, true)) return
        val c = context.applicationContext
        io.execute {
            try {
                val url = "https://${server(c)}/api/v1/mobile/app-update?app=$APP&version_code=${installedVersionCode(c)}"
                val tok = token(c)
                val b = Request.Builder().url(url)
                if (tok.isNotBlank()) b.header("Authorization", "Bearer $tok")
                http.newCall(b.build()).execute().use { r ->
                    if (r.code == 401 || r.code == 422) {
                        // expired/unknown token: ask again without it (default build for the TV)
                        http.newCall(Request.Builder().url(url).build()).execute().use { r2 -> handle(c, r2, signedIn = false) }
                    } else handle(c, r, signedIn = tok.isNotBlank())
                }
            } catch (e: Exception) {
                Log.w(TAG, "checkNow: ${e.message}")
            } finally {
                checking.set(false)
            }
        }
    }

    private fun handle(c: Context, r: okhttp3.Response, signedIn: Boolean) {
        if (!r.isSuccessful) return
        val json = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull() ?: return
        val hint = json.optJSONObject("app_update")
        if (hint != null || signedIn) onHint(c, hint)
    }

    fun onHint(context: Context, hint: JSONObject?) {
        val c = context.applicationContext
        if (hint == null || hint.optLong("version_code") <= installedVersionCode(c)) {
            if (_state.value.hint != null) { prefs(c).edit().remove(PREF).apply(); _state.value = UpdateState() }
            return
        }
        prefs(c).edit().putString(PREF, hint.toString()).apply()
        val f = file(c, hint.optLong("version_code"))
        val ready = f.exists() && verify(f, hint.optString("sha256"))
        _state.value = _state.value.copy(hint = hint, ready = ready)
        // A TV has no one to press "download": always fetch it in the background.
        if (!ready) download(c)
    }

    private fun verify(f: File, sha: String?): Boolean {
        if (sha.isNullOrBlank()) return f.length() > 0
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = s.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }.equals(sha, ignoreCase = true)
    }

    fun download(context: Context) {
        val hint = _state.value.hint ?: return
        if (!busy.compareAndSet(false, true)) return
        val c = context.applicationContext
        _state.value = _state.value.copy(downloading = true, progress = 0, error = null)
        io.execute {
            try {
                val out = file(c, hint.optLong("version_code"))
                val tmp = File(out.path + ".part")
                http.newCall(Request.Builder().url(hint.optString("url")).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("Download failed (HTTP ${r.code})")
                    val body = r.body ?: throw IllegalStateException("Empty download")
                    val total = body.contentLength().takeIf { it > 0 } ?: hint.optLong("size_bytes", -1)
                    body.byteStream().use { input ->
                        tmp.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf); if (n <= 0) break
                                o.write(buf, 0, n); done += n
                                if (total > 0) _state.value = _state.value.copy(progress = ((done * 100) / total).toInt().coerceIn(0, 100))
                            }
                        }
                    }
                }
                if (!verify(tmp, hint.optString("sha256"))) { tmp.delete(); throw IllegalStateException("The download was damaged, retrying later") }
                tmp.renameTo(out)
                _state.value = _state.value.copy(downloading = false, ready = true, progress = 100)
            } catch (e: Exception) {
                Log.w(TAG, "download: ${e.message}")
                _state.value = _state.value.copy(downloading = false, error = e.message)
            } finally {
                busy.set(false)
            }
        }
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun allowInstallsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        else Intent(Settings.ACTION_SECURITY_SETTINGS)

    /** Hand the verified APK to the system installer (on a TV: one OK on the remote). */
    fun install(context: Context) {
        val hint = _state.value.hint ?: return
        val c = context.applicationContext
        val f = file(c, hint.optLong("version_code"))
        if (!f.exists()) { download(c); return }
        if (installInProgress) return
        installInProgress = true
        _state.value = _state.value.copy(installing = true, error = null)
        io.execute {
            try {
                val pi = c.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(c.packageName) }
                val id = pi.createSession(params)
                pi.openSession(id).use { s ->
                    s.openWrite("update.apk", 0, f.length()).use { o -> f.inputStream().use { it.copyTo(o) }; s.fsync(o) }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val cb = PendingIntent.getBroadcast(c, id, Intent(c, InstallResultReceiver::class.java), flags)
                    s.commit(cb.intentSender)
                }
            } catch (e: Exception) {
                Log.w(TAG, "install: ${e.message}")
                installInProgress = false
                _state.value = _state.value.copy(installing = false, error = e.message)
            }
        }
    }

    internal fun onInstallStatus(status: Int, message: String?) {
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            installInProgress = false
            _state.value = _state.value.copy(installing = false,
                error = if (status == PackageInstaller.STATUS_SUCCESS) null else (message ?: "Install was cancelled"))
        }
    }
}

/** Gets the installer's answer: shows the system confirm screen, or reports failure. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
        AppUpdater.onInstallStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
