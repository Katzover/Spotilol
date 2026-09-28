package com.project.lol.util

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class GitHubRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val publishedAt: String,
    val htmlUrl: String,
    val apkUrl: String
)

object GitHubApi {

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "GitHubApi-Worker").apply { isDaemon = true }
        }

    fun fetchLatestRelease(
        owner: String,
        repo: String,
        onResult: (GitHubRelease?) -> Unit
    ) {
        executor.execute {
            try {
                val url = URL("https://api.github.com/repos/$owner/$repo/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                val release = if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    var apkUrl = ""
                    json.optJSONArray("assets")?.let { assets ->
                        for (i in 0 until assets.length()) {
                            val asset = assets.optJSONObject(i)
                            if (asset != null && asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                                apkUrl = asset.optString("browser_download_url")
                                break
                            }
                        }
                    }
                    GitHubRelease(
                        tagName = json.optString("tag_name", ""),
                        name = json.optString("name", ""),
                        body = json.optString("body", ""),
                        publishedAt = json.optString("published_at", ""),
                        htmlUrl = json.optString("html_url", ""),
                        apkUrl = apkUrl
                    )
                } else {
                    null
                }
                conn.disconnect()

                Handler(Looper.getMainLooper()).post { onResult(release) }
            } catch (_: Exception) {
                Handler(Looper.getMainLooper()).post { onResult(null) }
            }
        }
    }
}