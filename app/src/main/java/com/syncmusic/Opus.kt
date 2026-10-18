package com.syncmusic

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val MIME = MediaFormat.MIMETYPE_AUDIO_OPUS

/** Android's built-in Opus encoder (Android 10+). ~128 kbit/s instead of 1.5 Mbit/s raw. */
class OpusEncoder(private val onCsd: (List<ByteArray>) -> Unit) {
    private val codec = MediaCodec.createEncoderByType(MIME)
    private val info = MediaCodec.BufferInfo()
    private var haveConfig = false

    init {
        val f = MediaFormat.createAudioFormat(MIME, Audio.SAMPLE_RATE, 2)
        f.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Audio.CHUNK * 4)
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun encode(pcm: ByteArray, len: Int, ptsUs: Long, out: (ByteArray, Long) -> Unit) {
        val i = codec.dequeueInputBuffer(5_000)
        if (i >= 0) {
            val b = codec.getInputBuffer(i)!!
            b.clear()
            val n = minOf(len, b.remaining())
            b.put(pcm, 0, n)
            codec.queueInputBuffer(i, 0, n, ptsUs, 0)
        }
        while (true) {
            val o = codec.dequeueOutputBuffer(info, 0)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!haveConfig) {
                    val list = (0..2).mapNotNull { k ->
                        codec.outputFormat.getByteBuffer("csd-$k")?.let { bb -> ByteArray(bb.remaining()).also { bb.get(it) } }
                    }
                    if (list.isNotEmpty()) onCsd(list)
                }
                continue
            }
            if (o < 0) break
            val bb = codec.getOutputBuffer(o)!!
            bb.position(info.offset); bb.limit(info.offset + info.size)
            val arr = ByteArray(info.size).also { bb.get(it) }
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                haveConfig = true
                onCsd(listOf(arr))
            } else if (arr.isNotEmpty()) {
                out(arr, info.presentationTimeUs)
            }
            codec.releaseOutputBuffer(o, false)
        }
    }

    fun release() { runCatching { codec.stop() }; codec.release() }
}

class OpusDecoder(csd: List<ByteArray>) {
    private val codec = MediaCodec.createDecoderByType(MIME)
    private val info = MediaCodec.BufferInfo()

    init {
        val f = MediaFormat.createAudioFormat(MIME, Audio.SAMPLE_RATE, 2)
        val all = csd.toMutableList()
        // A bare OpusHead needs codec-delay and seek-preroll (ns) as csd-1/csd-2.
        if (all.size == 1 && !String(all[0], Charsets.ISO_8859_1).contains("AOPUSHDR")) {
            all += le64(6_500_000L)
            all += le64(80_000_000L)
        }
        all.forEachIndexed { k, b -> f.setByteBuffer("csd-$k", ByteBuffer.wrap(b)) }
        codec.configure(f, null, null, 0)
        codec.start()
    }

    private fun le64(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()

    fun decode(packet: ByteArray, ptsUs: Long, out: (ByteArray, Long) -> Unit) {
        val i = codec.dequeueInputBuffer(10_000)
        if (i >= 0) {
            val b = codec.getInputBuffer(i)!!
            b.clear(); b.put(packet)
            codec.queueInputBuffer(i, 0, packet.size, ptsUs, 0)
        }
        while (true) {
            val o = codec.dequeueOutputBuffer(info, 0)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (o < 0) break
            val bb = codec.getOutputBuffer(o)!!
            bb.position(info.offset); bb.limit(info.offset + info.size)
            val arr = ByteArray(info.size).also { bb.get(it) }
            codec.releaseOutputBuffer(o, false)
            if (arr.isNotEmpty()) out(arr, info.presentationTimeUs)
        }
    }

    fun release() { runCatching { codec.stop() }; runCatching { codec.release() } }
}

fun csdPayload(list: List<ByteArray>) = bytes {
    writeByte(list.size)
    list.forEach { writeInt(it.size); write(it) }
}

fun parseCsd(data: ByteArray): List<ByteArray> {
    val r = data.reader()
    val n = r.readUnsignedByte()
    return (0 until n).map { ByteArray(r.readInt()).also { b -> r.readFully(b) } }
}
