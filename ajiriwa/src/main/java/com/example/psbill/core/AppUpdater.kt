package com.example.psbill.core

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
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
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
 * In-app updates: the heartbeat says a newer build exists → download it (automatically
 * when the business allows), check its SHA-256, install with PackageInstaller.
 * A mandatory update blocks the app (see UpdateRequiredScreen) until it is installed —
 * the requirement is kept in prefs so it holds offline too.
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val PREF = "app_update_hint"
    private val io = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state

    private fun file(context: Context, code: Long) = File(File(context.cacheDir, "updates").apply { mkdirs() }, "update-$code.apk")

    /** Load the remembered offer (call at app start). Drops it once this build is installed. */
    fun restore(context: Context) {
        val hint = DeviceIdentity.prefs(context).getString(PREF, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (hint == null || hint.optLong("version_code") <= DeviceAgent.installedVersionCode(context)) {
            DeviceIdentity.prefs(context).edit().remove(PREF).apply()
            File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
            _state.value = UpdateState()
            return
        }
        val f = file(context, hint.optLong("version_code"))
        _state.value = _state.value.copy(hint = hint, ready = f.exists() && verify(f, hint.optString("sha256")))
    }

    /** From the heartbeat. Null = nothing newer (clears a stale offer). */
    fun onHint(context: Context, hint: JSONObject?) {
        val p = DeviceIdentity.prefs(context)
        if (hint == null || hint.optLong("version_code") <= DeviceAgent.installedVersionCode(context)) {
            if (_state.value.hint != null) { p.edit().remove(PREF).apply(); _state.value = UpdateState() }
            return
        }
        p.edit().putString(PREF, hint.toString()).apply()
        val f = file(context, hint.optLong("version_code"))
        val ready = f.exists() && verify(f, hint.optString("sha256"))
        _state.value = _state.value.copy(hint = hint, ready = ready)
        if (!ready && (hint.optBoolean("auto_download", true) || hint.optBoolean("mandatory"))) download(context)
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

    /** Download the offered APK in the background (resumes from scratch; verified before use). */
    fun download(context: Context) {
        val hint = _state.value.hint ?: return
        if (!busy.compareAndSet(false, true)) return
        val app = context.applicationContext
        _state.value = _state.value.copy(downloading = true, progress = 0, error = null)
        io.execute {
            try {
                val code = hint.optLong("version_code")
                val out = file(app, code)
                val tmp = File(out.path + ".part")
                val req = Request.Builder().url(hint.optString("url")).build()
                PhoneApi.http.newCall(req).execute().use { r ->
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
                if (!verify(tmp, hint.optString("sha256"))) { tmp.delete(); throw IllegalStateException("The download was damaged, try again") }
                tmp.renameTo(out)
                _state.value = _state.value.copy(downloading = false, ready = true, progress = 100)
                ActivityLog.log("app_update_downloaded", detail = JSONObject().put("version", hint.optString("version_name")), context = app)
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

    /** Settings screen where the user allows this app to install updates. */
    fun allowInstallsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        else Intent(Settings.ACTION_SECURITY_SETTINGS)

    /** Hand the verified APK to the system installer (it asks the user to confirm). */
    fun install(context: Context) {
        val hint = _state.value.hint ?: return
        val f = file(context, hint.optLong("version_code"))
        if (!f.exists()) { download(context); return }
        val app = context.applicationContext
        _state.value = _state.value.copy(installing = true, error = null)
        io.execute {
            try {
                val pi = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(app.packageName) }
                val id = pi.createSession(params)
                pi.openSession(id).use { s ->
                    s.openWrite("update.apk", 0, f.length()).use { o -> f.inputStream().use { it.copyTo(o) }; s.fsync(o) }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val cb = PendingIntent.getBroadcast(app, id, Intent(app, InstallResultReceiver::class.java), flags)
                    s.commit(cb.intentSender)
                }
                ActivityLog.log("app_update_install", detail = JSONObject().put("version", hint.optString("version_name")), context = app)
            } catch (e: Exception) {
                Log.w(TAG, "install: ${e.message}")
                _state.value = _state.value.copy(installing = false, error = e.message)
            }
        }
    }

    internal fun onInstallStatus(status: Int, message: String?) {
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
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
