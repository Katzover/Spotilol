package com.project.lol.offline.audio

import com.project.lol.util.Logger
import java.io.File

/**
 * In-process WebM/Opus demuxer. MediaExtractor parses inside the media.extractor process, where
 * every advance() is a round trip (~1.4ms per packet on device). The container is simple enough
 * to walk here, and the files we download fit in memory.
 */
internal class WebmOpusReader private constructor(
    val data: ByteArray,
    private val offsets: IntArray,
    private val lengths: IntArray,
    val preSkip: Int,
    val gainQ8: Int
) {
    val frames: Int get() = offsets.size

    fun offsetAt(index: Int): Int = offsets[index]

    fun lengthAt(index: Int): Int = lengths[index]

    companion object {
        private const val TAG = "Spl-DL"

        private const val ID_SEGMENT = 0x18538067
        private const val ID_TRACKS = 0x1654AE6B
        private const val ID_TRACK_ENTRY = 0xAE
        private const val ID_TRACK_NUMBER = 0xD7
        private const val ID_TRACK_TYPE = 0x83
        private const val ID_CODEC_ID = 0x86
        private const val ID_CODEC_PRIVATE = 0x63A2
        private const val ID_CODEC_DELAY = 0x56AA
        private const val ID_CLUSTER = 0x1F43B675
        private const val ID_SIMPLE_BLOCK = 0xA3
        private const val ID_BLOCK_GROUP = 0xA0
        private const val ID_BLOCK = 0xA1

        private const val TRACK_TYPE_AUDIO = 2
        private const val CODEC_OPUS = "A_OPUS"
        private const val OPUS_HEAD_SIZE = 19
        private const val SAMPLES_PER_MILLISECOND = 48
        private const val MAX_FRAME_BYTES = 64 * 1024

        fun read(file: File, maxBytes: Long, expectedChannels: Int): WebmOpusReader? {
            if (file.length() !in 1..maxBytes) return null
            return try {
                Parser(file.readBytes(), expectedChannels).parse()
            } catch (t: Throwable) {
                Logger.w(TAG, "webm: in-process demux unavailable (${t.message}), using MediaExtractor")
                null
            }
        }
    }

    private class TrackEntry(val number: Int, val head: OpusHead?, val delay: Long?)

    private class OpusHead(val preSkip: Int, val gainQ8: Int, val channels: Int) {
        companion object {
            fun parse(bytes: ByteArray): OpusHead? {
                if (bytes.size < OPUS_HEAD_SIZE) return null
                if (String(bytes, 0, 8, Charsets.US_ASCII) != "OpusHead") return null
                val preSkip = (bytes[10].toInt() and 0xFF) or ((bytes[11].toInt() and 0xFF) shl 8)
                val gain = ((bytes[16].toInt() and 0xFF) or (bytes[17].toInt() shl 8))
                    .toShort().toInt()
                return OpusHead(preSkip, gain, bytes[9].toInt() and 0xFF)
            }
        }
    }

    private class IntTable {
        private var values = IntArray(1024)
        var size = 0
            private set

        fun add(value: Int) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        fun toArray(): IntArray = values.copyOf(size)
    }

    private class Header(val id: Int, val start: Int, val end: Int)

    private class Parser(private val data: ByteArray, private val expectedChannels: Int) {
        private var pos = 0
        private var lastVintLength = 0

        fun parse(): WebmOpusReader {
            val limit = data.size
            val stack = ArrayDeque<Int>()
            var track = -1
            var head: OpusHead? = null
            var delay: Long? = null
            val offsets = IntTable()
            val lengths = IntTable()

            while (pos < limit) {
                while (stack.isNotEmpty() && pos >= stack.last()) stack.removeLast()
                if (pos >= limit) break
                val header = header(if (stack.isEmpty()) limit else stack.last())
                when (header.id) {
                    ID_TRACKS -> {
                        val entry = parseTracks(header.start, header.end)
                        track = entry.number
                        head = entry.head
                        delay = entry.delay
                        pos = header.end
                    }
                    ID_SEGMENT, ID_CLUSTER, ID_BLOCK_GROUP -> {
                        stack.addLast(header.end)
                        pos = header.start
                    }
                    ID_SIMPLE_BLOCK, ID_BLOCK -> {
                        readBlock(header.start, header.end, track, offsets, lengths)
                        pos = header.end
                    }
                    else -> pos = header.end
                }
            }

            if (offsets.size == 0) throw IllegalStateException("no Opus packets found")
            val preSkip = when {
                head != null && head.preSkip > 0 -> head.preSkip
                delay != null && delay > 0 ->
                    (delay * SAMPLES_PER_MILLISECOND / 1_000_000L).toInt()
                else -> 0
            }
            return WebmOpusReader(
                data, offsets.toArray(), lengths.toArray(), preSkip, head?.gainQ8 ?: 0
            )
        }

        private fun parseTracks(start: Int, end: Int): TrackEntry {
            pos = start
            while (pos < end) {
                val entry = header(end)
                if (entry.id == ID_TRACK_ENTRY) {
                    val track = parseTrackEntry(entry.start, entry.end)
                    if (track != null) return track
                }
                pos = entry.end
            }
            throw IllegalStateException("no Opus audio track")
        }

        private fun parseTrackEntry(start: Int, end: Int): TrackEntry? {
            var number = -1
            var type = -1
            var codec: String? = null
            var private: ByteArray? = null
            var delay: Long? = null
            pos = start
            while (pos < end) {
                val child = header(end)
                when (child.id) {
                    ID_TRACK_NUMBER -> number = intValue(child).toInt()
                    ID_TRACK_TYPE -> type = intValue(child).toInt()
                    ID_CODEC_ID -> codec = String(
                        data, child.start, child.end - child.start, Charsets.US_ASCII
                    )
                    ID_CODEC_PRIVATE -> private = data.copyOfRange(child.start, child.end)
                    ID_CODEC_DELAY -> delay = intValue(child)
                }
                pos = child.end
            }
            if (type != TRACK_TYPE_AUDIO || codec?.startsWith(CODEC_OPUS) != true) return null
            if (number <= 0) throw IllegalStateException("Opus track without a track number")
            val head = private?.let { OpusHead.parse(it) }
            if (head != null && head.channels != 0 && head.channels != expectedChannels) {
                throw IllegalStateException("opus channels ${head.channels} != $expectedChannels")
            }
            return TrackEntry(number, head, delay)
        }

        private fun readBlock(start: Int, end: Int, track: Int, offsets: IntTable, lengths: IntTable) {
            if (track < 0) throw IllegalStateException("block before the track list")
            pos = start
            val number = readVint()
            pos += 2
            if (pos >= end) throw IllegalStateException("truncated block at $start")
            val flags = data[pos].toInt() and 0xFF
            pos++
            if (number.toInt() != track) return
            when ((flags shr 1) and 3) {
                0 -> addFrame(pos, end, offsets, lengths)
                1 -> xiphLacing(end, offsets, lengths)
                2 -> fixedLacing(end, offsets, lengths)
                else -> ebmlLacing(end, offsets, lengths)
            }
        }

        private fun xiphLacing(end: Int, offsets: IntTable, lengths: IntTable) {
            val count = frameCount(end)
            val sizes = IntArray(count) { 0 }
            for (i in 0 until count - 1) {
                var size = 0
                while (true) {
                    val byte = data[pos++].toInt() and 0xFF
                    size += byte
                    if (byte != 0xFF) break
                }
                sizes[i] = size
            }
            addLaced(pos, end, count, sizes, offsets, lengths)
        }

        private fun fixedLacing(end: Int, offsets: IntTable, lengths: IntTable) {
            val count = frameCount(end)
            val size = (end - pos) / count
            val sizes = IntArray(count) { size }
            addLaced(pos, end, count, sizes, offsets, lengths)
        }

        private fun ebmlLacing(end: Int, offsets: IntTable, lengths: IntTable) {
            val count = frameCount(end)
            val sizes = IntArray(count) { 0 }
            var size = readVint().toInt()
            sizes[0] = size
            for (i in 1 until count - 1) {
                size += readSignedVint().toInt()
                sizes[i] = size
            }
            addLaced(pos, end, count, sizes, offsets, lengths)
        }

        private fun addLaced(
            start: Int,
            end: Int,
            count: Int,
            sizes: IntArray,
            offsets: IntTable,
            lengths: IntTable
        ) {
            var offset = start
            for (i in 0 until count - 1) {
                addFrame(offset, offset + sizes[i], offsets, lengths)
                offset += sizes[i]
            }
            addFrame(offset, end, offsets, lengths)
        }

        private fun frameCount(end: Int): Int {
            if (pos >= end) throw IllegalStateException("truncated lacing header")
            return (data[pos++].toInt() and 0xFF) + 1
        }

        private fun addFrame(offset: Int, end: Int, offsets: IntTable, lengths: IntTable) {
            val length = end - offset
            if (offset < 0 || length <= 0 || length > MAX_FRAME_BYTES) {
                throw IllegalStateException("bad packet size $length")
            }
            offsets.add(offset)
            lengths.add(length)
        }

        private fun header(limit: Int): Header {
            val id = readId()
            val size = readVint()
            val start = pos
            val end = if (size < 0) limit else minOf(start + size, limit.toLong()).toInt()
            return Header(id, start, end)
        }

        private fun readId(): Int {
            val first = data[pos].toInt() and 0xFF
            var length = 0
            for (n in 1..4) {
                if (first and (1 shl (8 - n)) != 0) {
                    length = n
                    break
                }
            }
            if (length == 0) throw IllegalStateException("bad element id at $pos")
            var value = 0
            for (i in 0 until length) value = (value shl 8) or (data[pos + i].toInt() and 0xFF)
            pos += length
            return value
        }

        private fun readVint(): Long {
            val first = data[pos].toInt() and 0xFF
            var length = 0
            for (n in 1..8) {
                if (first and (1 shl (8 - n)) != 0) {
                    length = n
                    break
                }
            }
            if (length == 0) throw IllegalStateException("bad vint at $pos")
            var value = (first and ((1 shl (8 - length)) - 1)).toLong()
            for (i in 1 until length) value = (value shl 8) or (data[pos + i].toLong() and 0xFF)
            pos += length
            lastVintLength = length
            if (value == (1L shl (7 * length)) - 1) return -1L
            return value
        }

        private fun readSignedVint(): Long {
            val value = readVint()
            if (value < 0) throw IllegalStateException("unknown size in signed vint")
            return value - ((1L shl (7 * lastVintLength - 1)) - 1)
        }

        private fun intValue(header: Header): Long {
            var value = 0L
            for (i in header.start until header.end) {
                value = (value shl 8) or (data[i].toLong() and 0xFF)
            }
            return value
        }
    }
}
