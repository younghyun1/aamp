package dev.cyh.aamp.playback

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException

/**
 * The play queue saved across process restarts, so Android Auto's autoplay on connect resumes
 * where listening stopped instead of starting something new.
 *
 * Stores media ids (which carry each track's list context), the current index and the position,
 * in a small versioned binary file replaced atomically on every write. Queues longer than
 * [MAX_ITEMS] are not saved.
 */
internal class QueueStore(private val file: File) {
    class Saved(val mediaIds: List<String>, val index: Int, val positionMs: Long)

    /** Writes [saved]; returns false when it was too long to save or the write failed. */
    fun write(saved: Saved): Boolean {
        if (saved.mediaIds.size > MAX_ITEMS) return false
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(saved.mediaIds.size)
                for (id in saved.mediaIds) out.writeUTF(id)
                out.writeInt(saved.index)
                out.writeLong(saved.positionMs)
            }
            tmp.renameTo(file)
        } catch (_: IOException) {
            tmp.delete()
            false
        }
    }

    /** The saved queue, or null when there is none or the file is not one this version wrote. */
    fun read(): Saved? {
        if (!file.isFile) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return null
                val count = input.readInt()
                if (count !in 1..MAX_ITEMS) return null
                val ids = List(count) { input.readUTF() }
                val index = input.readInt()
                val position = input.readLong()
                if (index !in ids.indices || position < 0) return null
                Saved(ids, index, position)
            }
        } catch (_: EOFException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    companion object {
        /** "AQ" plus format version 1. */
        private const val MAGIC = 0x41510001
        const val MAX_ITEMS = 20_000
    }
}
