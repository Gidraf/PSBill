package com.example.psbill

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lock screen palette: one place so the screen reads as one design. */
object LockColors {
    val Text = Color(0xFFF8FAFC)
    val Muted = Color(0xFF94A3B8)
    val Faint = Color(0xFF64748B)
    val Panel = Color(0xCC0F172A)
    val PanelBorder = Color(0xFF1E293B)
    val Accent = Color(0xFF22D3EE)
    val Go = Color(0xFF22C55E)
    val Warn = Color(0xFFF59E0B)
    val Stop = Color(0xFFEF4444)
}

@Composable
private fun Pill(text: String, color: Color, dot: Boolean = true) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        if (dot) Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
fun LockClock() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            kotlinx.coroutines.delay(15_000)
        }
    }
    Column(horizontalAlignment = Alignment.End) {
        Text(
            SimpleDateFormat("HH:mm", Locale.US).format(now),
            color = LockColors.Text, fontSize = 34.sp, fontWeight = FontWeight.Light
        )
        Text(
            SimpleDateFormat("EEE d MMM", Locale.US).format(now).uppercase(Locale.US),
            color = LockColors.Muted, fontSize = 12.sp, letterSpacing = 2.sp
        )
    }
}

@Composable
fun LockHeader(
    arcadeName: String,
    deviceName: String,
    hdmiConnected: Boolean,
    sessionEnded: Boolean,
    isAdminLoggedIn: Boolean,
    onAdminClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = arcadeName.ifBlank { "Gaming Lounge" },
                color = LockColors.Text,
                fontSize = 34.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    deviceName.ifBlank { "Screen" }.uppercase(Locale.US),
                    color = LockColors.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                )
                if (sessionEnded) Pill("SESSION ENDED", LockColors.Stop)
                else Pill("READY TO PLAY", LockColors.Go)
                if (!hdmiConnected) Pill("CONSOLE NOT CONNECTED", LockColors.Warn)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            LockClock()
            AdminChip(isAdminLoggedIn = isAdminLoggedIn, onClick = onAdminClick)
        }
    }
}

/** Small, quiet staff entry: players should not be drawn to it, staff can still focus it. */
@Composable
private fun AdminChip(isAdminLoggedIn: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent {
                if (it.type == KeyEventType.KeyUp &&
                    (it.key == Key.DirectionCenter || it.key == Key.Enter || it.key == Key.NumPadEnter)
                ) { onClick(); true } else false
            }
            .background(if (focused) Color.White else Color.Transparent, RoundedCornerShape(50))
            .border(1.dp, if (focused) Color.White else LockColors.Faint, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            if (isAdminLoggedIn) "STAFF LOGOUT" else "STAFF",
            color = if (focused) Color.Black else LockColors.Muted,
            fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
        )
    }
}

@Composable
fun ReadyToPlayContent(
    games: List<JSONObject>,
    isLoading: Boolean,
    queueTickets: List<JSONObject>,
    billingMode: String,
    onGameSelect: (String) -> Unit
) {
    val firstCard = remember { FocusRequester() }
    LaunchedEffect(games.size) {
        if (games.isNotEmpty()) runCatching { firstCard.requestFocus() }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "PICK A GAME",
                color = LockColors.Text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp
            )
            Text(
                if (billingMode.equals("PREPAID", true))
                    "Choose with the arrows, press OK, then pay the attendant or on the WiFi page."
                else
                    "Choose with the arrows and press OK. You pay for the time you play.",
                color = LockColors.Muted, fontSize = 15.sp
            )
            when {
                isLoading -> Box(Modifier.fillMaxWidth().height(290.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LockColors.Accent, modifier = Modifier.size(44.dp))
                }
                games.isEmpty() -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(290.dp)
                        .background(LockColors.Panel, RoundedCornerShape(18.dp))
                        .border(1.dp, LockColors.PanelBorder, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Games are being set up", color = LockColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("Ask the attendant to start your session.", color = LockColors.Muted, fontSize = 15.sp)
                    }
                }
                else -> LazyRow(
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    items(games.size) { i ->
                        val game = games[i]
                        Box(if (i == 0) Modifier.focusRequester(firstCard) else Modifier) {
                            GameCard(
                                game = game,
                                onClick = {
                                    val name = game.cleanStr("game_name")
                                    if (name.isNotEmpty()) onGameSelect(name)
                                }
                            )
                        }
                    }
                }
            }
        }
        QueuePanel(queueTickets)
    }
}

@Composable
private fun QueuePanel(queueTickets: List<JSONObject>) {
    Column(
        modifier = Modifier
            .width(300.dp)
            .height(360.dp)
            .background(LockColors.Panel, RoundedCornerShape(18.dp))
            .border(1.dp, LockColors.PanelBorder, RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("NEXT UP", color = LockColors.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        if (queueTickets.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No one waiting", color = LockColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("This screen is free now", color = LockColors.Muted, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                items(queueTickets) { ticket -> QueueTicketRow(ticket) }
            }
        }
    }
}

@Composable
fun LockFooter(macAddress: String, ipAddress: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Arrows to choose  -  OK to select",
            color = LockColors.Muted, fontSize = 14.sp
        )
        Text(
            "$ipAddress  ·  $macAddress",
            color = LockColors.Faint, fontSize = 11.sp
        )
    }
}

/** Shown instead of the games while a mandatory update is not installed (no new sessions). */
@Composable
fun UpdateRequiredPanel(st: UpdateState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(st.ready) { runCatching { focus.requestFocus() } }
    Column(
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .background(LockColors.Panel, RoundedCornerShape(20.dp))
            .border(1.dp, LockColors.Warn.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("UPDATING THIS SCREEN", color = LockColors.Warn, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        Text(
            "Version ${st.versionName} is required before the next game.",
            color = LockColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold
        )
        val msg = when {
            st.installing -> "Installing... press OK on the confirm screen if it appears."
            st.ready && !AppUpdater.canInstall(context) -> "Allow this app to install updates, then press OK again."
            st.ready -> "Ready to install. Press OK."
            st.downloading -> "Downloading... ${st.progress}%"
            else -> "Getting the update..."
        }
        Text(msg, color = LockColors.Muted, fontSize = 16.sp)
        if (st.downloading) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { st.progress / 100f },
                color = LockColors.Accent,
                modifier = Modifier.fillMaxWidth().height(8.dp)
            )
        }
        st.error?.let { Text(it, color = LockColors.Stop, fontSize = 14.sp) }
        Box(
            modifier = Modifier
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .onKeyEvent {
                    if (it.type == KeyEventType.KeyUp &&
                        (it.key == Key.DirectionCenter || it.key == Key.Enter || it.key == Key.NumPadEnter)
                    ) {
                        when {
                            st.ready && !AppUpdater.canInstall(context) ->
                                runCatching { context.startActivity(AppUpdater.allowInstallsIntent(context).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            st.ready -> AppUpdater.install(context)
                            !st.downloading -> AppUpdater.download(context)
                        }
                        true
                    } else false
                }
                .background(if (focused) LockColors.Accent else LockColors.PanelBorder, RoundedCornerShape(50))
                .padding(horizontal = 28.dp, vertical = 10.dp)
        ) {
            Text(
                if (st.ready) "INSTALL NOW" else "RETRY DOWNLOAD",
                color = if (focused) Color.Black else LockColors.Text,
                fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
            )
        }
    }
}

/**
 * Shown by KioskService while the screen is put to sleep from the dashboard / app.
 * It used to be a plain black box, which looked exactly like a broken TV. Now: a dim
 * clock and how to wake it, drifting a little every minute so nothing burns in.
 */
@Composable
fun SleepScreen(arcadeName: String, deviceName: String) {
    var now by remember { mutableStateOf(Date()) }
    var spot by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            now = Date()
            spot = (spot + 1) % SLEEP_SPOTS.size
        }
    }
    val (ax, ay) = SLEEP_SPOTS[spot]
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.align(androidx.compose.ui.BiasAlignment(ax, ay)).padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                SimpleDateFormat("HH:mm", Locale.US).format(now),
                color = Color(0xFF475569), fontSize = 64.sp, fontWeight = FontWeight.Light
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "SCREEN SLEEPING", color = Color(0xFF64748B), fontSize = 14.sp,
                letterSpacing = 4.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Ask the attendant to wake it from the dashboard or the PlayGate app",
                color = Color(0xFF334155), fontSize = 12.sp
            )
            val who = listOf(arcadeName, deviceName).filter { it.isNotBlank() }.joinToString("  ·  ")
            if (who.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(who, color = Color(0xFF334155), fontSize = 11.sp)
            }
        }
    }
}

private val SLEEP_SPOTS = listOf(
    0f to 0f, -0.5f to -0.5f, 0.5f to 0.4f, -0.4f to 0.5f, 0.5f to -0.4f, 0f to 0.6f, 0f to -0.6f
)
