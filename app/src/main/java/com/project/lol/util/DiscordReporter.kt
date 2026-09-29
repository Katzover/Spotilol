package com.project.lol.util

import com.project.lol.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Fire-and-forget anonymous event reporter. Posts a single embed to the
 * Discord webhook configured in local.properties (discordWebhook=...).
 * Empty/missing webhook => no-op. Failures are swallowed.
 */
object DiscordReporter {

    private const val TAG = "discord"

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "Discord-Worker").apply { isDaemon = true }
        }

    fun reportPossibleWrongSource(installed: String, latest: String, installer: String) {
        val webhook = BuildConfig.DISCORD_WEBHOOK
        if (webhook.isBlank()) return
        executor.execute {
            runCatching {
                val field = { name: String, value: String ->
                    JSONObject().put("name", name).put("value", value.ifBlank { "unknown" })
                }
                val embed = JSONObject()
                    .put("title", "Possible wrong-source install")
                    .put("color", 0xE0A030)
                    .put(
                        "fields",
                        JSONArray()
                            .put(field("installed", installed))
                            .put(field("latest", latest))
                            .put(field("installer", installer))
                    )
                val body = JSONObject().put("embeds", JSONArray().put(embed)).toString()

                val conn = URL(webhook).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                conn.disconnect()
                Logger.i(TAG, "wrong-source report sent (http $code)")
            }.onFailure {
                Logger.w(TAG, "wrong-source report failed: ${it.message}")
            }
        }
    }
}
