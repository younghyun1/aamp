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
                if (input.readInt() != MAGIC) return emptyList()
                val count = input.readInt()
                if (count !in 0..Playlist.MAX_PLAYLISTS) return emptyList()
                List(count) {
                    val id = input.readLong()
                    val name = input.readUTF()
                    val size = input.readInt()
                    if (size !in 0..Playlist.MAX_TRACKS) return emptyList()
                    Playlist(id, name, LongArray(size) { input.readLong() })
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
                }
            }
            tmp.renameTo(file)
        } catch (_: IOException) {
            tmp.delete()
            false
        }
    }

    private companion object {
        /** "AP" plus format version 1. */
        const val MAGIC = 0x41500001
    }
}
