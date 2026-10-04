package com.example.psbill.modules.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.psbill.SmsDispatcherService
import com.example.psbill.core.DeviceAgent
import com.example.psbill.core.DeviceIdentity
import com.example.psbill.core.PhoneApi
import com.example.psbill.core.SyncStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Incremental phone → server uploads. Each stream keeps a cursor (the newest
 * phone timestamp already uploaded); a full resync starts again from zero.
 * The first ever sync only goes back [firstDays] so a phone full of personal
 * history isn't dumped on the server by accident. The server de-duplicates,
 * so re-sending is always safe — every upload re-reads an overlap before the
 * cursor ([OVERLAP_MS]) so rows written late (a call is logged when it ends but
 * dated when it started; SMS saved after a reboot) are never skipped.
 * One stream uploads at a time ([lock]) even when several triggers fire.
 */
object SmsSync {
    private const val CHUNK = 200
    private val OVERLAP_MS = mapOf("sms" to 2 * 3_600_000L, "mpesa" to 2 * 3_600_000L, "calls" to 6 * 3_600_000L)
    private val lock = Any()
    private val SMS_URI: Uri = Uri.parse("content://sms")
    private const val MPESA_WHERE = "UPPER(address) IN ('MPESA','M-PESA')"

    private fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED
    private fun cursor(c: Context, key: String) = DeviceIdentity.prefs(c).getLong("sync_cursor_$key", 0L)
    private fun setCursor(c: Context, key: String, v: Long) = DeviceIdentity.prefs(c).edit().putLong("sync_cursor_$key", v).apply()
    private fun since(c: Context, key: String, full: Boolean, firstDays: Int): Long {
        if (full) return 0L
        val cur = cursor(c, key)
        return if (cur > 0) cur else System.currentTimeMillis() - firstDays * 86_400_000L
    }

    /** Where an upload starts reading: the cursor minus the overlap (never in pending counts). */
    private fun readFrom(c: Context, key: String, full: Boolean, firstDays: Int): Long {
        val s = since(c, key, full, firstDays)
        return if (full || cursor(c, key) <= 0) s else maxOf(0L, s - (OVERLAP_MS[key] ?: 0L))
    }

    /** Clear cursors (the next sync re-uploads the whole window). */
    fun reset(c: Context) = DeviceIdentity.prefs(c).edit().apply {
        listOf("sms", "mpesa", "calls", "contacts").forEach { remove("sync_cursor_$it") }
        remove("contacts_signature")
    }.apply()

    private fun count(c: Context, uri: Uri, where: String, args: Array<String>): Int = try {
        c.contentResolver.query(uri, arrayOf("_id"), where, args, null)?.use { it.count } ?: 0
    } catch (_: Exception) { 0 }

    // ── SMS (inbox + sent, without M-Pesa) and M-Pesa ────────────────────────
    private class SmsStream(override val key: String, override val label: String, val mpesa: Boolean, val firstDays: Int) : SyncStream {
        private fun where() = if (mpesa) "type = 1 AND $MPESA_WHERE AND date > ?" else "type IN (1,2) AND NOT ($MPESA_WHERE) AND date > ?"

        override fun pending(context: Context): Int {
            if (!granted(context, Manifest.permission.READ_SMS)) return 0
            return count(context, SMS_URI, where(), arrayOf(since(context, key, false, firstDays).toString()))
        }

        override fun sync(context: Context, full: Boolean): Int = synchronized(lock) { doSync(context, full) }

        private fun doSync(context: Context, full: Boolean): Int {
            if (!granted(context, Manifest.permission.READ_SMS)) throw IllegalStateException("SMS permission not granted")
            var sent = 0
            val batch = JSONArray()
            var newest = cursor(context, key)
            fun post() {
                if (batch.length() == 0) return
                PhoneApi.request(context, "/api/v1/kiosk/gateway/sms-history",
                    JSONObject().put("messages", batch).put("partner_id", DeviceIdentity.partnerId(context)))
                sent += batch.length()
                setCursor(context, key, newest)
                while (batch.length() > 0) batch.remove(0)
            }
            context.contentResolver.query(SMS_URI, arrayOf("address", "body", "date", "type", "date_sent"), where(),
                arrayOf(readFrom(context, key, full, firstDays).toString()), "date ASC")?.use { c ->
                val a = c.getColumnIndex("address"); val b = c.getColumnIndex("body")
                val d = c.getColumnIndex("date"); val t = c.getColumnIndex("type")
                val ds = c.getColumnIndex("date_sent")
                while (c.moveToNext()) {
                    val address = c.getString(a) ?: continue
                    val body = c.getString(b) ?: continue
                    val ts = c.getLong(d)
                    val sentAt = if (ds >= 0) c.getLong(ds) else 0L
                    val box = if (c.getInt(t) == 2) "sent" else "inbox"
                    // The live receiver keys an SMS on the network (SMSC) time = date_sent here,
                    // so a message seen live and again in history gets the same ref.
                    val refTime = if (box == "inbox" && sentAt > 0) sentAt else ts
                    batch.put(JSONObject().apply {
                        put("box", box); put("address", address); put("body", body); put("timestamp", ts)
                        if (sentAt > 0) put("date_sent", sentAt)
                        put("client_ref", SmsDispatcherService.clientRef(address, body, refTime))
                    })
                    if (ts > newest) newest = ts
                    if (batch.length() >= CHUNK) post()
                }
            }
            post()
            return sent
        }
    }

    // ── call logs ────────────────────────────────────────────────────────────
    private val calls = object : SyncStream {
        override val key = "calls"
        override val label = "Calls"

        override fun pending(context: Context): Int {
            if (!granted(context, Manifest.permission.READ_CALL_LOG)) return 0
            return count(context, CallLog.Calls.CONTENT_URI, "${CallLog.Calls.DATE} > ?", arrayOf(since(context, key, false, 30).toString()))
        }

        override fun sync(context: Context, full: Boolean): Int = synchronized(lock) { doSync(context, full) }

        private fun doSync(context: Context, full: Boolean): Int {
            if (!granted(context, Manifest.permission.READ_CALL_LOG)) throw IllegalStateException("Call log permission not granted")
            var sent = 0
            val batch = JSONArray()
            var newest = cursor(context, key)
            fun post() {
                if (batch.length() == 0) return
                PhoneApi.request(context, "/api/v1/kiosk/gateway/call-logs",
                    JSONObject().put("call_logs", batch).put("partner_id", DeviceIdentity.partnerId(context)))
                sent += batch.length()
                setCursor(context, key, newest)
                while (batch.length() > 0) batch.remove(0)
            }
            context.contentResolver.query(CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} > ?", arrayOf(readFrom(context, key, full, 30).toString()), "${CallLog.Calls.DATE} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val num = c.getString(0) ?: ""
                    val ts = c.getLong(4)
                    if (ts > newest) newest = ts
                    if (num.isBlank()) continue
                    batch.put(JSONObject().apply {
                        put("caller_number", num)
                        put("caller_name", c.getString(1) ?: "")
                        put("call_type", when (c.getInt(2)) {
                            CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                            CallLog.Calls.MISSED_TYPE -> "MISSED"
                            CallLog.Calls.REJECTED_TYPE -> "REJECTED"
                            else -> "INCOMING"
                        })
                        put("duration_seconds", c.getInt(3))
                        put("timestamp", ts)
                    })
                    if (batch.length() >= CHUNK) post()
                }
            }
            post()
            if (sent == 0 && newest > cursor(context, key)) setCursor(context, key, newest)
            return sent
        }
    }

    // ── contacts (whole list when it changed) ────────────────────────────────
    private val contacts = object : SyncStream {
        override val key = "contacts"
        override val label = "Contacts"

        private fun signature(context: Context): Pair<Int, String> {
            var n = 0
            var newest = 0L
            context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_LAST_UPDATED_TIMESTAMP), null, null, null)?.use { c ->
                while (c.moveToNext()) { n++; newest = maxOf(newest, c.getLong(0)) }
            }
            return n to "$n:$newest"
        }

        override fun pending(context: Context): Int {
            if (!granted(context, Manifest.permission.READ_CONTACTS)) return 0
            val (n, sig) = runCatching { signature(context) }.getOrElse { return 0 }
            return if (DeviceIdentity.prefs(context).getString("contacts_signature", "") == sig) 0 else n
        }

        override fun sync(context: Context, full: Boolean): Int {
            if (!granted(context, Manifest.permission.READ_CONTACTS)) throw IllegalStateException("Contacts permission not granted")
            val (_, sig) = signature(context)
            if (!full && DeviceIdentity.prefs(context).getString("contacts_signature", "") == sig) return 0
            var sent = 0
            val batch = JSONArray()
            fun post() {
                if (batch.length() == 0) return
                PhoneApi.request(context, "/api/v1/kiosk/gateway/contacts",
                    JSONObject().put("contacts", batch).put("partner_id", DeviceIdentity.partnerId(context)))
                sent += batch.length()
                while (batch.length() > 0) batch.remove(0)
            }
            context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    batch.put(JSONObject().put("name", c.getString(0) ?: "").put("phone", c.getString(1) ?: ""))
                    if (batch.length() >= 300) post()
                }
            }
            post()
            DeviceIdentity.prefs(context).edit().putString("contacts_signature", sig).apply()
            return sent
        }
    }

    val streams: List<SyncStream> = listOf(
        SmsStream("sms", "SMS", mpesa = false, firstDays = 30),
        SmsStream("mpesa", "M-Pesa", mpesa = true, firstDays = 365),
        calls,
        contacts,
    )

    /** Background incremental upload of the enabled streams (WebSocket reconnect / fetch_* events). */
    fun syncQuietly(context: Context, keys: List<String> = listOf("sms", "mpesa", "calls", "contacts")) {
        DeviceAgent.runInBackground {
            streams.filter { it.key in keys && DeviceAgent.streamEnabled(context, it.key) }.forEach { s ->
                runCatching { s.sync(context, false) }
            }
        }
    }
}
