/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.project.lol.yt

import android.net.ConnectivityManager
import android.net.Uri
import com.project.lol.util.Logger
import com.project.lol.innertube.NewPipeExtractor
import com.project.lol.innertube.YouTube
import com.project.lol.innertube.models.YouTubeClient
import com.project.lol.innertube.models.YouTubeClient.Companion.ANDROID_CREATOR
import com.project.lol.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.project.lol.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_61_48
import com.project.lol.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.project.lol.innertube.models.YouTubeClient.Companion.IOS
import com.project.lol.innertube.models.YouTubeClient.Companion.IPADOS
import com.project.lol.innertube.models.YouTubeClient.Companion.MOBILE
import com.project.lol.innertube.models.YouTubeClient.Companion.TVHTML5
import com.project.lol.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.project.lol.innertube.models.YouTubeClient.Companion.WEB
import com.project.lol.innertube.models.YouTubeClient.Companion.WEB_CREATOR
import com.project.lol.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.project.lol.innertube.models.response.PlayerResponse
import com.project.lol.yt.AudioQuality
import com.project.lol.yt.cipher.CipherDeobfuscator
import com.project.lol.yt.potoken.PoTokenGenerator
import com.project.lol.yt.potoken.PoTokenResult
import com.project.lol.yt.sabr.EjsNTransformSolver
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"
    private const val TAG = "YTPlayerUtils"
    /** Max seconds to wait for signature-timestamp resolution before giving up. */
    private const val SIG_FUTURE_TIMEOUT_SEC = 10L
    /** Max seconds to wait for PoToken generation before giving up. */
    private const val POT_FUTURE_TIMEOUT_SEC = 14L

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val poTokenGenerator = PoTokenGenerator()

    private val MAIN_CLIENT: YouTubeClient = WEB_REMIX

    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        TVHTML5_SIMPLY_EMBEDDED_PLAYER,  // Try embedded player first for age-restricted content
        TVHTML5,
        ANDROID_VR_1_43_32,
        ANDROID_VR_1_61_48,
        ANDROID_CREATOR,
        IPADOS,
        ANDROID_VR_NO_AUTH,
        MOBILE,
        IOS,
        WEB,
        WEB_CREATOR
    )
    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
    )
    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT].
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS].
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        skipValidation: Boolean = false,
        preferredMimeType: String? = null,
    ): Result<PlaybackData> = runCatching {

        // Check if this is an uploaded/privately owned track
        val isUploadedTrack = playlistId == "MLPT" || playlistId?.contains("MLPT") == true

        val isLoggedIn = YouTube.cookie != null

        // Run signature-timestamp and PoToken generation in parallel — they are
        // independent and each takes 1-3s, so overlapping them nearly halves the
        // cold-start latency. Both are blocking calls, so we use Java futures on
        // the IO executor rather than coroutine async (runCatching is non-suspend).
        val sessionId = if (isLoggedIn) YouTube.dataSyncId else YouTube.visitorData
        val mainClientNeedsPoToken = MAIN_CLIENT.useWebPoTokens

        val sigFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            getSignatureTimestampOrNull(videoId)
        }
        val potFuture: java.util.concurrent.CompletableFuture<PoTokenResult?>? =
            if (mainClientNeedsPoToken && sessionId != null) {
                java.util.concurrent.CompletableFuture.supplyAsync {
                    try {                    poTokenGenerator.getWebClientPoToken(videoId, sessionId)
                    } catch (e: Exception) {
                        Logger.e(TAG, "PoToken generation failed: ${e.message}", e)
                        null
                    }
                }
            } else null

        // Both signature-timestamp and PoToken generation can hang indefinitely when
        // the app is in the background (WebView JS may never call back, or NewPipe
        // extraction may stall). Use bounded waits so the resolution pipeline can
        // still fall through to clients that don't require these tokens.
        val signatureTimestamp = try {
            sigFuture.get(SIG_FUTURE_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            Logger.w(TAG, "Signature timestamp timed out or failed: ${e.message}")
            SignatureTimestampResult(null, isAgeRestricted = false)
        }
        var poToken: PoTokenResult? = try {
            potFuture?.get(POT_FUTURE_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            Logger.w(TAG, "PoToken timed out or failed: ${e.message}")
            null
        }

        // If MAIN_CLIENT needs a PoToken but we couldn't get one (WebView missing, JS
        // blocked, network hostile), WEB_REMIX will return streams that 403 on play.
        // Skip it and go straight to the fallback chain.
        val skipMainClient = mainClientNeedsPoToken && poToken == null
        if (skipMainClient) {
            Logger.w(TAG, "PoToken unavailable — skipping MAIN_CLIENT and using fallback chain directly")
        }

        // Try WEB_REMIX with signature timestamp and poToken (same as before)
        var mainPlayerResponse = YouTube.player(videoId, playlistId, MAIN_CLIENT, signatureTimestamp.timestamp, poToken?.playerRequestPoToken).getOrThrow()

        var usedAgeRestrictedClient: YouTubeClient? = null
        val wasOriginallyAgeRestricted: Boolean

        // Check if WEB_REMIX response indicates age-restricted
        val mainStatus = mainPlayerResponse.playabilityStatus.status
        val isAgeRestrictedFromResponse = mainStatus in listOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "LOGIN_REQUIRED", "CONTENT_CHECK_REQUIRED")
        wasOriginallyAgeRestricted = isAgeRestrictedFromResponse

        if (isAgeRestrictedFromResponse && isLoggedIn) {
            // Age-restricted: use WEB_CREATOR directly (no NewPipe needed from here)
            Logger.i(TAG, "Age-restricted: using WEB_CREATOR for videoId=$videoId")
            val creatorResponse = YouTube.player(videoId, playlistId, WEB_CREATOR, null, null).getOrNull()
            if (creatorResponse?.playabilityStatus?.status == "OK") {
                mainPlayerResponse = creatorResponse
                usedAgeRestrictedClient = WEB_CREATOR
            }
        }

        // If we still don't have a valid response, throw

        val audioConfig = mainPlayerResponse.playerConfig?.audioConfig
        val videoDetails = mainPlayerResponse.videoDetails
        val playbackTracking = mainPlayerResponse.playbackTracking
        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var streamPlayerResponse: PlayerResponse? = null
        val retryMainPlayerResponse: PlayerResponse? = if (usedAgeRestrictedClient != null) mainPlayerResponse else null

        // Check current status
        val currentStatus = mainPlayerResponse.playabilityStatus.status
        val isAgeRestricted = currentStatus in listOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "LOGIN_REQUIRED", "CONTENT_CHECK_REQUIRED")

        if (isAgeRestricted) {
            Logger.i(TAG, "Age-restricted content detected: videoId=$videoId, status=$currentStatus")
        }

        // Check if this is a privately owned track (uploaded song)
        val isPrivateTrack = mainPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

        // For private tracks: use TVHTML5 (index 1) with PoToken + n-transform
        // For age-restricted: skip main client, start with fallbacks
        // For normal content: standard order
        val startIndex = when {
            isPrivateTrack -> 1  // TVHTML5
            isAgeRestricted -> 0
            skipMainClient -> 0  // MAIN_CLIENT streams unplayable without PoToken
            else -> -1
        }

        for (clientIndex in (startIndex until STREAM_FALLBACK_CLIENTS.size)) {
            Logger.d(TAG, "stream attempt: index=$clientIndex poToken=${poToken != null} sigTs=${signatureTimestamp.timestamp != null}")
            // reset for each client
            format = null
            streamUrl = null
            streamExpiresInSeconds = null

            // decide which client to use for streams and load its player response
            val client: YouTubeClient
            if (clientIndex == -1) {
                // try with streams from main client first (use retry response if available)
                client = MAIN_CLIENT
                streamPlayerResponse = retryMainPlayerResponse ?: mainPlayerResponse
            } else {
                // after main client use fallback clients
                client = STREAM_FALLBACK_CLIENTS[clientIndex]

                if (client.loginRequired && !isLoggedIn && YouTube.cookie == null) {
                    // skip client if it requires login but user is not logged in
                    continue
                }

                // Only pass poToken for clients that support it
                val clientPoToken = if (client.useWebPoTokens) poToken?.playerRequestPoToken else null
                // Skip signature timestamp for age-restricted (faster), use it for normal content
                val clientSigTimestamp = if (wasOriginallyAgeRestricted) null else signatureTimestamp.timestamp
                streamPlayerResponse =
                    YouTube.player(videoId, playlistId, client, clientSigTimestamp, clientPoToken).getOrNull()
            }

            // YouTube content substitution guard: a client with a mismatched
            // session can return a playable response for a DIFFERENT video (the
            // classic "plays the wrong song" bug). Never accept streams whose
            // videoId doesn't match what we asked for.
            val returnedVideoId = streamPlayerResponse?.videoDetails?.videoId
            if (returnedVideoId != null && returnedVideoId != videoId) {
                Logger.w(TAG, 
                    "Client ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName} " +
                        "returned WRONG video: $returnedVideoId != $videoId — skipping",
                )
                continue
            }

            // process current client response
            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {

                // Skip NewPipe for age-restricted content (NewPipe doesn't use our auth)
                val responseToUse = if (wasOriginallyAgeRestricted) {
                    streamPlayerResponse
                } else {
                    // Try to get streams using newPipePlayer method
                    val newPipeResponse = YouTube.newPipePlayer(videoId, streamPlayerResponse)
                    newPipeResponse ?: streamPlayerResponse
                }

                format =
                    findFormat(
                        responseToUse,
                        audioQuality,
                        connectivityManager,
                        preferredMimeType,
                    )

                if (format == null) {
                    continue
                }

                streamUrl = findUrlOrNull(format, videoId, responseToUse, skipNewPipe = wasOriginallyAgeRestricted)
                if (streamUrl == null) {
                    continue
                }

                // Apply n-transform for throttle parameter handling
                val currentClient = if (clientIndex == -1) {
                    usedAgeRestrictedClient ?: MAIN_CLIENT
                } else {
                    STREAM_FALLBACK_CLIENTS[clientIndex]
                }
                Logger.i(TAG, "stream resolved: client=${currentClient.clientName} urlChars=${streamUrl.length}")

                // Check if this is a privately owned track
                val isPrivatelyOwnedTrack = streamPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"
                val musicVideoType = streamPlayerResponse.videoDetails?.musicVideoType

                // Apply n-transform and PoToken for web clients OR for private tracks (including TVHTML5)
                val needsNTransform = currentClient.useWebPoTokens ||
                    currentClient.clientName in listOf("WEB", "WEB_REMIX", "WEB_CREATOR", "TVHTML5") ||
                    isPrivatelyOwnedTrack

                if (needsNTransform) {
                    try {

                        val originalUrl = streamUrl
                        // Use CipherDeobfuscator for n-transform (fixed implementation)
                        streamUrl = CipherDeobfuscator.transformNParamInUrl(streamUrl)

                        // Append pot= parameter with streaming data poToken
                        val needsPoToken = (currentClient.useWebPoTokens || isPrivatelyOwnedTrack) && poToken?.streamingDataPoToken != null

                        if (needsPoToken) {
                            val separator = if ("?" in streamUrl) "&" else "?"
                            streamUrl = "${streamUrl}${separator}pot=${Uri.encode(poToken.streamingDataPoToken)}"
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "N-transform or pot append failed: ${e.message}", e)
                        Logger.e(TAG, "Stack trace: ${e.stackTraceToString().take(500)}")
                        // Continue with original URL
                    }
                } else {
                }

                streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds
                if (streamExpiresInSeconds == null) {
                    continue
                }

                // Check if this is a privately owned track (uploaded song)
                val isPrivatelyOwned = streamPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

                if (clientIndex == STREAM_FALLBACK_CLIENTS.size - 1 || isPrivatelyOwned) {
                    /** skip [validateStatus] for last client or private tracks */
                    if (isPrivatelyOwned) {
                    } else {
                    }
                    Logger.i(TAG, "Playback: client=${currentClient.clientName}, videoId=$videoId, private=$isPrivatelyOwned")
                    break
                }

                if (skipValidation || validateStatus(streamUrl)) {
                    // working stream found
                    // Log for release builds
                    Logger.i(TAG, "Playback: client=${currentClient.clientName}, videoId=$videoId")
                    break
                } else {
                }
            } else {
            }
        }

        if (streamPlayerResponse == null) {
            Logger.e(TAG, "Bad stream player response - all clients failed")
            throw Exception("Bad stream player response")
        }

        if (streamPlayerResponse.playabilityStatus.status != "OK") {
            val errorReason = streamPlayerResponse.playabilityStatus.reason
            Logger.e(TAG, "Playability status not OK: $errorReason")
            throw Exception(errorReason)
        }

        if (streamExpiresInSeconds == null) {
            Logger.e(TAG, "Missing stream expire time")
            throw Exception("Missing stream expire time")
        }

        if (format == null) {
            Logger.e(TAG, "Could not find format")
            throw Exception("Could not find format")
        }

        if (streamUrl == null) {
            Logger.e(TAG, "Could not find stream url")
            throw Exception("Could not find stream url")
        }

        PlaybackData(
            audioConfig,
            videoDetails,
            playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
        )
    }.onFailure { e ->
        Logger.e(TAG, "Playback failed for videoId=$videoId", e)
    }
    /**
     * Simple player response intended to use for metadata only.
     * Stream URLs of this response might not work so don't use them.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        return YouTube.player(videoId, playlistId, client = WEB_REMIX) // ANDROID_VR does not work with history
            .onSuccess { Logger.d(TAG, "Successfully fetched metadata") }
            .onFailure { Logger.e(TAG, "Failed to fetch metadata", it) }
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        preferredMimeType: String? = null,
    ): PlayerResponse.StreamingData.Format? {

        val candidates = playerResponse.streamingData?.adaptiveFormats
            ?.filter { it.isAudio && it.isOriginal }
            ?.let { formats ->
                if (preferredMimeType == null) formats
                else {
                    val preferred = formats.filter { it.mimeType.startsWith(preferredMimeType) }
                    if (preferred.isNotEmpty()) preferred else formats
                }
            }
            ?: return null

        return candidates.maxByOrNull {
            it.bitrate * when (audioQuality) {
                AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                AudioQuality.HIGH -> 1
                AudioQuality.LOW -> -1
            } + (if (preferredMimeType == null && it.mimeType.startsWith("audio/webm")) 10240 else 0)
        }
    }

    /**
     * Checks if the stream url returns a successful status.
     *
     * Why the leniency: on slow mobile networks HEAD can time out or be rejected by edge
     * CDNs (405/403/410 on HEAD while GET works). If we treat those as "failed" we skip a
     * stream that actually plays. Rules here:
     *  - 2xx → valid
     *  - 405/403/410 → treat as valid (HEAD may be restricted; ExoPlayer will GET)
     *  - IOException (timeout/reset) → treat as valid; ExoPlayer has its own retry and
     *    killing the client here just cascades us down the fallback chain for no reason
     *  - other HTTP codes (4xx/5xx) → invalid
     */
    private fun validateStatus(url: String): Boolean {
        try {
            val requestBuilder = okhttp3.Request.Builder()
                .head()
                .url(url)

            YouTube.cookie?.let { cookie ->
                requestBuilder.addHeader("Cookie", cookie)
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            response.close()
            val code = response.code
            val accepted = response.isSuccessful || code == 405 || code == 403 || code == 410
            return accepted
        } catch (e: java.io.IOException) {
            // Network timeout / reset while HEAD-probing. The stream URL itself may still
            // be fine — let ExoPlayer attempt GET rather than burning a fallback client.
            Logger.w(TAG, "Stream URL HEAD probe failed (IO); accepting optimistically", e)
            return true
        } catch (e: Exception) {
            Logger.e(TAG, "Stream URL validation failed with exception", e)
            reportException(e)
        }
        return false
    }
    data class SignatureTimestampResult(
        val timestamp: Int?,
        val isAgeRestricted: Boolean
    )

    private fun getSignatureTimestampOrNull(videoId: String): SignatureTimestampResult {
        val result = NewPipeExtractor.getSignatureTimestamp(videoId)
        return result.fold(
            onSuccess = { timestamp ->
                SignatureTimestampResult(timestamp, isAgeRestricted = false)
            },
            onFailure = { error ->
                val isAgeRestricted = error.message?.contains("age-restricted", ignoreCase = true) == true ||
                    error.cause?.message?.contains("age-restricted", ignoreCase = true) == true
                if (isAgeRestricted) {
                    Logger.i(TAG, "Age-restricted detected early via NewPipe: videoId=$videoId")
                } else {
                    Logger.e(TAG, "Failed to get signature timestamp", error)
                    reportException(error)
                }
                SignatureTimestampResult(null, isAgeRestricted)
            }
        )
    }

    private suspend fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        playerResponse: PlayerResponse,
        skipNewPipe: Boolean = false
    ): String? {

        // First check if format already has a URL
        if (!format.url.isNullOrEmpty()) {
            return format.url
        }

        // Try custom cipher deobfuscation for signatureCipher formats
        val signatureCipher = format.signatureCipher ?: format.cipher
        if (!signatureCipher.isNullOrEmpty()) {
            val customDeobfuscatedUrl = CipherDeobfuscator.deobfuscateStreamUrl(signatureCipher, videoId)
            if (customDeobfuscatedUrl != null) {
                return customDeobfuscatedUrl
            }
        }

        // Skip NewPipe for age-restricted content
        if (skipNewPipe) {
            return null
        }

        // Try to get URL using NewPipeExtractor signature deobfuscation
        val deobfuscatedUrl = NewPipeExtractor.getStreamUrl(format, videoId)
        if (deobfuscatedUrl != null) {
            return deobfuscatedUrl
        }

        // Fallback: try to get URL from StreamInfo
        val streamUrls = YouTube.getNewPipeStreamUrls(videoId)
        if (streamUrls.isNotEmpty()) {
            val streamUrl = streamUrls.find { it.first == format.itag }?.second
            if (streamUrl != null) {
                return streamUrl
            }

            // If exact itag not found, try to find any audio stream
            val audioStream = streamUrls.find { urlPair ->
                playerResponse.streamingData?.adaptiveFormats?.any {
                    it.itag == urlPair.first && it.isAudio
                } == true
            }?.second

            if (audioStream != null) {
                return audioStream
            }
        }

        Logger.e(TAG, "Failed to get stream URL")
        return null
    }

    fun forceRefreshForVideo(videoId: String) {
    }
}
