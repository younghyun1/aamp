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
 * Stores the player's media ids (which carry each track's list context), the current index and
 * position, and for a long queue the part not yet handed to the player ([QueueWindow.Rest]), in a
 * small versioned binary file replaced atomically on every write. Version 1 files, without the
 * rest, still read. Queues longer than [MAX_ITEMS] in the player, or rests longer than
 * [MAX_REST], are not saved.
 */
internal class QueueStore(private val file: File) {
    class Saved(val mediaIds: List<String>, val index: Int, val positionMs: Long, val rest: SavedRest? = null)

    /** A [QueueWindow.Rest] with its context as encoded by [dev.cyh.aamp.browse.MediaIds.encodeContext]. */
    class SavedRest(val trackIds: LongArray, val context: String, val next: Int, val lastHanded: String)

    /** Writes [saved]; returns false when it was too long to save or the write failed. */
    fun write(saved: Saved): Boolean {
        if (saved.mediaIds.size > MAX_ITEMS) return false
        val rest = saved.rest?.takeIf { it.trackIds.size <= MAX_REST }
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(saved.mediaIds.size)
                for (id in saved.mediaIds) out.writeUTF(id)
                out.writeInt(saved.index)
                out.writeLong(saved.positionMs)
                out.writeBoolean(rest != null)
                if (rest != null) {
                    out.writeUTF(rest.context)
                    out.writeInt(rest.next)
                    out.writeUTF(rest.lastHanded)
                    out.writeInt(rest.trackIds.size)
                    for (id in rest.trackIds) out.writeLong(id)
                }
            }
            tmp.renameTo(file)
        } catch (_: IOException) {
            tmp.delete()
            false
        }
    }

    /** The saved queue, or null when there is none or the file is not one this version reads. */
    fun read(): Saved? {
        if (!file.isFile) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val magic = input.readInt()
                if (magic != MAGIC && magic != MAGIC_V1) return null
                val count = input.readInt()
                if (count !in 1..MAX_ITEMS) return null
                val ids = List(count) { input.readUTF() }
                val index = input.readInt()
                val position = input.readLong()
                if (index !in ids.indices || position < 0) return null
                val rest = if (magic == MAGIC && input.readBoolean()) {
                    val context = input.readUTF()
                    val next = input.readInt()
                    val last = input.readUTF()
                    val size = input.readInt()
                    if (size !in 1..MAX_REST || next !in 0..size) return null
                    SavedRest(LongArray(size) { input.readLong() }, context, next, last)
                } else {
                    null
                }
                Saved(ids, index, position, rest)
            }
        } catch (_: EOFException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    companion object {
        /** "AQ" plus format version 2; version 1 had no rest. */
        private const val MAGIC = 0x41510002
        private const val MAGIC_V1 = 0x41510001
        const val MAX_ITEMS = 20_000
        const val MAX_REST = 200_000
    }
}
