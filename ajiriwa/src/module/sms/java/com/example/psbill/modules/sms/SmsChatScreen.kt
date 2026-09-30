package com.example.psbill.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.psbill.ui.theme.AjiriwaColors
import org.json.JSONObject

import androidx.compose.foundation.lazy.rememberLazyListState

@Composable
fun SmsChatScreen(
    sender: String,
    messages: List<JSONObject>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var conversationTab by remember { mutableStateOf("ALL") } // ALL, CHATS, CALLS
    val listState = rememberLazyListState()

    // Deduplicate and sort chronologically
    val dedupedMessages = remember(messages) {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<JSONObject>()
        for (m in messages) {
            val bodyText = m.optString("body").ifBlank { m.optString("text") }
            // Extract M-Pesa txid if present
            val txidMatch = Regex("""\b([A-Z0-9]{10})\b""").find(bodyText)?.groupValues?.get(1)
            val key = txidMatch ?: m.optString("id").ifBlank { bodyText.take(40) }

            if (key.isNotBlank()) {
                if (seen.add(key)) {
                    result.add(m)
                }
            } else {
                result.add(m)
            }
        }
        result
    }

    val chatMessages = remember(dedupedMessages) {
        dedupedMessages.filter { !(it.optBoolean("is_call", false) || it.optString("direction") == "CALL" || it.has("call_type")) }
    }
    val callLogs = remember(dedupedMessages) {
        dedupedMessages.filter { it.optBoolean("is_call", false) || it.optString("direction") == "CALL" || it.has("call_type") }
    }
    val filteredMessages = remember(dedupedMessages, conversationTab) {
        when (conversationTab) {
            "CHATS" -> chatMessages
            "CALLS" -> callLogs
            else -> dedupedMessages
        }
    }

    LaunchedEffect(filteredMessages.size, conversationTab) {
        if (filteredMessages.isNotEmpty()) {
            listState.scrollToItem(filteredMessages.size - 1)
        }
    }

    Column(modifier.fillMaxSize().background(AjiriwaColors.Surface)) {
        // Chat Header
        Surface(
            color = AjiriwaColors.Surface,
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = AjiriwaColors.Primary)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(sender, color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("${messages.size} total items", color = AjiriwaColors.TextSecondary, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Conversation Sub-Tabs
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "ALL" to "All (${messages.size})",
                        "CHATS" to "💬 Chats (${chatMessages.size})",
                        "CALLS" to "📞 Call Logs (${callLogs.size})"
                    ).forEach { (key, label) ->
                        val sel = conversationTab == key
                        Surface(
                            color = if (sel) AjiriwaColors.SecondaryContainer else AjiriwaColors.SurfaceAlt,
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier.clickable { conversationTab = key }
                        ) {
                            Text(
                                label,
                                color = if (sel) AjiriwaColors.OnSecondaryContainer else AjiriwaColors.TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }


        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(filteredMessages) { msg ->
                val isCall = msg.optBoolean("is_call", false) || msg.optString("direction") == "CALL" || msg.has("call_type")
                val body = msg.optString("body").ifBlank { msg.optString("text") }
                val date = msg.optLong("date", 0L)
                val timestampStr = msg.optString("timestamp")
                val receivedAt = msg.optString("received_at")
                val isMpesa = msg.optBoolean("is_mpesa", false)
                val mpesaAmt = msg.optDouble("mpesa_amount", 0.0)

                val dateStr = when {
                    date > 0 -> java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.US).format(java.util.Date(date))
                    timestampStr.isNotBlank() -> timestampStr.take(16).replace("T", " ")
                    receivedAt.isNotBlank() -> receivedAt.take(16).replace("T", " ")
                    else -> ""
                }

                if (isCall) {
                    val callType = msg.optString("call_type", "INCOMING").uppercase()
                    val duration = msg.optInt("duration_seconds", 0)
                    val durText = if (duration > 60) "${duration / 60}m ${duration % 60}s" else if (duration > 0) "${duration}s" else ""

                    val (icon, label, color) = when (callType) {
                        "INCOMING" -> Triple("📥 📞", "Incoming Call${if (durText.isNotBlank()) " ($durText)" else ""}", Color(0xFF00C853))
                        "OUTGOING" -> Triple("📤 📞", "Outgoing Call${if (durText.isNotBlank()) " ($durText)" else ""}", Color(0xFF29B6F6))
                        "MISSED" -> Triple("❌ 📞", "Missed Call", Color(0xFFEF5350))
                        "REJECTED" -> Triple("🚫 📞", "Rejected Call", Color(0xFFFF9800))
                        else -> Triple("📞", "Call Log${if (durText.isNotBlank()) " ($durText)" else ""}", AjiriwaColors.Primary)
                    }

                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Surface(
                            color = color.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(icon, fontSize = 12.sp)
                                Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                if (dateStr.isNotBlank()) {
                                    Text("• $dateStr", color = Color.Gray, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                } else {
                    val isOutbound = msg.optString("direction") == "OUTBOUND"
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = if (isOutbound) Alignment.End else Alignment.Start
                    ) {
                        Surface(
                            color = if (isOutbound) AjiriwaColors.Primary.copy(alpha = 0.2f) else Color(0xFF1E2638),
                            shape = if (isOutbound) RoundedCornerShape(12.dp, 12.dp, 0.dp, 12.dp) else RoundedCornerShape(12.dp, 12.dp, 12.dp, 0.dp),
                            modifier = Modifier.widthIn(max = 300.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                if (isMpesa) {
                                    Surface(
                                        color = Color(0xFF00C853).copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    ) {
                                        Text(
                                            "M-PESA ${if (mpesaAmt > 0) "KSh %.0f".format(mpesaAmt) else ""}".trim(),
                                            color = Color(0xFF00C853),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(body, color = AjiriwaColors.TextPrimary, fontSize = 14.sp)
                            }
                        }
                        Text(
                            dateStr,
                            color = Color.Gray,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
