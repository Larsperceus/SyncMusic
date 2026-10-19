package com.syncmusic

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object Updates {
    private const val RELEASES = "https://api.github.com/repos/Larsperceus/SyncMusic/releases/latest"

    /** Works when the repo is public; a private repo answers 404 and nothing happens. */
    fun checkGithub(ctx: Context) = thread(name = "update-check") {
        runCatching {
            val c = URL(RELEASES).openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 6000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode != 200) return@runCatching
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val v = j.getString("tag_name").removePrefix("build-").toLongOrNull() ?: return@runCatching
            val assets = j.getJSONArray("assets")
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
            }
            if (url != null && v > appVersion(ctx)) AppState.githubUpdate.value = UpdateOffer(v, url, "GitHub")
        }
    }

    /** Opens the download in the browser; Android then offers to install it. No install permission needed. */
    fun install(activity: Activity, offer: UpdateOffer) {
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(offer.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            AppState.updateProgress.value = UpdateProgress(error = "Couldn't open the download. Open ${offer.url} in your browser.")
        }
    }
}
