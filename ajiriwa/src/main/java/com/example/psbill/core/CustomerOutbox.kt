package com.example.psbill.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Customers saved or edited in the app (real name, nickname, house, location) go to the backend
 * through the contact sync (`POST /api/v1/kiosk/gateway/contacts` -> `link_phone`), the same single
 * path as phone contacts. Each entry carries `profile_updated_at`, so the server keeps the newest
 * edit and replays are harmless. Kept in prefs until the server accepts it: works offline.
 */
object CustomerOutbox {
    private const val TAG = "CustomerOutbox"
    private const val PREF = "customer_outbox_v1"
    private val io = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    private fun read(context: Context): JSONArray =
        runCatching { JSONArray(DeviceIdentity.prefs(context).getString(PREF, "[]")) }.getOrElse { JSONArray() }

    private fun write(context: Context, arr: JSONArray) {
        DeviceIdentity.prefs(context).edit().putString(PREF, arr.toString()).apply()
    }

    fun pending(context: Context): Int = read(context).length()

    /**
     * Queue one customer: [phone] (any format), [contactName] (as saved on the phone, may be blank)
     * and the profile fields (first_name, last_name, nickname, house_name, house_number,
     * location_text, location_lat, location_lng). A newer save for the same phone replaces the older.
     */
    @Synchronized
    fun enqueue(context: Context, phone: String, contactName: String, profile: JSONObject, editedAtMs: Long = System.currentTimeMillis()) {
        val entry = JSONObject()
            .put("phone", phone.trim())
            .put("name", contactName.trim())
            .put("profile", profile)
            .put("profile_updated_at", editedAtMs)
        val old = read(context)
        val out = JSONArray()
        val key = digits(phone)
        for (i in 0 until old.length()) {
            val e = old.optJSONObject(i) ?: continue
            if (digits(e.optString("phone")) != key) out.put(e)
        }
        out.put(entry)
        write(context, out)
    }

    /** Send everything queued; [done] runs on the worker thread with (ok, message). */
    fun flushAsync(context: Context, done: ((Boolean, String?) -> Unit)? = null) {
        val app = context.applicationContext
        if (!busy.compareAndSet(false, true)) { done?.invoke(false, "Already sending"); return }
        io.execute {
            try {
                val batch = read(app)
                if (batch.length() == 0) { done?.invoke(true, null); return@execute }
                if (DeviceIdentity.token(app).isBlank()) { done?.invoke(false, "Not signed in"); return@execute }
                val r = PhoneApi.request(app, "/api/v1/kiosk/gateway/contacts", JSONObject().put("contacts", batch))
                removeSent(app, batch)
                val invalid = r.optJSONArray("invalid_profiles")
                val msg = if (invalid != null && invalid.length() > 0)
                    invalid.optJSONObject(0)?.let { "${it.optString("field")}: ${it.optString("error")}" } else null
                done?.invoke(msg == null, msg)
            } catch (e: Exception) {
                Log.w(TAG, "flush: ${e.message}")
                done?.invoke(false, e.message ?: "Network error")
            } finally {
                busy.set(false)
            }
        }
    }

    /** Drop what was sent, keeping anything queued meanwhile (a newer edit of the same phone stays). */
    @Synchronized
    private fun removeSent(context: Context, sent: JSONArray) {
        val sentKeys = HashSet<String>()
        for (i in 0 until sent.length()) sent.optJSONObject(i)?.let { sentKeys.add(it.toString()) }
        val now = read(context)
        val keep = JSONArray()
        for (i in 0 until now.length()) {
            val e = now.optJSONObject(i) ?: continue
            if (e.toString() !in sentKeys) keep.put(e)
        }
        write(context, keep)
    }

    private fun digits(s: String) = s.filter { it.isDigit() }.takeLast(9)
}
