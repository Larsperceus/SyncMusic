package com.syncmusic

import android.annotation.SuppressLint
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.service.quicksettings.TileService
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The sharing phone. Gets audio from another app (playback capture) or from songs on this
 * phone, and sends it, stamped with capture time, to app listeners (Opus over TCP) and to
 * browsers (raw audio over HTTP). Also relays song info, reactions, requests and volume.
 */
class HostService : Service() {

    companion object {
        @Volatile var instance: HostService? = null
    }

    @Volatile private var active = false
    private var local = false
    private var projection: MediaProjection? = null
    private var server: ServerSocket? = null
    private val web = WebServer()
    private val clients = CopyOnWriteArrayList<Client>()
    private val webClients = CopyOnWriteArrayList<WebClient>()
    private val kicked = java.util.Collections.synchronizedSet(HashSet<Int>())
    private val ids = AtomicInteger(1)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var csd: Frame? = null
    @Volatile private var metaFrame: Frame? = null
    @Volatile private var artFrame: Frame? = null
    @Volatile private var meta = NowPlaying()
    @Volatile private var artBytes = ByteArray(0)
    @Volatile private var artVersion = 0
    private var lastArt: Bitmap? = null
    @Volatile private var opusOk = false
    private var encoder: OpusEncoder? = null

    private var source: LocalSource? = null
    private var ownPlayer: SyncPlayer? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val sentBytes = AtomicLong(0)
    private val overflow = AtomicInteger(0)
    private var peak = 0
    private var lastLevel = 0L
    private var silentSince = 0L

    private val reactionLog = ArrayDeque<Pair<Int, Reaction>>()
    private var reactionSeq = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Act.STOP -> { stopSelf(); return START_NOT_STICKY }
            Act.PLAYPAUSE -> { HostControls.playPause(); return START_NOT_STICKY }
            Act.NEXT -> { HostControls.next(); return START_NOT_STICKY }
            Act.PREV -> { HostControls.previous(); return START_NOT_STICKY }
        }
        if (active) return START_NOT_STICKY

        local = intent?.getStringExtra("mode") == "local"
        startForeground(1, notification(),
            if (local) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        var proj: MediaProjection? = null
        var uris: List<Uri> = emptyList()
        if (local) {
            uris = (if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableArrayListExtra("uris", Uri::class.java)
                else @Suppress("DEPRECATION") intent?.getParcelableArrayListExtra<Uri>("uris")).orEmpty()
            if (uris.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        } else {
            val code = intent?.getIntExtra("code", 0) ?: 0
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                intent?.getParcelableExtra("data", Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
            if (data == null) { stopSelf(); return START_NOT_STICKY }
            proj = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            if (proj == null) {
                AppState.host.update { it.copy(message = "Android didn't allow sharing. Try again.") }
                stopSelf(); return START_NOT_STICKY
            }
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, main)
            projection = proj
        }

        active = true
        instance = this
        locks(true)
        AppState.requests.value = emptyList()
        AppState.host.value = HostState(running = true, local = local,
            message = if (local) null else "Play something in any music app.")
        Media.listener = { if (!local) publishMeta(it) }
        Media.start(this)
        if (!local) publishMeta(AppState.nowPlaying.value)

        encoder = runCatching {
            OpusEncoder { list ->
                val f = Frame(Msg.CSD, csdPayload(list))
                csd = f
                opusOk = true
                clients.forEach { it.offer(f) }
            }
        }.getOrNull()

        thread(name = "accept") { acceptLoop() }
        web.start()
        if (local) {
            ownPlayer = SyncPlayer({ 0L }).also { it.start() }
            source = LocalSource(this, uris, ::emit) { np ->
                AppState.nowPlaying.value = np
                publishMeta(np)
            }.also { HostControls.local = it; it.start() }
        } else {
            thread(name = "capture") { captureLoop(proj!!) }
        }
        thread(name = "host-diag") { diagLoop() }
        TileService.requestListeningState(this, ComponentName(this, ShareTile::class.java))
        return START_NOT_STICKY
    }

    private fun locks(on: Boolean) {
        if (on) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SyncMusic:host").apply { acquire(6 * 60 * 60 * 1000L) }
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "SyncMusic:host").apply { acquire() }
        } else {
            runCatching { wake?.release() }
            runCatching { wifiLock?.release() }
        }
    }

    private fun notification(): android.app.Notification {
        val n = clients.size + webClients.size
        val title = meta.title.ifBlank { "Sharing what's playing" }
        val text = when (n) { 0 -> "Waiting for listeners"; 1 -> "1 person listening"; else -> "$n people listening" }
        val c = HostService::class.java
        return Notif.build(this, title, text, listOf(
            Notif.action(this, R.drawable.ic_prev, "Previous", c, Act.PREV, 10),
            Notif.action(this, if (meta.playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (meta.playing) "Pause" else "Play", c, Act.PLAYPAUSE, 11),
            Notif.action(this, R.drawable.ic_next, "Next", c, Act.NEXT, 12),
            Notif.action(this, R.drawable.ic_stop, "Stop", c, Act.STOP, 13),
        ))
    }

    private fun refreshNotification() = main.post { if (active) Notif.update(this, 1, notification()) }

    // ---------- song info ----------

    private fun publishMeta(np: NowPlaying) {
        meta = np
        val m = Frame(Msg.META, bytes { writeUTF(np.title); writeUTF(np.artist); writeBoolean(np.playing) })
        metaFrame = m
        clients.forEach { it.offer(m) }
        if (np.art !== lastArt) {
            lastArt = np.art
            artBytes = np.art?.let { jpeg(it) } ?: ByteArray(0)
            artVersion++
            val a = Frame(Msg.ART, artBytes)
            artFrame = a
            clients.forEach { it.offer(a) }
        }
        refreshNotification()
    }

    private fun jpeg(b: Bitmap): ByteArray = runCatching {
        val s = 400f / max(b.width, b.height)
        val src = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
        ByteArrayOutputStream().also { src.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
    }.getOrDefault(ByteArray(0))

    private fun hostName() = AppState.myName.value ?: Build.MODEL

    private fun hostInfo(): Frame {
        val g = AppState.group.value
        return Frame(Msg.HOSTINFO, bytes {
            writeUTF(hostName())
            writeUTF(g?.ssid ?: ""); writeUTF(g?.pass ?: "")
            writeLong(appVersion(this@HostService))
        })
    }

    // ---------- social ----------

    fun react(name: String, emoji: String) {
        val r = Reaction(name.take(30), emoji.take(8))
        AppState.reactions.tryEmit(r)
        val f = Frame(Msg.REACTION, bytes { writeUTF(r.name); writeUTF(r.emoji) })
        clients.forEach { it.offer(f) }
        synchronized(reactionLog) {
            reactionSeq++
            reactionLog.addLast(reactionSeq to r)
            if (reactionLog.size > 30) reactionLog.removeFirst()
        }
    }

    private fun addRequest(name: String, text: String) {
        val t = text.trim().take(120)
        if (t.isEmpty()) return
        AppState.requests.update { it + SongRequest(System.nanoTime(), name.take(30), t) }
    }

    fun setVolume(id: Int, v: Float) {
        val vol = v.coerceIn(0f, 1f)
        clients.find { it.id == id }?.let { c ->
            c.volume = vol
            c.offerUrgent(Frame(Msg.VOLUME, bytes { writeFloat(vol) }))
        }
        webClients.find { it.id == id }?.volume = vol
        updateListeners()
    }

    fun kick(id: Int) {
        kicked += id
        clients.find { it.id == id }?.let { c ->
            c.offerUrgent(Frame(Msg.KICK, ByteArray(0)))
            thread { Thread.sleep(400); c.close() }
        }
        webClients.find { it.id == id }?.close()
    }

    private fun updateListeners() {
        AppState.host.update {
            it.copy(listeners = clients.map { c -> ListenerInfo(c.id, c.name, false, c.volume) } +
                webClients.map { w -> ListenerInfo(w.id, w.name, true, w.volume) })
        }
        refreshNotification()
    }

    // ---------- audio out ----------

    /** Called for every 20 ms of audio, from the capture thread or the local-songs thread. */
    private fun emit(t: Long, pcm: ShortArray) {
        val enc = encoder
        val le = shortsToBytes(pcm)
        if (enc != null && clients.any { !it.wantsPcm }) {
            try {
                enc.encode(le, le.size, t * 1000) { pkt, ptsUs ->
                    val f = Frame(Msg.OPUS, bytes { writeLong(ptsUs / 1000); write(pkt) })
                    for (c in clients) if (!c.wantsPcm) { c.offer(f); sentBytes.addAndGet(f.data.size + 5L) }
                }
            } catch (_: Exception) {
                runCatching { enc.release() }
                encoder = null
                opusOk = false
            }
        }
        if (clients.any { it.wantsPcm || !opusOk }) {
            val f = Frame(Msg.PCM, bytes { writeLong(t); write(le) })
            for (c in clients) if (c.wantsPcm || !opusOk) { c.offer(f); sentBytes.addAndGet(f.data.size + 5L) }
        }
        for (w in webClients) { w.offer(t, pcm); sentBytes.addAndGet(pcm.size * 2 + 12L) }
        ownPlayer?.offer(Chunk(t, pcm))

        var i = 0
        while (i < pcm.size) { peak = max(peak, abs(pcm[i].toInt())); i += 4 }
        val now = SystemClock.elapsedRealtime()
        if (now - lastLevel >= 80) {
            if (peak >= 50) silentSince = now
            val msg = if (!local && now - silentSince > 4000)
                "Nothing to share yet. Play music, or this app may block sharing." else null
            AppState.host.update { it.copy(level = sqrt(peak / 32768f), message = msg) }
            peak = 0; lastLevel = now
        }
    }

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
            main.post { stopSelf() }; return
        }
        silentSince = SystemClock.elapsedRealtime()
        try {
            rec.startRecording()
            val chunk = ByteArray(Audio.CHUNK)
            while (active) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n < 0) { AppState.host.update { it.copy(message = "Audio capture stopped ($n).") }; break }
                if (n == 0) continue
                emit(SystemClock.elapsedRealtime(), bytesToShorts(chunk, n - n % Audio.BYTES_PER_FRAME))
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    private fun diagLoop() {
        var last = 0L
        try {
            while (active) {
                Thread.sleep(1000)
                val b = sentBytes.get()
                val kbps = (b - last) * 8 / 1000
                last = b
                val rows = mutableListOf(
                    "Source" to if (local) "Songs on this phone" else "Other apps",
                    "Format" to if (opusOk) "Opus, compressed" else "Raw audio",
                    "Sending" to "$kbps kbit/s",
                    "App listeners" to "${clients.size}",
                    "Browser listeners" to "${webClients.size}",
                    "Audio dropped (slow link)" to "${overflow.get()} bits",
                )
                ownPlayer?.let { p ->
                    rows += "Your delay" to (p.latencyMs?.let { "$it ms" } ?: "–")
                    rows += "Headphone delay" to (p.deviceLatencyMs?.let { "$it ms" } ?: "Not reported")
                    rows += "Output" to (p.deviceName ?: "–")
                }
                AppState.hostDiag.value = rows
            }
        } catch (_: InterruptedException) {
        }
    }

    // ---------- app listeners (TCP) ----------

    private fun acceptLoop() {
        try {
            val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Audio.PORT)) }
            server = ss
            while (active) {
                val s = ss.accept()
                s.tcpNoDelay = true
                val c = Client(ids.getAndIncrement(), s)
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

    private inner class Client(val id: Int, private val socket: Socket) {
        @Volatile var name: String = "Listener"
        @Volatile var wantsPcm = false
        @Volatile var volume = 1f
        private val queue = LinkedBlockingDeque<Frame>(250)

        fun offer(f: Frame) { while (!queue.offerLast(f)) { queue.pollFirst(); overflow.incrementAndGet() } }
        fun offerUrgent(f: Frame) { while (!queue.offerFirst(f)) queue.pollLast() }

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
                            Msg.HELLO -> { name = f.data.reader().readUTF().take(30); updateListeners() }
                            Msg.PING -> {
                                val t0 = f.data.reader().readLong()
                                offerUrgent(Frame(Msg.PONG, bytes { writeLong(t0); writeLong(SystemClock.elapsedRealtime()) }))
                            }
                            Msg.WANT_PCM -> wantsPcm = true
                            Msg.REQUEST -> addRequest(name, f.data.reader().readUTF())
                            Msg.REACT -> react(name, f.data.reader().readUTF())
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

    // ---------- browser listeners (HTTP) ----------

    private inner class WebClient(val id: Int, val name: String, private val socket: Socket, private val out: OutputStream) {
        @Volatile var volume = 1f
        private val queue = LinkedBlockingDeque<ByteArray>(150)

        fun offer(t: Long, pcm: ShortArray) {
            val b = ByteBuffer.allocate(12 + pcm.size * 2)
            b.order(ByteOrder.BIG_ENDIAN).putLong(t).putInt(pcm.size * 2)
            b.order(ByteOrder.LITTLE_ENDIAN)
            val v = volume
            if (v >= 0.999f) for (s in pcm) b.putShort(s)
            else for (s in pcm) b.putShort((s * v).toInt().toShort())
            while (!queue.offerLast(b.array())) { queue.pollFirst(); overflow.incrementAndGet() }
        }

        fun run() {
            try {
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                    "Cache-Control: no-store\r\nX-Listener-Id: $id\r\n" +
                    "Access-Control-Expose-Headers: X-Listener-Id\r\nConnection: close\r\n\r\n").toByteArray())
                out.flush()
                while (active && !socket.isClosed) {
                    val b = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    out.write(b)
                    if (queue.isEmpty()) out.flush()
                }
            } catch (_: Exception) {
            } finally { close() }
        }

        fun close() {
            if (webClients.remove(this)) updateListeners()
            runCatching { socket.close() }
        }
    }

    private inner class WebServer {
        private var ss: ServerSocket? = null
        private val cache = HashMap<String, ByteArray>()

        fun start() = thread(name = "web") {
            try {
                val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Audio.WEB_PORT)) }
                ss = s
                while (active) {
                    val c = s.accept()
                    thread(name = "web-req") { handle(c) }
                }
            } catch (_: Exception) {
            }
        }

        fun stop() { runCatching { ss?.close() } }

        private fun asset(name: String): ByteArray = synchronized(cache) {
            cache.getOrPut(name) { assets.open(name).use { it.readBytes() } }
        }

        private fun readLine(inp: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val c = inp.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(c.toChar())
                if (sb.length > 8192) return null
            }
        }

        private fun query(s: String): MutableMap<String, String> {
            val m = mutableMapOf<String, String>()
            if (s.isEmpty()) return m
            s.split('&').forEach { kv ->
                val k = kv.substringBefore('=')
                val v = kv.substringAfter('=', "")
                runCatching { m[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8") }
            }
            return m
        }

        private fun send(out: OutputStream, code: Int, type: String, body: ByteArray, cacheable: Boolean = false) {
            val status = when (code) { 200 -> "OK"; 204 -> "No Content"; else -> "Not Found" }
            out.write(("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                (if (cacheable) "Cache-Control: max-age=86400\r\n" else "Cache-Control: no-store\r\n") +
                "Connection: close\r\n\r\n").toByteArray())
            out.write(body)
            out.flush()
        }

        private fun sendFile(out: OutputStream, f: File, type: String) {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${f.length()}\r\n" +
                "Content-Disposition: attachment; filename=\"SyncMusic.apk\"\r\nConnection: close\r\n\r\n").toByteArray())
            f.inputStream().use { it.copyTo(out, 64 * 1024) }
            out.flush()
        }

        private fun metaJson(id: Int?, since: Int): String {
            val j = JSONObject()
            j.put("host", hostName())
            j.put("title", meta.title)
            j.put("artist", meta.artist)
            j.put("playing", meta.playing)
            j.put("art", if (artBytes.isEmpty()) 0 else artVersion)
            j.put("version", appVersion(this@HostService))
            j.put("kicked", id != null && id in kicked)
            j.put("volume", (webClients.find { it.id == id }?.volume ?: 1f).toDouble())
            val arr = JSONArray()
            synchronized(reactionLog) {
                reactionLog.filter { it.first > since }.forEach { (seq, r) ->
                    arr.put(JSONObject().put("seq", seq).put("name", r.name).put("e", r.emoji))
                }
                j.put("seq", reactionSeq)
            }
            j.put("reactions", arr)
            return j.toString()
        }

        private fun handle(sock: Socket) {
            var keep = false
            try {
                sock.soTimeout = 15_000
                val inp = BufferedInputStream(sock.getInputStream())
                val line = readLine(inp) ?: return
                var len = 0
                while (true) {
                    val h = readLine(inp) ?: break
                    if (h.isEmpty()) break
                    val i = h.indexOf(':')
                    if (i > 0 && h.substring(0, i).trim().equals("content-length", true))
                        len = h.substring(i + 1).trim().toIntOrNull() ?: 0
                }
                val parts = line.split(" ")
                if (parts.size < 2) return
                val full = parts[1]
                val path = full.substringBefore('?')
                val q = query(full.substringAfter('?', ""))
                if (len in 1..4096) {
                    val body = ByteArray(len)
                    DataInputStream(inp).readFully(body)
                    query(String(body)).forEach { (k, v) -> q.putIfAbsent(k, v) }
                }
                val out = BufferedOutputStream(sock.getOutputStream(), 32_768)
                when (path) {
                    "/", "/index.html" -> send(out, 200, "text/html; charset=utf-8", asset("web/index.html"))
                    "/font.ttf" -> send(out, 200, "font/ttf", asset("web/font.ttf"), cacheable = true)
                    "/time" -> send(out, 200, "text/plain", SystemClock.elapsedRealtime().toString().toByteArray())
                    "/meta" -> send(out, 200, "application/json",
                        metaJson(q["id"]?.toIntOrNull(), q["since"]?.toIntOrNull() ?: 0).toByteArray())
                    "/art" -> send(out, 200, "image/jpeg", artBytes)
                    "/app.apk" -> sendFile(out, File(applicationInfo.sourceDir), "application/vnd.android.package-archive")
                    "/react" -> { react(q["name"] ?: "Guest", q["e"] ?: "❤️"); send(out, 204, "text/plain", ByteArray(0)) }
                    "/request" -> { addRequest(q["name"] ?: "Guest", q["text"] ?: ""); send(out, 204, "text/plain", ByteArray(0)) }
                    "/stream" -> {
                        keep = true
                        sock.soTimeout = 0
                        val name = q["name"]?.trim()?.take(30)?.ifBlank { null } ?: "Browser"
                        val w = WebClient(ids.getAndIncrement(), name, sock, out)
                        webClients += w
                        updateListeners()
                        w.run()
                    }
                    else -> send(out, 404, "text/plain", "Not found".toByteArray())
                }
            } catch (_: Exception) {
            } finally {
                if (!keep) runCatching { sock.close() }
            }
        }
    }

    override fun onDestroy() {
        active = false
        instance = null
        Media.listener = null
        HostControls.local = null
        source?.stop()
        ownPlayer?.stop()
        runCatching { server?.close() }
        web.stop()
        clients.forEach { it.close() }
        webClients.forEach { it.close() }
        clients.clear(); webClients.clear()
        encoder?.release()
        runCatching { projection?.stop() }
        projection = null
        locks(false)
        AppState.host.value = HostState()
        AppState.hostDiag.value = emptyList()
        AppState.nowPlaying.update { if (local) NowPlaying() else it }
        TileService.requestListeningState(this, ComponentName(this, ShareTile::class.java))
        super.onDestroy()
    }
}
