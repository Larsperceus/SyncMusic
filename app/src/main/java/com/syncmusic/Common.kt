package com.syncmusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/** Shared audio format: 48 kHz, 16-bit, stereo PCM = 192 KB/s, fine for a local link. */
object Audio {
    const val PORT = 5050
    const val SAMPLE_RATE = 48000
    const val BYTES_PER_FRAME = 4                       // 2 channels x 2 bytes
    const val CHUNK = SAMPLE_RATE / 50 * BYTES_PER_FRAME // 20 ms = 3840 bytes
    val MAGIC = "SYNC".toByteArray()
}

data class HostState(
    val running: Boolean = false,
    val listeners: Int = 0,
    val level: Float = 0f,
    val message: String? = null,
)

data class ListenState(
    val running: Boolean = false,
    val playing: Boolean = false,
    val host: String = "",
    val message: String? = null,
)

data class Peer(val name: String, val address: String, val hosting: Boolean, val connected: Boolean)

/** App-wide state the services write and the UI observes. */
object AppState {
    val host = MutableStateFlow(HostState())
    val listen = MutableStateFlow(ListenState())
    val peers = MutableStateFlow<List<Peer>>(emptyList())
    val searching = MutableStateFlow(false)
    val myName = MutableStateFlow<String?>(null)
    val p2pMessage = MutableStateFlow<String?>(null)
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
