package com.syncmusic

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Wire format: [type: 1 byte][length: 4 bytes][payload]. */
object Msg {
    // host → listener
    const val CSD = 1       // Opus codec setup
    const val OPUS = 2      // [captureTimeMs: long][opus packet]
    const val PCM = 3       // [captureTimeMs: long][raw pcm] (fallback)
    const val PONG = 4      // [t0: long][hostNow: long]
    const val META = 5      // title, artist, playing
    const val ART = 6       // jpeg bytes (empty = none)
    const val HOSTINFO = 7  // name, group ssid, group password, app version (long)
    const val VOLUME = 8    // float 0..1
    const val KICK = 9
    const val REACTION = 16 // name, emoji
    // listener → host
    const val HELLO = 10    // name
    const val PING = 11     // [t0: long]
    const val WANT_PCM = 12 // listener can't decode Opus
    const val REQUEST = 13  // song request text
    const val REACT = 14    // emoji
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

/** Lets the UI send messages over the listener's current connection. */
object ListenerLink {
    @Volatile var send: ((Int, ByteArray) -> Unit)? = null
}
