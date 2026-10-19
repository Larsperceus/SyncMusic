package com.syncmusic

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.net.Uri
import android.os.PowerManager
import android.media.projection.MediaProjectionManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.syncmusic.ui.Actions
import com.syncmusic.ui.SyncApp
import com.syncmusic.ui.SyncTheme

@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {

    private var afterPerms: (() -> Unit)? = null
    private var requiredPerms: List<String> = emptyList()
    private var wantListen = false
    private var pendingName: String? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val action = afterPerms
            afterPerms = null
            if (granted(requiredPerms)) action?.invoke()
            else toast("Allow the permission to continue. You can change it in Settings › Apps › SyncMusic.")
        }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode != RESULT_OK || r.data == null) {
                toast("Sharing needs the screen-share permission. Only the sound is used.")
                return@registerForActivityResult
            }
            startForegroundService(Intent(this, HostService::class.java)
                .putExtra("code", r.resultCode).putExtra("data", r.data))
            startGroup()
        }

    private val localPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            uris.forEach { runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            startForegroundService(Intent(this, HostService::class.java)
                .putExtra("mode", "local")
                .putParcelableArrayListExtra("uris", ArrayList<Uri>(uris)))
            startGroup()
        }

    private fun startGroup() {
        if (granted(wifiPerms())) {
            val (ssid, pass) = Prefs.hostCreds(this)
            P2p.createGroup(ssid, pass) { AppState.p2pMessage.value = it }
        } else AppState.p2pMessage.value = "Nearby-devices permission is off, so friends need to connect by IP."
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { r ->
        val text = r.contents ?: return@registerForActivityResult
        val j = JoinInfo.fromQr(text)
        if (j == null) toast("That's not a SyncMusic code. Scan the code on the sharing phone.")
        else join(j)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val m = P2p.manager ?: return
            when (i.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> m.requestPeers(P2p.channel) { list ->
                    AppState.peers.value = list.deviceList.map { d ->
                        Peer(d.deviceName.ifBlank { d.deviceAddress }, d.deviceAddress,
                            d.isGroupOwner, d.status == WifiP2pDevice.CONNECTED)
                    }.sortedByDescending { it.hosting }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION ->
                    m.requestConnectionInfo(P2p.channel) { onConnectionInfo(it) }
                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION ->
                    AppState.searching.value = i.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, 0) ==
                        WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val d: WifiP2pDevice? = if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    AppState.myName.value = d?.deviceName?.ifBlank { null }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        P2p.init(this)
        Prefs.load(this)

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)

        val actions = Actions(
            savedIp = Prefs.savedIp(this),
            startSharing = ::startSharing,
            stopSharing = {
                stopService(Intent(this, HostService::class.java))
                P2p.removeGroup()
            },
            findHosts = ::findHosts,
            connectPeer = ::connectPeer,
            connectIp = { ip -> Prefs.saveIp(this, ip); startListener(ip, ip) },
            scanCode = {
                scanLauncher.launch(ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Scan the code on the sharing phone")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false))
            },
            rejoin = { AppState.lastHost.value?.let { join(it) } },
            stopListening = {
                stopService(Intent(this, ListenerService::class.java))
                P2p.removeGroup()
            },
            allowMediaAccess = {
                runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            },
            setSyncOffset = { Prefs.saveSyncOffset(this, it) },
            startLocal = {
                ensurePerms(emptyList(), wifiPerms() + notifPerm()) { localPicker.launch(arrayOf("audio/*")) }
            },
            react = { e ->
                val h = HostService.instance
                if (h != null) h.react(myName(), e)
                else ListenerLink.send?.invoke(Msg.REACT, bytes { writeUTF(e) })
            },
            request = { text ->
                val send = ListenerLink.send
                if (send == null) toast("Join a friend first.")
                else { send(Msg.REQUEST, bytes { writeUTF(text) }); toast("Request sent") }
            },
            setVolume = { id, v -> HostService.instance?.setVolume(id, v) },
            kick = { id -> HostService.instance?.kick(id) },
            dismissRequest = { id -> AppState.requests.value = AppState.requests.value.filterNot { it.id == id } },
            setAutoComp = { Prefs.saveAutoComp(this, it) },
            allowBattery = {
                runCatching {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            },
            installUpdate = { Updates.install(this, it) },
        )
        setContent { SyncTheme { SyncApp(actions) } }
        Updates.checkGithub(this)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra("autoShare", false) == true && !AppState.host.value.running) {
            i.removeExtra("autoShare")
            startSharing()
        }
    }

    private fun myName() = AppState.myName.value ?: Build.MODEL

    override fun onResume() {
        super.onResume()
        AppState.mediaAccess.value = Media.hasAccess(this)
        AppState.batteryOk.value = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        Media.start(this)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    // ---------- sharing ----------

    private fun startSharing() {
        ensurePerms(listOf(Manifest.permission.RECORD_AUDIO), wifiPerms() + notifPerm()) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            captureLauncher.launch(
                if (Build.VERSION.SDK_INT >= 34)
                    mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else mpm.createScreenCaptureIntent()
            )
        }
    }

    // ---------- listening ----------

    private fun findHosts() {
        ensurePerms(wifiPerms(), notifPerm()) {
            AppState.peers.value = emptyList()
            P2p.discover { AppState.p2pMessage.value = it }
        }
    }

    private fun join(j: JoinInfo) {
        ensurePerms(wifiPerms(), notifPerm()) {
            wantListen = true
            pendingName = j.name
            P2p.join(j) { AppState.p2pMessage.value = it }
        }
    }

    private fun connectPeer(p: Peer) {
        wantListen = true
        pendingName = p.name
        if (p.connected) P2p.manager?.requestConnectionInfo(P2p.channel) { onConnectionInfo(it) }
        else P2p.connect(p.address) { AppState.p2pMessage.value = it }
    }

    private fun onConnectionInfo(info: WifiP2pInfo?) {
        if (info == null || !info.groupFormed || info.isGroupOwner || !wantListen) return
        val ip = info.groupOwnerAddress?.hostAddress ?: return
        wantListen = false
        AppState.p2pMessage.value = null
        startListener(ip, pendingName ?: ip)
    }

    private fun startListener(ip: String, name: String) {
        ensurePerms(emptyList(), notifPerm()) {
            stopService(Intent(this, ListenerService::class.java))
            startForegroundService(Intent(this, ListenerService::class.java)
                .putExtra("ip", ip).putExtra("name", name))
        }
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
        permLauncher.launch(missing.toTypedArray())
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
