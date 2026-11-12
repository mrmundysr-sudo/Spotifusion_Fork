package com.music.spotui.data.offline

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Fork feature: a finished download is also saved, as an ordinary playable audio file, in
 * "Internal Storage/Download/Spotifusion/", through MediaStore (no storage permission needed).
 * The private, encrypted copy that the app plays is unchanged.
 *
 * - The audio keeps its codec and quality. M4A/MP4 and MP3 are tagged in place; WebM/Opus is
 *   remuxed to Ogg Opus (same packets) because WebM cannot carry tags; anything else is copied.
 * - Title, artist, album and cover are written into the file itself.
 * - Nothing is overwritten: MediaStore adds " (1)" to a name that is taken, and a song already
 *   exported (and still there) is not exported twice.
 */
object SharedAudioExporter {
    private const val TAG = "Export"
    private const val PREFS = "shared_audio_exports"
    private const val MAX_COVER = 2 * 1024 * 1024L

    /** MediaStore wants the path relative to the storage root. */
    val RELATIVE_PATH: String = Environment.DIRECTORY_DOWNLOADS + "/Spotifusion/"

    class Meta(val title: String, val artist: String, val album: String, val cover: File? = null)

    class Result(val uri: Uri, val displayName: String, val alreadyExported: Boolean)

    /**
     * Saves [plain] (the clear audio file) for [key] (the track id). Throws on failure: nothing
     * is left half-written in the shared folder.
     */
    fun export(context: Context, key: String, plain: File, meta: Meta): Result {
        val resolver = context.contentResolver
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        prefs.getString(key, null)?.let(Uri::parse)?.let { old ->
            if (stillThere(context, old)) return Result(old, "", alreadyExported = true)
        }

        val work = File(context.cacheDir, "export").apply { mkdirs() }
        val prepared = File(work, "prepared-${System.nanoTime()}")
        try {
            val tags = AudioTags(
                title = meta.title, artist = meta.artist, album = meta.album,
                cover = meta.cover?.takeIf { it.isFile && it.length() in 1..MAX_COVER }?.readBytes(),
            )
            val (ext, mime) = prepare(plain, prepared, tags)
            val name = ExportNames.base(meta.artist, meta.title) + "." + ext

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused $name")
            try {
                (resolver.openOutputStream(uri, "w") ?: throw IOException("cannot open $uri")).use { out ->
                    prepared.inputStream().use { it.copyTo(out, 256 * 1024) }
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            prefs.edit().putString(key, uri.toString()).apply()
            // MediaStore renames a name that is taken ("Name (1).m4a"): report the real one.
            val finalName = runCatching {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull() ?: name
            Log.i(TAG, "saved $finalName -> $uri")
            return Result(uri, finalName, alreadyExported = false)
        } finally {
            prepared.delete()
        }
    }

    /** [export] for the download pipeline: a failure is logged and shown, never fatal. */
    fun tryExport(context: Context, key: String, plain: File, meta: Meta): Boolean =
        runCatching { export(context, key, plain, meta) }
            .onFailure {
                Log.w(TAG, "${meta.title}: could not save to ${RELATIVE_PATH}: $it")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, "Could not save \"${meta.title}\" to Download/Spotifusion", android.widget.Toast.LENGTH_LONG).show()
                }
            }.isSuccess

    /** Writes the tagged copy to [dst]; returns its extension and MIME type. */
    private fun prepare(src: File, dst: File, tags: AudioTags): Pair<String, String> {
        val ext = src.extension.lowercase()
        when (ext) {
            "m4a", "mp4" -> if (runCatching { Mp4Tagger.tag(src, dst, tags) }.getOrDefault(false)) return "m4a" to "audio/mp4"
            "mp3" -> if (runCatching { Id3Tagger.tag(src, dst, tags) }.isSuccess) return "mp3" to "audio/mpeg"
            "webm" -> if (WebmOpusRemuxer.remux(src, dst, tags)) return "opus" to "audio/ogg"
        }
        // Untaggable or unknown layout: the audio is still saved, as it is.
        src.copyTo(dst, overwrite = true)
        return (ext.ifBlank { "m4a" }) to when (ext) {
            "m4a", "mp4" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "webm" -> "audio/webm"
            "aac" -> "audio/aac"
            "ogg", "opus" -> "audio/ogg"
            else -> "audio/mpeg"
        }
    }

    private fun stillThere(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } == true
    }.getOrDefault(false)
}
