package com.example.psbill.modules.properties

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.psbill.core.ModuleContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class ApiException(message: String) : IOException(message)

/** Thin client for /api/v1/properties/<partner>/… (same API the web dashboard uses). */
class PropertiesApi(private val ctx: ModuleContext) {
    private val base get() = "${ctx.baseUrl}/api/v1/properties"
    private val json = "application/json; charset=utf-8".toMediaType()

    private suspend fun call(req: Request.Builder): String = withContext(Dispatchers.IO) {
        http.newCall(req.headers(ctx.headers()).build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(body).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }
                throw ApiException(msg ?: "Request failed (${resp.code})")
            }
            body
        }
    }

    private suspend fun get(path: String) = JSONObject(call(Request.Builder().url("$base$path").get()))
    private suspend fun send(method: String, path: String, body: JSONObject = JSONObject()) =
        JSONObject(call(Request.Builder().url("$base$path").method(method, body.toString().toRequestBody(json))))

    private val p get() = ctx.partnerId

    suspend fun schema() = get("/spec-schema")
    suspend fun me() = get("/$p/me")
    suspend fun list(approval: String? = null, q: String? = null): JSONObject {
        val params = buildList {
            add("limit=200")
            if (!approval.isNullOrBlank()) add("approval=$approval")
            if (!q.isNullOrBlank()) add("q=${Uri.encode(q)}")
        }.joinToString("&")
        return get("/$p/listings?$params")
    }
    suspend fun detail(id: String) = get("/$p/listings/$id")
    suspend fun create(body: JSONObject) = send("POST", "/$p/listings", body)
    suspend fun update(id: String, body: JSONObject) = send("PUT", "/$p/listings/$id", body)
    suspend fun submit(id: String) = send("POST", "/$p/listings/$id/submit")
    suspend fun review(id: String, approve: Boolean, note: String?) =
        send("POST", "/$p/listings/$id/${if (approve) "approve" else "reject"}", JSONObject().put("note", note ?: ""))
    suspend fun delete(id: String) = send("DELETE", "/$p/listings/$id")
    suspend fun setCover(id: String, photoId: String) = send("PATCH", "/$p/listings/$id/photos/$photoId", JSONObject().put("cover", true))
    suspend fun deletePhoto(id: String, photoId: String) = send("DELETE", "/$p/listings/$id/photos/$photoId")
    suspend fun route(id: String, mode: String, from: Pair<Double, Double>? = null): JSONObject {
        val f = from?.let { "&from=${it.first},${it.second}" } ?: ""
        return get("/$p/listings/$id/route?mode=$mode$f")
    }
    suspend fun share(id: String, name: String, phone: String, email: String, note: String) =
        send("POST", "/$p/listings/$id/share", JSONObject().apply {
            if (name.isNotBlank()) put("name", name)
            if (phone.isNotBlank()) put("phone", phone)
            if (email.isNotBlank()) put("email", email)
            if (note.isNotBlank()) put("note", note)
        })
    suspend fun displayFeed(): JSONObject = JSONObject(
        call(Request.Builder().url("${ctx.baseUrl}/api/v1/properties/public/partner/$p/display").get())
    )

    /** Upload photos (camera captures or gallery picks) for one room. */
    suspend fun uploadPhotos(context: Context, id: String, uris: List<Uri>, room: String): JSONObject = withContext(Dispatchers.IO) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val form = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("room", room)
        uris.forEachIndexed { i, uri ->
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@forEachIndexed
            val name = displayName(context, uri) ?: "photo-${i + 1}.jpg"
            val type = context.contentResolver.getType(uri) ?: "image/jpeg"
            form.addFormDataPart("photos", name, bytes.toRequestBody(type.toMediaType()))
            form.addFormDataPart("captured_at", iso.format(Date()))
        }
        JSONObject(call(Request.Builder().url("$base/$p/listings/$id/photos").post(form.build() as RequestBody)))
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    companion object {
        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS) // photo uploads on slow mobile data
            .build()
    }
}

fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
fun JSONObject.dbl(key: String): Double? = if (isNull(key) || !has(key)) null else optDouble(key).takeIf { !it.isNaN() }
