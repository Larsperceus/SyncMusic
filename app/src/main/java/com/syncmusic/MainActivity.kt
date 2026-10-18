package com.syncmusic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var hostInfo: TextView
    private lateinit var p2pText: TextView
    private lateinit var peerList: LinearLayout
    private lateinit var ipInput: EditText

    private var myName: String? = null
    private var wantListen = false
    private var afterPerms: (() -> Unit)? = null
    private var requiredPerms: List<String> = emptyList()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val m = P2p.manager ?: return
            when (i.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION ->
                    m.requestPeers(P2p.channel) { showPeers(it.deviceList) }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION ->
                    m.requestConnectionInfo(P2p.channel) { onConnectionInfo(it) }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val d: WifiP2pDevice? = if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    myName = d?.deviceName
                    refreshHostInfo()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        P2p.init(this)
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(t: String, size: Float = 15f, bold: Boolean = false, top: Int = 0, parent: LinearLayout = root) =
            TextView(this).apply {
                text = t; textSize = size
                if (bold) setTypeface(null, Typeface.BOLD)
                setPadding(0, (top * dp).toInt(), 0, (6 * dp).toInt())
            }.also { parent.addView(it) }
        fun button(t: String, onClick: () -> Unit) =
            Button(this).apply { text = t; setOnClickListener { onClick() } }.also { root.addView(it) }

        label("SyncMusic", 28f, bold = true)
        label("Turn Wi-Fi on on both phones (no network or internet needed for the link).")

        label("Phone that plays the music", 18f, bold = true, top = 16)
        button("Start hosting") { startHost() }
        button("Stop hosting") {
            stopService(Intent(this, HostService::class.java))
            P2p.removeGroup()
        }
        hostInfo = label("", 14f)
        label("When asked, share the entire screen – only the audio is used. Then play music in YT Music (or any app).", 13f)

        label("Phone that listens (Bluetooth)", 18f, bold = true, top = 16)
        button("Find host phone") { findHosts() }
        peerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { root.addView(it) }
        button("Stop listening") {
            stopService(Intent(this, ListenerService::class.java))
            P2p.removeGroup()
        }
        label("Or, on the same Wi-Fi network: enter the host's IP", 13f, top = 8)
        ipInput = EditText(this).apply {
            hint = "e.g. 192.168.1.23"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(getPreferences(MODE_PRIVATE).getString("ip", ""))
        }.also { root.addView(it) }
        button("Connect by IP") {
            val ip = ipInput.text.toString().trim()
            if (ip.isEmpty()) SyncStatus.set("Enter the host's IP first")
            else {
                getPreferences(MODE_PRIVATE).edit().putString("ip", ip).apply()
                ensurePerms(emptyList(), notifPerm()) { startListener(ip) }
            }
        }

        label("Status", 18f, bold = true, top = 16)
        p2pText = label("", 14f)
        status = label(SyncStatus.text, 16f)

        setContentView(ScrollView(this).apply { addView(root) })

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        SyncStatus.observer = { status.text = it }
        status.text = SyncStatus.text
        refreshHostInfo()
    }

    override fun onPause() {
        SyncStatus.observer = null
        super.onPause()
    }

    // ---------- host ----------

    private fun startHost() {
        ensurePerms(listOf(Manifest.permission.RECORD_AUDIO), wifiPerms() + notifPerm()) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val intent = if (Build.VERSION.SDK_INT >= 34)
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else mpm.createScreenCaptureIntent()
            startActivityForResult(intent, REQ_CAPTURE)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) { SyncStatus.set("Capture permission denied"); return }
        startForegroundService(Intent(this, HostService::class.java)
            .putExtra("code", resultCode).putExtra("data", data))
        SyncStatus.set("Starting host…")
        if (granted(wifiPerms())) P2p.createGroup { p2pText.text = it; refreshHostInfo() }
        else p2pText.text = "Wi-Fi Direct needs the nearby-devices permission. Use the IP instead."
    }

    private fun refreshHostInfo() {
        if (!::hostInfo.isInitialized) return
        val ips = localIps()
        hostInfo.text = buildString {
            append("This phone appears as: ").append(myName ?: "(turn Wi-Fi on)")
            append("\nIP: ").append(ips.ifEmpty { "none" })
        }
    }

    // ---------- listener ----------

    private fun findHosts() {
        ensurePerms(wifiPerms(), notifPerm()) {
            peerList.removeAllViews()
            P2p.discover { p2pText.text = it }
        }
    }

    private fun showPeers(devices: Collection<WifiP2pDevice>) {
        peerList.removeAllViews()
        devices.sortedByDescending { it.isGroupOwner }.forEach { d ->
            peerList.addView(Button(this).apply {
                text = d.deviceName.ifBlank { d.deviceAddress } + if (d.isGroupOwner) "  ▶ hosting" else ""
                setOnClickListener {
                    wantListen = true
                    if (d.status == WifiP2pDevice.CONNECTED)
                        P2p.manager?.requestConnectionInfo(P2p.channel) { onConnectionInfo(it) }
                    else P2p.connect(d.deviceAddress) { p2pText.text = it }
                }
            })
        }
        if (devices.isEmpty()) p2pText.text = "No phones found yet. Is the host started and Wi-Fi on?"
    }

    private fun onConnectionInfo(info: WifiP2pInfo?) {
        if (info == null || !info.groupFormed || info.isGroupOwner || !wantListen) return
        val ip = info.groupOwnerAddress?.hostAddress ?: return
        wantListen = false
        p2pText.text = "Connected over Wi-Fi Direct"
        startListener(ip)
    }

    private fun startListener(ip: String) {
        stopService(Intent(this, ListenerService::class.java))
        startForegroundService(Intent(this, ListenerService::class.java).putExtra("ip", ip))
    }

    // ---------- permissions ----------

    private fun wifiPerms() = if (Build.VERSION.SDK_INT >= 33)
        listOf(Manifest.permission.NEARBY_WIFI_DEVICES) else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun notifPerm() = if (Build.VERSION.SDK_INT >= 33)
        listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    private fun granted(perms: List<String>) =
        perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun ensurePerms(required: List<String>, optional: List<String>, action: () -> Unit) {
        val missing = (required + optional).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { action(); return }
        afterPerms = action
        requiredPerms = required
        requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val action = afterPerms
        afterPerms = null
        if (granted(requiredPerms)) action?.invoke()
        else SyncStatus.set("Permission needed: allow it in the prompt, or in Settings › Apps › SyncMusic")
    }

    private fun localIps(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .distinct()
            .joinToString("  /  ")
    }.getOrDefault("")

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_CAPTURE = 2
    }
}
