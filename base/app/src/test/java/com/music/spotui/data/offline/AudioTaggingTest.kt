package com.music.spotui.data.offline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class AudioTaggingTest {
    private val tags = AudioTags("Títle: One", "Artist", "Álbum", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte()))

    private fun tmp(): File = Files.createTempDirectory("tag").toFile()

    // ── names ──

    @Test fun fileNamesAreDescriptiveAndSafe() {
        assertEquals("Artist - Title", ExportNames.base("Artist", "Title"))
        assertEquals("AC DC - Back In Black", ExportNames.base("AC/DC", "Back In Black").replace("AC DC", "AC DC"))
        assertEquals("A B - What", ExportNames.base("A:B", "What?"))
        assertEquals("Only title", ExportNames.base("", "Only title"))
        assertEquals("Spotifusion track", ExportNames.base(" ", "..."))
        assertTrue(ExportNames.base("x".repeat(300), "y").length <= 120)
        assertFalse(ExportNames.base("a\nb", "c|d").contains(Regex("[\n|]")))
    }

    // ── MP3 ──

    @Test fun mp3TagReplacesPublisherTagAndKeepsAudio() {
        val dir = tmp()
        val audio = ByteArray(2000) { (it % 251).toByte() }
        // an old ID3v2 of 20 bytes of body
        val old = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 20) + ByteArray(20)
        val src = File(dir, "a.mp3").apply { writeBytes(old + audio) }
        val dst = File(dir, "b.mp3")
        Id3Tagger.tag(src, dst, tags)
        val out = dst.readBytes()
        assertEquals("ID3", String(out, 0, 3))
        val size = (out[6].toInt() shl 21) or (out[7].toInt() shl 14) or (out[8].toInt() shl 7) or out[9].toInt()
        assertArrayEquals(audio, out.copyOfRange(10 + size, out.size))
        val text = String(out, 10, size, Charsets.ISO_8859_1)
        assertTrue(text.contains("TIT2") && text.contains("TPE1") && text.contains("TALB") && text.contains("APIC"))
    }

    // ── MP4 ──

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val size = 8 + parts.sumOf { it.size }
        return ByteBuffer.allocate(size).putInt(size).put(type.toByteArray()).also { b -> parts.forEach { b.put(it) } }.array()
    }

    private fun stco(vararg offsets: Int): ByteArray {
        val b = ByteBuffer.allocate(8 + offsets.size * 4).putInt(0).putInt(offsets.size)
        offsets.forEach { b.putInt(it) }
        return box("stco", b.array())
    }

    private fun moovWith(stcoBox: ByteArray) =
        box("moov", box("mvhd", ByteArray(100)), box("trak", box("mdia", box("minf", box("stbl", stcoBox)))))

    private fun firstStco(file: ByteArray): Int {
        val i = String(file, Charsets.ISO_8859_1).indexOf("stco")
        return ByteBuffer.wrap(file, i + 12, 4).int
    }

    @Test fun mp4WithMoovFirstShiftsChunkOffsets() {
        val dir = tmp()
        val ftyp = box("ftyp", "M4A ".toByteArray(), ByteArray(4), "M4A mp42isom".toByteArray())
        val payload = ByteArray(500) { (it * 7).toByte() }
        val mdat = box("mdat", payload)
        // moov size does not depend on the offset value, so it can be computed first
        val moovSize = moovWith(stco(0)).size
        val dataStart = ftyp.size + moovSize + 8
        val src = File(dir, "a.m4a").apply { writeBytes(ftyp + moovWith(stco(dataStart)) + mdat) }
        val dst = File(dir, "b.m4a")
        assertTrue(Mp4Tagger.tag(src, dst, tags))
        val out = dst.readBytes()
        val grown = out.size - src.length().toInt()
        assertTrue(grown > 0)
        val newOffset = firstStco(out)
        assertEquals(dataStart + grown, newOffset)
        assertArrayEquals(payload, out.copyOfRange(newOffset, newOffset + payload.size))
        val text = String(out, Charsets.ISO_8859_1)
        assertTrue(text.contains("ilst") && text.contains("©nam") && text.contains("©ART") && text.contains("©alb") && text.contains("covr"))
    }

    @Test fun mp4WithMoovLastKeepsOffsetsAndReplacesOldUdta() {
        val dir = tmp()
        val ftyp = box("ftyp", "M4A ".toByteArray(), ByteArray(4), "M4A mp42isom".toByteArray())
        val payload = ByteArray(300) { it.toByte() }
        val mdat = box("mdat", payload)
        val offset = ftyp.size + 8
        val moov = box("moov", box("mvhd", ByteArray(100)), box("udta", ByteArray(40)),
            box("trak", box("mdia", box("minf", box("stbl", stco(offset))))))
        val src = File(dir, "a.m4a").apply { writeBytes(ftyp + mdat + moov) }
        val dst = File(dir, "b.m4a")
        assertTrue(Mp4Tagger.tag(src, dst, tags))
        val out = dst.readBytes()
        assertEquals(offset, firstStco(out))
        assertArrayEquals(payload, out.copyOfRange(offset, offset + payload.size))
        assertEquals(1, Regex("udta").findAll(String(out, Charsets.ISO_8859_1)).count())
    }

    @Test fun mp4RefusesWhatItCannotRewrite() {
        val dir = tmp()
        val junk = File(dir, "junk.m4a").apply { writeBytes(ByteArray(64) { 1 }) }
        assertFalse(Mp4Tagger.tag(junk, File(dir, "o.m4a"), tags))
    }

    // ── Ogg Opus ──

    private class Page(val type: Int, val granule: Long, val seq: Int, val segs: List<Int>, val body: ByteArray, val crcOk: Boolean)

    private fun crc(b: ByteArray): Int {
        var c = 0
        for (x in b) {
            c = c xor ((x.toInt() and 0xff) shl 24)
            repeat(8) { c = if (c < 0) (c shl 1) xor 0x04c11db7 else c shl 1 }
        }
        return c
    }

    private fun pages(d: ByteArray): List<Page> {
        val list = ArrayList<Page>()
        var pos = 0
        while (pos < d.size) {
            assertEquals("OggS", String(d, pos, 4))
            val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
            val n = d[pos + 26].toInt() and 0xff
            val segs = (0 until n).map { d[pos + 27 + it].toInt() and 0xff }
            val len = 27 + n + segs.sum()
            val copy = d.copyOfRange(pos, pos + len)
            val stored = bb.getInt(pos + 22)
            ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).putInt(22, 0)
            list += Page(d[pos + 5].toInt(), bb.getLong(pos + 6), bb.getInt(pos + 18), segs, d.copyOfRange(pos + 27 + n, pos + len), crc(copy) == stored)
            pos += len
        }
        return list
    }

    private fun packets(ps: List<Page>): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var cur = ByteArrayOutputStream()
        for (p in ps) {
            var off = 0
            for (s in p.segs) {
                cur.write(p.body, off, s); off += s
                if (s < 255) { out += cur.toByteArray(); cur = ByteArrayOutputStream() }
            }
        }
        return out
    }

    private fun toc(config: Int, c: Int) = byteArrayOf(((config shl 3) or c).toByte())

    @Test fun opusPacketDurations() {
        assertEquals(960, OggOpusWriter.packetSamples(toc(31, 0) + ByteArray(50)))      // CELT 20 ms
        assertEquals(1920, OggOpusWriter.packetSamples(toc(31, 1) + ByteArray(50)))     // two frames
        assertEquals(480, OggOpusWriter.packetSamples(toc(12, 0) + ByteArray(5)))       // hybrid 10 ms
        assertEquals(2880, OggOpusWriter.packetSamples(toc(3, 0) + ByteArray(5)))       // SILK 60 ms
        assertEquals(3 * 960, OggOpusWriter.packetSamples(byteArrayOf(((31 shl 3) or 3).toByte(), 3, 0, 0)))
    }

    @Test fun oggStreamIsWellFormed() {
        val head = OggOpusWriter.opusHead(2, 312)
        val audio = (0 until 400).map { i -> toc(31, 0) + ByteArray(40 + i % 300) { (i + it).toByte() } }
        val bigCover = ByteArray(150_000) { (it * 31).toByte() } // spills over several pages
        val out = ByteArrayOutputStream()
        val w = OggOpusWriter(out)
        w.writeHeaders(head, AudioTags("T", "A", "B", bigCover))
        audio.forEach { w.addPacket(it, OggOpusWriter.packetSamples(it)) }
        val total = 400L * 960 // the packets already include the pre-skip
        w.finish(total - 100)
        val ps = pages(out.toByteArray())
        assertTrue("every page CRC", ps.all { it.crcOk })
        assertEquals((0 until ps.size).toList(), ps.map { it.seq })
        assertEquals(2, ps[0].type); assertEquals(0L, ps[0].granule)
        assertEquals(4, ps.last().type and 4)
        assertEquals(total - 100, ps.last().granule)
        assertTrue(ps.dropLast(1).none { it.type and 4 != 0 })
        val pk = packets(ps)
        assertArrayEquals(head, pk[0])
        assertEquals("OpusTags", String(pk[1], 0, 8))
        assertEquals(400, pk.size - 2)
        for (i in 0 until 400) assertArrayEquals(audio[i], pk[i + 2])
        val tagText = String(pk[1], Charsets.ISO_8859_1)
        assertTrue(tagText.contains("TITLE=T") && tagText.contains("ARTIST=A") && tagText.contains("ALBUM=B") && tagText.contains("METADATA_BLOCK_PICTURE="))
        // granules never go backwards
        val g = ps.drop(2).map { it.granule }
        assertEquals(g.sorted(), g)
    }
}
