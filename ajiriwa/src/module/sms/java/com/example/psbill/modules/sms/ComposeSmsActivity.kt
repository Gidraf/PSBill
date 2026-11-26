package com.example.psbill

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import kotlinx.coroutines.flow.MutableStateFlow

/** A "write an SMS to X" request from another app, waiting for the SMS screen to open that chat. */
object SmsCompose {
    val pending = MutableStateFlow<Pair<String, String>?>(null)

    /** Number + text from sms:/smsto: links, Contacts' "message" button, share sheets. */
    fun parse(i: Intent?): Pair<String, String>? {
        i ?: return null
        val data: Uri? = i.data
        var number = data?.schemeSpecificPart.orEmpty().removePrefix("//").substringBefore('?')
            .split(',', ';').firstOrNull().orEmpty().let { Uri.decode(it) }.trim()
        var body = i.getStringExtra(Intent.EXTRA_TEXT) ?: i.getStringExtra("sms_body")
            ?: data?.schemeSpecificPart?.substringAfter("?body=", "")?.let { Uri.decode(it) }.orEmpty()
        if (number.isBlank()) number = i.getStringExtra("address").orEmpty()
        body = body.trim()
        if (number.isBlank() && body.isBlank()) return null
        return number to body
    }

    fun openApp(context: Context) {
        context.startActivity(Intent().setClassName(context.packageName, "com.example.psbill.MainActivity")
            .putExtra("open_screen", "sms")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}

/**
 * Default SMS app: "send an SMS" from anywhere on the phone (Contacts, the dialler, a link) opens the chat with that
 * number inside Ajiriwa, so staff never need another messenger (owner 2026-11-26).
 */
class ComposeSmsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SmsCompose.parse(intent)?.let { SmsCompose.pending.value = it }
        com.example.psbill.core.ActivityLog.log("sms_compose_intent", context = this)
        SmsCompose.openApp(this)
        finish()
    }
}
