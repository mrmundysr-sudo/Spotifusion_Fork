package com.music.spotui.data.offline

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Turns a WebM/Opus download (what YouTube serves at its best audio quality) into an Ogg Opus
 * `.opus` file with tags. The Opus packets are copied unchanged, so the audio is bit-identical;
 * only the container differs, because WebM cannot carry tags that players read.
 */
internal object WebmOpusRemuxer {
    /** False when [src] is not WebM/Opus or cannot be read; [dst] is then to be ignored. */
    fun remux(src: File, dst: File, tags: AudioTags): Boolean {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(src.path)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/opus"
            } ?: return false
            val fmt = ex.getTrackFormat(track)
            ex.selectTrack(track)

            val csd0 = fmt.getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } }
            val hasHead = csd0 != null && csd0.size >= 19 && String(csd0, 0, 8, Charsets.ISO_8859_1) == "OpusHead"
            val preSkip = when {
                hasHead -> ByteBuffer.wrap(csd0!!).order(ByteOrder.LITTLE_ENDIAN).getShort(10).toInt() and 0xffff
                else -> fmt.getByteBuffer("csd-1")?.takeIf { it.remaining() >= 8 }
                    ?.let { (it.duplicate().order(ByteOrder.nativeOrder()).getLong(it.position()) * 48L / 1_000_000L).toInt() } ?: 312
            }
            val head = if (hasHead) csd0!! else {
                val channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                if (channels > 2) return false // a channel map would be needed
                OggOpusWriter.opusHead(channels, preSkip)
            }

            var packets = 0
            dst.outputStream().buffered(256 * 1024).use { out ->
                val w = OggOpusWriter(out)
                w.writeHeaders(head, tags)
                val buf = ByteBuffer.allocate(64 * 1024)
                while (true) {
                    buf.clear()
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    val p = ByteArray(n)
                    buf.position(0); buf.limit(n); buf.get(p)
                    w.addPacket(p, OggOpusWriter.packetSamples(p))
                    packets++
                    ex.advance()
                }
                val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
                w.finish(if (durationUs > 0) durationUs * 48L / 1000L + preSkip else -1L)
            }
            return packets > 0
        } catch (e: Exception) {
            return false
        } finally {
            runCatching { ex.release() }
        }
    }
}
