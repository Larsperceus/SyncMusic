package com.syncmusic

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.flow.update
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.max

/** Estimates the host's clock from ping round-trips (keeps the fastest recent sample). */
class ClockSync {
    private val samples = ArrayDeque<Pair<Long, Long>>()
    @Volatile var offset: Long? = null
        private set

    @Synchronized
    fun onPong(t0: Long, hostNow: Long, t3: Long) {
        samples.addLast((t3 - t0) to (hostNow - (t0 + t3) / 2))
        if (samples.size > 12) samples.removeFirst()
        offset = samples.minBy { it.first }.second
    }
}

private class Chunk(val t: Long, val pcm: ByteArray)

/**
 * Receives the stream and plays every sound at (capture time + target delay) on the
 * host's clock, so all listeners stay in step with each other.
 */
class ListenerService : Service() {

    @Volatile private var active = false
    @Volatile private var socket: Socket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ip = intent?.getStringExtra("ip")
        if (ip.isNullOrBlank() || active) return START_NOT_STICKY
        val name = intent.getStringExtra("name") ?: ip
        startForeground(2, Notif.build(this, "Listening along with $name"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        active = true
        AppState.listen.value = ListenState(running = true, host = name, message = "Connecting…")
        AppState.remoteNow.value = NowPlaying()
        thread(name = "listener") { loop(ip.trim()) }
        return START_NOT_STICKY
    }

    private fun loop(ip: String) {
        while (active) {
            try {
                session(ip)
            } catch (e: Exception) {
                if (!active) break
                val msg = if (e.message?.startsWith("Update") == true) e.message
                    else "Lost the connection. Reconnecting…"
                AppState.listen.update { it.copy(playing = false, latencyMs = null, message = msg) }
                try { Thread.sleep(1500) } catch (_: InterruptedException) { break }
            } finally {
                runCatching { socket?.close() }
            }
        }
    }

    private fun session(ip: String) {
        val s = Socket().apply { tcpNoDelay = true; connect(InetSocketAddress(ip, Audio.PORT), 5000) }
        socket = s
        val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 32_768))
        val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
        val magic = ByteArray(Audio.MAGIC.size)
        inp.readFully(magic)
        if (!magic.contentEquals(Audio.MAGIC)) throw IOException("Update SyncMusic on both phones to the same version.")

        fun send(type: Int, data: ByteArray = ByteArray(0)) = synchronized(out) {
            out.writeFrame(Frame(type, data)); out.flush()
        }
        send(Msg.HELLO, bytes { writeUTF(AppState.myName.value ?: Build.MODEL) })

        val clock = ClockSync()
        val player = Player(clock)
        player.start()
        val pinger = thread(name = "ping") {
            var i = 0
            try {
                while (active && !s.isClosed) {
                    send(Msg.PING, bytes { writeLong(SystemClock.elapsedRealtime()) })
                    Thread.sleep(if (i++ < 10) 150 else 1000)
                }
            } catch (_: Exception) {
            }
        }

        var decoder: OpusDecoder? = null
        try {
            while (active) {
                val f = inp.readFrame()
                when (f.type) {
                    Msg.CSD -> {
                        decoder?.release()
                        decoder = try { OpusDecoder(parseCsd(f.data)) } catch (_: Exception) { send(Msg.WANT_PCM); null }
                    }
                    Msg.OPUS -> {
                        val d = decoder ?: continue
                        val r = f.data.reader()
                        val t = r.readLong()
                        val pkt = f.data.copyOfRange(8, f.data.size)
                        try {
                            d.decode(pkt, t * 1000) { pcm, pts -> player.offer(Chunk(pts / 1000, pcm)) }
                        } catch (_: Exception) {
                            d.release(); decoder = null
                            send(Msg.WANT_PCM)
                        }
                    }
                    Msg.PCM -> {
                        val t = f.data.reader().readLong()
                        player.offer(Chunk(t, f.data.copyOfRange(8, f.data.size)))
                    }
                    Msg.PONG -> {
                        val r = f.data.reader()
                        clock.onPong(r.readLong(), r.readLong(), SystemClock.elapsedRealtime())
                    }
                    Msg.META -> {
                        val r = f.data.reader()
                        val title = r.readUTF(); val artist = r.readUTF(); val playing = r.readBoolean()
                        AppState.remoteNow.update { it.copy(title = title, artist = artist, playing = playing) }
                    }
                    Msg.ART -> {
                        val bmp = if (f.data.isEmpty()) null
                            else runCatching { BitmapFactory.decodeByteArray(f.data, 0, f.data.size) }.getOrNull()
                        AppState.remoteNow.update { it.copy(art = bmp) }
                    }
                    Msg.HOSTINFO -> {
                        val r = f.data.reader()
                        val name = r.readUTF(); val ssid = r.readUTF(); val pass = r.readUTF()
                        AppState.listen.update { it.copy(host = name) }
                        if (ssid.isNotEmpty()) Prefs.saveLastHost(this, JoinInfo(name, ssid, pass))
                    }
                }
            }
        } finally {
            player.stop()
            decoder?.release()
            pinger.interrupt()
        }
    }

    private inner class Player(private val clock: ClockSync) {
        private val queue = ArrayBlockingQueue<Chunk>(200)
        @Volatile private var running = true
        private var worker: Thread? = null

        fun offer(c: Chunk) { while (!queue.offer(c)) queue.poll() }
        fun start() { worker = thread(name = "player") { play() } }
        fun stop() { running = false; worker?.interrupt(); runCatching { worker?.join(500) } }

        private fun play() {
            val minBuf = AudioTrack.getMinBufferSize(
                Audio.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Audio.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(max(minBuf * 2, Audio.CHUNK * 3))
                .build()
            val silence = ByteArray(Audio.CHUNK)
            var written = 0L
            var lastReport = 0L
            var smoothed = -1.0
            var started = false
            try {
                track.play()
                while (running && active) {
                    val c = try { queue.poll(300, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break } ?: continue
                    if (!started) {
                        started = true
                        AppState.listen.update { it.copy(playing = true, message = null) }
                    }
                    val off = clock.offset
                    if (off != null) {
                        val now = SystemClock.elapsedRealtime()
                        val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                        val pendingMs = (written - head).coerceAtLeast(0) * 1000 / Audio.SAMPLE_RATE
                        val latency = now + off - c.t + pendingMs
                        val target = Audio.BASE_LATENCY_MS + AppState.syncOffsetMs.value
                        smoothed = if (smoothed < 0) latency.toDouble() else smoothed * 0.9 + latency * 0.1
                        if (now - lastReport > 500) {
                            lastReport = now
                            AppState.listen.update { it.copy(latencyMs = smoothed.toInt()) }
                        }
                        if (latency > target + 45) continue          // running late: skip ahead
                        if (latency < target - 45) {                 // running early: wait
                            var gap = ((target - latency) * Audio.SAMPLE_RATE / 1000).toInt()
                            while (gap > 0) {
                                val n = minOf(gap, silence.size / Audio.BYTES_PER_FRAME)
                                track.write(silence, 0, n * Audio.BYTES_PER_FRAME)
                                written += n; gap -= n
                            }
                        }
                    }
                    track.write(c.pcm, 0, c.pcm.size)
                    written += c.pcm.size / Audio.BYTES_PER_FRAME
                }
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        }
    }

    override fun onDestroy() {
        active = false
        runCatching { socket?.close() }
        AppState.listen.value = ListenState()
        AppState.remoteNow.value = NowPlaying()
        super.onDestroy()
    }
}
