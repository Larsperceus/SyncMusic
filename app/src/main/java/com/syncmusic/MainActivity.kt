package com.syncmusic

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
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

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var ipInput: EditText
    private var pendingHost = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(t: String, size: Float = 15f, bold: Boolean = false, top: Int = 0) =
            TextView(this).apply {
                text = t; textSize = size
                if (bold) setTypeface(null, Typeface.BOLD)
                setPadding(0, (top * dp).toInt(), 0, (6 * dp).toInt())
            }.also { root.addView(it) }
        fun button(t: String, onClick: () -> Unit) =
            Button(this).apply { text = t; setOnClickListener { onClick() } }.also { root.addView(it) }

        label("SyncMusic", 28f, bold = true)
        label("Put both phones on the same Wi-Fi, or turn on a hotspot on one and join it with the other.")

        label("Phone that plays the music", 18f, bold = true, top = 18)
        label("This phone's IP: ${localIps().ifEmpty { "not connected to a network" }}", 16f)
        button("Start hosting") { startHost() }
        button("Stop hosting") { stopService(Intent(this, HostService::class.java)) }
        label("Then play something in YT Music (or any app). When asked, share the entire screen – only the audio is used.", 13f)

        label("Phone that listens (Bluetooth)", 18f, bold = true, top = 18)
        ipInput = EditText(this).apply {
            hint = "Host IP, e.g. 192.168.1.23"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(getPreferences(MODE_PRIVATE).getString("ip", ""))
        }.also { root.addView(it) }
        button("Start listening") { startListening() }
        button("Stop listening") { stopService(Intent(this, ListenerService::class.java)) }

        label("Status", 18f, bold = true, top = 18)
        status = label(SyncStatus.text, 16f)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        SyncStatus.observer = { status.text = it }
        status.text = SyncStatus.text
    }

    override fun onPause() {
        SyncStatus.observer = null
        super.onPause()
    }

    private fun startHost() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            pendingHost = true
            requestPermissions(perms.toTypedArray(), REQ_PERMS)
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34)
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        startActivityForResult(intent, REQ_CAPTURE)
    }

    private fun startListening() {
        val ip = ipInput.text.toString().trim()
        if (ip.isEmpty()) { SyncStatus.set("Enter the host phone's IP first"); return }
        getPreferences(MODE_PRIVATE).edit().putString("ip", ip).apply()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        stopService(Intent(this, ListenerService::class.java))
        startForegroundService(Intent(this, ListenerService::class.java).putExtra("ip", ip))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS && pendingHost) {
            pendingHost = false
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startHost()
            else SyncStatus.set("Audio permission is required to capture what's playing")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, HostService::class.java)
                .putExtra("code", resultCode).putExtra("data", data))
            SyncStatus.set("Starting host…")
        } else SyncStatus.set("Capture permission denied")
    }

    private fun localIps(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .joinToString("  /  ") { it.hostAddress ?: "" }
    }.getOrDefault("")

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_CAPTURE = 2
        private const val REQ_NOTIF = 3
    }
}
