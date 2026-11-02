package dev.cyh.aamp.library

/**
 * A playlist made in aamp: a name and its songs as MediaStore track ids, in play order. A song may
 * appear more than once. Songs no longer in the library stay in the playlist but are skipped, so
 * they come back if the file returns with the same id.
 */
class Playlist(val id: Long, val name: String, val trackIds: LongArray) {
    /** The songs still in [snapshot], as snapshot track indices in playlist order. */
    fun tracksIn(snapshot: LibrarySnapshot): IntArray {
        val out = IntArray(trackIds.size)
        var n = 0
        for (trackId in trackIds) {
            val t = snapshot.trackIndex(trackId)
            if (t >= 0) out[n++] = t
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    fun copy(name: String = this.name, trackIds: LongArray = this.trackIds): Playlist = Playlist(id, name, trackIds)

    companion object {
        /** Longest playlist kept; adding beyond it drops the excess. */
        const val MAX_TRACKS = 10_000

        /** Most playlists kept; creating more is refused. */
        const val MAX_PLAYLISTS = 500

        const val MAX_NAME_LENGTH = 100
    }
}
