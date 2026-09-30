package com.example.psbill.modules.marketing

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.psbill.core.FeatureModule
import com.example.psbill.core.ModuleContext
import com.example.psbill.core.NavModule
import com.example.psbill.ui.theme.AjiriwaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Marketing: send an SMS campaign to customers. Messages go into the server's
 * SMS queue and are delivered by the partner's SMS-engine phone(s).
 */
object MarketingFeature : FeatureModule() {
    override val slug = "marketing"
    override val nav = NavModule("marketing", "Marketing", Icons.Filled.Send, listOf("marketing"), order = 35)

    private val http by lazy { OkHttpClient() }

    private data class Contact(val name: String, val phone: String)

    @Composable
    override fun Content(ctx: ModuleContext) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
        val selected = remember { mutableStateListOf<String>() }
        var message by remember { mutableStateOf("") }
        var loading by remember { mutableStateOf(true) }
        var sending by remember { mutableStateOf(false) }
        var filter by remember { mutableStateOf("") }

        LaunchedEffect(ctx.partnerId) {
            loading = true
            contacts = runCatching {
                withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url("${ctx.baseUrl}/api/v1/all-whatsapp-users?page=1&limit=500").headers(ctx.headers()).build())
                        .execute().use { r ->
                            val body = JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
                            val arr = body.optJSONArray("data") ?: body.optJSONArray("results") ?: JSONArray()
                            (0 until arr.length()).mapNotNull { i ->
                                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                                val phone = c.optString("phone").ifBlank { c.optString("whatsappNumber").ifBlank { c.optString("phone_number") } }
                                if (phone.isBlank()) null else Contact(c.optString("name").ifBlank { c.optString("whatsappName", phone) }, phone)
                            }.distinctBy { it.phone }
                        }
                }
            }.getOrElse { emptyList() }
            loading = false
        }

        val visible = contacts.filter { filter.isBlank() || it.name.contains(filter, true) || it.phone.contains(filter) }
        val parts = if (message.length <= 160) 1 else (message.length + 152) / 153

        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("SMS campaign", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            OutlinedTextField(
                value = message, onValueChange = { message = it }, minLines = 3,
                label = { Text("Message") }, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("${message.length} chars · $parts SMS each · ${selected.size} recipient(s)") },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = AjiriwaColors.TextPrimary, unfocusedTextColor = AjiriwaColors.TextPrimary),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(filter, { filter = it }, placeholder = { Text("Filter customers") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { selected.clear(); selected.addAll(visible.map { it.phone }) }) { Text("All") }
                TextButton(onClick = { selected.clear() }) { Text("None") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f)) {
                items(visible, key = { it.phone }) { c ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                            if (c.phone in selected) selected.remove(c.phone) else selected.add(c.phone)
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = c.phone in selected, onCheckedChange = null)
                        Column {
                            Text(c.name, color = AjiriwaColors.TextPrimary, fontSize = 14.sp)
                            Text(c.phone, color = AjiriwaColors.TextMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
            Button(
                enabled = !sending && message.isNotBlank() && selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AjiriwaColors.PrimaryAccent),
                onClick = {
                    sending = true
                    scope.launch {
                        val ok = runCatching {
                            withContext(Dispatchers.IO) {
                                val body = JSONObject().put("message", message.trim()).put("phone_numbers", JSONArray(selected.toList()))
                                http.newCall(
                                    Request.Builder().url("${ctx.baseUrl}/api/v1/kiosk/sms/send").headers(ctx.headers())
                                        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                                ).execute().use { it.isSuccessful }
                            }
                        }.getOrDefault(false)
                        Toast.makeText(context, if (ok) "Queued ${selected.size} SMS" else "Could not queue the campaign", Toast.LENGTH_LONG).show()
                        if (ok) { message = ""; selected.clear() }
                        sending = false
                    }
                },
            ) { Text(if (sending) "Queuing…" else "Send to ${selected.size}") }
            Text(
                "Messages are delivered by the phone running the SMS engine, paced to avoid carrier blocks.",
                color = AjiriwaColors.TextMuted, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().background(Color.Transparent),
            )
        }
    }
}
