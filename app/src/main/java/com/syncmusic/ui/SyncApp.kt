@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.syncmusic.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syncmusic.AppState
import com.syncmusic.Audio
import com.syncmusic.HostControls
import com.syncmusic.HostState
import com.syncmusic.JoinInfo
import com.syncmusic.ListenState
import com.syncmusic.ListenerInfo
import com.syncmusic.NowPlaying
import com.syncmusic.Peer
import com.syncmusic.Qr
import com.syncmusic.Reaction
import com.syncmusic.SongRequest
import com.syncmusic.UpdateOffer
import com.syncmusic.localIps
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class Actions(
    val savedIp: String,
    val startSharing: () -> Unit,
    val stopSharing: () -> Unit,
    val findHosts: () -> Unit,
    val connectPeer: (Peer) -> Unit,
    val connectIp: (String) -> Unit,
    val scanCode: () -> Unit,
    val rejoin: () -> Unit,
    val stopListening: () -> Unit,
    val allowMediaAccess: () -> Unit,
    val setSyncOffset: (Int) -> Unit,
    val startLocal: () -> Unit,
    val react: (String) -> Unit,
    val request: (String) -> Unit,
    val setVolume: (Int, Float) -> Unit,
    val kick: (Int) -> Unit,
    val dismissRequest: (Long) -> Unit,
    val setAutoComp: (Boolean) -> Unit,
    val allowBattery: () -> Unit,
    val installUpdate: (UpdateOffer) -> Unit,
)

private val EMOJIS = listOf("❤️", "🔥", "🙌", "😂", "🎉")

@Composable
fun SyncApp(actions: Actions) {
    val host by AppState.host.collectAsState()
    val listen by AppState.listen.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(if (AppState.listen.value.running) 1 else 0) }

    Box(Modifier.fillMaxSize()) {
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
                UpdateBanner(actions)
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
        ReactionOverlay()
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

@Composable
private fun UpdateBanner(a: Actions) {
    val peer by AppState.peerUpdate.collectAsState()
    val gh by AppState.githubUpdate.collectAsState()
    val progress by AppState.updateProgress.collectAsState()
    val offer = peer ?: gh ?: return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Palette.Sky.copy(alpha = 0.12f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("New version available", style = MaterialTheme.typography.titleLarge)
        Text(
            if (offer.from == "GitHub") "Build ${offer.version} is out on GitHub."
            else "${offer.from} has build ${offer.version}. Update straight from their phone, no internet needed.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        progress.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Coral) }
        if (progress.active) {
            LinearProgressIndicator(
                progress = { progress.percent / 100f },
                modifier = Modifier.fillMaxWidth(), color = Palette.Sky,
            )
        } else {
            Button(
                onClick = { a.installUpdate(offer) },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Sky, contentColor = Palette.Night),
                shape = RoundedCornerShape(14.dp),
            ) { Icon(Icons.Rounded.SystemUpdate, null); Spacer(Modifier.width(8.dp)); Text("Update") }
        }
    }
}

// ---------------- Share ----------------

@Composable
private fun ColumnScope.SharePane(host: HostState, a: Actions) {
    val myName by AppState.myName.collectAsState()
    val p2p by AppState.p2pMessage.collectAsState()
    val np by AppState.nowPlaying.collectAsState()
    val access by AppState.mediaAccess.collectAsState()
    val group by AppState.group.collectAsState()
    val requests by AppState.requests.collectAsState()
    val diag by AppState.hostDiag.collectAsState()
    var useLocal by rememberSaveable { mutableStateOf(false) }
    var ips by remember { mutableStateOf(localIps()) }
    LaunchedEffect(host.running, group) { delay(1500); ips = localIps() }

    Waveform(level = if (host.running) host.level else 0f, color = Palette.Coral)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            when {
                !host.running -> "Share what you're playing"
                host.local -> "Playing your songs"
                else -> "Sharing what's playing"
            },
            style = MaterialTheme.typography.displaySmall,
        )
        Text(
            when {
                !host.running && useLocal -> "Pick songs on this phone. Everyone, you included, hears them in step."
                !host.running -> "Play music in any app. Your friends hear it on their own headphones."
                host.listeners.isEmpty() -> "Waiting for someone to join. Ask them to scan your code."
                host.listeners.size == 1 -> "1 person is listening with you."
                else -> "${host.listeners.size} people are listening with you."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (!host.running) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !useLocal, onClick = { useLocal = false },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Any app") }
            SegmentedButton(
                selected = useLocal, onClick = { useLocal = true },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Songs on this phone") }
        }
    }

    if (host.local) {
        if (!np.isEmpty) NowPlayingCard(np, accent = Palette.Coral, controls = true)
    } else if (access) {
        if (!np.isEmpty) NowPlayingCard(np, accent = Palette.Coral, controls = true)
    } else if (!useLocal || host.running) {
        Notice(
            "Show the song on your friends' phones and control playback from here.",
            action = "Allow access", onAction = a.allowMediaAccess,
        )
    }

    if (host.running) {
        OutlinedButton(
            onClick = a.stopSharing,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp),
        ) { Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop sharing") }
    } else if (useLocal) {
        BigButton("Choose songs", Icons.Rounded.LibraryMusic, Palette.Coral, a.startLocal)
    } else {
        BigButton("Start sharing", Icons.Rounded.Podcasts, Palette.Coral, a.startSharing)
    }

    host.message?.let { Notice(it) }
    if (host.running) p2p?.let { Notice(it) }

    if (host.running) {
        if (requests.isNotEmpty()) RequestsCard(requests, a)
        if (host.listeners.isNotEmpty()) ListenersCard(host.listeners, a)
        ReactionBar(a.react)
        group?.let { QrCard(it.toQr()) }
        BrowserCard(group, ips)
    }

    DetailRows(
        listOf(
            Triple(Icons.Rounded.Smartphone, "Shows up as", myName ?: "Turn on Wi-Fi to see the name"),
            Triple(Icons.Rounded.Wifi, "IP address", ips.joinToString("\n").ifEmpty { "Not on a network" }),
        )
    )
    if (host.running) Diagnostics(diag)
    BatteryNotice(a)
    if (!host.running && !useLocal) Text(
        "Android asks to share your screen. Only the sound is sent, nothing on screen. " +
            "Tip: add the SyncMusic tile to Quick Settings to start sharing from anywhere.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RequestsCard(requests: List<SongRequest>, a: Actions) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 8.dp),
    ) {
        Text("Song requests", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        requests.forEach { r ->
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Avatar(Icons.Rounded.MusicNote, Palette.Coral) },
                overlineContent = { Text("${r.name} wants") },
                headlineContent = { Text(r.text, fontWeight = FontWeight.SemiBold) },
                trailingContent = {
                    IconButton(onClick = { a.dismissRequest(r.id) }) { Icon(Icons.Rounded.Close, "Dismiss") }
                },
            )
        }
    }
}

@Composable
private fun ListenersCard(list: List<ListenerInfo>, a: Actions) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Listening now", style = MaterialTheme.typography.titleLarge)
        list.forEach { l -> ListenerRow(l, a) }
    }
}

@Composable
private fun ListenerRow(l: ListenerInfo, a: Actions) {
    var vol by remember(l.id) { mutableFloatStateOf(l.volume) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(if (l.web) Icons.Rounded.Language else Icons.Rounded.Headphones, Palette.Sky)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(l.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (l.web) "In a browser" else "In the app", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { a.kick(l.id) }) { Icon(Icons.Rounded.PersonRemove, "Remove ${l.name}", tint = Palette.Mist) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.VolumeDown, null, tint = Palette.Mist, modifier = Modifier.size(18.dp))
            Slider(
                value = vol, onValueChange = { vol = it },
                onValueChangeFinished = { a.setVolume(l.id, vol) },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(thumbColor = Palette.Sky, activeTrackColor = Palette.Sky),
            )
            Text("${(vol * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = Palette.Mist)
        }
    }
}

@Composable
private fun QrCard(text: String) {
    val bmp = remember(text) { Qr.bitmap(text, dark = Palette.Night.toArgb(), light = Palette.Cloud.toArgb()) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(bmp.asImageBitmap(), "Join code", Modifier.size(132.dp).clip(RoundedCornerShape(14.dp)))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.QrCode2, null, tint = Palette.Coral)
            Text("Scan to join", style = MaterialTheme.typography.titleLarge)
            Text(
                "Friends with the app open Listen and tap Scan code.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BrowserCard(group: JoinInfo?, ips: List<String>) {
    var open by rememberSaveable { mutableStateOf(false) }
    val url = if (group != null) "http://192.168.49.1:${Audio.WEB_PORT}"
        else ips.firstOrNull { !it.startsWith("192.168.49.") }?.let { "http://$it:${Audio.WEB_PORT}" }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(Icons.Rounded.Language, Palette.Sky)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("No app? Use a browser", style = MaterialTheme.typography.titleLarge)
                Text("Works on iPhone too", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { open = !open }) {
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) "Hide" else "Show")
            }
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (url == null) {
                    Text("Turn on Wi-Fi to get a link.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (group != null) QrTile("1. Join Wi-Fi", group.toWifiQr(), Modifier.weight(1f))
                        QrTile(if (group != null) "2. Open page" else "Open page", url, Modifier.weight(1f))
                    }
                    Text(url, style = MaterialTheme.typography.titleMedium, color = Palette.Sky)
                    Text(
                        if (group != null) "Scan the first code with the camera to join this phone's Wi-Fi " +
                            "(${group.ssid}, password ${group.pass}), then the second to open the player. " +
                            "Friends on the same Wi-Fi as you can use the IP address below instead."
                        else "Friends on the same Wi-Fi open this link.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun QrTile(label: String, text: String, modifier: Modifier) {
    val bmp = remember(text) { Qr.bitmap(text, size = 480, dark = Palette.Night.toArgb(), light = Palette.Cloud.toArgb()) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Image(bmp.asImageBitmap(), label, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

// ---------------- Listen ----------------

@Composable
private fun ColumnScope.ListenPane(listen: ListenState, a: Actions) {
    val peers by AppState.peers.collectAsState()
    val searching by AppState.searching.collectAsState()
    val p2p by AppState.p2pMessage.collectAsState()
    val last by AppState.lastHost.collectAsState()
    val remote by AppState.remoteNow.collectAsState()
    val diag by AppState.listenDiag.collectAsState()
    var searched by rememberSaveable { mutableStateOf(false) }

    if (listen.running) {
        if (!remote.isEmpty) NowPlayingCard(remote, accent = Palette.Sky, controls = false, large = true)
        else Waveform(level = if (listen.playing) 0.7f else 0f, color = Palette.Sky)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (listen.playing) "Listening along" else "Joining…", style = MaterialTheme.typography.displaySmall)
            Text(
                listen.message ?: "Playing from ${listen.host} on ${listen.device?.lowercaseFirst() ?: "this phone"}.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (listen.playing) {
            ReactionBar(a.react)
            RequestField(a.request)
            SyncControl(listen, a)
            Diagnostics(diag)
        }
        OutlinedButton(
            onClick = a.stopListening,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp),
        ) { Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop listening") }
        BatteryNotice(a)
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Join a friend", style = MaterialTheme.typography.displaySmall)
        Text(
            "Connect your Bluetooth headphones, then scan the code on the phone that's sharing.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    BigButton("Scan code", Icons.Rounded.QrCodeScanner, Palette.Sky, a.scanCode)

    last?.let { j ->
        Card(
            onClick = a.rejoin,
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Avatar(Icons.Rounded.Refresh, Palette.Sky) },
                headlineContent = { Text("Rejoin ${j.name}", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Works when they're sharing again") },
            )
        }
    }

    OutlinedButton(
        onClick = { searched = true; a.findHosts() },
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp),
        enabled = !searching,
    ) {
        if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Icon(Icons.Rounded.Search, null)
        Spacer(Modifier.width(8.dp))
        Text(if (searching) "Searching nearby…" else "Find nearby phones")
    }

    if (peers.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            peers.forEach { p -> PeerCard(p) { a.connectPeer(p) } }
        }
    } else if (searched && !searching) {
        Notice("No phones found. Check your friend tapped Start sharing and has Wi-Fi on, then search again.")
    }
    p2p?.let { Notice(it) }

    IpFallback(a)
    BatteryNotice(a)
}

private fun String.lowercaseFirst() = if (startsWith("Bluetooth") || startsWith("USB")) this
    else replaceFirstChar { it.lowercase() }

@Composable
private fun RequestField(onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = { if (text.isNotBlank()) { onSend(text.trim()); text = "" } }
    OutlinedTextField(
        value = text, onValueChange = { if (it.length <= 120) text = it },
        label = { Text("Request a song") },
        placeholder = { Text("Mr. Brightside") },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send() }),
        trailingIcon = { IconButton(onClick = { send() }) { Icon(Icons.Rounded.Send, "Send request") } },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SyncControl(listen: ListenState, a: Actions) {
    val saved by AppState.syncOffsetMs.collectAsState()
    val auto by AppState.autoComp.collectAsState()
    var value by remember { mutableFloatStateOf(saved.toFloat()) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Delay", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text(
                listen.latencyMs?.let { "$it ms behind" } ?: "Measuring…",
                style = MaterialTheme.typography.bodyMedium, color = Palette.Sky,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Match headphone delay", fontWeight = FontWeight.Medium)
                Text(
                    listen.deviceLatencyMs?.let { "Detected $it ms on ${listen.device ?: "this output"}" }
                        ?: "This phone doesn't report it. Use the slider instead.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = auto, onCheckedChange = a.setAutoComp,
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.Sky, checkedThumbColor = Palette.Night),
            )
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { a.setSyncOffset(value.roundToInt()) },
            valueRange = 0f..400f,
            colors = SliderDefaults.colors(thumbColor = Palette.Sky, activeTrackColor = Palette.Sky),
        )
        Text(
            "Everyone on the same sharing phone plays in step. If this phone is still ahead of a friend's, " +
                "add delay here. Extra: ${value.roundToInt()} ms.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
                Avatar(if (p.hosting) Icons.Rounded.Podcasts else Icons.Rounded.Smartphone,
                    if (p.hosting) Palette.Coral else Palette.Mist)
            },
            headlineContent = { Text(p.name, fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(if (p.hosting) "Sharing music, tap to join" else "Nearby phone") },
        )
    }
}

@Composable
private fun ColumnScope.IpFallback(a: Actions) {
    var open by rememberSaveable { mutableStateOf(false) }
    var ip by rememberSaveable { mutableStateOf(a.savedIp) }
    TextButton(onClick = { open = !open }) {
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

@Composable
private fun ReactionBar(onReact: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        EMOJIS.forEach { e ->
            Box(
                Modifier.size(52.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onReact(e) },
                contentAlignment = Alignment.Center,
            ) { Text(e, fontSize = 24.sp) }
        }
    }
}

private class Floating(val id: Long, val r: Reaction, val x: Float)

@Composable
private fun ReactionOverlay() {
    val items = remember { mutableStateListOf<Floating>() }
    LaunchedEffect(Unit) {
        var n = 0L
        AppState.reactions.collect { r -> items.add(Floating(n++, r, 0.08f + 0.74f * Random.nextFloat())) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        items.toList().forEach { f ->
            key(f.id) { FloatingEmoji(f, w, h) { items.remove(f) } }
        }
    }
}

@Composable
private fun FloatingEmoji(f: Floating, w: Int, h: Int, onDone: () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        p.animateTo(1f, tween(2800, easing = LinearOutSlowInEasing))
        onDone()
    }
    Column(
        Modifier
            .offset { IntOffset((f.x * w).toInt(), (h * (0.86f - 0.6f * p.value)).toInt()) }
            .graphicsLayer { alpha = 1f - p.value * p.value },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(f.r.emoji, fontSize = 44.sp)
        Text(f.r.name, style = MaterialTheme.typography.labelSmall, color = Palette.Mist)
    }
}

@Composable
private fun NowPlayingCard(np: NowPlaying, accent: Color, controls: Boolean, large: Boolean = false) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val art = np.art
        if (large && art != null) {
            Image(
                art.asImageBitmap(), null,
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)),
                contentScale = ContentScale.Crop,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!large || art == null) {
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Raised),
                    contentAlignment = Alignment.Center,
                ) {
                    if (art != null) Image(art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(Icons.Rounded.MusicNote, null, tint = accent)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(np.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (np.artist.isNotBlank()) Text(
                    np.artist, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (controls) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { HostControls.previous() }) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(30.dp)) }
                FilledIconButton(
                    onClick = { HostControls.playPause() },
                    modifier = Modifier.size(60.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = Palette.Night),
                ) {
                    Icon(if (np.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (np.playing) "Pause" else "Play", Modifier.size(32.dp))
                }
                IconButton(onClick = { HostControls.next() }) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(30.dp)) }
            }
        }
    }
}

/** The signature element: a live bar waveform driven by the audio level. */
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
                val hh = (0.06f + lvl * (0.25f + 0.75f * wave)).coerceIn(0.06f, 1f)
                Box(
                    Modifier.weight(1f).fillMaxHeight(hh).clip(CircleShape)
                        .background(if (lvl < 0.02f) Palette.Line else color.copy(alpha = 0.45f + 0.55f * hh)),
                )
            }
        }
    }
}

@Composable
private fun Diagnostics(rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { open = !open }) {
            Icon(Icons.Rounded.Insights, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Connection details", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(open) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rows.forEach { (k, v) ->
                    Row {
                        Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f))
                        Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryNotice(a: Actions) {
    val ok by AppState.batteryOk.collectAsState()
    if (!ok) Notice("Keep SyncMusic playing when the screen is off: set SyncMusic to Unrestricted or Not optimized.", action = "Open", onAction = a.allowBattery)
}

@Composable
private fun BigButton(text: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Palette.Night),
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(10.dp))
        Text(text)
    }
}

@Composable
private fun Avatar(icon: ImageVector, tint: Color) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint) }
}

@Composable
private fun Notice(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Info, null, tint = Palette.Mist, modifier = Modifier.size(18.dp))
        Text(
            text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        if (action != null) TextButton(onClick = onAction) { Text(action, color = Palette.Cloud) }
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
