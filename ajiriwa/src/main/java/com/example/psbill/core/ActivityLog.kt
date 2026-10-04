package com.example.psbill.core

import android.content.Context
import android.util.Log
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Everything done on this phone (screens opened, orders, payments, stock, SMS, app launches…),
 * kept in a file outbox so it survives being offline / restarts, and uploaded to
 * /api/v1/mobile/activity by the agent heartbeat or when the network comes back.
 */
object ActivityLog {
    private const val TAG = "ActivityLog"
    private const val FILE = "activity_outbox.json"
    private const val MAX = 10_000
    private val lock = Any()
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    @Volatile private var app: Context? = null

    fun init(context: Context) { if (app == null) app = context.applicationContext }

    val stream = object : SyncStream {
        override val key = "activity"
        override val label = "Activity log"
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

    /** Record one operation. Never throws; works before sign-in (uploaded once signed in). */
    fun log(action: String, screen: String? = null, detail: JSONObject? = null, context: Context? = null) {
        val ctx = context?.applicationContext ?: app ?: return
        runCatching {
            val ev = JSONObject().apply {
                put("action", action.take(60))
                screen?.let { put("screen", it.take(60)) }
                detail?.let { put("detail", it) }
                put("at", iso.format(Date()))
                put("client_ref", UUID.randomUUID().toString())
            }
            synchronized(lock) {
                val a = load(ctx)
                a.put(ev)
                val trimmed = if (a.length() > MAX) JSONArray().also { t -> for (i in a.length() - MAX until a.length()) t.put(a.get(i)) } else a
                save(ctx, trimmed)
            }
        }.onFailure { Log.w(TAG, "log failed: ${it.message}") }
    }

    fun size(context: Context): Int = synchronized(lock) { load(context).length() }

    /** Upload everything queued (200 per request); returns events sent. Throws if the server can't be reached. */
    fun flush(context: Context): Int {
        if (!DeviceIdentity.signedIn(context)) return 0
        var sent = 0
        while (true) {
            val batch = synchronized(lock) {
                val a = load(context)
                JSONArray().also { b -> for (i in 0 until minOf(200, a.length())) b.put(a.get(i)) }
            }
            if (batch.length() == 0) return sent
            PhoneApi.request(context, "/api/v1/mobile/activity", JSONObject().put("events", batch))
            synchronized(lock) {
                val a = load(context)
                val rest = JSONArray()
                for (i in batch.length() until a.length()) rest.put(a.get(i))
                save(context, rest)
            }
            sent += batch.length()
        }
    }

    // ── automatic logging of write calls made by any screen ─────────────────
    /** Background / agent traffic that is not an operator action. */
    private val SKIP = listOf(
        "/mobile/heartbeat", "/mobile/activity", "/mobile/ack", "/delivery/location", "/gateway/",
        "/sms/inbound", "/sms/history", "/sms/sent", "/sms/expired", "/calls/upload", "/call-logs",
        "/contacts/sync", "/mpesa/upload", "/kiosk/heartbeat", "/sms/claim", "/sms/ack", "/auth/refresh",
    )

    private val NAMES = listOf(
        Regex("/shop/orders/[^/]+/payment") to "order_payment",
        Regex("/shop/orders/[^/]+/items") to "order_items_edit",
        Regex("/shop/orders/[^/]+/reschedule") to "order_reschedule",
        Regex("/shop/orders/[^/]+/(status|cancel|deliver)") to "order_status",
        Regex("/orders?/[^/]+/(send-receipt|receipt)") to "order_receipt",
        Regex("/(shop/)?orders?(/create|/manual)?$") to "order_create",
        Regex("/stock/[^/]+/(readings|meter)") to "meter_reading",
        Regex("/stock/[^/]+/(movements|restock|transfer|waste|adjust|levels)") to "stock_movement",
        Regex("/stock/") to "stock_change",
        Regex("/sms/send|/send_sms|/send-sms") to "sms_send",
        Regex("/marketing") to "marketing",
        Regex("/kiosk/wifi-pass|/wifi") to "wifi",
        Regex("/kiosk/") to "arcade",
        Regex("/customers?/") to "customer_change",
        Regex("/products?/|/catalogue") to "product_change",
        Regex("/properties") to "property_change",
        Regex("/print") to "printing",
        Regex("/auth/login") to "login",
    )

    private fun nameFor(method: String, path: String): String =
        NAMES.firstOrNull { it.first.containsMatchIn(path) }?.second ?: "${method.lowercase()}_request"

    private val interceptor = Interceptor { chain ->
        val req = chain.request()
        val method = req.method
        val path = req.url.encodedPath
        val write = method != "GET" && method != "HEAD"
        if (!write || SKIP.any { path.contains(it) }) return@Interceptor chain.proceed(req)
        val started = System.currentTimeMillis()
        var res: Response? = null
        try {
            res = chain.proceed(req)
            return@Interceptor res
        } finally {
            log(nameFor(method, path), detail = JSONObject().apply {
                put("method", method)
                put("path", path.take(160))
                put("status", res?.code ?: 0)
                put("ok", res?.isSuccessful == true)
                if (res == null) put("offline", true)
                put("ms", System.currentTimeMillis() - started)
            })
        }
    }

    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .addInterceptor(interceptor)
            .build()
    }

    /** The shared HTTP client: every write call made through it is logged. */
    val client: OkHttpClient get() = base

    /** Builder sharing the pool + logging, for screens needing other timeouts. */
    fun builder(): OkHttpClient.Builder = base.newBuilder()
}
