package com.music.spotui.data.offline

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/*
 * Plain-JVM audio tagging used when a download is exported to Download/Spotifusion/ (see
 * [SharedAudioExporter]). Nothing here touches Android, so it is unit-tested on the JVM.
 *
 * The exported file must carry its own title / artist / album (and cover) so that any player
 * or file manager reads them without help from this app. Each writer leaves the audio bytes
 * untouched: only the container metadata is added.
 */

/** What is written into an exported file. Blank fields are left out. */
class AudioTags(
    val title: String,
    val artist: String,
    val album: String,
    /** A JPEG or PNG; null for none. */
    val cover: ByteArray? = null,
) {
    val coverIsPng: Boolean
        get() = cover != null && cover.size > 8 && cover[0] == 0x89.toByte() && cover[1] == 'P'.code.toByte()
}

private fun Int.be32() = byteArrayOf((this ushr 24).toByte(), (this ushr 16).toByte(), (this ushr 8).toByte(), this.toByte())

// ─────────────────────────────── MP4 / M4A ───────────────────────────────

/**
 * Adds iTunes-style metadata (`moov/udta/meta/ilst`) to an MP4/M4A file, copying every other
 * byte as it is. Handles both a plain MP4 (chunk offsets are shifted when `moov` precedes the
 * media data) and a fragmented one such as YouTube's. Returns false, leaving [dst] unusable,
 * when the layout is one it cannot rewrite safely; the caller then exports the file untagged.
 */
object Mp4Tagger {
    private class Box(val type: String, val start: Long, val size: Long, val header: Int)

    private val CONTAINERS = setOf("trak", "mdia", "minf", "stbl")

    fun tag(src: File, dst: File, tags: AudioTags): Boolean {
        RandomAccessFile(src, "r").use { raf ->
            val top = topLevel(raf) ?: return false
            val moov = top.firstOrNull { it.type == "moov" } ?: return false
            if (moov.header != 8 || moov.size > 64L * 1024 * 1024) return false
            val firstMdat = top.firstOrNull { it.type == "mdat" }?.start ?: Long.MAX_VALUE
            // sidx offsets are counted from the end of the sidx: a sidx before moov would shift.
            if (top.any { it.type == "sidx" && it.start < moov.start }) return false

            val old = ByteArray(moov.size.toInt()).also { raf.seek(moov.start); raf.readFully(it) }
            val body = ByteArrayOutputStream()
            var pos = 8
            while (pos + 8 <= old.size) {
                val sz = ByteBuffer.wrap(old, pos, 4).int
                if (sz < 8 || pos + sz > old.size) return false
                val type = String(old, pos + 4, 4, Charsets.ISO_8859_1)
                if (type != "udta") body.write(old, pos, sz) // an old udta is replaced by ours
                pos += sz
            }
            body.write(udta(tags))
            val inner = body.toByteArray()
            val moovNew = ByteArray(8 + inner.size)
            ByteBuffer.wrap(moovNew).putInt(moovNew.size).put("moov".toByteArray(Charsets.ISO_8859_1))
            System.arraycopy(inner, 0, moovNew, 8, inner.size)
            val delta = moovNew.size - old.size

            if (moov.start < firstMdat) { // media data comes after moov: it moves by delta
                if (!shiftChunkOffsets(moovNew, 8, moovNew.size, delta)) return false
                // A fragment that names its data by absolute offset would move too: not handled.
                for (b in top) if (b.type == "moof" && hasAbsoluteBase(raf, b)) return false
            }

            dst.outputStream().buffered(256 * 1024).use { out ->
                copyRange(raf, 0, moov.start, out)
                out.write(moovNew)
                copyRange(raf, moov.start + moov.size, raf.length(), out)
            }
        }
        return true
    }

    private fun topLevel(raf: RandomAccessFile): List<Box>? {
        val list = ArrayList<Box>()
        var pos = 0L
        val len = raf.length()
        val h = ByteArray(16)
        while (pos + 8 <= len) {
            raf.seek(pos); raf.readFully(h, 0, 8)
            var size = ByteBuffer.wrap(h, 0, 4).int.toLong() and 0xffffffffL
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                raf.readFully(h, 8, 8); size = ByteBuffer.wrap(h, 8, 8).long; header = 16
            } else if (size == 0L) size = len - pos
            if (size < header || pos + size > len) return null
            list += Box(type, pos, size, header)
            pos += size
        }
        return if (pos == len && list.isNotEmpty()) list else null
    }

    /** True when a moof's tfhd carries a base-data-offset (flag 0x1). */
    private fun hasAbsoluteBase(raf: RandomAccessFile, moof: Box): Boolean {
        val data = ByteArray(moof.size.toInt()).also { raf.seek(moof.start); raf.readFully(it) }
        var i = moof.header
        while (i + 8 <= data.size) {
            if (data[i + 4] == 't'.code.toByte() && data[i + 5] == 'f'.code.toByte() &&
                data[i + 6] == 'h'.code.toByte() && data[i + 7] == 'd'.code.toByte()
            ) {
                val flags = ByteBuffer.wrap(data, i + 8, 4).int and 0xffffff
                if (flags and 0x1 != 0) return true
            }
            i++
        }
        return false
    }

    /** Adds [delta] to every stco / co64 entry under [from, to) of [b]; false if malformed. */
    private fun shiftChunkOffsets(b: ByteArray, from: Int, to: Int, delta: Int): Boolean {
        var pos = from
        while (pos + 8 <= to) {
            val sz = ByteBuffer.wrap(b, pos, 4).int
            if (sz < 8 || pos + sz > to) return false
            when (String(b, pos + 4, 4, Charsets.ISO_8859_1)) {
                in CONTAINERS -> if (!shiftChunkOffsets(b, pos + 8, pos + sz, delta)) return false
                "stco" -> {
                    val n = ByteBuffer.wrap(b, pos + 12, 4).int
                    if (pos + 16 + n.toLong() * 4 > pos + sz) return false
                    val bb = ByteBuffer.wrap(b)
                    for (k in 0 until n) { val o = pos + 16 + k * 4; bb.putInt(o, bb.getInt(o) + delta) }
                }
                "co64" -> {
                    val n = ByteBuffer.wrap(b, pos + 12, 4).int
                    if (pos + 16 + n.toLong() * 8 > pos + sz) return false
                    val bb = ByteBuffer.wrap(b)
                    for (k in 0 until n) { val o = pos + 16 + k * 8; bb.putLong(o, bb.getLong(o) + delta) }
                }
            }
            pos += sz
        }
        return true
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val size = 8 + parts.sumOf { it.size }
        val out = ByteArrayOutputStream(size)
        out.write(size.be32()); out.write(type.toByteArray(Charsets.ISO_8859_1))
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun dataAtom(flags: Int, payload: ByteArray) =
        box("data", flags.be32(), ByteArray(4), payload)

    private fun textItem(type: ByteArray, value: String) =
        box(String(type, Charsets.ISO_8859_1), dataAtom(1, value.toByteArray(Charsets.UTF_8)))

    private fun udta(t: AudioTags): ByteArray {
        val items = ByteArrayOutputStream()
        fun text(key: ByteArray, v: String) { if (v.isNotBlank()) items.write(textItem(key, v)) }
        val c = 0xA9.toByte()
        text(byteArrayOf(c, 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte()), t.title)
        text(byteArrayOf(c, 'A'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte()), t.artist)
        text(byteArrayOf(c, 'a'.code.toByte(), 'l'.code.toByte(), 'b'.code.toByte()), t.album)
        text("aART".toByteArray(Charsets.ISO_8859_1), t.artist)
        t.cover?.let { items.write(box("covr", dataAtom(if (t.coverIsPng) 14 else 13, it))) }
        val hdlr = box("hdlr", ByteArray(4), ByteArray(4), "mdir".toByteArray(), "appl".toByteArray(), ByteArray(8), ByteArray(1))
        val meta = box("meta", ByteArray(4), hdlr, box("ilst", items.toByteArray()))
        return box("udta", meta)
    }
}

private fun copyRange(raf: RandomAccessFile, from: Long, to: Long, out: OutputStream) {
    raf.seek(from)
    val buf = ByteArray(256 * 1024)
    var left = to - from
    while (left > 0) {
        val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
        if (n < 0) throw IOException("unexpected end of file")
        out.write(buf, 0, n); left -= n
    }
}

// ─────────────────────────────────── MP3 ───────────────────────────────────

/** Writes an ID3v2.3 tag at the start of an MP3 (replacing a publisher's own ID3v2, if any). */
object Id3Tagger {
    fun tag(src: File, dst: File, tags: AudioTags) {
        val frames = ByteArrayOutputStream()
        fun text(id: String, v: String) {
            if (v.isBlank()) return
            val p = byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + v.toByteArray(Charsets.UTF_16LE)
            frames.write(frame(id, p))
        }
        text("TIT2", tags.title); text("TPE1", tags.artist); text("TALB", tags.album)
        tags.cover?.let {
            val mime = (if (tags.coverIsPng) "image/png" else "image/jpeg").toByteArray()
            frames.write(frame("APIC", byteArrayOf(0) + mime + byteArrayOf(0, 3, 0) + it))
        }
        val body = frames.toByteArray()
        RandomAccessFile(src, "r").use { raf ->
            val skip = existingId3Length(raf)
            dst.outputStream().buffered(256 * 1024).use { out ->
                out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0))
                val n = body.size
                out.write(byteArrayOf((n ushr 21 and 0x7f).toByte(), (n ushr 14 and 0x7f).toByte(), (n ushr 7 and 0x7f).toByte(), (n and 0x7f).toByte()))
                out.write(body)
                copyRange(raf, skip, raf.length(), out)
            }
        }
    }

    private fun frame(id: String, payload: ByteArray) =
        id.toByteArray(Charsets.ISO_8859_1) + payload.size.be32() + byteArrayOf(0, 0) + payload

    private fun existingId3Length(raf: RandomAccessFile): Long {
        if (raf.length() < 10) return 0
        val h = ByteArray(10).also { raf.seek(0); raf.readFully(it) }
        if (h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) return 0
        if (h[6] < 0 || h[7] < 0 || h[8] < 0 || h[9] < 0) return 0
        val size = (h[6].toInt() shl 21) or (h[7].toInt() shl 14) or (h[8].toInt() shl 7) or h[9].toInt()
        val footer = if (h[5].toInt() and 0x10 != 0) 10 else 0
        return (10 + size + footer).toLong().coerceAtMost(raf.length())
    }
}

// ─────────────────────────────── Ogg Opus ───────────────────────────────

/**
 * Writes Opus packets into an Ogg stream (RFC 7845), so a WebM/Opus download becomes a normal
 * `.opus` file with tags. The packets are copied as they are: same codec, same bits.
 */
class OggOpusWriter(private val out: OutputStream, private val serial: Int = 0x53504F46) {
    private var seq = 0
    private var granule = 0L
    private val seg = ArrayList<Int>()
    private val data = ByteArrayOutputStream()
    private var pageGranule = 0L
    private var lastPageGranule = 0L
    private var started = false

    /** [opusHead] is the 19-byte (or longer) "OpusHead" packet. */
    fun writeHeaders(opusHead: ByteArray, tags: AudioTags) {
        check(!started) { "headers already written" }
        started = true
        writeWhole(opusHead, bos = true)
        writeWhole(opusTags(tags), bos = false)
    }

    /** One Opus packet lasting [samples] samples at 48 kHz. */
    fun addPacket(packet: ByteArray, samples: Int) {
        val need = packet.size / 255 + 1
        if (seg.size + need > 255 || data.size() + packet.size > 16 * 1024) flush(eos = false)
        lace(packet.size).forEach { seg += it }
        data.write(packet)
        granule += samples
        pageGranule = granule
    }

    /**
     * Ends the stream. [totalSamples], when known (the container's duration plus the pre-skip),
     * trims the padding of the last Opus frame so the file is exactly as long as the source.
     */
    fun finish(totalSamples: Long = -1) {
        if (totalSamples in 1 until pageGranule && totalSamples > lastPageGranule) pageGranule = totalSamples
        flush(eos = true)
        out.flush()
    }

    private fun lace(n: Int): List<Int> = List(n / 255) { 255 } + (n % 255)

    /** A header packet on its own page(s); granule 0, spilling over pages when large. */
    private fun writeWhole(packet: ByteArray, bos: Boolean) {
        val lacing = lace(packet.size)
        var i = 0
        var off = 0
        var first = true
        while (i < lacing.size) {
            val take = minOf(255, lacing.size - i)
            val part = lacing.subList(i, i + take)
            val bytes = part.sum()
            val last = i + take >= lacing.size
            var type = 0
            if (!first) type = type or 0x01
            if (bos && first) type = type or 0x02
            page(type, if (last) 0L else -1L, part, packet, off, bytes)
            off += bytes; i += take; first = false
        }
    }

    private fun flush(eos: Boolean) {
        if (seg.isEmpty()) return
        val bytes = data.toByteArray()
        page(if (eos) 0x04 else 0, pageGranule, seg, bytes, 0, bytes.size)
        lastPageGranule = pageGranule
        seg.clear(); data.reset()
    }

    private fun page(type: Int, granulePos: Long, segments: List<Int>, payload: ByteArray, off: Int, len: Int) {
        val head = ByteBuffer.allocate(27 + segments.size).order(ByteOrder.LITTLE_ENDIAN)
        head.put("OggS".toByteArray()).put(0).put(type.toByte()).putLong(granulePos)
            .putInt(serial).putInt(seq++).putInt(0).put(segments.size.toByte())
        segments.forEach { head.put(it.toByte()) }
        val header = head.array()
        var crc = Crc.update(0, header, 0, header.size)
        crc = Crc.update(crc, payload, off, len)
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(22, crc)
        out.write(header)
        out.write(payload, off, len)
    }

    private fun opusTags(t: AudioTags): ByteArray {
        val vendor = "Spotifusion".toByteArray()
        val comments = ArrayList<ByteArray>()
        fun add(k: String, v: String) { if (v.isNotBlank()) comments += "$k=$v".toByteArray(Charsets.UTF_8) }
        add("TITLE", t.title); add("ARTIST", t.artist); add("ALBUM", t.album); add("ALBUMARTIST", t.artist)
        t.cover?.let { comments += ("METADATA_BLOCK_PICTURE=" + Base64.getEncoder().encodeToString(flacPicture(it, t.coverIsPng))).toByteArray() }
        val b = ByteArrayOutputStream()
        fun le32(n: Int) = b.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array())
        b.write("OpusTags".toByteArray()); le32(vendor.size); b.write(vendor); le32(comments.size)
        comments.forEach { le32(it.size); b.write(it) }
        return b.toByteArray()
    }

    private fun flacPicture(img: ByteArray, png: Boolean): ByteArray {
        val mime = (if (png) "image/png" else "image/jpeg").toByteArray()
        val b = ByteArrayOutputStream()
        b.write(3.be32()); b.write(mime.size.be32()); b.write(mime); b.write(0.be32()) // front cover, no description
        repeat(4) { b.write(0.be32()) } // width, height, depth, colours: unknown
        b.write(img.size.be32()); b.write(img)
        return b.toByteArray()
    }

    private object Crc {
        private val table = IntArray(256) { n ->
            var c = n shl 24
            repeat(8) { c = if (c < 0) (c shl 1) xor 0x04c11db7 else c shl 1 }
            c
        }
        fun update(start: Int, b: ByteArray, off: Int, len: Int): Int {
            var c = start
            for (i in off until off + len) c = (c shl 8) xor table[((c ushr 24) xor (b[i].toInt() and 0xff)) and 0xff]
            return c
        }
    }

    companion object {
        /** Samples (at 48 kHz) in one Opus packet, from its TOC byte (RFC 6716 §3.1). */
        fun packetSamples(p: ByteArray): Int {
            if (p.isEmpty()) return 0
            val toc = p[0].toInt() and 0xff
            val config = toc shr 3
            val frame = when {
                config < 12 -> intArrayOf(480, 960, 1920, 2880)[config % 4]
                config < 16 -> if (config % 2 == 0) 480 else 960
                else -> intArrayOf(120, 240, 480, 960)[config % 4]
            }
            val frames = when (toc and 3) {
                0 -> 1
                1, 2 -> 2
                else -> if (p.size > 1) p[1].toInt() and 0x3f else 0
            }
            return frame * frames
        }

        /** A 19-byte OpusHead for [channels] channels with [preSkip] samples to discard. */
        fun opusHead(channels: Int, preSkip: Int): ByteArray =
            ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
                .put("OpusHead".toByteArray()).put(1).put(channels.toByte()).putShort(preSkip.toShort())
                .putInt(48000).putShort(0).put(0).array()
    }
}

// ─────────────────────────────── File names ───────────────────────────────

/** Names for the files exported to Download/Spotifusion/. */
object ExportNames {
    private val ILLEGAL = Regex("""[\\/:*?"<>|\u0000-\u001f]""")
    private const val MAX = 120

    /** "Artist - Title", safe as a file name on shared storage; never blank. */
    fun base(artist: String, title: String): String {
        fun clean(s: String) = ILLEGAL.replace(s, " ").replace(Regex("""\s+"""), " ").trim()
        val a = clean(artist)
        val t = clean(title)
        val joined = when {
            a.isNotEmpty() && t.isNotEmpty() -> "$a - $t"
            t.isNotEmpty() -> t
            a.isNotEmpty() -> a
            else -> "Spotifusion track"
        }
        return joined.take(MAX).trim().trimEnd('.').ifBlank { "Spotifusion track" }
    }
}
