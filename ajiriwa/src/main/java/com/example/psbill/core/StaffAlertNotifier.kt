package com.example.psbill.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

/**
 * Attendant alerts from the server (buy SMS bundle, read the meter, pump water,
 * restock…) shown as phone notifications. A server repeat (times_notified went up)
 * notifies again; resolved alerts are cleared.
 */
object StaffAlertNotifier {
    const val CHANNEL = "attendant_alerts"
    const val EXTRA_OPEN = "open_screen"
    private const val SEEN = "alerts_seen"

    /** Which app screen fixes this alert. */
    fun screenFor(kind: String?): String = when (kind) {
        "SMS_UNSENT" -> "sms"
        else -> "stock"
    }

    private fun channel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Attendant alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Things to do now: buy SMS bundle, read the meter, pump water, restock"
            })
        }
    }

    fun onAlerts(context: Context, alerts: List<JSONObject>) {
        val prefs = DeviceIdentity.prefs(context)
        val seen = runCatching { JSONObject(prefs.getString(SEEN, "{}") ?: "{}") }.getOrElse { JSONObject() }
        val now = JSONObject()
        val nmc = NotificationManagerCompat.from(context)
        channel(context)
        for (a in alerts) {
            val id = a.optString("id")
            if (id.isBlank()) continue
            val times = a.optInt("times_notified", 1)
            now.put(id, times)
            if (seen.has(id) && seen.optInt(id) >= times) continue
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                putExtra(EXTRA_OPEN, screenFor(a.optString("kind")))
            }
            val pi = open?.let {
                PendingIntent.getActivity(context, id.hashCode(), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(a.optString("title"))
                .setContentText(a.optString("message"))
                .setStyle(NotificationCompat.BigTextStyle().bigText(a.optString("message")))
                .setPriority(if (a.optString("severity") == "CRITICAL") NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .apply { pi?.let { setContentIntent(it) } }
                .build()
            runCatching { nmc.notify(id.hashCode(), n) }
            ActivityLog.log("alert_shown", detail = JSONObject().put("kind", a.optString("kind")).put("title", a.optString("title")), context = context)
        }
        // Resolved on the server: take the notification down.
        seen.keys().forEach { old -> if (!now.has(old)) runCatching { nmc.cancel(old.hashCode()) } }
        prefs.edit().putString(SEEN, now.toString()).apply()
    }
}
