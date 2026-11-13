package com.example.psbill.core

import android.util.Base64
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/*
 * Keeps the staff login alive. A login lasts 36 h; while the phone is online it is renewed
 * (POST /auth/refresh) once less than a day is left. A phone that was offline (lounge
 * internet down) can still renew a login that ran out up to 7 days ago, so an outage never
 * signs staff out. Network errors never sign anyone out: only the server's refusal does.
 */
object LoginRenewal {
    private const val RENEW_WHEN_LEFT_SECS = 24 * 3600L
    private val client = OkHttpClient()

    /** Seconds until the token runs out (negative = already ran out), null if unreadable. */
    fun secondsLeft(token: String): Long? = try {
        val part = token.split(".").getOrNull(1) ?: return null
        val payload = JSONObject(String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)))
        if (!payload.has("exp")) null else payload.getLong("exp") - System.currentTimeMillis() / 1000
    } catch (_: Exception) {
        null
    }

    fun due(token: String): Boolean {
        val left = secondsLeft(token) ?: return false
        return token.isNotBlank() && left < RENEW_WHEN_LEFT_SECS
    }

    /**
     * done(newToken, refused): newToken on success; refused = the server said no (sign in
     * again); both null/false = no connection, try later and keep the current login.
     */
    fun renew(baseUrl: String, token: String, done: (String?, Boolean) -> Unit) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/auth/refresh")
            .header("Authorization", "Bearer $token")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = done(null, false)
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    when {
                        it.isSuccessful -> {
                            val t = runCatching { JSONObject(body).optString("refresh_token") }.getOrNull().orEmpty()
                            done(t.ifBlank { null }, false)
                        }
                        it.code == 401 || it.code == 403 -> done(null, true)
                        else -> done(null, false)
                    }
                }
            }
        })
    }
}
