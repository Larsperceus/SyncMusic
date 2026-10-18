package com.syncmusic

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.flow.update

/**
 * Captures what other apps are playing (Android 10+ AudioPlaybackCapture)
 * and streams raw PCM over TCP to every connected listener.
 */
class HostService : Service() {

    @Volatile private var active = false
    private var projection: MediaProjection? = null
    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (active) return START_NOT_STICKY

        // Must be in the foreground BEFORE asking for the MediaProjection (Android 14 rule).
        startForeground(1, Notif.build(this, "Sharing audio on port ${Audio.PORT}"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent?.getIntExtra("code", 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra("data", Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")

        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        if (proj == null) { AppState.host.update { it.copy(message = "Android didn't grant audio sharing. Try again.") }; stopSelf(); return START_NOT_STICKY }
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))
        projection = proj

        active = true
        AppState.host.value = HostState(running = true, message = "Play something in any music app.")
        thread(name = "accept") { acceptLoop() }
        thread(name = "capture") { captureLoop(proj) }
        return START_NOT_STICKY
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Audio.PORT)) }
            server = ss
            while (active) {
                val s = ss.accept()
                s.tcpNoDelay = true
                Client(s).also { clients += it; it.start() }
            }
        } catch (e: Exception) {
            if (active) AppState.host.update { it.copy(message = "Couldn't open the connection: ${e.message}") }
        }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested in MainActivity
    private fun captureLoop(proj: MediaProjection) {
        val config = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(Audio.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            Audio.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)

        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(max(minBuf * 2, Audio.CHUNK * 4))
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            AppState.host.update { it.copy(message = "Couldn't start sharing: ${e.message}") }
            stopSelf(); return
        }

        try {
            rec.startRecording()
            val chunk = ByteArray(Audio.CHUNK)
            var peak = 0
            var lastReport = 0L
            var silentSince = System.currentTimeMillis()
            while (active) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n < 0) { AppState.host.update { it.copy(message = "Audio capture stopped ($n).") }; break }
                if (n == 0) continue
                val len = n - n % Audio.BYTES_PER_FRAME
                val copy = chunk.copyOf(len)
                for (c in clients) c.offer(copy)

                var i = 0
                while (i + 1 < len) {
                    val s = (copy[i].toInt() and 0xff) or (copy[i + 1].toInt() shl 8)
                    peak = max(peak, abs(s.toShort().toInt()))
                    i += 8
                }
                val now = System.currentTimeMillis()
                if (now - lastReport >= 80) {
                    if (peak >= 50) silentSince = now
                    val msg = if (now - silentSince > 4000)
                        "Nothing to share yet. Play music, or this app may block sharing." else null
                    val level = sqrt(peak / 32768f)
                    AppState.host.update { it.copy(level = level, listeners = clients.size, message = msg) }
                    peak = 0; lastReport = now
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    private inner class Client(private val socket: Socket) {
        private val queue = ArrayBlockingQueue<ByteArray>(25) // ~0.5 s max backlog
        fun offer(b: ByteArray) {
            if (!queue.offer(b)) { queue.clear(); queue.offer(b) } // too slow: drop old audio
        }
        fun start() = thread(name = "client") {
            try {
                val out = socket.getOutputStream()
                out.write(Audio.MAGIC)
                while (active && !socket.isClosed) {
                    val b = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    out.write(b)
                }
            } catch (_: Exception) {
            } finally {
                clients.remove(this)
                runCatching { socket.close() }
            }
        }
        fun close() = runCatching { socket.close() }
    }

    override fun onDestroy() {
        active = false
        runCatching { server?.close() }
        clients.forEach { it.close() }
        clients.clear()
        runCatching { projection?.stop() }
        projection = null
        AppState.host.value = HostState()
        super.onDestroy()
    }
}
