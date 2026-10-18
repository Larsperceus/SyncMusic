package com.syncmusic

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/** Exists only so Android lets us read other apps' media sessions (song info + controls). */
class MediaListener : NotificationListenerService()

/** Watches the active music app: title, artist, artwork, and play/pause/skip. */
object Media {
    var listener: ((NowPlaying) -> Unit)? = null
    private var msm: MediaSessionManager? = null
    private var comp: ComponentName? = null
    private var controller: MediaController? = null
    private var appPackage: String? = null
    private val main = Handler(Looper.getMainLooper())

    fun hasAccess(ctx: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = push()
        override fun onPlaybackStateChanged(state: PlaybackState?) = push()
        override fun onSessionDestroyed() { refresh() }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }

    fun start(ctx: Context) {
        if (msm != null || !hasAccess(ctx)) return
        val app = ctx.applicationContext
        appPackage = app.packageName
        val m = app.getSystemService(MediaSessionManager::class.java)
        val c = ComponentName(app, MediaListener::class.java)
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, c, main)
            msm = m; comp = c
            refresh()
        } catch (_: SecurityException) {
        }
    }

    private fun refresh() = runCatching { pick(msm?.getActiveSessions(comp)) }

    private fun pick(list: List<MediaController>?) {
        val others = list.orEmpty().filter { it.packageName != appPackage }
        val c = others.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: others.firstOrNull()
        if (c?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = c
            c?.registerCallback(callback, main)
        }
        push()
    }

    private fun push() {
        val c = controller
        val m = c?.metadata
        val np = NowPlaying(
            title = m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = (m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty(),
            art = m?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART),
            playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
        val old = AppState.nowPlaying.value
        AppState.nowPlaying.value = if (np.art == null && np.title == old.title) np.copy(art = old.art) else np
        listener?.invoke(AppState.nowPlaying.value)
    }

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }
}
