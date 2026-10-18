package com.syncmusic

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.IBinder
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.math.max
import kotlinx.coroutines.flow.update

/**
 * Connects to a host and plays the PCM stream. Output goes wherever this phone's
 * media audio goes – so just connect your Bluetooth headphones/speaker.
 */
class ListenerService : Service() {

    @Volatile private var active = false
    @Volatile private var socket: Socket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ip = intent?.getStringExtra("ip")
        val name = intent?.getStringExtra("name") ?: ip ?: ""
        if (ip.isNullOrBlank() || active) return START_NOT_STICKY
        startForeground(2, Notif.build(this, "Listening to $ip"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        active = true
        AppState.listen.value = ListenState(running = true, host = name, message = "Connecting…")
        thread(name = "player") { playLoop(ip.trim()) }
        return START_NOT_STICKY
    }

    private fun playLoop(ip: String) {
        val minBuf = AudioTrack.getMinBufferSize(
            Audio.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Audio.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBuf * 2, Audio.CHUNK * 4))
            .build()

        // Keep latency low: if more than ~150 ms piles up in the socket, skip ahead to ~40 ms.
        val maxBacklog = Audio.SAMPLE_RATE * Audio.BYTES_PER_FRAME * 150 / 1000
        val target = Audio.CHUNK * 2
        val buf = ByteArray(Audio.CHUNK)
        val scratch = ByteArray(maxBacklog * 4)

        while (active) {
            try {
                AppState.listen.update { it.copy(playing = false, message = "Connecting…") }
                val s = Socket().apply { tcpNoDelay = true; connect(InetSocketAddress(ip, Audio.PORT), 5000) }
                socket = s
                val input = DataInputStream(s.getInputStream().buffered(Audio.CHUNK * 8))
                val magic = ByteArray(4)
                input.readFully(magic)
                if (!magic.contentEquals(Audio.MAGIC)) throw IOException("not a SyncMusic host")

                AppState.listen.update { it.copy(playing = true, message = null) }
                track.play()
                while (active) {
                    val backlog = input.available()
                    if (backlog > maxBacklog) {
                        var skip = (backlog - target).let { it - it % Audio.BYTES_PER_FRAME }
                        while (skip > 0) {
                            val k = minOf(skip, scratch.size)
                            input.readFully(scratch, 0, k); skip -= k
                        }
                    }
                    input.readFully(buf)
                    track.write(buf, 0, buf.size)
                }
            } catch (e: Exception) {
                if (!active) break
                runCatching { track.pause(); track.flush() }
                AppState.listen.update { it.copy(playing = false, message = "Lost the connection. Reconnecting…") }
                try { Thread.sleep(1500) } catch (_: InterruptedException) { break }
            } finally {
                runCatching { socket?.close() }
            }
        }
        runCatching { track.stop() }
        track.release()
    }

    override fun onDestroy() {
        active = false
        runCatching { socket?.close() }
        AppState.listen.value = ListenState()
        super.onDestroy()
    }
}
