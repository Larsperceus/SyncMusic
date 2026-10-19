package com.syncmusic

import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Plays songs stored on the sharing phone: decodes them to 48 kHz stereo and hands out
 * 20 ms chunks stamped with the time they should count as "captured". Because SyncMusic
 * plays these itself, the sharing phone can delay its own sound and be in step too.
 */
class LocalSource(
    private val ctx: Context,
    private val uris: List<Uri>,
    private val emit: (Long, ShortArray) -> Unit,
    private val onMeta: (NowPlaying) -> Unit,
) {
    @Volatile private var alive = true
    @Volatile private var paused = false
    @Volatile private var jump = 0
    private var index = 0
    private var worker: Thread? = null
    private val chunk = ShortArray(Audio.CHUNK_FRAMES * 2)
    private var fill = 0
    private var startT = 0L
    private var frames = 0L
    private var current = NowPlaying()

    fun start() { worker = thread(name = "local-source") { runCatching { run() } } }
    fun stop() { alive = false; worker?.interrupt() }
    fun playPause() { paused = !paused; publish() }
    fun next() { jump = 1; paused = false }
    fun previous() { jump = -1; paused = false }

    private fun publish() = onMeta(current.copy(playing = !paused))

    private fun resetClock() {
        startT = SystemClock.elapsedRealtime() + 20
        frames = 0
    }

    private fun run() {
        resetClock()
        while (alive && uris.isNotEmpty()) {
            index = ((index % uris.size) + uris.size) % uris.size
            val uri = uris[index]
            current = meta(uri)
            publish()
            jump = 0
            val ok = runCatching { decode(uri) }.isSuccess
            if (!alive) break
            index += if (jump == -1) -1 else 1
            if (!ok) Thread.sleep(300)
        }
    }

    private fun push(s: ShortArray) {
        var i = 0
        while (i < s.size) {
            val n = minOf(chunk.size - fill, s.size - i)
            System.arraycopy(s, i, chunk, fill, n)
            fill += n; i += n
            if (fill == chunk.size) { emitChunk(); fill = 0 }
        }
    }

    private fun emitChunk() {
        if (paused) {
            while (paused && alive && jump == 0) Thread.sleep(50)
            resetClock()
        }
        val t = startT + frames * 1000 / Audio.SAMPLE_RATE
        val wait = t - SystemClock.elapsedRealtime() - 60     // send ~60 ms ahead of "capture" time
        if (wait > 0) Thread.sleep(wait)
        emit(t, chunk.copyOf())
        frames += Audio.CHUNK_FRAMES
    }

    private fun decode(uri: Uri) {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        val ti = (0 until ex.trackCount).first {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        ex.selectTrack(ti)
        val fmt = ex.getTrackFormat(ti)
        val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(fmt, null, null, 0)
        dec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var isFloat = false
        val resampler = Resampler()
        val info = MediaCodec.BufferInfo()
        var inDone = false
        try {
            while (alive && jump == 0) {
                if (!inDone) {
                    val i = dec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val b = dec.getInputBuffer(i)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = dec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    continue
                }
                if (o < 0) continue
                val bb = dec.getOutputBuffer(o)!!
                bb.position(info.offset); bb.limit(info.offset + info.size)
                bb.order(ByteOrder.LITTLE_ENDIAN)
                val raw: ShortArray = if (isFloat) {
                    val fb = bb.asFloatBuffer()
                    ShortArray(fb.remaining()) { (fb.get(it) * 32767f).roundToInt().coerceIn(-32768, 32767).toShort() }
                } else {
                    val sb = bb.asShortBuffer()
                    ShortArray(sb.remaining()).also { sb.get(it) }
                }
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                dec.releaseOutputBuffer(o, false)
                val stereo = toStereo(raw, ch)
                push(if (rate == Audio.SAMPLE_RATE) stereo else resampler.process(stereo, rate.toDouble() / Audio.SAMPLE_RATE))
                if (eos) break
            }
        } finally {
            runCatching { dec.stop() }
            dec.release()
            ex.release()
        }
    }

    private fun toStereo(s: ShortArray, ch: Int): ShortArray = when {
        ch == 2 -> s
        ch == 1 -> ShortArray(s.size * 2) { s[it / 2] }
        ch > 2 -> ShortArray(s.size / ch * 2) { k -> s[(k / 2) * ch + (k % 2)] }
        else -> s
    }

    private fun meta(uri: Uri): NowPlaying {
        val display = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.substringBeforeLast('.') ?: "Song ${index + 1}"
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val art = r.embeddedPicture?.let { b ->
                val o = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeByteArray(b, 0, b.size, o)
            }
            NowPlaying(
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.ifBlank { null } ?: display,
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                art = art,
                playing = true,
            )
        } catch (_: Exception) {
            NowPlaying(title = display, playing = true)
        } finally {
            runCatching { r.release() }
        }
    }
}
