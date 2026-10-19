package com.syncmusic

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import java.lang.reflect.Method
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

/** t = capture time on the host's clock (ms); pcm = interleaved stereo 48 kHz. */
class Chunk(val t: Long, val pcm: ShortArray)

/**
 * Plays every chunk at (capture time + target delay) on the host's clock.
 * - Measures how far behind it really is, including the headphones' own delay when Android reports it.
 * - Corrects small errors by playing up to 0.8% faster or slower (inaudible), big ones by skipping or waiting.
 */
class SyncPlayer(private val hostOffset: () -> Long?, private val onStart: () -> Unit = {}) {
    private val queue = ArrayBlockingQueue<Chunk>(300)
    @Volatile private var running = true
    private var worker: Thread? = null

    @Volatile var volume = 1f
    @Volatile var latencyMs: Int? = null; private set
    @Volatile var deviceLatencyMs: Int? = null; private set
    @Volatile var deviceName: String? = null; private set
    @Volatile var speedPct = 0.0; private set
    @Volatile var lateDrops = 0; private set
    @Volatile var gapMs = 0L; private set

    fun queuedMs() = queue.size * 20

    fun offer(c: Chunk) { while (!queue.offer(c)) queue.poll() }

    fun start() {
        worker = thread(name = "sync-player", priority = Thread.MAX_PRIORITY) { runCatching { play() } }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        runCatching { worker?.join(500) }
    }

    private fun play() {
        val sr = Audio.SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sr)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(max(minBuf * 2, Audio.CHUNK * 3))
            .build()
        val resampler = Resampler()
        val silence = ShortArray(Audio.CHUNK_FRAMES * 2)
        var written = 0L
        var smooth: Double? = null
        var lastVol = -1f
        var lastDev = 0L
        var devLat = 0
        var started = false

        fun writeSilence(frames: Int) {
            var left = frames
            while (left > 0) {
                val n = minOf(left, Audio.CHUNK_FRAMES)
                track.write(silence, 0, n * 2)
                written += n; left -= n
            }
        }

        try {
            track.play()
            while (running) {
                val c = try { queue.poll(300, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break } ?: continue
                val off = hostOffset() ?: continue
                if (!started) { started = true; onStart() }

                val v = volume
                if (v != lastVol) { track.setVolume(v); lastVol = v }

                val now = SystemClock.elapsedRealtime()
                if (now - lastDev > 1000) {
                    lastDev = now
                    val bufMs = track.bufferSizeInFrames * 1000 / sr
                    val raw = runCatching { getLatency?.invoke(track) as? Int }.getOrNull()
                    val d = raw?.let { it - bufMs }?.takeIf { it in 0..800 }
                    deviceLatencyMs = d
                    devLat = d ?: 0
                    deviceName = track.routedDevice?.let { describe(it) }
                }
                val comp = if (AppState.autoComp.value) devLat else 0

                val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                val pendingMs = (written - head).coerceAtLeast(0) * 1000.0 / sr
                val latency = now + pendingMs + comp + off - c.t
                val target = Audio.BASE_LATENCY_MS + AppState.syncOffsetMs.value
                val err = latency - target

                if (abs(err) > 60) {
                    smooth = null
                    resampler.reset()
                    if (err > 0) { lateDrops++; continue }      // running late: skip this bit
                    val gap = (-err).toLong()                  // running early: wait
                    gapMs += gap
                    writeSilence((gap * sr / 1000).toInt())
                    track.write(c.pcm, 0, c.pcm.size)
                    written += c.pcm.size / 2
                    latencyMs = target
                    continue
                }

                val s = smooth?.let { it * 0.92 + err * 0.08 } ?: err
                smooth = s
                latencyMs = (target + s).toInt()
                val speed = if (abs(s) < 3) 0.0 else (s / 2500.0).coerceIn(-0.008, 0.008)
                speedPct = speed * 100
                val out = resampler.process(c.pcm, 1.0 + speed)
                track.write(out, 0, out.size)
                written += out.size / 2
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    companion object {
        private val getLatency: Method? = runCatching { AudioTrack::class.java.getMethod("getLatency") }.getOrNull()

        fun describe(d: AudioDeviceInfo): String {
            val name = d.productName?.toString().orEmpty()
            return when (d.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER ->
                    if (name.isNotBlank()) "Bluetooth: $name" else "Bluetooth"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
                else -> name.ifBlank { "Audio output" }
            }
        }
    }
}
