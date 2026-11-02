package dev.cyh.aamp.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * The process-wide playlists. Loading and every edit run in order on one background thread, and
 * each edit is written to the store as soon as it is applied; [all] publishes the result.
 */
class Playlists(
    private val store: PlaylistStore,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val loaded = CompletableDeferred<Unit>()
    private val _all = MutableStateFlow<List<Playlist>>(emptyList())

    /** Playlists in the order they were made. */
    val all: StateFlow<List<Playlist>> = _all.asStateFlow()

    init {
        scope.launch(io) {
            _all.value = store.read()
            loaded.complete(Unit)
        }
    }

    /** The playlists once loaded from disk. */
    suspend fun await(): List<Playlist> {
        loaded.await()
        return _all.value
    }

    /**
     * Makes a playlist named [name] holding [trackIds] and returns its id. Blank names, and more
     * than [Playlist.MAX_PLAYLISTS] playlists, are refused; the id then names nothing.
     */
    fun create(name: String, trackIds: LongArray = LongArray(0)): Long {
        val id = random.nextLong()
        val clean = name.trim().take(Playlist.MAX_NAME_LENGTH)
        if (clean.isEmpty()) return id
        edit { all -> if (all.size >= Playlist.MAX_PLAYLISTS) all else all + Playlist(id, clean, trackIds.capped(), clock()) }
        return id
    }

    /** Appends [trackIds] to playlist [id]. */
    fun add(id: Long, trackIds: LongArray) = update(id) { it.copy(trackIds = (it.trackIds + trackIds).capped()) }

    /** Appends the songs of [trackIds] that playlist [id] does not have yet, in order. */
    fun addMissing(id: Long, trackIds: LongArray) = update(id) { p ->
        val present = p.trackIds.toHashSet()
        val missing = trackIds.filter { present.add(it) }.toLongArray()
        if (missing.isEmpty()) p else p.copy(trackIds = (p.trackIds + missing).capped())
    }

    /** Removes every occurrence of [trackId] from playlist [id]. */
    fun removeTrack(id: Long, trackId: Long) = update(id) { p ->
        if (trackId !in p.trackIds) p else p.copy(trackIds = p.trackIds.filter { it != trackId }.toLongArray())
    }

    /** Removes the song at [position] in playlist [id]. */
    fun removeAt(id: Long, position: Int) = update(id) { p ->
        if (position !in p.trackIds.indices) p
        else p.copy(trackIds = LongArray(p.trackIds.size - 1) { if (it < position) p.trackIds[it] else p.trackIds[it + 1] })
    }

    fun rename(id: Long, name: String) {
        val clean = name.trim().take(Playlist.MAX_NAME_LENGTH)
        if (clean.isNotEmpty()) update(id) { it.copy(name = clean) }
    }

    fun delete(id: Long) = edit { all -> all.filter { it.id != id } }

    /** Returns once every edit made before the call is applied and written. */
    internal suspend fun idle() = withContext(io) {}

    /** Applies [change] to playlist [id], stamping the change time when something changed. */
    private fun update(id: Long, change: (Playlist) -> Playlist) = edit { all ->
        all.map { p ->
            if (p.id != id) return@map p
            val changed = change(p)
            if (changed === p) p else changed.copy(updatedMillis = clock())
        }
    }

    // Loading is the first task on the serial dispatcher and never suspends, so every edit runs
    // after it, in the order the edits were made.
    private fun edit(change: (List<Playlist>) -> List<Playlist>) {
        scope.launch(io) {
            val next = change(_all.value)
            if (next == _all.value) return@launch
            _all.value = next
            store.write(next)
        }
    }

    private fun LongArray.capped(): LongArray = if (size <= Playlist.MAX_TRACKS) this else copyOf(Playlist.MAX_TRACKS)
}
