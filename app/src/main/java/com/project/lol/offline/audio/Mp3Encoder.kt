package com.project.lol.offline.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import com.project.lol.util.Logger
import com.project.lol.opus.OpusDecoder
import net.qiujuer.lame.Lame
import net.qiujuer.lame.Lame.LameModel
import net.qiujuer.lame.Lame.LameQuality
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object Mp3Encoder {
    private const val TAG = "Spl-DL"
    private const val MIME_OPUS = "audio/opus"
    private const val OPUS_SAMPLE_RATE = 48000
    private const val OPUS_MAX_FRAMES = 5760
    private const val OPUS_MAX_PACKET_BYTES = 64 * 1024
    private const val MAX_WEBM_BYTES = 32L * 1024 * 1024
    private const val DEFAULT_BITRATE = 320
    private const val MAX_FRAMES_PER_CALL = 8192
    private const val QUEUE_DEPTH = 16
    private const val POLL_TIMEOUT_MS = 100L
    private const val PROGRESS_MIN_INTERVAL_MS = 200L
    private const val FINISH_TIMEOUT_MIN = 10L

    private class PcmChunk(val samples: ShortArray) {
        var frames = 0
        var timeUs = 0L
    }

    private val opusAvailable: Boolean by lazy {
        runCatching { OpusDecoder.version() }
            .onFailure { Logger.w(TAG, "transcode: libopus unavailable, falling back to MediaCodec") }
            .isSuccess
    }

    private class Stats {
        val demuxNanos = AtomicLong()
        val decodeNanos = AtomicLong()
        val feedNanos = AtomicLong()
        val encodeNanos = AtomicLong()
        val buffers = AtomicLong()
        val frames = AtomicLong()
    }

    // Opus sources are demuxed from the WebM container in-process and decoded with the vendored
    // libopus: both MediaExtractor and MediaCodec parse in other processes, so every call to
    // them costs a round trip that dominated the transcode. PCM goes to a dedicated LAME thread
    // through a bounded queue, so decoding and encoding overlap. Other sources (AAC in m4a
    // containers) still go through MediaExtractor and MediaCodec's asynchronous callbacks.
    fun transcode(
        input: File,
        output: File,
        bitrateKbps: Int = DEFAULT_BITRATE,
        onProgress: ((Int) -> Unit)? = null
    ): Boolean {
        val startedAt = System.nanoTime()
        val stats = Stats()
        val extractor = MediaExtractor()
        val queue = ArrayBlockingQueue<PcmChunk>(QUEUE_DEPTH)
        val pool = ConcurrentLinkedQueue<PcmChunk>()
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val pcmDone = AtomicBoolean(false)
        var codec: MediaCodec? = null
        var codecThread: HandlerThread? = null
        var decoderThread: Thread? = null
        var encoderThread: Thread? = null

        try {
            val probe = probeTrack(input, extractor) ?: run {
                Logger.w(TAG, "transcode: no audio track in ${input.name}")
                return false
            }
            val mime = probe.mime
            val sampleRate = probe.sampleRate
            val channelCount = probe.channelCount
            val durationUs = probe.durationUs
            val pcmEncoding = probe.pcmEncoding
            val opusReader = if (mime == MIME_OPUS && sampleRate == OPUS_SAMPLE_RATE && opusAvailable) {
                val demuxStart = System.nanoTime()
                val reader = WebmOpusReader.read(input, MAX_WEBM_BYTES, channelCount)
                stats.demuxNanos.addAndGet(System.nanoTime() - demuxStart)
                reader
            } else {
                null
            }

            val poolSamples = MAX_FRAMES_PER_CALL * channelCount
            val model = if (channelCount == 1) LameModel.MONO else LameModel.JOINT_STEREO
            val lame = Lame(sampleRate, channelCount, sampleRate, bitrateKbps, model, LameQuality.OK)
            val mp3Buf = ByteArray(
                lame.getMp3bufferSize(MAX_FRAMES_PER_CALL).coerceAtLeast(7200)
            )

            fun obtainChunk(frames: Int): PcmChunk {
                val needed = frames * channelCount
                if (needed <= poolSamples) {
                    return pool.poll() ?: PcmChunk(ShortArray(poolSamples))
                }
                return PcmChunk(ShortArray(needed))
            }

            fun recycle(chunk: PcmChunk) {
                if (chunk.samples.size == poolSamples) pool.offer(chunk)
            }

            fun failed(): Boolean = failure.get() != null

            fun fail(cause: Throwable) {
                failure.compareAndSet(null, cause)
            }

            fun handOff(chunk: PcmChunk) {
                val start = System.nanoTime()
                while (!queue.offer(chunk, POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    if (failed()) return
                }
                stats.feedNanos.addAndGet(System.nanoTime() - start)
            }

            fun emit(source: ShortArray, offset: Int, frames: Int, timeUs: Long) {
                val chunk = obtainChunk(frames)
                System.arraycopy(
                    source, offset * channelCount, chunk.samples, 0, frames * channelCount
                )
                chunk.frames = frames
                chunk.timeUs = timeUs
                handOff(chunk)
            }

            encoderThread = Thread({
                val left = ShortArray(MAX_FRAMES_PER_CALL)
                val right = ShortArray(MAX_FRAMES_PER_CALL)
                var lastProgressAt = 0L
                try {
                    BufferedOutputStream(FileOutputStream(output)).use { out ->
                        while (true) {
                            val chunk = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            if (chunk == null) {
                                if (failed() || (pcmDone.get() && queue.isEmpty())) break
                                continue
                            }
                            var consumed = 0
                            var sample = 0
                            while (consumed < chunk.frames) {
                                val n = minOf(MAX_FRAMES_PER_CALL, chunk.frames - consumed)
                                if (channelCount == 2) {
                                    for (i in 0 until n) {
                                        left[i] = chunk.samples[sample]
                                        right[i] = chunk.samples[sample + 1]
                                        sample += 2
                                    }
                                } else {
                                    for (i in 0 until n) {
                                        left[i] = chunk.samples[sample]
                                        sample++
                                    }
                                }
                                val encodeStart = System.nanoTime()
                                val encoded = lame.encode(left, right, n, mp3Buf)
                                stats.encodeNanos.addAndGet(System.nanoTime() - encodeStart)
                                if (encoded < 0) {
                                    throw IllegalStateException("LAME encode error $encoded")
                                }
                                if (encoded > 0) {
                                    out.write(mp3Buf, 0, encoded)
                                }
                                consumed += n
                            }
                            stats.buffers.incrementAndGet()
                            stats.frames.addAndGet(chunk.frames.toLong())
                            if (onProgress != null && durationUs > 0) {
                                val now = System.nanoTime()
                                if (now - lastProgressAt >= PROGRESS_MIN_INTERVAL_MS * 1_000_000L) {
                                    lastProgressAt = now
                                    onProgress(
                                        (chunk.timeUs * 100 / durationUs).toInt().coerceIn(0, 100)
                                    )
                                }
                            }
                            recycle(chunk)
                        }
                        val flushed = lame.flush(mp3Buf)
                        if (flushed > 0) out.write(mp3Buf, 0, flushed)
                    }
                } catch (t: Throwable) {
                    fail(t)
                } finally {
                    lame.close()
                    finished.countDown()
                }
            }, "mp3-encode").apply { start() }

            val decoderLabel: String
            if (opusReader != null) {
                decoderLabel = "libopus ${OpusDecoder.version()} webm=in-process"
                decoderThread = Thread({
                    try {
                        decodeOpus(
                            opusReader, channelCount, stats,
                            shouldStop = { failed() },
                            emit = { source, offset, frames, timeUs ->
                                emit(source, offset, frames, timeUs)
                            }
                        )
                    } catch (t: Throwable) {
                        fail(t)
                    } finally {
                        pcmDone.set(true)
                    }
                }, "mp3-decode").apply { start() }
            } else {
                val format = probe.format
                if (pcmEncoding != AudioFormat.ENCODING_PCM_16BIT) {
                    Logger.w(TAG, "transcode: unsupported pcm encoding $pcmEncoding for ${input.name}")
                    return false
                }
                val handlerThread = HandlerThread("mp3-codec").apply { start() }
                codecThread = handlerThread
                var inputDone = false
                val callback = object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                        if (inputDone) return
                        try {
                            val buffer = codec.getInputBuffer(index)
                            if (buffer == null) return
                            val size = extractor.readSampleData(buffer, 0)
                            val timeUs = if (size >= 0) extractor.sampleTime else 0L
                            if (size >= 0) extractor.advance()
                            if (size < 0) {
                                inputDone = true
                                codec.queueInputBuffer(
                                    index, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                            } else {
                                codec.queueInputBuffer(index, 0, size, timeUs, 0)
                            }
                        } catch (t: Throwable) {
                            fail(t)
                        }
                    }

                    override fun onOutputBufferAvailable(
                        codec: MediaCodec,
                        index: Int,
                        info: MediaCodec.BufferInfo
                    ) {
                        try {
                            if (info.size > 0) {
                                val frames = info.size / (2 * channelCount)
                                val buffer = codec.getOutputBuffer(index)
                                if (frames > 0 && buffer != null) {
                                    val chunk = obtainChunk(frames)
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    buffer.order(ByteOrder.nativeOrder())
                                    buffer.asShortBuffer().get(
                                        chunk.samples, 0, frames * channelCount
                                    )
                                    chunk.frames = frames
                                    chunk.timeUs = info.presentationTimeUs
                                    handOff(chunk)
                                }
                            }
                            codec.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                pcmDone.set(true)
                            }
                        } catch (t: Throwable) {
                            fail(t)
                        }
                    }

                    // Rate, channel count and duration already come from the track probe, so the
                    // decoder's own output format carries nothing we need.
                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit

                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                        fail(e)
                    }
                }

                val decoder = MediaCodec.createDecoderByType(mime)
                codec = decoder
                decoder.setCallback(callback, Handler(handlerThread.looper))
                decoder.configure(format, null, null, 0)
                decoderLabel = decoder.name
                decoder.start()
            }

            Logger.i(
                TAG,
                String.format(
                    Locale.US,
                    "transcode: start file=%s size=%d mime=%s decoder=%s rate=%d ch=%d duration=%dms",
                    input.name, input.length(), mime, decoderLabel, sampleRate, channelCount,
                    durationUs / 1000
                )
            )

            val completed = finished.await(FINISH_TIMEOUT_MIN, TimeUnit.MINUTES)
            if (!completed) {
                pcmDone.set(true)
                encoderThread.join(5_000)
            }

            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            val audioMs = durationUs / 1000
            val speed = if (elapsedMs > 0) audioMs.toDouble() / elapsedMs else 0.0
            Logger.i(
                TAG,
                String.format(
                    Locale.US,
                    "transcode: done audio=%dms elapsed=%dms (%.1fx realtime) encode=%dms decode=%dms demux=%dms feedWait=%dms packets=%d frames=%d bitrate=%d",
                    audioMs, elapsedMs, speed,
                    stats.encodeNanos.get() / 1_000_000,
                    stats.decodeNanos.get() / 1_000_000,
                    stats.demuxNanos.get() / 1_000_000,
                    stats.feedNanos.get() / 1_000_000,
                    stats.buffers.get(), stats.frames.get(), bitrateKbps
                )
            )

            val cause = failure.get()
            if (!completed || cause != null || output.length() <= 0) {
                if (cause != null) {
                    Logger.e(TAG, "transcode: failed: ${cause.message}", cause)
                } else if (!completed) {
                    Logger.e(TAG, "transcode: timed out waiting for the encoder")
                } else {
                    Logger.w(TAG, "transcode: no output for ${input.name}")
                }
                runCatching { output.delete() }
                return false
            }
            return true
        } catch (e: Exception) {
            Logger.e(TAG, "transcode: failed: ${e.message}", e)
            runCatching { output.delete() }
            return false
        } finally {
            runCatching { decoderThread?.let { if (it.isAlive) it.join(5_000) } }
            runCatching { encoderThread?.let { if (it.isAlive) it.join(5_000) } }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { codecThread?.quitSafely() }
            runCatching { extractor.release() }
        }
    }

    private class TrackProbe(
        val mime: String,
        val sampleRate: Int,
        val channelCount: Int,
        val durationUs: Long,
        val pcmEncoding: Int,
        val format: MediaFormat
    )

    private fun probeTrack(input: File, extractor: MediaExtractor): TrackProbe? {
        extractor.setDataSource(input.absolutePath)
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("audio/")) continue
            extractor.selectTrack(i)
            return TrackProbe(
                mime = mime,
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else 0L,
                pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                },
                format = format
            )
        }
        return null
    }

    private fun decodeOpus(
        reader: WebmOpusReader,
        channelCount: Int,
        stats: Stats,
        shouldStop: () -> Boolean,
        emit: (ShortArray, Int, Int, Long) -> Unit
    ) {
        val decoder = OpusDecoder(OPUS_SAMPLE_RATE, channelCount, reader.gainQ8)
        val packet = ByteBuffer.allocateDirect(OPUS_MAX_PACKET_BYTES).order(ByteOrder.nativeOrder())
        val pcm = ShortArray(OPUS_MAX_FRAMES * channelCount)
        var skip = reader.preSkip
        var framesTotal = 0L
        try {
            for (i in 0 until reader.frames) {
                if (shouldStop()) return
                val length = reader.lengthAt(i)
                packet.clear()
                packet.put(reader.data, reader.offsetAt(i), length)

                val decodeStart = System.nanoTime()
                val frames = decoder.decode(packet, length, pcm, OPUS_MAX_FRAMES)
                stats.decodeNanos.addAndGet(System.nanoTime() - decodeStart)
                if (frames < 0) {
                    throw IllegalStateException("libopus decode error $frames")
                }

                val timeUs = framesTotal * 1_000_000L / OPUS_SAMPLE_RATE
                framesTotal += frames
                var offset = 0
                var count = frames
                if (skip > 0) {
                    val dropped = minOf(skip, count)
                    skip -= dropped
                    offset = dropped
                    count -= dropped
                }
                if (count > 0) emit(pcm, offset, count, timeUs)
            }
        } finally {
            decoder.close()
        }
    }
}
