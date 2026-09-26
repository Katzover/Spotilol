package com.project.lol.offline.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.project.lol.util.Logger
import java.io.File
import java.nio.ByteBuffer

object M4aEncoder {
    private const val TAG = "Spl-DL"
    private const val AAC_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val DEFAULT_BITRATE = 256000
    private const val TIMEOUT_US = 10_000L

    fun isTranscodable(mimeType: String): Boolean =
        mimeType.startsWith("audio/webm") || mimeType.startsWith("audio/opus") || mimeType.startsWith("audio/vorbis")

    fun transcode(
        input: File,
        output: File,
        bitrateKbps: Int = DEFAULT_BITRATE / 1000,
        onProgress: ((Int) -> Unit)? = null,
        shouldAbort: (() -> Boolean)? = null,
    ): Boolean {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var muxerStopped = false
        try {
            extractor.setDataSource(input.absolutePath)
            var srcFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    srcFormat = f
                    extractor.selectTrack(i)
                    break
                }
            }
            val inFormat = srcFormat ?: run {
                Logger.w(TAG, "m4aTranscode: no audio track in ${input.name}")
                return false
            }

            val sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            val decMime = inFormat.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(decMime)
            decoder.configure(inFormat, null, null, 0)
            decoder.start()

            val encFormat = MediaFormat.createAudioFormat(AAC_MIME, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            }
            encoder = MediaCodec.createEncoderByType(AAC_MIME)
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var decInputEOS = false
            var decOutputEOS = false
            var encInputEOS = false
            var sawOutputEOS = false
            var pendingDecOut = -1
            var pendingEos = false

            while (!sawOutputEOS) {
                if (shouldAbort?.invoke() == true) {
                    Logger.i(TAG, "m4aTranscode: aborted")
                    return false
                }
                var didWork = false

                if (!decInputEOS) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val ib = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(ib, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decInputEOS = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        didWork = true
                    }
                }

                if (pendingDecOut < 0 && !decOutputEOS) {
                    val outIdx = decoder.dequeueOutputBuffer(decInfo, 0)
                    if (outIdx >= 0) {
                        pendingDecOut = outIdx
                        pendingEos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    }
                }

                if (pendingDecOut >= 0) {
                    val encInIdx = encoder.dequeueInputBuffer(0)
                    if (encInIdx >= 0) {
                        didWork = true
                        if (pendingEos) {
                            decoder.releaseOutputBuffer(pendingDecOut, false)
                            pendingDecOut = -1
                            decOutputEOS = true
                            encoder.queueInputBuffer(encInIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encInputEOS = true
                        } else {
                            val encIn = encoder.getInputBuffer(encInIdx)!!
                            val ob = decoder.getOutputBuffer(pendingDecOut)!!
                            encIn.clear()
                            val chunk = minOf(decInfo.size, encIn.capacity())
                            ob.position(decInfo.offset)
                            ob.limit(decInfo.offset + chunk)
                            encIn.put(ob)
                            encoder.queueInputBuffer(encInIdx, 0, chunk, decInfo.presentationTimeUs, 0)
                            decoder.releaseOutputBuffer(pendingDecOut, false)
                            pendingDecOut = -1
                        }
                    }
                } else if (decOutputEOS && !encInputEOS) {
                    val encInIdx = encoder.dequeueInputBuffer(0)
                    if (encInIdx >= 0) {
                        encoder.queueInputBuffer(encInIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        encInputEOS = true
                        didWork = true
                    }
                }

                while (true) {
                    val encOutIdx = encoder.dequeueOutputBuffer(encInfo, 0)
                    when {
                        encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                            didWork = true
                        }
                        encOutIdx >= 0 -> {
                            val ob = encoder.getOutputBuffer(encOutIdx)!!
                            if (encInfo.size > 0 && muxerStarted && trackIndex >= 0) {
                                ob.position(encInfo.offset)
                                ob.limit(encInfo.offset + encInfo.size)
                                muxer.writeSampleData(trackIndex, ob, encInfo)
                                if (onProgress != null && durationUs > 0 && encInfo.presentationTimeUs > 0) {
                                    onProgress(
                                        (encInfo.presentationTimeUs * 100 / durationUs)
                                            .toInt().coerceIn(0, 100)
                                    )
                                }
                            }
                            encoder.releaseOutputBuffer(encOutIdx, false)
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEOS = true
                            }
                            didWork = true
                        }
                        else -> break
                    }
                }

                if (!didWork) {
                    Thread.sleep(10)
                }
            }

            muxer.stop()
            muxerStopped = true
            return output.length() > 0
        } catch (e: Exception) {
            Logger.e(TAG, "m4aTranscode: failed: ${e.message}", e)
            runCatching { output.delete() }
            return false
        } finally {
            runCatching { if (muxerStarted && !muxerStopped) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }
}
