package com.syncmusic

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.flow.update
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Estimates the host's clock from ping round-trips (keeps the fastest recent sample). */
class ClockSync {
    private val samples = ArrayDeque<Pair<Long, Long>>()
    @Volatile var offset: Long? = null
        private set
    @Volatile var rtt: Long? = null
        private set

    @Synchronized
    fun onPong(t0: Long, hostNow: Long, t3: Long) {
        samples.addLast((t3 - t0) to (hostNow - (t0 + t3) / 2))
        if (samples.size > 12) samples.removeFirst()
        val best = samples.minBy { it.first }
        offset = best.second
        rtt = best.first
    }
}

/** Receives the stream and plays it in step with every other listener. */
class ListenerService : Service() {

    @Volatile private var active = false
    @Volatile private var socket: Socket? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val main = Handler(Looper.getMainLooper())
    private var hostName = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Act.STOP) { stopSelf(); return START_NOT_STICKY }
        val ip = intent?.getStringExtra("ip")
        if (ip.isNullOrBlank() || active) return START_NOT_STICKY
        hostName = intent.getStringExtra("name") ?: ip
        startForeground(2, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        P2p.init(this)
        locks(true)
        active = true
        AppState.listen.value = ListenState(running = true, host = hostName, message = "Connecting…")
        AppState.remoteNow.value = NowPlaying()
        AppState.peerUpdate.value = null
        thread(name = "listener") { loop(ip.trim()) }
        return START_NOT_STICKY
    }

    private fun notification() = Notif.build(
        this, "Listening along",
        AppState.remoteNow.value.title.ifBlank { "With $hostName" },
        listOf(Notif.action(this, R.drawable.ic_stop, "Stop", ListenerService::class.java, Act.STOP, 20)),
    )

    private fun locks(on: Boolean) {
        if (on) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SyncMusic:listen").apply { acquire(6 * 60 * 60 * 1000L) }
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "SyncMusic:listen").apply { acquire() }
        } else {
            runCatching { wake?.release() }
            runCatching { wifiLock?.release() }
        }
    }

    private fun loop(ip: String) {
        var failures = 0
        while (active) {
            try {
                session(ip)
                failures = 0
            } catch (e: Exception) {
                if (!active) break
                failures++
                val msg = if (e.message?.startsWith("Update") == true) e.message
                    else "Lost the connection. Reconnecting…"
                AppState.listen.update { it.copy(playing = false, latencyMs = null, message = msg) }
                // The Wi-Fi Direct link itself may have dropped: rejoin the group every few tries.
                val last = AppState.lastHost.value
                if (ip.startsWith("192.168.49.") && last != null && failures % 3 == 0) {
                    main.post { P2p.join(last) { } }
                }
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

        val send: (Int, ByteArray) -> Unit = { type, data ->
            synchronized(out) { out.writeFrame(Frame(type, data)); out.flush() }
        }
        ListenerLink.send = { t, d -> thread { runCatching { send(t, d) } } }
        send(Msg.HELLO, bytes { writeUTF(AppState.myName.value ?: Build.MODEL) })

        val clock = ClockSync()
        val player = SyncPlayer({ clock.offset }) {
            AppState.listen.update { it.copy(playing = true, message = null) }
        }
        player.start()
        val received = AtomicLong(0)
        var opus = false

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
        val diag = thread(name = "diag") {
            var lastBytes = 0L
            try {
                while (active && !s.isClosed) {
                    Thread.sleep(1000)
                    val b = received.get()
                    val kbps = (b - lastBytes) * 8 / 1000
                    lastBytes = b
                    AppState.listen.update {
                        it.copy(latencyMs = player.latencyMs, deviceLatencyMs = player.deviceLatencyMs, device = player.deviceName)
                    }
                    AppState.listenDiag.value = listOf(
                        "Format" to if (opus) "Opus, compressed" else "Raw audio",
                        "Receiving" to "$kbps kbit/s",
                        "Round trip" to (clock.rtt?.let { "$it ms" } ?: "Measuring…"),
                        "Behind sharing phone" to (player.latencyMs?.let { "$it ms" } ?: "–"),
                        "Target" to "${Audio.BASE_LATENCY_MS + AppState.syncOffsetMs.value} ms",
                        "Headphone delay" to (player.deviceLatencyMs?.let { "$it ms" } ?: "Not reported"),
                        "Output" to (player.deviceName ?: "–"),
                        "Speed correction" to "%+.2f%%".format(player.speedPct),
                        "Late audio skipped" to "${player.lateDrops}",
                        "Gaps filled" to "${player.gapMs} ms",
                        "Buffered" to "${player.queuedMs()} ms",
                    )
                }
            } catch (_: InterruptedException) {
            }
        }

        var decoder: OpusDecoder? = null
        try {
            while (active) {
                val f = inp.readFrame()
                received.addAndGet(f.data.size + 5L)
                when (f.type) {
                    Msg.CSD -> {
                        decoder?.release()
                        decoder = try { OpusDecoder(parseCsd(f.data)) } catch (_: Exception) { send(Msg.WANT_PCM, ByteArray(0)); null }
                    }
                    Msg.OPUS -> {
                        val d = decoder ?: continue
                        opus = true
                        val t = f.data.reader().readLong()
                        val pkt = f.data.copyOfRange(8, f.data.size)
                        try {
                            d.decode(pkt, t * 1000) { pcm, pts -> player.offer(Chunk(pts / 1000, bytesToShorts(pcm))) }
                        } catch (_: Exception) {
                            d.release(); decoder = null; opus = false
                            send(Msg.WANT_PCM, ByteArray(0))
                        }
                    }
                    Msg.PCM -> {
                        opus = false
                        val t = f.data.reader().readLong()
                        player.offer(Chunk(t, bytesToShorts(f.data.copyOfRange(8, f.data.size))))
                    }
                    Msg.PONG -> {
                        val r = f.data.reader()
                        clock.onPong(r.readLong(), r.readLong(), SystemClock.elapsedRealtime())
                    }
                    Msg.META -> {
                        val r = f.data.reader()
                        val title = r.readUTF(); val artist = r.readUTF(); val playing = r.readBoolean()
                        AppState.remoteNow.update { it.copy(title = title, artist = artist, playing = playing) }
                        main.post { if (active) Notif.update(this, 2, notification()) }
                    }
                    Msg.ART -> {
                        val bmp = if (f.data.isEmpty()) null
                            else runCatching { BitmapFactory.decodeByteArray(f.data, 0, f.data.size) }.getOrNull()
                        AppState.remoteNow.update { it.copy(art = bmp) }
                    }
                    Msg.HOSTINFO -> {
                        val r = f.data.reader()
                        val name = r.readUTF(); val ssid = r.readUTF(); val pass = r.readUTF()
                        val version = runCatching { r.readLong() }.getOrDefault(0L)
                        hostName = name
                        AppState.listen.update { it.copy(host = name) }
                        if (ssid.isNotEmpty()) Prefs.saveLastHost(this, JoinInfo(name, ssid, pass))
                        if (version > appVersion(this)) AppState.peerUpdate.value =
                            UpdateOffer(version, "http://$ip:${Audio.WEB_PORT}/app.apk", name)
                    }
                    Msg.VOLUME -> player.volume = f.data.reader().readFloat().coerceIn(0f, 1f)
                    Msg.REACTION -> {
                        val r = f.data.reader()
                        AppState.reactions.tryEmit(Reaction(r.readUTF(), r.readUTF()))
                    }
                    Msg.KICK -> {
                        active = false
                        AppState.p2pMessage.value = "$hostName removed you from the session."
                        main.post { stopSelf() }
                        break
                    }
                }
            }
        } finally {
            ListenerLink.send = null
            player.stop()
            decoder?.release()
            pinger.interrupt()
            diag.interrupt()
        }
    }

    override fun onDestroy() {
        active = false
        runCatching { socket?.close() }
        locks(false)
        ListenerLink.send = null
        AppState.listen.value = ListenState()
        AppState.remoteNow.value = NowPlaying()
        AppState.listenDiag.value = emptyList()
        super.onDestroy()
    }
}
