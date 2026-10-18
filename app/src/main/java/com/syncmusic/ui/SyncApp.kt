@file:OptIn(ExperimentalMaterial3Api::class)

package com.syncmusic.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.syncmusic.AppState
import com.syncmusic.HostState
import com.syncmusic.ListenState
import com.syncmusic.Peer
import com.syncmusic.localIps
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

class Actions(
    val savedIp: String,
    val startSharing: () -> Unit,
    val stopSharing: () -> Unit,
    val findHosts: () -> Unit,
    val connectPeer: (Peer) -> Unit,
    val connectIp: (String) -> Unit,
    val stopListening: () -> Unit,
)

@Composable
fun SyncApp(actions: Actions) {
    val host by AppState.host.collectAsState()
    val listen by AppState.listen.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(if (AppState.listen.value.running) 1 else 0) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Header()
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = tab == 0, onClick = { tab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    colors = SegmentedButtonDefaults.colors(activeContainerColor = Palette.Coral.copy(alpha = 0.18f), activeContentColor = Palette.Coral),
                    icon = { Icon(Icons.Rounded.Podcasts, null, Modifier.size(18.dp)) },
                ) { Text("Share") }
                SegmentedButton(
                    selected = tab == 1, onClick = { tab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    colors = SegmentedButtonDefaults.colors(activeContainerColor = Palette.Sky.copy(alpha = 0.18f), activeContentColor = Palette.Sky),
                    icon = { Icon(Icons.Rounded.Headphones, null, Modifier.size(18.dp)) },
                ) { Text("Listen") }
            }
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                label = "tab",
            ) { t ->
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (t == 0) SharePane(host, actions) else ListenPane(listen, actions)
                }
            }
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(10, 20, 14, 6).forEachIndexed { i, h ->
                Box(Modifier.width(5.dp).height(h.dp).clip(CircleShape)
                    .background(if (i < 2) Palette.Coral else Palette.Sky))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text("SyncMusic", style = MaterialTheme.typography.headlineSmall)
    }
}

// ---------------- Share ----------------

@Composable
private fun ColumnScope.SharePane(host: HostState, a: Actions) {
    val myName by AppState.myName.collectAsState()
    val p2p by AppState.p2pMessage.collectAsState()
    var ips by remember { mutableStateOf(localIps()) }
    LaunchedEffect(host.running) { delay(1500); ips = localIps() }

    Waveform(level = if (host.running) host.level else 0f, color = Palette.Coral)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (host.running) "Sharing what's playing" else "Share what you're playing",
            style = MaterialTheme.typography.displaySmall,
        )
        Text(
            when {
                !host.running -> "Play music in any app. Your friend hears it on their own headphones."
                host.listeners == 0 -> "Waiting for someone to join. Ask them to open Listen."
                host.listeners == 1 -> "1 person is listening with you."
                else -> "${host.listeners} people are listening with you."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (host.running) {
        OutlinedButton(
            onClick = a.stopSharing,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop sharing")
        }
    } else {
        BigButton("Start sharing", Icons.Rounded.Podcasts, Palette.Coral, a.startSharing)
    }

    host.message?.let { Notice(it) }

    DetailRows(
        listOf(
            Triple(Icons.Rounded.Smartphone, "Shows up as", myName ?: "Turn on Wi-Fi to see the name"),
            Triple(Icons.Rounded.Wifi, "IP address", ips.joinToString("\n").ifEmpty { "Not on a network" }),
        )
    )
    if (host.running) p2p?.let { Notice(it) }
    if (!host.running) Text(
        "Android asks to share your screen. Only the sound is sent, nothing on screen.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---------------- Listen ----------------

@Composable
private fun ColumnScope.ListenPane(listen: ListenState, a: Actions) {
    val peers by AppState.peers.collectAsState()
    val searching by AppState.searching.collectAsState()
    val p2p by AppState.p2pMessage.collectAsState()
    var searched by rememberSaveable { mutableStateOf(false) }

    if (listen.running) {
        Waveform(level = if (listen.playing) 0.7f else 0f, color = Palette.Sky)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (listen.playing) "Listening along" else "Joining…", style = MaterialTheme.typography.displaySmall)
            Text(
                listen.message ?: "Playing from ${listen.host} on this phone's headphones.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = a.stopListening,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop listening")
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Join a friend", style = MaterialTheme.typography.displaySmall)
        Text(
            "Connect your Bluetooth headphones, then find the phone that's sharing.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    BigButton(
        if (searching) "Searching nearby…" else "Find nearby phones",
        Icons.Rounded.Search, Palette.Sky,
        onClick = { searched = true; a.findHosts() },
        busy = searching,
    )

    if (peers.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            peers.forEach { PeerCard(it) { a.connectPeer(it) } }
        }
    } else if (searched && !searching) {
        Notice("No phones found. Make sure your friend tapped Start sharing and has Wi-Fi on, then search again.")
    }
    p2p?.let { Notice(it) }

    IpFallback(a)
}

@Composable
private fun PeerCard(p: Peer, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .background(if (p.hosting) Palette.Coral.copy(alpha = 0.18f) else Palette.Raised),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (p.hosting) Icons.Rounded.Podcasts else Icons.Rounded.Smartphone, null,
                        tint = if (p.hosting) Palette.Coral else Palette.Mist,
                    )
                }
            },
            headlineContent = { Text(p.name, fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                Text(if (p.hosting) "Sharing music, tap to join" else "Nearby phone")
            },
        )
    }
}

@Composable
private fun ColumnScope.IpFallback(a: Actions) {
    var open by rememberSaveable { mutableStateOf(false) }
    var ip by rememberSaveable { mutableStateOf(a.savedIp) }
    TextButton(onClick = { open = !open }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
        Text("Same Wi-Fi? Connect by IP", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    AnimatedVisibility(open) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = ip, onValueChange = { ip = it },
                label = { Text("Sharing phone's IP") },
                placeholder = { Text("192.168.1.23") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { if (ip.isNotBlank()) a.connectIp(ip.trim()) },
                enabled = ip.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Connect") }
        }
    }
}

// ---------------- shared pieces ----------------

/** The signature element: a live bar waveform driven by the captured audio level. */
@Composable
private fun Waveform(level: Float, color: Color) {
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "phase",
    )
    val lvl by animateFloatAsState(level.coerceIn(0f, 1f), tween(140), label = "level")
    Box(
        Modifier.fillMaxWidth().height(148.dp).clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(32) { i ->
                val wave = (sin(phase + i * 0.5f) * sin(phase * 0.5f + i * 0.21f) + 1f) / 2f
                val h = (0.06f + lvl * (0.25f + 0.75f * wave)).coerceIn(0.06f, 1f)
                Box(
                    Modifier.weight(1f).fillMaxHeight(h).clip(CircleShape)
                        .background(if (lvl < 0.02f) Palette.Line else color.copy(alpha = 0.45f + 0.55f * h)),
                )
            }
        }
    }
}

@Composable
private fun BigButton(text: String, icon: ImageVector, color: Color, onClick: () -> Unit, busy: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color, contentColor = Palette.Night,
            disabledContainerColor = color.copy(alpha = 0.35f), disabledContentColor = Palette.Night,
        ),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Palette.Night, strokeWidth = 2.5.dp)
        else Icon(icon, null)
        Spacer(Modifier.width(10.dp))
        Text(text)
    }
}

@Composable
private fun Notice(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Info, null, tint = Palette.Mist, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DetailRows(rows: List<Triple<ImageVector, String, String>>) {
    Column {
        rows.forEachIndexed { i, (icon, label, value) ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(icon, null, tint = Palette.Mist) },
                overlineContent = { Text(label) },
                headlineContent = { Text(value, fontWeight = FontWeight.Medium) },
            )
        }
    }
}
