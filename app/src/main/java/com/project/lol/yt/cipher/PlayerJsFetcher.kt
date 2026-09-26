package com.project.lol.yt.cipher

import com.project.lol.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.project.lol.util.Logger
import java.io.File

/**
 * Fetches and caches YouTube's player.js for cipher operations.
 *
 * The player.js contains the signature deobfuscation and n-transform functions
 * that are required to access stream URLs on web clients.
 */
object PlayerJsFetcher {
    private const val TAG = "Metrolist_CipherFetcher"
    private const val IFRAME_API_URL = "https://www.youtube.com/iframe_api"
    private const val PLAYER_JS_URL_TEMPLATE = "https://www.youtube.com/s/player/%s/player_ias.vflset/en_GB/base.js"
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()

    // Regex to extract player hash from iframe_api response
    private val PLAYER_HASH_REGEX = Regex("""\\?/s\\?/player\\?/([a-zA-Z0-9_-]+)\\?/""")

    private fun getCacheDir(): File = File(CipherDeobfuscator.appContext.filesDir, "cipher_cache")

    private fun getCacheFile(hash: String): File = File(getCacheDir(), "player_$hash.js")

    private fun getHashFile(): File = File(getCacheDir(), "current_hash.txt")

    /**
     * Get player.js content and hash.
     *
     * Uses cached version if available and not expired, otherwise fetches fresh.
     * Returns Pair(playerJs, hash) or null if failed.
     */
    suspend fun getPlayerJs(forceRefresh: Boolean = false): Pair<String, String>? = withContext(Dispatchers.IO) {

        try {
            val cacheDir = getCacheDir()
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            // Check cache first (unless forced refresh)
            if (!forceRefresh) {
                val cached = readFromCache()
                if (cached != null) {
                    return@withContext cached
                }
            }

            // Fetch player hash from iframe_api
            val hash = fetchPlayerHash()
            if (hash == null) {
                Logger.e(TAG, "Failed to extract player hash from iframe_api")
                return@withContext null
            }

            // Download player JS
            val playerJs = downloadPlayerJs(hash)
            if (playerJs == null) {
                Logger.e(TAG, "Failed to download player JS for hash=$hash")
                return@withContext null
            }

            // Cache the result
            writeToCache(hash, playerJs)

            Pair(playerJs, hash)
        } catch (e: Exception) {
            Logger.e(TAG, "getPlayerJs exception: ${e.message}", e)
            null
        }
    }

    /**
     * Invalidate the player.js cache.
     * Call this when cipher operations fail to force a fresh fetch.
     */
    fun invalidateCache() {
        try {
            val cacheDir = getCacheDir()
            if (cacheDir.exists()) {
                val files = cacheDir.listFiles()
                files?.forEach {
                    it.delete()
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to invalidate cache: ${e.message}", e)
        }
    }

    private fun readFromCache(): Pair<String, String>? {
        try {
            val hashFile = getHashFile()
            if (!hashFile.exists()) {
                return null
            }

            val hashData = hashFile.readText().split("\n")
            if (hashData.size < 2) {
                return null
            }

            val hash = hashData[0]
            val timestamp = hashData[1].toLongOrNull()
            if (timestamp == null) {
                return null
            }

            val ageMs = System.currentTimeMillis() - timestamp
            val ageHours = ageMs / (1000 * 60 * 60)

            // Check TTL
            if (ageMs > CACHE_TTL_MS) {
                return null
            }

            val cacheFile = getCacheFile(hash)
            if (!cacheFile.exists()) {
                return null
            }

            val playerJs = cacheFile.readText()
            if (playerJs.isEmpty()) {
                return null
            }

            return Pair(playerJs, hash)
        } catch (e: Exception) {
            Logger.e(TAG, "Error reading cache: ${e.message}", e)
            return null
        }
    }

    private fun writeToCache(hash: String, playerJs: String) {
        try {
            val cacheDir = getCacheDir()

            // Clean old cache files
            val oldFiles = cacheDir.listFiles()?.filter { it.name.startsWith("player_") }
            oldFiles?.forEach { it.delete() }

            getCacheFile(hash).writeText(playerJs)
            getHashFile().writeText("$hash\n${System.currentTimeMillis()}")

        } catch (e: Exception) {
            Logger.e(TAG, "Error writing cache: ${e.message}", e)
        }
    }

    private fun fetchPlayerHash(): String? {

        val request = Request.Builder()
            .url(IFRAME_API_URL)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            Logger.e(TAG, "iframe_api HTTP ${response.code}")
            return null
        }

        val body = response.body.string()

        val match = PLAYER_HASH_REGEX.find(body)
        if (match == null) {
            Logger.e(TAG, "Could not find player hash in iframe_api response")
            return null
        }

        val hash = match.groupValues[1]
        return hash
    }

    private fun downloadPlayerJs(hash: String): String? {
        val url = PLAYER_JS_URL_TEMPLATE.format(hash)

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            Logger.e(TAG, "player.js download HTTP ${response.code}")
            return null
        }

        val body = response.body.string()

        return body
    }

    /**
     * Debug method: Get cache information
     */
    fun getCacheInfo(): Map<String, Any?> {
        return try {
            val hashFile = getHashFile()
            if (!hashFile.exists()) {
                return mapOf("exists" to false)
            }

            val hashData = hashFile.readText().split("\n")
            val hash = hashData.getOrNull(0)
            val timestamp = hashData.getOrNull(1)?.toLongOrNull()
            val cacheFile = hash?.let { getCacheFile(it) }

            mapOf(
                "exists" to true,
                "hash" to hash,
                "timestamp" to timestamp,
                "ageMs" to (timestamp?.let { System.currentTimeMillis() - it }),
                "fileExists" to (cacheFile?.exists() == true),
                "fileSize" to (cacheFile?.length()),
            )
        } catch (e: Exception) {
            mapOf("error" to e.message)
        }
    }
}
