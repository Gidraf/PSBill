package com.example.psbill

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/*
 * The staff phone's hotspot as a backup WiFi for the TV.
 *
 * The owner sets the hotspot name + password in the PSBill app; the TV gets them in its sync
 * and saves the network. When the lounge WiFi is gone (router off) or keeps failing to reach
 * the internet, Android's own network selection moves the TV to another SAVED network with
 * internet: the phone's hotspot. Sessions keep running on the TV either way; the hotspot only
 * keeps the dashboard and the staff app in touch with it.
 *
 * How the network gets saved depends on the TV:
 *  - device owner, or Android 9 and older: silently (WifiManager.addNetwork);
 *  - Android 11+: the system "Save this network?" page (ACTION_WIFI_ADD_NETWORKS), opened
 *    from the lock screen while the TV is idle; staff press OK once per TV;
 *  - Android 10, or a TV whose settings lack that page: a network suggestion, and staff can
 *    also add the hotspot by hand in the TV's WiFi settings.
 * The state goes to the server in the sync (backup_wifi_state) so staff see which TVs have it.
 */
object BackupWifi {
    private const val TAG = "BackupWifi"
    private const val PREFS = "KioskPrefs"
    private const val K_SSID = "backup_wifi_ssid"
    private const val K_PASS = "backup_wifi_pass"
    private const val K_STATE = "backup_wifi_state"
    private const val K_ASKED = "backup_wifi_asked_at"
    private const val ASK_AGAIN_MS = 24 * 3600_000L

    const val NONE = "none"            // no hotspot set by the owner
    const val SAVED = "saved"          // saved on the TV
    const val ASK = "ask"              // waiting for staff to press OK on the TV
    const val SUGGESTED = "suggested"  // only a suggestion (Android 10 / no system page)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun state(ctx: Context): String = prefs(ctx).getString(K_STATE, NONE) ?: NONE

    /** From the sync: {"backup_wifi": {"ssid", "password"}} (absent = none set). */
    fun onSync(ctx: Context, data: JSONObject) {
        val bw = data.optJSONObject("backup_wifi")
        val ssid = bw?.optString("ssid").orEmpty().trim()
        val pass = bw?.optString("password").orEmpty()
        val p = prefs(ctx)
        if (ssid == p.getString(K_SSID, "") && pass == p.getString(K_PASS, "")) return
        p.edit().putString(K_SSID, ssid).putString(K_PASS, pass).remove(K_ASKED).apply()
        if (ssid.isEmpty()) {
            p.edit().putString(K_STATE, NONE).apply()
            return
        }
        p.edit().putString(K_STATE, installQuietly(ctx, ssid, pass)).apply()
    }

    private fun isDeviceOwner(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isDeviceOwnerApp(ctx.packageName)
    } catch (_: Exception) {
        false
    }

    @Suppress("DEPRECATION")
    private fun installQuietly(ctx: Context, ssid: String, pass: String): String {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return SUGGESTED
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || isDeviceOwner(ctx)) {
            try {
                val conf = WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    if (pass.isEmpty()) allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    else preSharedKey = "\"$pass\""
                }
                val id = wm.addNetwork(conf)
                if (id != -1) {
                    wm.enableNetwork(id, false)  // saved and allowed, without leaving the current WiFi
                    return SAVED
                }
            } catch (e: Exception) {
                Log.w(TAG, "addNetwork failed: ${e.message}")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && addIntent(ctx, ssid, pass) != null) return ASK
        suggest(ctx, wm, ssid, pass)
        return SUGGESTED
    }

    private fun suggest(ctx: Context, wm: WifiManager, ssid: String, pass: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val s = WifiNetworkSuggestion.Builder().setSsid(ssid).apply {
                if (pass.isNotEmpty()) setWpa2Passphrase(pass)
            }.build()
            wm.removeNetworkSuggestions(emptyList())  // drop an older hotspot
            wm.addNetworkSuggestions(listOf(s))
        } catch (e: Exception) {
            Log.w(TAG, "network suggestion failed: ${e.message}")
        }
    }

    /** The system page that asks to save the hotspot (Android 11+), or null when the TV has none. */
    private fun addIntent(ctx: Context, ssid: String, pass: String): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val s = WifiNetworkSuggestion.Builder().setSsid(ssid).apply {
            if (pass.isNotEmpty()) setWpa2Passphrase(pass)
        }.build()
        val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
            .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(s))
        return if (intent.resolveActivity(ctx.packageManager) != null) intent else null
    }

    /** The lock screen calls this while the TV is idle: the "save network" page to open, if due. */
    fun pendingAsk(ctx: Context): Intent? {
        val p = prefs(ctx)
        if (state(ctx) != ASK) return null
        if (System.currentTimeMillis() - p.getLong(K_ASKED, 0L) < ASK_AGAIN_MS) return null
        val ssid = p.getString(K_SSID, "").orEmpty()
        if (ssid.isEmpty()) return null
        return addIntent(ctx, ssid, p.getString(K_PASS, "").orEmpty())
    }

    /** While the system page is open the lock screen must not pull itself back on top. */
    @Volatile private var askOpenUntil = 0L
    val askOpen: Boolean get() = System.currentTimeMillis() < askOpenUntil

    fun markAsked(ctx: Context) {
        prefs(ctx).edit().putLong(K_ASKED, System.currentTimeMillis()).apply()
        askOpenUntil = System.currentTimeMillis() + 3 * 60_000L  // safety: never longer than 3 min
    }

    /** Result of the system page: saved, or still waiting (asked again tomorrow). */
    fun onAskResult(ctx: Context, resultCode: Int, data: Intent?) {
        val codes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            data?.getIntegerArrayListExtra(Settings.EXTRA_WIFI_NETWORK_RESULT_LIST) else null
        val ok = resultCode == android.app.Activity.RESULT_OK && (codes == null || codes.all {
            it == Settings.ADD_WIFI_RESULT_SUCCESS || it == Settings.ADD_WIFI_RESULT_ALREADY_EXISTS
        })
        askOpenUntil = 0L
        if (ok) prefs(ctx).edit().putString(K_STATE, SAVED).apply()
        Log.d(TAG, "save-network page result=$resultCode codes=$codes saved=$ok")
    }
}
