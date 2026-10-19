package com.syncmusic

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
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

    fun install(activity: Activity, offer: UpdateOffer) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AppState.updateProgress.value = UpdateProgress(error = "Allow SyncMusic to install updates, then tap Update again.")
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")))
            }
            return
        }
        if (AppState.updateProgress.value.active) return
        thread(name = "update-download") {
            try {
                AppState.updateProgress.value = UpdateProgress(active = true)
                val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                val f = File(dir, "update.apk")
                val c = URL(offer.url).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 8000; c.readTimeout = 15000
                if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
                val total = c.contentLengthLong
                c.inputStream.use { inp ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) AppState.updateProgress.value = UpdateProgress(true, (done * 100 / total).toInt())
                        }
                    }
                }
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", f)
                val i = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.runOnUiThread { runCatching { activity.startActivity(i) } }
                AppState.updateProgress.value = UpdateProgress()
            } catch (_: Exception) {
                AppState.updateProgress.value = UpdateProgress(error = "Couldn't download the update. Check the connection and try again.")
            }
        }
    }
}
