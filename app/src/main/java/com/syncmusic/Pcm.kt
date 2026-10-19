package com.syncmusic

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

fun bytesToShorts(b: ByteArray, len: Int = b.size): ShortArray {
    val n = len / 2
    val s = ShortArray(n)
    ByteBuffer.wrap(b, 0, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s)
    return s
}

fun shortsToBytes(s: ShortArray): ByteArray {
    val b = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    b.asShortBuffer().put(s)
    return b.array()
}

/**
 * Streaming linear-interpolation resampler for interleaved stereo 16-bit audio.
 * step = input frames consumed per output frame (1.0 = unchanged, >1 = faster/fewer frames).
 */
class Resampler {
    private var lastL = 0f
    private var lastR = 0f
    private var pos = 1.0

    fun reset() { pos = 1.0; lastL = 0f; lastR = 0f }

    fun process(inp: ShortArray, step: Double): ShortArray {
        val n = inp.size / 2
        if (n == 0) return inp
        var out = ShortArray((((n - pos) / step).toInt() + 4) * 2)
        var k = 0
        var p = pos
        while (p < n) {
            val i = p.toInt()
            val f = (p - i).toFloat()
            val aL: Float
            val aR: Float
            if (i == 0) { aL = lastL; aR = lastR } else { aL = inp[2 * (i - 1)].toFloat(); aR = inp[2 * (i - 1) + 1].toFloat() }
            val bL = inp[2 * i].toFloat()
            val bR = inp[2 * i + 1].toFloat()
            if (2 * k + 1 >= out.size) out = out.copyOf(out.size * 2)
            out[2 * k] = (aL + (bL - aL) * f).roundToInt().coerceIn(-32768, 32767).toShort()
            out[2 * k + 1] = (aR + (bR - aR) * f).roundToInt().coerceIn(-32768, 32767).toShort()
            k++
            p += step
        }
        pos = p - n
        lastL = inp[2 * (n - 1)].toFloat()
        lastR = inp[2 * (n - 1) + 1].toFloat()
        return out.copyOf(k * 2)
    }
}
