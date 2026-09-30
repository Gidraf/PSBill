package com.example.psbill

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Durable, at-least-once delivery of phone → server calls (incoming SMS,
 * delivery reports, M-Pesa confirmations).
 *
 * Calls are persisted before any network attempt and only removed once the
 * server answers 2xx (or a permanent 4xx), so nothing is lost when the phone
 * is offline, the server restarts or Android kills the process. The server
 * endpoints are idempotent, so replays are harmless.
 */
object GatewayOutbox {
    private const val TAG = "GatewayOutbox"
    private const val PREFS = "SmsGatewayOutbox"
    private const val KEY = "items"
    private const val MAX_ITEMS = 1000
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val lock = Any()
    @Volatile private var flushing = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Queue a POST of [body] to [path] (e.g. "/api/v1/kiosk/sms/inbox"). */
    fun enqueue(context: Context, path: String, body: JSONObject, dedupeKey: String? = null) {
        synchronized(lock) {
            val items = load(context)
            if (dedupeKey != null) {
                // A newer report for the same thing replaces the older one.
                for (i in items.length() - 1 downTo 0) {
                    if (items.optJSONObject(i)?.optString("key") == dedupeKey) items.remove(i)
                }
            }
            items.put(JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("key", dedupeKey ?: "")
                put("path", path)
                put("body", body.toString())
                put("created", System.currentTimeMillis())
                put("attempts", 0)
            })
            while (items.length() > MAX_ITEMS) items.remove(0)
            save(context, items)
        }
    }

    /** Queue and try to send right away on a background thread. */
    fun enqueueAndFlush(context: Context, path: String, body: JSONObject, dedupeKey: String? = null) {
        enqueue(context, path, body, dedupeKey)
        val app = context.applicationContext
        Thread { flush(app) }.start()
    }

    fun size(context: Context): Int = synchronized(lock) { load(context).length() }

    /** Send everything queued, in order. Blocking — call off the main thread. */
    fun flush(context: Context) {
        synchronized(lock) {
            if (flushing) return
            flushing = true
        }
        try {
            val prefs = context.getSharedPreferences("AttenderPrefs", Context.MODE_PRIVATE)
            val server = prefs.getString("server_domain", "") ?: ""
            val token = prefs.getString("auth_token", "") ?: ""
            val partnerId = prefs.getString("partner_id", "") ?: ""
            if (server.isBlank() || token.isBlank()) return

            val snapshot = synchronized(lock) { load(context) }
            val done = mutableSetOf<String>()
            val bumped = mutableSetOf<String>()
            val now = System.currentTimeMillis()

            for (i in 0 until snapshot.length()) {
                val item = snapshot.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (now - item.optLong("created", now) > MAX_AGE_MS || item.optInt("attempts") > 200) {
                    Log.w(TAG, "Dropping stale outbox item ${item.optString("path")}")
                    done += id
                    continue
                }
                val req = Request.Builder()
                    .url("https://$server${item.optString("path")}")
                    .post(item.optString("body").toRequestBody(JSON))
                    .header("Authorization", "Bearer $token")
                    .apply { if (partnerId.isNotBlank()) header("X-Partner-Id", partnerId) }
                    .build()
                val outcome = try {
                    http.newCall(req).execute().use { resp ->
                        when {
                            resp.isSuccessful -> "ok"
                            // Permanent client errors: retrying cannot help.
                            resp.code in 400..499 && resp.code !in listOf(401, 403, 408, 409, 425, 429) -> "drop"
                            else -> "retry"
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Outbox send failed (${item.optString("path")}): ${e.message}")
                    "offline"
                }
                when (outcome) {
                    "ok", "drop" -> done += id
                    "retry" -> bumped += id
                    "offline" -> { bumped += id; break } // network down: stop, keep order
                }
            }

            if (done.isNotEmpty() || bumped.isNotEmpty()) {
                synchronized(lock) {
                    val current = load(context)
                    val kept = JSONArray()
                    for (i in 0 until current.length()) {
                        val item = current.optJSONObject(i) ?: continue
                        val id = item.optString("id")
                        if (id in done) continue
                        if (id in bumped) item.put("attempts", item.optInt("attempts") + 1)
                        kept.put(item)
                    }
                    save(context, kept)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Outbox flush error: ${e.message}")
        } finally {
            flushing = false
        }
    }

    private fun load(context: Context): JSONArray = try {
        JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    private fun save(context: Context, items: JSONArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, items.toString()).commit()
    }
}
