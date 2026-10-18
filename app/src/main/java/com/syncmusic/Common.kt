package com.syncmusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

object Audio {
    const val PORT = 5050
    const val SAMPLE_RATE = 48000
    const val BYTES_PER_FRAME = 4                        // stereo, 16-bit
    const val CHUNK = SAMPLE_RATE / 50 * BYTES_PER_FRAME // 20 ms
    /** Every listener aims to play each sound this many ms after the host captured it. */
    const val BASE_LATENCY_MS = 160
    val MAGIC = "SYNC2".toByteArray()
}

data class NowPlaying(
    val title: String = "",
    val artist: String = "",
    val art: Bitmap? = null,
    val playing: Boolean = false,
) {
    val isEmpty get() = title.isBlank()
}

data class HostState(
    val running: Boolean = false,
    val listeners: List<String> = emptyList(),
    val level: Float = 0f,
    val message: String? = null,
)

data class ListenState(
    val running: Boolean = false,
    val playing: Boolean = false,
    val host: String = "",
    val message: String? = null,
    val latencyMs: Int? = null,
)

/** What a listener needs to join a host's Wi-Fi Direct group without discovery. */
data class JoinInfo(val name: String, val ssid: String, val pass: String) {
    fun toQr() = listOf("SYNCMUSIC", "1", name.replace("|", " "), ssid, pass).joinToString("|")

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
    val group = MutableStateFlow<JoinInfo?>(null)     // host's own group, for the QR code
    val lastHost = MutableStateFlow<JoinInfo?>(null)
    val syncOffsetMs = MutableStateFlow(0)
}

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("syncmusic", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = p(ctx)
        AppState.syncOffsetMs.value = p.getInt("syncOffset", 0)
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
    fun build(ctx: Context, text: String): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "SyncMusic", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(ctx, CHANNEL)
            .setContentTitle("SyncMusic")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
