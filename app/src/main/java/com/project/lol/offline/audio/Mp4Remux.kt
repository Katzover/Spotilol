package com.project.lol.offline.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.project.lol.util.Logger
import java.io.File
import java.nio.ByteBuffer

object Mp4Remux {
    private const val TAG = "Spl-DL"

    fun remux(input: File, output: File): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input.absolutePath)
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackFormat = f
                    extractor.selectTrack(i)
                    break
                }
            }
            val format = trackFormat ?: run {
                Logger.w(TAG, "mp4Remux: no audio track in ${input.name}")
                return false
            }

            val maxSampleSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else 0
            val buffer = ByteBuffer.allocate(maxOf(maxSampleSize, 256 * 1024))

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackIndex = muxer.addTrack(format)
            muxer.start()

            val info = MediaCodec.BufferInfo()
            while (true) {
                info.offset = 0
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break
                info.presentationTimeUs = extractor.sampleTime
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0
                muxer.writeSampleData(trackIndex, buffer, info)
                extractor.advance()
            }

            muxer.stop()
            return output.length() > 0
        } catch (e: Exception) {
            Logger.e(TAG, "mp4Remux: failed: ${e.message}", e)
            runCatching { output.delete() }
            return false
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }
}
