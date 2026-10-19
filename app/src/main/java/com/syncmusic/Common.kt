package com.syncmusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

object Audio {
    const val PORT = 5050
    const val WEB_PORT = 8080
    const val SAMPLE_RATE = 48000
    const val BYTES_PER_FRAME = 4                        // stereo, 16-bit
    const val CHUNK_FRAMES = 960                         // 20 ms
    const val CHUNK = CHUNK_FRAMES * BYTES_PER_FRAME
    /** Every listener aims to hear each sound this many ms after the host captured it. */
    const val BASE_LATENCY_MS = 160
    val MAGIC = "SYNC2".toByteArray()
}

object Act {
    const val STOP = "com.syncmusic.STOP"
    const val PLAYPAUSE = "com.syncmusic.PLAYPAUSE"
    const val NEXT = "com.syncmusic.NEXT"
    const val PREV = "com.syncmusic.PREV"
}

data class NowPlaying(
    val title: String = "",
    val artist: String = "",
    val art: Bitmap? = null,
    val playing: Boolean = false,
) {
    val isEmpty get() = title.isBlank()
}

data class ListenerInfo(val id: Int, val name: String, val web: Boolean, val volume: Float)

data class HostState(
    val running: Boolean = false,
    val local: Boolean = false,
    val listeners: List<ListenerInfo> = emptyList(),
    val level: Float = 0f,
    val message: String? = null,
)

data class ListenState(
    val running: Boolean = false,
    val playing: Boolean = false,
    val host: String = "",
    val message: String? = null,
    val latencyMs: Int? = null,
    val deviceLatencyMs: Int? = null,
    val device: String? = null,
)

data class Reaction(val name: String, val emoji: String)
data class SongRequest(val id: Long, val name: String, val text: String)
data class UpdateOffer(val version: Long, val url: String, val from: String)
data class UpdateProgress(val active: Boolean = false, val percent: Int = 0, val error: String? = null)

/** What a listener needs to join a host's Wi-Fi Direct group without discovery. */
data class JoinInfo(val name: String, val ssid: String, val pass: String) {
    fun toQr() = listOf("SYNCMUSIC", "1", name.replace("|", " "), ssid, pass).joinToString("|")

    fun toWifiQr(): String {
        fun esc(s: String) = buildString {
            for (ch in s) { if (ch in "\\;,:\"") append('\\'); append(ch) }
        }
        return "WIFI:T:WPA;S:${esc(ssid)};P:${esc(pass)};;"
    }

    companion object {
        fun fromQr(s: String): JoinInfo? {
            val p = s.split("|")
            return if (p.size >= 5 && p[0] == "SYNCMUSIC") JoinInfo(p[2], p[3], p[4]) else null
        }
    }
}

data class Peer(val name: String, val address: String, val hosting: Boolean, val connected: Boolean)

object AppState {
    val host = MutableStateFlow(HostState())
    val listen = MutableStateFlow(ListenState())
    val peers = MutableStateFlow<List<Peer>>(emptyList())
    val searching = MutableStateFlow(false)
    val myName = MutableStateFlow<String?>(null)
    val p2pMessage = MutableStateFlow<String?>(null)
    val nowPlaying = MutableStateFlow(NowPlaying())   // on the host
    val remoteNow = MutableStateFlow(NowPlaying())    // on a listener
    val mediaAccess = MutableStateFlow(false)
    val group = MutableStateFlow<JoinInfo?>(null)     // host's own group, for the QR codes
    val lastHost = MutableStateFlow<JoinInfo?>(null)
    val syncOffsetMs = MutableStateFlow(0)
    val autoComp = MutableStateFlow(true)
    val reactions = MutableSharedFlow<Reaction>(extraBufferCapacity = 64)
    val requests = MutableStateFlow<List<SongRequest>>(emptyList())
    val hostDiag = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val listenDiag = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val peerUpdate = MutableStateFlow<UpdateOffer?>(null)
    val githubUpdate = MutableStateFlow<UpdateOffer?>(null)
    val updateProgress = MutableStateFlow(UpdateProgress())
    val batteryOk = MutableStateFlow(true)
}

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("syncmusic", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = p(ctx)
        AppState.syncOffsetMs.value = p.getInt("syncOffset", 0)
        AppState.autoComp.value = p.getBoolean("autoComp", true)
        val ssid = p.getString("lastSsid", null)
        AppState.lastHost.value = ssid?.let {
            JoinInfo(p.getString("lastName", "") ?: "", it, p.getString("lastPass", "") ?: "")
        }
    }

    fun saveLastHost(ctx: Context, j: JoinInfo) {
        p(ctx).edit().putString("lastName", j.name).putString("lastSsid", j.ssid)
            .putString("lastPass", j.pass).apply()
        AppState.lastHost.value = j
    }

    fun saveSyncOffset(ctx: Context, ms: Int) {
        AppState.syncOffsetMs.value = ms
        p(ctx).edit().putInt("syncOffset", ms).apply()
    }

    fun saveAutoComp(ctx: Context, on: Boolean) {
        AppState.autoComp.value = on
        p(ctx).edit().putBoolean("autoComp", on).apply()
    }

    fun savedIp(ctx: Context) = p(ctx).getString("ip", "") ?: ""
    fun saveIp(ctx: Context, ip: String) = p(ctx).edit().putString("ip", ip).apply()

    /** Fixed group name and password, so listeners can rejoin this host later. */
    fun hostCreds(ctx: Context): Pair<String, String> {
        val p = p(ctx)
        val chars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        fun rnd(n: Int) = (1..n).map { chars.random() }.joinToString("")
        val ssid = p.getString("hostSsid", null) ?: "DIRECT-SM-${rnd(4)}".also { p.edit().putString("hostSsid", it).apply() }
        val pass = p.getString("hostPass", null) ?: rnd(12).also { p.edit().putString("hostPass", it).apply() }
        return ssid to pass
    }
}

fun appVersion(ctx: Context): Long =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(0L)

fun localIps(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }
        .distinct()
}.getOrDefault(emptyList())

object Notif {
    private const val CHANNEL = "syncmusic"

    fun action(ctx: Context, icon: Int, title: String, cls: Class<*>, act: String, req: Int): Notification.Action {
        val pi = PendingIntent.getService(
            ctx, req, Intent(ctx, cls).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(ctx, icon), title, pi).build()
    }

    fun build(ctx: Context, title: String, text: String, actions: List<Notification.Action> = emptyList()): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "SyncMusic", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(ctx, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
        actions.forEach { b.addAction(it) }
        if (actions.isNotEmpty()) {
            val compact = IntArray(minOf(actions.size, 3)) { it }
            b.setStyle(Notification.MediaStyle().setShowActionsInCompactView(*compact))
        }
        return b.build()
    }

    fun update(ctx: Context, id: Int, n: Notification) =
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
}
