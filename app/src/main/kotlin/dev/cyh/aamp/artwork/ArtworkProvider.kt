package dev.cyh.aamp.artwork

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * Serves album art to Android Auto, which only loads artwork from local `content://` URIs.
 * `content://dev.cyh.aamp.artwork/album/<MediaStore album id>` returns a JPEG of that album's
 * cover from [ArtworkCache].
 *
 * The provider is exported, read-only. System UI and Android Auto read covers straight from the
 * media session metadata, System UI without ever connecting to the session, so per-app URI grants
 * cannot reach every reader. Only `album/<id>` paths are served, album ids are 64-bit MediaStore
 * hashes rather than counters, and every cover is something any app with media access can read.
 */
class ArtworkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = MIME_TYPE

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Artwork is read-only: $uri")
        val albumId = albumIdOf(uri) ?: throw FileNotFoundException("Not an artwork URI: $uri")
        val context = context ?: throw FileNotFoundException("Provider is not attached")
        val file = ArtworkCache.fileFor(context, albumId) ?: throw FileNotFoundException("No artwork for album $albumId")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "dev.cyh.aamp.artwork"
        private const val MIME_TYPE = "image/jpeg"
        private const val ALBUM_PATH = "album"

        private val ROOT: Uri = Uri.Builder().scheme("content").authority(AUTHORITY).build()

        fun albumUri(albumId: Long): Uri = ROOT.buildUpon().appendPath(ALBUM_PATH).appendPath(albumId.toString()).build()

        private fun albumIdOf(uri: Uri): Long? {
            if (uri.authority != AUTHORITY) return null
            val segments = uri.pathSegments
            if (segments.size != 2 || segments[0] != ALBUM_PATH) return null
            return segments[1].toLongOrNull()
        }
    }
}
