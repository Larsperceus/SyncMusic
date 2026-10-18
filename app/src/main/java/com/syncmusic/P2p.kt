package com.syncmusic

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper

/**
 * Wi-Fi Direct: the host creates a group with a fixed name and password (always at
 * 192.168.49.1); listeners join by tapping it, scanning its QR code, or rejoining.
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
        WifiP2pManager.P2P_UNSUPPORTED -> "This phone doesn't support Wi-Fi Direct."
        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Try again in a moment."
        else -> "Turn on Wi-Fi and try again."
    }

    private fun cb(ok: () -> Unit = {}, fail: (String) -> Unit = {}) =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() = ok()
            override fun onFailure(r: Int) = fail(reason(r))
        }

    fun createGroup(ssid: String, pass: String, done: (String?) -> Unit) {
        val m = manager ?: return done("Wi-Fi Direct isn't available on this phone.")
        m.removeGroup(channel, cb(ok = { main.postDelayed({ make(ssid, pass, done) }, 600) }, fail = { make(ssid, pass, done) }))
    }

    private fun make(ssid: String, pass: String, done: (String?) -> Unit) {
        val m = manager ?: return
        val cfg = runCatching {
            WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO).build()
        }.getOrNull()
        val onOk = { main.postDelayed({ readGroup() }, 800); done(null) }
        val plain = { m.createGroup(channel, cb(ok = onOk, fail = { done("Wi-Fi Direct failed. $it") })) }
        if (cfg == null) plain()
        else m.createGroup(channel!!, cfg, cb(ok = onOk, fail = { plain() }))
    }

    private fun readGroup() {
        manager?.requestGroupInfo(channel) { g ->
            if (g != null && g.isGroupOwner && !g.passphrase.isNullOrEmpty())
                AppState.group.value = JoinInfo(AppState.myName.value ?: "SyncMusic", g.networkName, g.passphrase)
        }
    }

    fun removeGroup() {
        AppState.group.value = null
        manager?.removeGroup(channel, cb())
    }

    fun discover(done: (String?) -> Unit) {
        val m = manager ?: return done("Wi-Fi Direct isn't available on this phone.")
        m.discoverPeers(channel, cb(ok = { done(null) }, fail = { done("Couldn't search. $it") }))
    }

    /** Join a group we have the name and password for: no invite popup on the host. */
    fun join(j: JoinInfo, done: (String?) -> Unit) {
        val m = manager ?: return done("Wi-Fi Direct isn't available on this phone.")
        val cfg = WifiP2pConfig.Builder().setNetworkName(j.ssid).setPassphrase(j.pass).build()
        m.connect(channel, cfg, cb(
            ok = { done("Joining ${j.name}…") },
            fail = { done("Couldn't join ${j.name}. Is it sharing right now? $it") }))
    }

    fun connect(address: String, done: (String?) -> Unit) {
        val m = manager ?: return done("Wi-Fi Direct isn't available on this phone.")
        val cfg = WifiP2pConfig().apply {
            deviceAddress = address
            groupOwnerIntent = 0
            wps.setup = WpsInfo.PBC
        }
        m.connect(channel, cfg, cb(
            ok = { done("Connecting… accept the invite on the sharing phone if it asks.") },
            fail = { done("Couldn't connect. $it") }))
    }
}
