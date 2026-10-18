package com.syncmusic

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Wire format: [type: 1 byte][length: 4 bytes][payload]. */
object Msg {
    const val CSD = 1       // host → listener: Opus codec setup
    const val OPUS = 2      // [captureTimeMs: long][opus packet]
    const val PCM = 3       // [captureTimeMs: long][raw pcm] (fallback)
    const val PONG = 4      // [t0: long][hostNow: long]
    const val META = 5      // title, artist, playing
    const val ART = 6       // jpeg bytes (empty = none)
    const val HOSTINFO = 7  // name, group ssid, group password
    const val HELLO = 10    // listener → host: name
    const val PING = 11     // [t0: long]
    const val WANT_PCM = 12 // listener can't decode Opus
}

class Frame(val type: Int, val data: ByteArray)

fun DataOutputStream.writeFrame(f: Frame) {
    writeByte(f.type)
    writeInt(f.data.size)
    write(f.data)
}

fun DataInputStream.readFrame(): Frame {
    val type = readUnsignedByte()
    val len = readInt()
    if (len < 0 || len > 4_000_000) throw IOException("bad frame")
    val b = ByteArray(len)
    readFully(b)
    return Frame(type, b)
}

inline fun bytes(block: DataOutputStream.() -> Unit): ByteArray {
    val bo = ByteArrayOutputStream()
    DataOutputStream(bo).use { it.block() }
    return bo.toByteArray()
}

fun ByteArray.reader() = DataInputStream(inputStream())
