package dev.cyh.aamp.artwork

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.database.SQLException
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import java.io.File
import java.io.IOException

/**
 * Album covers as JPEG files in the app's cache directory, made on first request.
 *
 * A cover comes from an image in the album's folder (cover.jpg, folder.jpg and similar, which
 * needs the photos permission), else from the cover embedded in the audio files through
 * MediaStore's album thumbnail. Albums with neither are remembered in a bounded set so Android Auto
 * scrolling past them does not repeat the search.
 *
 * The directory is capped at [MAX_BYTES]: when a new cover pushes it over, the least recently
 * served files are deleted down to [TRIM_TO_BYTES]. Android may also clear it under storage
 * pressure; covers are then made again on demand.
 */
internal object ArtworkCache {
    private const val TAG = "AampArtwork"
    private const val DIR = "artwork"
    private const val SIZE_PX = 512
    private const val JPEG_QUALITY = 90
    private const val MAX_BYTES = 48L * 1024 * 1024
    private const val TRIM_TO_BYTES = 40L * 1024 * 1024
    private const val MAX_MISSING = 4096

    /** Folder image names, most preferred first; matched case-insensitively. */
    private val COVER_NAMES = listOf(
        "cover.jpg", "cover.jpeg", "cover.png", "cover.webp",
        "folder.jpg", "folder.jpeg", "folder.png",
        "front.jpg", "front.jpeg", "front.png",
        "album.jpg", "albumart.jpg",
    )

    private val trimLock = Any()

    // Album ids known to have no cover, oldest evicted first.
    private val missing = object : LinkedHashMap<Long, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Unit>?): Boolean = size > MAX_MISSING
    }

    /** The cover file for [albumId], made if needed; null when the album has no cover. */
    fun fileFor(context: Context, albumId: Long): File? {
        val dir = File(context.cacheDir, DIR)
        val file = File(dir, "$albumId.jpg")
        if (file.isFile) {
            // Recency for trimming: covers Android Auto keeps asking for survive longest.
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        if (synchronized(missing) { missing.containsKey(albumId) }) return null

        val resolver = context.contentResolver
        val bitmap = folderCover(resolver, albumId) ?: embeddedCover(resolver, albumId)
        if (bitmap == null) {
            synchronized(missing) { missing[albumId] = Unit }
            return null
        }
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
            // A unique temporary name lets concurrent requests for the same album race safely:
            // each rename installs a complete file.
            val tmp = File.createTempFile("$albumId-", ".tmp", dir)
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("Cannot rename ${tmp.name} to ${file.name}")
            }
            trim(dir)
            file
        } catch (e: IOException) {
            Log.w(TAG, "Could not cache album $albumId artwork", e)
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** Deletes every cached cover and forgets missing ones, so changed artwork is picked up. */
    fun clear(context: Context) {
        synchronized(missing) { missing.clear() }
        File(context.cacheDir, DIR).listFiles()?.forEach { it.delete() }
    }

    /** An image next to the album's first track, decoded at most [SIZE_PX] on its longer side. */
    private fun folderCover(resolver: ContentResolver, albumId: Long): Bitmap? {
        val folder = albumFolder(resolver, albumId) ?: return null
        val image = coverImage(resolver, folder) ?: return null
        return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, image)) { decoder, info, _ ->
                val longer = maxOf(info.size.width, info.size.height)
                if (longer > SIZE_PX) {
                    decoder.setTargetSize(info.size.width * SIZE_PX / longer, info.size.height * SIZE_PX / longer)
                }
                // Software memory, since hardware bitmaps cannot be re-encoded.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not decode $image", e)
            null
        }
    }

    private class Folder(val volume: String, val relativePath: String)

    private fun albumFolder(resolver: ContentResolver, albumId: Long): Folder? {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Audio.Media.ALBUM_ID} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(albumId.toString()))
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        val projection = arrayOf(MediaStore.Audio.Media.VOLUME_NAME, MediaStore.Audio.Media.RELATIVE_PATH)
        return query(resolver, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, args) { c ->
            val volume = c.getString(0) ?: return@query null
            val path = c.getString(1) ?: return@query null
            Folder(volume, path)
        }
    }

    /** The preferred cover image in [folder], or null (also when the photos permission is missing). */
    private fun coverImage(resolver: ContentResolver, folder: Folder): Uri? {
        val names = COVER_NAMES.joinToString(",") { "?" }
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND lower(${MediaStore.Images.Media.DISPLAY_NAME}) IN ($names)",
            )
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(folder.relativePath) + COVER_NAMES)
        }
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
        val images = MediaStore.Images.Media.getContentUri(folder.volume)
        val found = ArrayList<Pair<Int, Long>>()
        query(resolver, images, projection, args, all = true) { c ->
            val rank = COVER_NAMES.indexOf(c.getString(1)?.lowercase() ?: "")
            if (rank >= 0) found += rank to c.getLong(0)
            null
        }
        val best = found.minByOrNull { it.first } ?: return null
        return ContentUris.withAppendedId(images, best.second)
    }

    private fun embeddedCover(resolver: ContentResolver, albumId: Long): Bitmap? {
        val albumUri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)
        return try {
            resolver.loadThumbnail(albumUri, Size(SIZE_PX, SIZE_PX), null)
        } catch (_: IOException) {
            null // No embedded cover.
        } catch (e: SecurityException) {
            Log.w(TAG, "No access to album $albumId artwork", e)
            null
        }
    }

    /**
     * Runs [read] on the first row, or on every row when [all]; returns the first non-null result.
     * Provider failures read as no result.
     */
    private fun <T : Any> query(
        resolver: ContentResolver,
        uri: Uri,
        projection: Array<String>,
        args: Bundle,
        all: Boolean = false,
        read: (Cursor) -> T?,
    ): T? = try {
        resolver.query(uri, projection, args, null)?.use { c ->
            var result: T? = null
            while (result == null && c.moveToNext()) {
                result = read(c)
                if (!all) break
            }
            result
        }
    } catch (e: SecurityException) {
        Log.w(TAG, "MediaStore query refused: $uri", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "MediaStore query rejected: $uri", e)
        null
    } catch (e: SQLException) {
        Log.w(TAG, "MediaStore query failed: $uri", e)
        null
    }

    private fun trim(dir: File) {
        synchronized(trimLock) {
            val files = dir.listFiles() ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_BYTES) return
            for (file in files.sortedBy { it.lastModified() }) {
                if (total <= TRIM_TO_BYTES) break
                val size = file.length()
                if (file.delete()) total -= size
            }
        }
    }
}
