package com.example.psbill.ui.screens

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.psbill.PrinterDiscovery
import com.example.psbill.core.AndroidPrintHelper
import com.example.psbill.ui.components.SectionCard
import com.example.psbill.ui.components.SectionTitle
import com.example.psbill.ui.theme.AjiriwaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket


/**
 * PrintingScreen — ESC/POS over TCP (port 9100).
 *
 * Discovers and connects to an Epson TM or compatible receipt printer
 * on the local WiFi network.
 * - Auto-discovery using mDNS (NsdManager)
 * - User enters printer IP (saved to SharedPreferences)
 * - "Test Print" sends an ESC/POS init + test message via TCP socket
 */
@Composable
fun PrintingScreen(
    server: String,
    printerIp: String,
    onPrinterIpChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var ipField by remember(printerIp) { mutableStateOf(printerIp) }
    var connectionStatus by remember { mutableStateOf("Not connected") }
    var isConnecting by remember { mutableStateOf(false) }
    var isConnected by remember { mutableStateOf(false) }

    // Discovery state
    var isDiscovering by remember { mutableStateOf(false) }
    val discoveredPrinters = remember { mutableStateListOf<Pair<String, String>>() }

    val discovery = remember {
        PrinterDiscovery(context) { name, host ->
            if (discoveredPrinters.none { it.second == host }) {
                discoveredPrinters.add(name to host)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            discovery.stopDiscovery()
        }
    }

    // ── ESC/POS helpers ───────────────────────────────────────────────────────
    val ESC = 0x1B.toByte()
    val GS = 0x1D.toByte()
    val LF = 0x0A.toByte()

    fun escInit(): ByteArray = byteArrayOf(ESC, 0x40) // ESC @ — Initialize
    fun escBold(on: Boolean): ByteArray = byteArrayOf(ESC, 0x45, if (on) 1 else 0)
    fun escAlign(align: Int): ByteArray = byteArrayOf(ESC, 0x61, align.toByte()) // 0=L,1=C,2=R
    fun escLf(n: Int = 3): ByteArray = ByteArray(n) { LF }
    fun escCut(): ByteArray = byteArrayOf(GS, 0x56, 0x41, 0x10) // Full cut

    fun buildText(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    fun buildTestPrint(): ByteArray {
        return escInit() +
            escAlign(1) +  // center
            escBold(true) +
            buildText("AJIRIWA CLIENT\n") +
            escBold(false) +
            buildText("========================\n") +
            buildText("Printer Test OK\n") +
            buildText("========================\n") +
            escAlign(0) +  // left
            buildText("Connected & Ready\n") +
            escLf() +
            escCut()
    }

    suspend fun testPrintTcp(ip: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, 9100), 5000)
                val out: OutputStream = socket.getOutputStream()
                out.write(buildTestPrint())
                out.flush()
                socket.close()
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("Printing", "ESC/POS Receipt Printer")
        Spacer(Modifier.height(16.dp))

        // Printer IP config
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Printer Configuration", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (isDiscovering) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = AjiriwaColors.Primary, strokeWidth = 2.dp)
                    }
                }
                
                Text("Search for Epson printers on your network or enter the IP manually.", color = AjiriwaColors.TextSecondary, fontSize = 12.sp)
                
                Button(
                    onClick = {
                        isDiscovering = true
                        discoveredPrinters.clear()
                        discovery.startDiscovery()
                        scope.launch {
                            delay(15000) // Search for 15 seconds
                            isDiscovering = false
                            discovery.stopDiscovery()
                        }
                    },
                    enabled = !isDiscovering,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2638)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isDiscovering) "Searching..." else "🔍 Auto-Discover Printers", color = AjiriwaColors.Primary)
                }

                if (!isDiscovering && discoveredPrinters.isEmpty()) {
                    Surface(
                        color = Color(0xFFFFB74D).copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFFFB74D).copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Printer not found?", color = Color(0xFFFFB74D), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("For L3250 series:", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                            Text("1. Hold the [Wi-Fi] button until the indicators flash.", color = Color.Gray, fontSize = 11.sp)
                            Text("2. Ensure your phone is on the same 2.4GHz Wi-Fi network.", color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                }

                if (discoveredPrinters.isNotEmpty()) {
                    Text("Discovered Printers:", color = AjiriwaColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    discoveredPrinters.forEach { (name, host) ->
                        Surface(
                            color = Color(0xFF0B0F19),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().clickable {
                                ipField = host
                                onPrinterIpChange(host)
                                isConnected = false
                                connectionStatus = "Printer selected: $name"
                            }
                        ) {
                            Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(name, color = Color.White, fontSize = 13.sp)
                                Text(host, color = AjiriwaColors.Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                OutlinedTextField(
                    value = ipField,
                    onValueChange = { ipField = it },
                    label = { Text("Printer IP (e.g. 192.168.1.100)", color = Color.Gray) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AjiriwaColors.Primary,
                        focusedTextColor = AjiriwaColors.TextPrimary,
                        unfocusedTextColor = AjiriwaColors.TextPrimary
                    )
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onPrinterIpChange(ipField)
                            Toast.makeText(context, "Printer IP saved: $ipField", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AjiriwaColors.Primary),
                        modifier = Modifier.weight(0.8f)
                    ) { Text("Save", color = Color.Black, fontWeight = FontWeight.Bold) }

                    Button(
                        onClick = {
                            isConnecting = true
                            scope.launch {
                                val ok = testPrintTcp(ipField.trim())
                                isConnecting = false
                                isConnected = ok
                                if (ok) {
                                    connectionStatus = "✅ Connected — Thermal Test printed!"
                                } else {
                                    connectionStatus = "❌ Thermal connection failed. Trying System Mode..."
                                    AndroidPrintHelper.printTestPage(context)
                                }
                            }
                        },
                        enabled = ipField.isNotBlank() && !isConnecting,
                        colors = ButtonDefaults.buttonColors(containerColor = if (isConnected) Color(0xFF00C853) else Color(0xFF0D47A1)),
                        modifier = Modifier.weight(1.2f)
                    ) {
                        if (isConnecting) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                        else Text("Test Print", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = {
                        AndroidPrintHelper.printTestPage(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🖨️ Print via Android System (For L3250)", color = Color.White)
                }

                if (connectionStatus.isNotBlank()) {
                    Text(
                        connectionStatus,
                        color = if (isConnected) Color(0xFF00C853) else Color(0xFFB71C1C),
                        fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Print actions
        Text("Print Actions", color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))

        val printActions = listOf(
            "🧾 Latest Order Receipt" to "Print a receipt for the most recently delivered order",
            "📶 WiFi Voucher Codes" to "Print a batch of generated WiFi voucher codes",
            "📊 Daily Summary" to "Print today's orders and revenue summary",
        )

        printActions.forEach { (title, desc) ->
            SectionCard(modifier = Modifier.padding(bottom = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(title, color = AjiriwaColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(desc, color = AjiriwaColors.TextSecondary, fontSize = 11.sp)
                    }
                    TextButton(
                        onClick = {
                            if (!isConnected && ipField.isBlank()) {
                                Toast.makeText(context, "Connect to a printer first", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Opening Print Spooler...", Toast.LENGTH_SHORT).show()
                                AndroidPrintHelper.printTestPage(context) // Placeholder for actual content
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Build, contentDescription = null, tint = AjiriwaColors.Primary)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Surface(
            color = AjiriwaColors.Surface,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "📡 Uses ESC/POS over TCP port 9100. Compatible with Epson TM series (TM-T82, TM-T88, TM-U220) and most network receipt printers.",
                color = AjiriwaColors.TextSecondary, fontSize = 11.sp,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}
