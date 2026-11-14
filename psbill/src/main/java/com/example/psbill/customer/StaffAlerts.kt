package com.example.psbill.customer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One screen as the alert service last saw it. */
data class ScreenSnap(
    val name: String, val status: String, val ended: Boolean, val online: Boolean,
    val secondsLeft: Int, val prepaid: Boolean, val console: String, val topupAt: String,
    val topupMinutes: Int, val bill: Double, val game: String, val queuePending: Int = 0,
)

data class StaffAlert(val key: String, val urgent: Boolean, val title: String, val text: String) {
    /** The screen this alert is about ("kind:<device id>"). */
    val deviceId: String get() = key.substringAfter(':', "")
}

/**
 * Tapping an alert opens that screen: the notification carries [EXTRA_SCREEN]; MainActivity puts it
 * here, the app switches to Screens and the Screens tab opens that screen's detail (bill, call next,
 * stop, free, top-up) and clears it.
 */
object StaffAlertNav {
    const val EXTRA_SCREEN = "psbill.open_screen"
    val openScreen = androidx.compose.runtime.mutableStateOf<String?>(null)

    fun fromIntent(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_SCREEN)?.takeIf { it.isNotBlank() } ?: return
        openScreen.value = id
        intent.removeExtra(EXTRA_SCREEN)
    }
}

/**
 * What changed between two polls -> what the attendant must know (pure, no Android).
 * Urgent = they must act now: a top-up request, a bill to collect, overtime, a TV that went
 * offline or a console unplugged during a session.
 */
object StaffAlertRules {
    private val LIVE = setOf("ACTIVE", "OVERTIME", "PENDING")

    fun snap(j: JSONObject): ScreenSnap {
        val tr = j.optJSONObject("topup_request")
        val pending = tr != null && tr.optString("status") == "PENDING"
        return ScreenSnap(
            name = j.optString("device_name").takeIf { it.isNotBlank() && it != "null" } ?: "Screen",
            status = j.optString("status"),
            ended = j.optBoolean("has_ended_session"),
            online = j.optBoolean("is_online"),
            secondsLeft = j.optInt("seconds_remaining"),
            prepaid = j.optString("billing_mode").equals("PREPAID", true),
            console = j.optString("console_state"),
            topupAt = if (pending) tr!!.optString("at") else "",
            topupMinutes = if (pending) tr!!.optInt("minutes") else 0,
            bill = j.optDouble("active_billing_kes", 0.0).let { if (it.isNaN()) 0.0 else it },
            game = j.optString("current_game").takeIf { it.isNotBlank() && it != "null" } ?: "",
            queuePending = j.optInt("queue_pending"),
        )
    }

    /** prev = null on the first poll: only waiting top-ups ring, the rest is the starting point. */
    fun diff(prev: Map<String, ScreenSnap>?, now: Map<String, ScreenSnap>): List<StaffAlert> {
        val out = mutableListOf<StaffAlert>()
        for ((id, n) in now) {
            if (n.topupAt.isNotBlank() && n.topupAt != prev?.get(id)?.topupAt) {
                out += StaffAlert("topup:$id", true, "${n.name} wants more time",
                    (if (n.topupMinutes > 0) "${n.topupMinutes} min requested. " else "") + "Approve or decline in PSBill.")
            }
            if (n.queuePending > (prev?.get(id)?.queuePending ?: 0)) {
                out += StaffAlert("queue:$id", true, "Queue request for ${n.name}",
                    "Someone asked to join the queue on the WiFi page. Approve or reject in PSBill (Screens).")
            }
            val p = prev?.get(id) ?: continue
            val wasLive = p.status in LIVE
            val isLive = n.status in LIVE
            if (!wasLive && isLive) {
                out += StaffAlert("start:$id", false, "Session started on ${n.name}",
                    if (n.game.isNotBlank()) n.game else "A player started playing.")
            }
            if (wasLive && (!isLive || (n.ended && !p.ended))) {
                out += StaffAlert("end:$id", true, "Session ended on ${n.name}",
                    if (p.bill > 0) "Collect KES ${p.bill.toInt()} (check the bill in PSBill)." else "Check the bill in PSBill.")
            }
            if (isLive && n.status == "OVERTIME" && p.status != "OVERTIME") {
                out += StaffAlert("overtime:$id", true, "${n.name} is in overtime", "The time is up and the meter is still running.")
            }
            if (isLive && n.prepaid && n.secondsLeft in 1..120 && p.secondsLeft > 120) {
                out += StaffAlert("ending:$id", false, "${n.name}: 2 minutes left", "Ask the player if they want more time.")
            }
            if (isLive && p.online && !n.online) {
                out += StaffAlert("offline:$id", true, "${n.name} went offline", "A session is running. Check the TV and its WiFi.")
            }
            if (isLive && n.console == "unplugged" && p.console != "unplugged") {
                out += StaffAlert("console:$id", true, "${n.name}: console unplugged", "The HDMI cable or console is off during a session. The meter pauses after 1 minute until it is back.")
            }
        }
        return out
    }
}

/**
 * Runs while the attendant is signed in, also with the app closed: asks the server every
 * 10 s for the screens' state (GET /api/v1/kiosk/staff-alerts/state) and rings for what
 * StaffAlertRules finds. Uses the token the app keeps in prefs (renewals are picked up).
 */
class StaffAlertService : Service() {
    companion object {
        private const val TAG = "StaffAlerts"
        private const val SERVER = "https://api.ajiriwa.gidraf.dev"
        private const val CH_URGENT = "psbill_urgent_v1"
        private const val CH_INFO = "psbill_info_v1"
        private const val CH_RUN = "psbill_running_v1"
        private const val ONGOING_ID = 7001
        private const val POLL_MS = 10_000L

        fun start(c: Context) {
            runCatching { ContextCompat.startForegroundService(c, Intent(c, StaffAlertService::class.java)) }
                .onFailure { Log.w(TAG, "start: ${it.message}") }
        }

        fun stop(c: Context) { runCatching { c.stopService(Intent(c, StaffAlertService::class.java)) } }

        fun canNotify(c: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    @Volatile private var running = false
    private var worker: Thread? = null
    private var last: Map<String, ScreenSnap>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channels()
        val n = NotificationCompat.Builder(this, CH_RUN)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("PSBill alerts are on")
            .setContentText("You will hear top-ups, session ends and problems on the TVs.")
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ONGOING_ID, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            worker = Thread({ loop() }, "staff-alerts").apply { isDaemon = true; start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        super.onDestroy()
    }

    private fun token() = getSharedPreferences("PSBillCustomerPrefs", Context.MODE_PRIVATE).getString("auth_token", "") ?: ""

    private fun loop() {
        while (running) {
            val tok = token()
            if (tok.isBlank()) { stopSelf(); return }
            try {
                http.newCall(Request.Builder().url("$SERVER/api/v1/kiosk/staff-alerts/state")
                    .header("Authorization", "Bearer $tok").build()).execute().use { r ->
                    if (r.isSuccessful) {
                        val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("screens")
                        val now = linkedMapOf<String, ScreenSnap>()
                        if (arr != null) for (i in 0 until arr.length()) {
                            val j = arr.optJSONObject(i) ?: continue
                            now[j.optString("id")] = StaffAlertRules.snap(j)
                        }
                        StaffAlertRules.diff(last, now).forEach(::notify)
                        last = now
                    }
                    // 401: the app renews the login when opened; keep waiting quietly
                }
            } catch (e: Exception) {
                Log.w(TAG, "poll: ${e.message}")
            }
            try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { return }
        }
    }

    private fun notify(a: StaffAlert) {
        if (!canNotify(this)) return
        val n = NotificationCompat.Builder(this, if (a.urgent) CH_URGENT else CH_INFO)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(a.title)
            .setContentText(a.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.text))
            .setPriority(if (a.urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (a.urgent) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openScreen(a))
            .addAction(0, "Open screen", openScreen(a))
            .apply { if (Build.VERSION.SDK_INT < 26) setDefaults(Notification.DEFAULT_ALL) }
            .build()
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(a.key.hashCode(), n)
        }
    }

    /** Opens the app on this alert's screen (one PendingIntent per alert, so each opens its own screen). */
    private fun openScreen(a: StaffAlert): PendingIntent = PendingIntent.getActivity(
        this, a.key.hashCode(),
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(StaffAlertNav.EXTRA_SCREEN, a.deviceId),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
    )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
    )

    private fun channels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        nm.createNotificationChannel(NotificationChannel(CH_URGENT, "Act now (top-ups, bills, problems)",
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Top-up requests, sessions ended, overtime, a TV offline or a console unplugged"
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), attrs)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
        })
        nm.createNotificationChannel(NotificationChannel(CH_INFO, "Sessions starting, time running out",
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), attrs)
        })
        nm.createNotificationChannel(NotificationChannel(CH_RUN, "Alerts running", NotificationManager.IMPORTANCE_MIN))
    }
}
