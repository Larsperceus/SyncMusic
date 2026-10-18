package com.syncmusic

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
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
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.flow.update
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Captures what other apps are playing, encodes it with Opus and sends it to every
 * listener, each frame stamped with its capture time so listeners can play in step.
 */
class HostService : Service() {

    @Volatile private var active = false
    private var projection: MediaProjection? = null
    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()

    @Volatile private var csd: Frame? = null
    @Volatile private var metaFrame: Frame? = null
    @Volatile private var artFrame: Frame? = null
    private var lastArt: Bitmap? = null
    @Volatile private var opusOk = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (active) return START_NOT_STICKY

        startForeground(1, Notif.build(this, "Sharing what's playing"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent?.getIntExtra("code", 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra("data", Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val proj = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        if (proj == null) {
            AppState.host.update { it.copy(message = "Android didn't allow sharing. Try again.") }
            stopSelf(); return START_NOT_STICKY
        }
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))
        projection = proj

        active = true
        AppState.host.value = HostState(running = true, message = "Play something in any music app.")
        Media.listener = { onMedia(it) }
        Media.start(this)
        onMedia(AppState.nowPlaying.value)

        thread(name = "accept") { acceptLoop() }
        thread(name = "capture") { captureLoop(proj) }
        return START_NOT_STICKY
    }

    // ---------- now playing ----------

    private fun onMedia(np: NowPlaying) {
        val meta = Frame(Msg.META, bytes { writeUTF(np.title); writeUTF(np.artist); writeBoolean(np.playing) })
        metaFrame = meta
        clients.forEach { it.offer(meta) }
        if (np.art !== lastArt) {
            lastArt = np.art
            val art = Frame(Msg.ART, np.art?.let { jpeg(it) } ?: ByteArray(0))
            artFrame = art
            clients.forEach { it.offer(art) }
        }
    }

    private fun jpeg(b: Bitmap): ByteArray = runCatching {
        val s = 320f / max(b.width, b.height)
        val src = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
        ByteArrayOutputStream().also { src.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
    }.getOrDefault(ByteArray(0))

    private fun hostInfo(): Frame {
        val g = AppState.group.value
        return Frame(Msg.HOSTINFO, bytes {
            writeUTF(AppState.myName.value ?: Build.MODEL)
            writeUTF(g?.ssid ?: ""); writeUTF(g?.pass ?: "")
        })
    }

    // ---------- network ----------

    private fun acceptLoop() {
        try {
            val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Audio.PORT)) }
            server = ss
            while (active) {
                val s = ss.accept()
                s.tcpNoDelay = true
                val c = Client(s)
                c.offer(hostInfo())
                csd?.let { c.offer(it) }
                metaFrame?.let { c.offer(it) }
                artFrame?.let { c.offer(it) }
                clients += c
                c.start()
                updateListeners()
            }
        } catch (e: Exception) {
            if (active) AppState.host.update { it.copy(message = "Couldn't open the connection: ${e.message}") }
        }
    }

    private fun updateListeners() = AppState.host.update { it.copy(listeners = clients.map { c -> c.name }) }

    private inner class Client(private val socket: Socket) {
        @Volatile var name: String = "Listener"
        @Volatile var wantsPcm = false
        private val queue = LinkedBlockingDeque<Frame>(250)

        fun offer(f: Frame) { while (!queue.offerLast(f)) queue.pollFirst() }   // full: drop oldest audio
        private fun offerUrgent(f: Frame) { while (!queue.offerFirst(f)) queue.pollLast() }

        fun start() {
            thread(name = "client-out") {
                try {
                    val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 16_384))
                    out.write(Audio.MAGIC)
                    out.flush()
                    while (active && !socket.isClosed) {
                        val f = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                        out.writeFrame(f)
                        if (queue.isEmpty()) out.flush()
                    }
                } catch (_: Exception) {
                } finally { close() }
            }
            thread(name = "client-in") {
                try {
                    val inp = DataInputStream(BufferedInputStream(socket.getInputStream()))
                    while (active) {
                        val f = inp.readFrame()
                        when (f.type) {
                            Msg.HELLO -> { name = f.data.reader().readUTF(); updateListeners() }
                            Msg.PING -> {
                                val t0 = f.data.reader().readLong()
                                offerUrgent(Frame(Msg.PONG, bytes { writeLong(t0); writeLong(SystemClock.elapsedRealtime()) }))
                            }
                            Msg.WANT_PCM -> wantsPcm = true
                        }
                    }
                } catch (_: Exception) {
                } finally { close() }
            }
        }

        fun close() {
            if (clients.remove(this)) updateListeners()
            runCatching { socket.close() }
        }
    }

    // ---------- capture ----------

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested in MainActivity
    private fun captureLoop(proj: MediaProjection) {
        val config = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(Process.myUid())
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

        var encoder: OpusEncoder? = runCatching {
            OpusEncoder { list ->
                val f = Frame(Msg.CSD, csdPayload(list))
                csd = f
                opusOk = true
                clients.forEach { it.offer(f) }
            }
        }.getOrNull()

        try {
            rec.startRecording()
            val chunk = ByteArray(Audio.CHUNK)
            var peak = 0
            var lastReport = 0L
            var silentSince = SystemClock.elapsedRealtime()
            while (active) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n < 0) { AppState.host.update { it.copy(message = "Audio capture stopped ($n).") }; break }
                if (n == 0) continue
                val t = SystemClock.elapsedRealtime()
                val len = n - n % Audio.BYTES_PER_FRAME

                val enc = encoder
                if (enc != null) {
                    try {
                        enc.encode(chunk, len, t * 1000) { pkt, ptsUs ->
                            val f = Frame(Msg.OPUS, bytes { writeLong(ptsUs / 1000); write(pkt) })
                            for (c in clients) if (!c.wantsPcm) c.offer(f)
                        }
                    } catch (_: Exception) {
                        runCatching { enc.release() }
                        encoder = null
                        opusOk = false
                    }
                }
                if (clients.any { it.wantsPcm || !opusOk }) {
                    val f = Frame(Msg.PCM, bytes { writeLong(t); write(chunk, 0, len) })
                    for (c in clients) if (c.wantsPcm || !opusOk) c.offer(f)
                }

                var i = 0
                while (i + 1 < len) {
                    val s = (chunk[i].toInt() and 0xff) or (chunk[i + 1].toInt() shl 8)
                    peak = max(peak, abs(s.toShort().toInt()))
                    i += 8
                }
                if (t - lastReport >= 80) {
                    if (peak >= 50) silentSince = t
                    val msg = if (t - silentSince > 4000)
                        "Nothing to share yet. Play music, or this app may block sharing." else null
                    val level = sqrt(peak / 32768f)
                    AppState.host.update { it.copy(level = level, message = msg) }
                    peak = 0; lastReport = t
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            encoder?.release()
        }
    }

    override fun onDestroy() {
        active = false
        Media.listener = null
        runCatching { server?.close() }
        clients.forEach { it.close() }
        clients.clear()
        runCatching { projection?.stop() }
        projection = null
        AppState.host.value = HostState()
        super.onDestroy()
    }
}
