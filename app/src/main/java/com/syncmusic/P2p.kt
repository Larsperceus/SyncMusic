package com.syncmusic

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper

/**
 * Wi-Fi Direct helper. The host creates a group (it becomes the "access point",
 * always at 192.168.49.1); listeners join it. No router or internet involved.
 */
@SuppressLint("MissingPermission") // permissions are requested in MainActivity
object P2p {
    var manager: WifiP2pManager? = null
        private set
    var channel: WifiP2pManager.Channel? = null
        private set
    private val main = Handler(Looper.getMainLooper())

    fun init(ctx: Context) {
        if (manager != null) return
        val app = ctx.applicationContext
        manager = app.getSystemService(WifiP2pManager::class.java)
        channel = manager?.initialize(app, Looper.getMainLooper(), null)
    }

    private fun reason(code: Int) = when (code) {
        WifiP2pManager.P2P_UNSUPPORTED -> "this phone doesn't support Wi-Fi Direct"
        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy, try again"
        else -> "is Wi-Fi turned on? (code $code)"
    }

    private fun cb(ok: () -> Unit = {}, fail: (String) -> Unit = {}) =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() = ok()
            override fun onFailure(r: Int) = fail(reason(r))
        }

    fun createGroup(done: (String) -> Unit) {
        val m = manager ?: return done("Wi-Fi Direct unavailable")
        // Drop any old group first, then create a fresh one.
        m.removeGroup(channel, cb(ok = { main.postDelayed({ make(done) }, 600) }, fail = { make(done) }))
    }

    private fun make(done: (String) -> Unit) {
        manager?.createGroup(channel, cb(
            ok = { done("Wi-Fi Direct on. The other phone can now find this one.") },
            fail = { done("Wi-Fi Direct failed: $it. Use the IP instead.") }))
    }

    fun removeGroup() { manager?.removeGroup(channel, cb()) }

    fun discover(done: (String) -> Unit) {
        val m = manager ?: return done("Wi-Fi Direct unavailable")
        m.discoverPeers(channel, cb(
            ok = { done("Searching for nearby phones…") },
            fail = { done("Search failed: $it") }))
    }

    fun connect(address: String, done: (String) -> Unit) {
        val m = manager ?: return done("Wi-Fi Direct unavailable")
        val cfg = WifiP2pConfig().apply {
            deviceAddress = address
            groupOwnerIntent = 0          // we want to join, not host
            wps.setup = WpsInfo.PBC
        }
        m.connect(channel, cfg, cb(
            ok = { done("Connecting… accept the invitation on the host phone if asked.") },
            fail = { done("Connect failed: $it") }))
    }
}
