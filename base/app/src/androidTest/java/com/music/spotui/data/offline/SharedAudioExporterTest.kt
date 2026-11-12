package com.music.spotui.data.offline

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs on a device or emulator: a downloaded file must land in Download/Spotifusion/ as an
 * ordinary file that a plain MediaPlayer plays (no help from this app's crypto or data source)
 * and that carries its own tags.
 */
@RunWith(AndroidJUnit4::class)
class SharedAudioExporterTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val tag = System.nanoTime().toString().takeLast(6)

    private fun fixture(name: String): File =
        File(context.cacheDir, "fx-$tag-$name").also { f ->
            inst.context.assets.open("export/$name").use { i -> f.outputStream().use { i.copyTo(it) } }
        }

    private fun durationOf(f: File): Int {
        val p = MediaPlayer()
        try {
            p.setDataSource(f.path); p.prepare(); return p.duration
        } finally {
            p.release()
        }
    }

    private fun publicFile(displayName: String) =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Spotifusion/$displayName")

    /** Plays [uri] with the platform player alone and reads its tags back. */
    private fun assertPlaysAndTagged(uri: android.net.Uri, title: String, artist: String, album: String, untaggedDuration: Int) {
        val mmr = MediaMetadataRetriever()
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { mmr.setDataSource(it.fileDescriptor) }
        assertEquals(title, mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
        assertEquals(artist, mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
        assertEquals(album, mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
        mmr.release()

        val player = MediaPlayer()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")!!.use { player.setDataSource(it.fileDescriptor) }
            player.prepare()
            // A fragmented MP4 without a sidx has no duration for Android (-1) even untagged:
            // the export must report whatever the untouched fixture reports.
            assertEquals(untaggedDuration, player.duration)
            if (untaggedDuration != -1) assertTrue("duration ${player.duration}", player.duration in 1000..2500)
            player.start()
            Thread.sleep(300)
            assertTrue(player.isPlaying)
            assertTrue(player.currentPosition > 0)
        } finally {
            player.release()
        }
    }

    private fun check(fixtureName: String, expectedExt: String) {
        val key = "t-$tag-$fixtureName"
        val title = "Song $tag: Part 1?"
        val artist = "Tëst Artist"
        val r = SharedAudioExporter.export(context, key, fixture(fixtureName), SharedAudioExporter.Meta(title, artist, "Álbum $tag"))
        assertFalse(r.alreadyExported)
        assertTrue(r.displayName.endsWith(".$expectedExt"))
        assertFalse(r.displayName.contains(Regex("[:?]")))

        // In the shared folder, under its descriptive name, readable by path like any file.
        val file = publicFile(r.displayName)
        assertTrue("$file exists", file.isFile && file.length() > 1000)

        // Listed by MediaStore under Download/Spotifusion/.
        context.contentResolver.query(
            r.uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null,
        )!!.use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Download/Spotifusion/", c.getString(0))
            assertEquals(0, c.getInt(1))
            assertEquals(r.displayName, c.getString(2))
        }
        assertPlaysAndTagged(r.uri, title, artist, "Álbum $tag", durationOf(fixture(fixtureName)))

        // The same track again: no second file.
        val again = SharedAudioExporter.export(context, key, fixture(fixtureName), SharedAudioExporter.Meta(title, artist, "Álbum $tag"))
        assertTrue(again.alreadyExported)
        assertEquals(r.uri, again.uri)

        // Another track with the very same name never overwrites the first.
        val other = SharedAudioExporter.export(context, key + "-b", fixture(fixtureName), SharedAudioExporter.Meta(title, artist, "Álbum $tag"))
        assertFalse(other.alreadyExported)
        assertNotEquals(r.uri, other.uri)
        assertNotEquals(r.displayName, other.displayName)
        assertTrue(publicFile(r.displayName).isFile && publicFile(other.displayName).isFile)

        context.contentResolver.delete(r.uri, null, null)
        context.contentResolver.delete(other.uri, null, null)
    }

    @Test fun fragmentedM4aKeepsAacAndGetsTags() = check("frag.m4a", "m4a")
    @Test fun plainM4aKeepsAacAndGetsTags() = check("plain.m4a", "m4a")
    @Test fun webmOpusBecomesTaggedOggOpus() = check("opus.webm", "opus")
    @Test fun mp3GetsTags() = check("clip.mp3", "mp3")

    @Test fun reExportsAfterTheUserDeletedTheFile() {
        val key = "t-$tag-deleted"
        val meta = SharedAudioExporter.Meta("Gone $tag", "A", "B")
        val first = SharedAudioExporter.export(context, key, fixture("plain.m4a"), meta)
        context.contentResolver.delete(first.uri, null, null)
        val second = SharedAudioExporter.export(context, key, fixture("plain.m4a"), meta)
        assertFalse(second.alreadyExported)
        context.contentResolver.delete(second.uri, null, null)
    }

    /**
     * Leaves one file per format in Download/Spotifusion/ so the CI job can list them with adb,
     * pull them and open them with a decoder that has nothing to do with Android or this app.
     */
    @Test fun leavesFilesForInspection() {
        for ((fx, label) in listOf("frag.m4a" to "m4a-fragmented", "plain.m4a" to "m4a-plain", "opus.webm" to "webm-opus", "clip.mp3" to "mp3")) {
            val r = SharedAudioExporter.export(
                context, "ci-evidence-$label-$tag", fixture(fx),
                SharedAudioExporter.Meta("CI check $label", "Spotifusion CI", "Shared storage test"),
            )
            assertTrue(publicFile(r.displayName).isFile)
        }
    }
}
