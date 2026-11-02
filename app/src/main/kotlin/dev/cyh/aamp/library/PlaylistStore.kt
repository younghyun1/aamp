package dev.cyh.aamp.library

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException

/**
 * Playlists on disk: a small versioned binary file, replaced atomically on every write. A file this
 * version did not write, or a damaged one, reads as no playlists rather than failing.
 */
class PlaylistStore(private val file: File) {
    fun read(): List<Playlist> {
        if (!file.isFile) return emptyList()
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val magic = input.readInt()
                if (magic != MAGIC && magic != MAGIC_V1) return emptyList()
                val count = input.readInt()
                if (count !in 0..Playlist.MAX_PLAYLISTS) return emptyList()
                List(count) {
                    val id = input.readLong()
                    val name = input.readUTF()
                    val size = input.readInt()
                    if (size !in 0..Playlist.MAX_TRACKS) return emptyList()
                    val tracks = LongArray(size) { input.readLong() }
                    Playlist(id, name, tracks, if (magic == MAGIC) input.readLong() else 0L)
                }
            }
        } catch (_: EOFException) {
            emptyList()
        } catch (_: IOException) {
            emptyList()
        }
    }

    /** Writes [playlists]; returns false when the write failed and the old file is still in place. */
    fun write(playlists: List<Playlist>): Boolean {
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(playlists.size)
                for (p in playlists) {
                    out.writeLong(p.id)
                    out.writeUTF(p.name)
                    out.writeInt(p.trackIds.size)
                    for (id in p.trackIds) out.writeLong(id)
                    out.writeLong(p.updatedMillis)
                }
            }
            tmp.renameTo(file)
        } catch (_: IOException) {
            tmp.delete()
            false
        }
    }

    private companion object {
        /** "AP" plus format version 2; version 1 had no change times. */
        const val MAGIC = 0x41500002
        const val MAGIC_V1 = 0x41500001
    }
}
