package com.example.psbill.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One kind of phone data the server can ask for ("sms", "calls", "contacts",
 * "mpesa", "location"). Feature modules contribute streams through
 * [FeatureModule.syncStreams]; the agent runs them on request.
 */
interface SyncStream {
    val key: String
    val label: String

    /** Rows on the phone not uploaded yet (0 when unknown / permission missing). */
    fun pending(context: Context): Int

    /** Upload what is new (or everything when [full]); returns rows sent. Blocking; throws on failure. */
    fun sync(context: Context, full: Boolean): Int
}

/** Who this phone is and how to reach the server (shared prefs written at sign-in). */
object DeviceIdentity {
    private const val PREFS = "AttenderPrefs"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun installId(context: Context): String {
        val p = prefs(context)
        p.getString("device_install_id", null)?.let { if (it.isNotBlank()) return it }
        val id = "and-" + UUID.randomUUID().toString()
        p.edit().putString("device_install_id", id).apply()
        return id
    }

    fun server(context: Context): String =
        (prefs(context).getString("server_domain", "api.ajiriwa.gidraf.dev") ?: "api.ajiriwa.gidraf.dev")
            .trim().removePrefix("https://").removePrefix("http://").trimEnd('/')

    fun token(context: Context): String = prefs(context).getString("auth_token", "") ?: ""
    fun partnerId(context: Context): String = prefs(context).getString("partner_id", "") ?: ""
    fun signedIn(context: Context): Boolean = token(context).isNotBlank() && partnerId(context).isNotBlank()
}

/** Small blocking JSON client for background work (never call on the main thread). */
object PhoneApi {
    private val JSON = "application/json; charset=utf-8".toMediaType()
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    }

    fun request(context: Context, path: String, body: JSONObject? = null, method: String = if (body != null) "POST" else "GET"): JSONObject {
        val token = DeviceIdentity.token(context)
        if (token.isBlank()) throw IOException("Not signed in")
        val b = Request.Builder()
            .url("https://${DeviceIdentity.server(context)}$path")
            .header("Authorization", "Bearer $token")
            .header("X-Device-Id", DeviceIdentity.installId(context))
        DeviceIdentity.partnerId(context).takeIf { it.isNotBlank() }?.let { b.header("X-Partner-Id", it) }
        when (method) {
            "GET" -> b.get()
            "DELETE" -> b.delete()
            else -> b.method(method, (body ?: JSONObject()).toString().toRequestBody(JSON))
        }
        http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val json = runCatching { JSONObject(text.ifBlank { "{}" }) }.getOrElse { JSONObject() }
            if (!r.isSuccessful) throw IOException(json.optString("error").ifBlank { "HTTP ${r.code}" })
            return json
        }
    }
}

data class AgentState(
    val online: Boolean = false,
    val lastHeartbeatAt: Long = 0L,
    val lastError: String? = null,
    val device: JSONObject? = null,
    val runningRequestId: String? = null,
    val runningStreams: List<String> = emptyList(),
    val progress: Map<String, String> = emptyMap(),
    val lastResult: String? = null,
    /** Newer build published on the server: {version_name, url, notes, mandatory, auto_download, sha256…} */
    val appUpdate: JSONObject? = null,
    /** Open attendant alerts (buy SMS bundle, read meter, pump, restock…). */
    val alerts: List<JSONObject> = emptyList(),
)

/**
 * The always-on "device agent": heartbeat (presence + pending counts), picking up
 * sync requests from the web / app, running them and reporting back
 * (ACCEPTED → DONE with counts). Runs inside [com.example.psbill.DeliveryTrackingService].
 */
object DeviceAgent {
    private const val TAG = "DeviceAgent"
    val ALL_STREAMS = listOf("sms", "mpesa", "calls", "contacts", "location", "activity")

    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state

    private val jobs = Executors.newSingleThreadExecutor()
    private val beating = AtomicBoolean(false)
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun streams(): List<SyncStream> =
        CompiledModules.features.flatMap { it.syncStreams } + LocationOutbox.stream + ActivityLog.stream

    // ── settings pushed by the server ────────────────────────────────────────
    private fun settings(context: Context): JSONObject =
        runCatching { JSONObject(DeviceIdentity.prefs(context).getString("agent_settings", "{}") ?: "{}") }.getOrElse { JSONObject() }

    fun streamEnabled(context: Context, key: String): Boolean =
        settings(context).optJSONObject("streams")?.optBoolean(key, true) ?: true

    fun trackingEnabled(context: Context): Boolean = settings(context).optBoolean("tracking_enabled", true)

    fun heartbeatSeconds(context: Context): Long = settings(context).optLong("heartbeat_seconds", 60).coerceIn(20, 600)

    fun lastSync(context: Context, key: String): Long = DeviceIdentity.prefs(context).getLong("sync_last_$key", 0L)

    private fun markSynced(context: Context, key: String) =
        DeviceIdentity.prefs(context).edit().putLong("sync_last_$key", System.currentTimeMillis()).apply()

    // ── start / kick ─────────────────────────────────────────────────────────
    /** Start (or poke) the background service; safe to call often. */
    fun start(context: Context, action: String? = null) {
        if (!DeviceIdentity.signedIn(context)) return
        val i = Intent(context, com.example.psbill.DeliveryTrackingService::class.java)
        if (action != null) i.action = action
        try {
            ContextCompat.startForegroundService(context, i)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the agent service: ${e.message}")
            // App in background on newer Android: do the work inline instead.
            if (action == com.example.psbill.DeliveryTrackingService.ACTION_SYNC_NOW) jobs.execute { heartbeat(context.applicationContext) }
        }
    }

    /** Heartbeat now (e.g. after a sync request was pushed over the WebSocket). */
    fun kick(context: Context) = start(context, com.example.psbill.DeliveryTrackingService.ACTION_SYNC_NOW)

    fun onLogout(context: Context) {
        runCatching { context.stopService(Intent(context, com.example.psbill.DeliveryTrackingService::class.java)) }
        _state.value = AgentState()
    }

    fun installedVersionCode(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)

    // ── heartbeat ────────────────────────────────────────────────────────────
    private fun granted(context: Context, p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    fun permissions(context: Context): JSONObject = JSONObject().apply {
        put("sms", granted(context, Manifest.permission.READ_SMS))
        put("calls", granted(context, Manifest.permission.READ_CALL_LOG))
        put("contacts", granted(context, Manifest.permission.READ_CONTACTS))
        put("location", granted(context, Manifest.permission.ACCESS_FINE_LOCATION) || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION))
        put("background_location", Build.VERSION.SDK_INT < 29 || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        put("notifications", Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS))
    }

    private fun network(context: Context): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            else -> "other"
        }
    } catch (_: Exception) { "unknown" }

    fun pendingCounts(context: Context): JSONObject = JSONObject().apply {
        streams().forEach { s -> put(s.key, runCatching { s.pending(context) }.getOrDefault(0)) }
    }

    private fun lastSyncJson(context: Context) = JSONObject().apply {
        streams().forEach { s -> lastSync(context, s.key).takeIf { it > 0 }?.let { put(s.key, iso.format(Date(it))) } }
    }

    /** One heartbeat round trip; runs a returned sync request. Blocking. */
    fun heartbeat(context: Context) {
        if (!DeviceIdentity.signedIn(context)) return
        if (!beating.compareAndSet(false, true)) return
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val body = JSONObject().apply {
                put("device_id", DeviceIdentity.installId(context))
                put("app", "ajiriwa")
                put("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                put("os_version", "Android ${Build.VERSION.RELEASE}")
                put("app_version", runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "")
                put("version_code", installedVersionCode(context))
                bm?.let {
                    put("battery", it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                    put("charging", it.isCharging)
                }
                put("network", network(context))
                put("modules", JSONArray(CompiledModules.keys.toList()))
                put("permissions", permissions(context))
                put("pending", pendingCounts(context))
                put("last_sync", lastSyncJson(context))
                LocationOutbox.last(context)?.let { put("location", it) }
            }
            val r = PhoneApi.request(context, "/api/v1/mobile/heartbeat", body)
            r.optJSONObject("settings")?.let { DeviceIdentity.prefs(context).edit().putString("agent_settings", it.toString()).apply() }
            val alerts = r.optJSONArray("alerts")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
            _state.value = _state.value.copy(online = true, lastHeartbeatAt = System.currentTimeMillis(), lastError = null,
                device = r.optJSONObject("device"), appUpdate = r.optJSONObject("app_update"), alerts = alerts)
            runCatching { StaffAlertNotifier.onAlerts(context, alerts) }
            runCatching { AppUpdater.onHint(context, r.optJSONObject("app_update")) }
            val req = r.optJSONObject("sync_request")
            if (req != null && _state.value.runningRequestId == null) runRequest(context, req)
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat failed: ${e.message}")
            _state.value = _state.value.copy(online = false, lastError = e.message)
        } finally {
            beating.set(false)
        }
    }

    private fun ack(context: Context, id: String, status: String, result: JSONObject? = null, error: String? = null) {
        runCatching {
            PhoneApi.request(context, "/api/v1/mobile/ack", JSONObject().apply {
                put("acks", JSONArray().put(JSONObject().apply {
                    put("id", id); put("status", status)
                    result?.let { put("result", it) }
                    error?.let { put("error", it) }
                }))
                put("pending", pendingCounts(context))
                put("last_sync", lastSyncJson(context))
            })
        }.onFailure { Log.w(TAG, "ack $status failed: ${it.message}") }
    }

    /** Accept a server request, upload each asked stream, report counts. Blocking. */
    private fun runRequest(context: Context, req: JSONObject) {
        val id = req.optString("id")
        val full = req.optBoolean("full", false)
        val wanted = req.optJSONArray("streams")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: ALL_STREAMS
        val todo = streams().filter { it.key in wanted && streamEnabled(context, it.key) }
        _state.value = _state.value.copy(runningRequestId = id, runningStreams = todo.map { it.key }, progress = emptyMap())
        ack(context, id, "ACCEPTED")
        val result = JSONObject()
        val errors = mutableListOf<String>()
        for (s in todo) {
            _state.value = _state.value.copy(progress = _state.value.progress + (s.key to "syncing…"))
            try {
                val n = s.sync(context, full)
                result.put(s.key, n)
                markSynced(context, s.key)
                _state.value = _state.value.copy(progress = _state.value.progress + (s.key to "$n sent"))
            } catch (e: Exception) {
                errors += "${s.label}: ${e.message}"
                _state.value = _state.value.copy(progress = _state.value.progress + (s.key to "failed"))
            }
        }
        if (errors.isEmpty()) ack(context, id, "DONE", result) else ack(context, id, "FAILED", result, errors.joinToString("; "))
        val summary = if (errors.isEmpty()) "Synced: " + todo.joinToString { "${it.label} ${result.optInt(it.key)}" } else errors.joinToString("; ")
        _state.value = _state.value.copy(runningRequestId = null, runningStreams = emptyList(), lastResult = summary)
    }

    private val catchingUp = AtomicBoolean(false)
    private var lastCatchUp = 0L

    /**
     * Upload whatever piled up on the phone (SMS, M-Pesa, calls, contacts) — on reconnect and
     * every [every] ms. Incremental and safe to repeat: the server keeps one row per message/call.
     * Blocking; skipped while another catch-up or a server sync request is running.
     */
    fun catchUp(context: Context, every: Long = 0L) {
        if (!DeviceIdentity.signedIn(context)) return
        if (every > 0 && System.currentTimeMillis() - lastCatchUp < every) return
        if (_state.value.runningRequestId != null || !catchingUp.compareAndSet(false, true)) return
        try {
            lastCatchUp = System.currentTimeMillis()
            streams().filter { it.key != "location" && it.key != "activity" && streamEnabled(context, it.key) }.forEach { s ->
                runCatching { s.sync(context, false) }
                    .onSuccess { markSynced(context, s.key) }
                    .onFailure { Log.w(TAG, "catch-up ${s.key}: ${it.message}") }
            }
        } finally {
            catchingUp.set(false)
        }
    }

    /** Local helper for screens: run an async block off the main thread on the agent executor. */
    fun runInBackground(block: () -> Unit) = jobs.execute { runCatching(block) }
}

/**
 * GPS fixes waiting to be uploaded (survives offline periods and restarts).
 * Fixes are sent in batches to /api/v1/delivery/location with the phone's time.
 */
object LocationOutbox {
    private const val FILE = "location_outbox.json"
    private const val MAX = 20_000
    private val lock = Any()

    val stream = object : SyncStream {
        override val key = "location"
        override val label = "Location"
        override fun pending(context: Context) = size(context)
        override fun sync(context: Context, full: Boolean) = flush(context)
    }

    private fun load(context: Context): JSONArray = try {
        val f = context.getFileStreamPath(FILE)
        if (f.exists()) JSONArray(f.readText()) else JSONArray()
    } catch (_: Exception) { JSONArray() }

    private fun save(context: Context, a: JSONArray) {
        runCatching { context.openFileOutput(FILE, Context.MODE_PRIVATE).use { it.write(a.toString().toByteArray()) } }
    }

    fun add(context: Context, fix: JSONObject) = synchronized(lock) {
        val a = load(context)
        a.put(fix)
        val trimmed = if (a.length() > MAX) JSONArray().also { t -> for (i in a.length() - MAX until a.length()) t.put(a.get(i)) } else a
        save(context, trimmed)
        DeviceIdentity.prefs(context).edit()
            .putString("last_fix", JSONObject().put("lat", fix.optDouble("latitude")).put("lng", fix.optDouble("longitude")).toString())
            .apply()
    }

    fun size(context: Context): Int = synchronized(lock) { load(context).length() }

    fun last(context: Context): JSONObject? =
        DeviceIdentity.prefs(context).getString("last_fix", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** Upload everything queued (200 per request); returns fixes sent. Throws if the server can't be reached. */
    fun flush(context: Context): Int {
        var sent = 0
        while (true) {
            val batch = synchronized(lock) {
                val a = load(context)
                JSONArray().also { b -> for (i in 0 until minOf(200, a.length())) b.put(a.get(i)) }
            }
            if (batch.length() == 0) return sent
            PhoneApi.request(context, "/api/v1/delivery/location", JSONObject().put("pings", batch))
            synchronized(lock) {
                val a = load(context)
                val rest = JSONArray()
                for (i in batch.length() until a.length()) rest.put(a.get(i))
                save(context, rest)
            }
            sent += batch.length()
        }
    }

    fun clear(context: Context) = synchronized(lock) { save(context, JSONArray()) }
}
