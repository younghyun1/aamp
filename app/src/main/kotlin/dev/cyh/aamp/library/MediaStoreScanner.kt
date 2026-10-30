package dev.cyh.aamp.library

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.database.SQLException
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Audio.Media

/** Outcome of reading the audio collection from MediaStore. */
sealed interface ScanResult {
    class Success(val rows: List<RawTrack>) : ScanResult
    data object PermissionDenied : ScanResult
    data class Failure(val message: String) : ScanResult
}

/**
 * MediaStore's change counters. When neither the provider version nor any external volume's
 * generation has moved, the audio collection is unchanged and a rescan can be skipped.
 */
data class MediaStoreSignature(val version: String, val generations: Map<String, Long>) {
    companion object {
        fun read(context: Context): MediaStoreSignature {
            val volumes = MediaStore.getExternalVolumeNames(context)
            return MediaStoreSignature(
                version = MediaStore.getVersion(context),
                generations = volumes.associateWith { MediaStore.getGeneration(context, it) },
            )
        }
    }
}

/** Reads every music row from MediaStore. MediaStore has already parsed the tags; no files are opened. */
object MediaStoreScanner {
    private val projection = arrayOf(
        Media._ID, Media.TITLE, Media.ARTIST, Media.ALBUM, Media.ALBUM_ID, Media.ALBUM_ARTIST,
        Media.TRACK, Media.DISC_NUMBER, Media.YEAR, Media.DURATION, Media.COMPILATION,
    )

    fun scan(resolver: ContentResolver): ScanResult {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${Media.IS_MUSIC} != 0")
        }
        return try {
            resolver.query(Media.EXTERNAL_CONTENT_URI, projection, args, null)?.use(::readRows)
                ?: ScanResult.Failure("MediaStore returned no cursor")
        } catch (_: SecurityException) {
            ScanResult.PermissionDenied
        } catch (e: IllegalArgumentException) {
            ScanResult.Failure("MediaStore rejected the query: ${e.message}")
        } catch (e: IllegalStateException) {
            ScanResult.Failure("MediaStore cursor failed: ${e.message}")
        } catch (e: SQLException) {
            ScanResult.Failure("MediaStore database error: ${e.message}")
        }
    }

    private fun readRows(c: Cursor): ScanResult {
        val id = c.getColumnIndexOrThrow(Media._ID)
        val title = c.getColumnIndexOrThrow(Media.TITLE)
        val artist = c.getColumnIndexOrThrow(Media.ARTIST)
        val album = c.getColumnIndexOrThrow(Media.ALBUM)
        val albumId = c.getColumnIndexOrThrow(Media.ALBUM_ID)
        val albumArtist = c.getColumnIndexOrThrow(Media.ALBUM_ARTIST)
        val track = c.getColumnIndexOrThrow(Media.TRACK)
        val disc = c.getColumnIndexOrThrow(Media.DISC_NUMBER)
        val year = c.getColumnIndexOrThrow(Media.YEAR)
        val duration = c.getColumnIndexOrThrow(Media.DURATION)
        val compilation = c.getColumnIndexOrThrow(Media.COMPILATION)

        val rows = ArrayList<RawTrack>(c.count)
        while (c.moveToNext()) {
            // TRACK packs the disc into the thousands (disc * 1000 + track); DISC_NUMBER is the
            // raw tag ("2" or "2/3") and is preferred when present.
            val packed = c.getInt(track)
            rows += RawTrack(
                id = c.getLong(id),
                title = c.text(title),
                artist = c.text(artist),
                album = c.text(album),
                albumId = c.getLong(albumId),
                albumArtist = c.text(albumArtist),
                trackNumber = packed % 1000,
                discNumber = (leadingInt(c.getString(disc)) ?: (packed / 1000)).coerceAtLeast(1),
                year = c.getInt(year),
                durationMs = c.getLong(duration),
                isCompilation = c.getString(compilation) == "1",
            )
        }
        return ScanResult.Success(rows)
    }

    /** Column text with MediaStore's "<unknown>" placeholder and nulls turned into "". */
    private fun Cursor.text(column: Int): String {
        val value = getString(column) ?: return ""
        return if (value == MediaStore.UNKNOWN_STRING) "" else value.trim()
    }

    private fun leadingInt(text: String?): Int? = text?.trim()?.takeWhile(Char::isDigit)?.toIntOrNull()
}
