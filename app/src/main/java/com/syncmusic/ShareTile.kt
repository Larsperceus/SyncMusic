package com.syncmusic

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: tap to start sharing (opens the app for Android's permission) or stop. */
class ShareTile : TileService() {
    override fun onStartListening() {
        val t = qsTile ?: return
        val on = AppState.host.value.running
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "SyncMusic"
        t.subtitle = if (on) "Sharing" else "Share music"
        t.updateTile()
    }

    override fun onClick() {
        if (AppState.host.value.running) {
            startService(Intent(this, HostService::class.java).setAction(Act.STOP))
            return
        }
        val i = Intent(this, MainActivity::class.java)
            .putExtra("autoShare", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 7, i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(i)
        }
    }
}
