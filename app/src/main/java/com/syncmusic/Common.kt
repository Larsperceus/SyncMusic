package com.syncmusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Shared audio format: 48 kHz, 16-bit, stereo PCM = 192 KB/s, fine for local Wi-Fi. */
object Audio {
    const val PORT = 5050
    const val SAMPLE_RATE = 48000
    const val BYTES_PER_FRAME = 4                       // 2 channels x 2 bytes
    const val CHUNK = SAMPLE_RATE / 50 * BYTES_PER_FRAME // 20 ms = 3840 bytes
    val MAGIC = "SYNC".toByteArray()
}

/** Tiny status bus so the services can show text in the UI. */
object SyncStatus {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var text: String = "Idle"
        private set
    var observer: ((String) -> Unit)? = null
    fun set(s: String) {
        text = s
        main.post { observer?.invoke(s) }
    }
}

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
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
