package com.example.psbill

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/*
 * Remote pause / removal of PSBill, set by the owner from the dashboard or the staff app
 * (server: app/services/screen_remote.py). Every sync reply carries
 *   "remote": {"paused": bool, "remove": bool, "message": "..."}
 *
 * Paused: PSBill lets go of the TV. No lock task, no lock screen, no watchdog, no console
 * cover; HOME opens PausedActivity, which offers TV settings, the apps list and the home
 * screen settings. KioskService keeps syncing, so "resume" from the dashboard brings the
 * lock screen back.
 * Remove: as paused, then PSBill clears its device-owner rights, tells the server
 * ("removing", which unlinks the screen and stops billing it) and opens the uninstall prompt.
 * Both are kept in plain prefs so they survive a reboot and an unlinked (tokenless) app.
 */
object RemoteControl {
    private const val TAG = "RemoteControl"
    private const val PREFS = "KioskPrefs"
    private const val KEY_PAUSED = "remote_paused"
    private const val KEY_REMOVE = "remote_remove"
    private const val KEY_MESSAGE = "remote_message"
    private const val KEY_ACKED = "remote_acked_state"

    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    fun isPaused(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PAUSED, false)

    fun isRemoving(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_REMOVE, false)

    fun message(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MESSAGE, "") ?: ""

    /**
     * Apply the server's remote state from a sync reply. Returns true while PSBill must stay out
     * of the way (paused or removing): the caller then skips its lock/unlock handling.
     */
    fun onSync(ctx: Context, data: JSONObject, deviceId: String, baseUrl: String, token: String,
               fingerprint: String): Boolean {
        val remote = data.optJSONObject("remote") ?: return isPaused(ctx)
        val paused = remote.optBoolean("paused", false)
        val remove = remote.optBoolean("remove", false)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wasPaused = prefs.getBoolean(KEY_PAUSED, false)
        val wasRemoving = prefs.getBoolean(KEY_REMOVE, false)
        prefs.edit().putBoolean(KEY_PAUSED, paused || remove).putBoolean(KEY_REMOVE, remove)
            .putString(KEY_MESSAGE, remote.optString("message", "")).apply()
        val state = when {
            remove -> "removing"
            paused -> "paused"
            else -> "running"
        }
        when {
            remove && !wasRemoving -> {
                letGo(ctx)
                startRemoval(ctx)
            }
            (paused || remove) && !wasPaused -> letGo(ctx)
            !paused && !remove && wasPaused -> comeBack(ctx)
        }
        if (prefs.getString(KEY_ACKED, "") != state && deviceId.isNotEmpty() && token.isNotEmpty()) {
            ack(ctx, baseUrl, deviceId, token, fingerprint, state)
        }
        return paused || remove
    }

    /** Close the lock screen (it stops lock task on ACTION_UNLOCK) and show the paused page. */
    private fun letGo(ctx: Context) {
        Log.i(TAG, "PSBill paused by the owner: releasing the screen")
        ctx.sendBroadcast(Intent("com.example.psbill.ACTION_UNLOCK").setPackage(ctx.packageName))
        if (ConsoleOffActivity.isOpen()) ConsoleOffActivity.close()
        openPausedPage(ctx)
    }

    private fun comeBack(ctx: Context) {
        Log.i(TAG, "PSBill resumed: back to the lock screen")
        PausedActivity.closeIfOpen()
        try {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.e(TAG, "relaunch failed: ${e.message}")
        }
    }

    fun openPausedPage(ctx: Context) {
        try {
            ctx.startActivity(Intent(ctx, PausedActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (e: Exception) {
            Log.e(TAG, "paused page failed: ${e.message}")
        }
    }

    /** Give up device-owner rights (a device owner cannot be uninstalled), then ask to uninstall. */
    fun startRemoval(ctx: Context) {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(ctx, AdminReceiver::class.java)
        try {
            if (dpm.isDeviceOwnerApp(ctx.packageName)) {
                try { dpm.setLockTaskPackages(admin, arrayOf()) } catch (_: Exception) {}
                @Suppress("DEPRECATION")
                dpm.clearDeviceOwnerApp(ctx.packageName)
            }
            if (dpm.isAdminActive(admin)) dpm.removeActiveAdmin(admin)
        } catch (e: Exception) {
            Log.e(TAG, "could not clear device owner: ${e.message}")
        }
        openUninstall(ctx)
    }

    fun openUninstall(ctx: Context) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.e(TAG, "uninstall prompt failed: ${e.message}")
            openPausedPage(ctx)
        }
    }

    private fun ack(ctx: Context, baseUrl: String, deviceId: String, token: String, fingerprint: String,
                    state: String) {
        Thread {
            try {
                val body = JSONObject().put("state", state).toString()
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url("$baseUrl/api/v1/kiosk/devices/$deviceId/remote-ack")
                    .header("Authorization", "Bearer $token").header("X-Device-Fingerprint", fingerprint)
                    .post(body).build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putString(KEY_ACKED, state).apply()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "remote ack failed: ${e.message}")
            }
        }.start()
    }
}
