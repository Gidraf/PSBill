package com.example.psbill

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import org.json.JSONObject
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.Collections

/*
 * The TV's fingerprint, worked out ONCE and then kept.
 *
 * It used to be recomputed on every call from ANDROID_ID + the wlan0 MAC + the model.
 * Right after a reboot wlan0 is not there yet, so the MAC came out as 02:00:00:00:00:00
 * and the fingerprint changed: the saved login was refused ("fingerprint mismatch")
 * and the TV showed the activation page although it was still linked.
 *
 * Order: memory -> the fingerprint inside the saved login (what the server checks)
 * -> the one saved in prefs -> compute it now and save it.
 */
object DeviceIdentity {
    private const val PREFS = "KioskPrefs"
    private const val KEY = "device_fingerprint"

    @Volatile
    private var cached: String? = null

    fun fingerprint(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val fp = fromSavedToken(app)
                ?: prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }
                ?: compute(app)
            if (prefs.getString(KEY, null) != fp) prefs.edit().putString(KEY, fp).apply()
            cached = fp
            return fp
        }
    }

    /** The fingerprint claim of the saved device login, if there is one. */
    private fun fromSavedToken(context: Context): String? = try {
        val masterKey = androidx.security.crypto.MasterKeys.getOrCreate(
            androidx.security.crypto.MasterKeys.AES256_GCM_SPEC
        )
        val secure = androidx.security.crypto.EncryptedSharedPreferences.create(
            "SecureKioskPrefs",
            masterKey,
            context,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val token = secure.getString("auth_token", "") ?: ""
        val parts = token.split(".")
        if (parts.size < 2) null else {
            val payload = String(
                Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            )
            JSONObject(payload).optString("fingerprint", "").takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        null
    }

    private fun compute(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "UNKNOWN_ID"
        val model = Build.MODEL ?: "UNKNOWN_MODEL"
        val bytes = MessageDigest.getInstance("SHA-256").digest((androidId + wlanMac() + model).toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun wlanMac(): String {
        try {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.name.equals("wlan0", ignoreCase = true)) continue
                val mac = nif.hardwareAddress ?: return "02:00:00:00:00:00"
                return mac.joinToString(":") { "%02X".format(it) }
            }
        } catch (_: Exception) {
        }
        return "02:00:00:00:00:00"
    }
}
