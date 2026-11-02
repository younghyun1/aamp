package dev.cyh.aamp.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import dev.cyh.aamp.browse.BrowseItems
import dev.cyh.aamp.browse.TrackContext
import dev.cyh.aamp.library.Library
import dev.cyh.aamp.library.LibrarySnapshot
import dev.cyh.aamp.library.LibraryState
import dev.cyh.aamp.voice.Resolution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hands long queues to the player a window at a time.
 *
 * Every queued item costs about 3 KB of heap: ExoPlayer makes a media source for each, and the
 * session keeps converted copies for Android Auto. The whole library shuffled held some 22 MB
 * for 7,600 songs. A queue longer than [MAX_WHOLE] now starts as a window of up to [WINDOW] songs
 * around the first one to play; the rest stays here as MediaStore ids, eight bytes each. When
 * playback comes within [REFILL_AT] songs of the window's end, the next [CHUNK] are appended and
 * songs more than [KEEP_BEHIND] back are dropped, in one change, so the player holds a few hundred
 * items whatever the queue's length. With repeat all, the rest wraps to the start.
 *
 * [rest] belongs to the player's queue only while the player's last item is the last one handed
 * over; any other queue makes it stale, and it is dropped. Player calls run on the main thread.
 */
@OptIn(UnstableApi::class) // MediaItemsWithStartPosition
internal class QueueWindow(
    private val items: BrowseItems,
    private val library: Library,
    private val scope: CoroutineScope,
) {
    /**
     * The part of a queue not yet in the player: its tracks as MediaStore ids, the context its
     * items carry, the index of the next one to hand over, and the media id of the last handed over.
     */
    class Rest(val trackIds: LongArray, val context: TrackContext, val next: Int, val lastHanded: String)

    @Volatile
    var rest: Rest? = null

    private var refilling = false

    /** The first window of [queue], remembering the rest. Any thread. */
    fun start(s: LibrarySnapshot, queue: Resolution.Queue): MediaItemsWithStartPosition {
        val tracks = queue.tracks
        val context = items.contextOf(s, queue.source)
        val span = span(tracks.size, queue.startIndex)
        val window = List(span.last - span.first) { items.track(s, tracks[span.first + it], context) }
        rest = if (span.last < tracks.size || span.first > 0) {
            Rest(LongArray(tracks.size) { s.trackIds[tracks[it]] }, context, span.last, window.last().mediaId)
        } else {
            null
        }
        return MediaItemsWithStartPosition(window, queue.startIndex - span.first, C.TIME_UNSET)
    }

    /** Refills and trims the player's window when playback nears its end. Main thread. */
    fun onAdvance(player: Player) {
        val rest = rest ?: return
        val count = player.mediaItemCount
        if (count == 0 || player.getMediaItemAt(count - 1).mediaId != rest.lastHanded) {
            this.rest = null
            return
        }
        if (refilling || remaining(player) > REFILL_AT) return
        val wrap = player.repeatMode == Player.REPEAT_MODE_ALL
        if (rest.next >= rest.trackIds.size && !wrap) return
        val s = (library.state.value as? LibraryState.Ready)?.library?.snapshot ?: return
        refilling = true
        scope.launch(Dispatchers.Default) {
            val (chunk, next) = chunk(s, rest, wrap)
            withContext(Dispatchers.Main) {
                refilling = false
                val n = player.mediaItemCount
                if (chunk.isEmpty() || this@QueueWindow.rest !== rest || n == 0 || player.getMediaItemAt(n - 1).mediaId != rest.lastHanded) {
                    return@withContext
                }
                // Drop long-played songs and append the next ones together: Android Auto gets the
                // queue again on every change, so changes come in batches.
                val behind = if (player.shuffleModeEnabled) 0 else player.currentMediaItemIndex - KEEP_BEHIND
                if (behind > 0) player.removeMediaItems(0, behind)
                player.addMediaItems(chunk)
                this@QueueWindow.rest = Rest(rest.trackIds, rest.context, next, chunk.last().mediaId)
            }
        }
    }

    /** The next [CHUNK] songs of [rest] still in [s], and where the following chunk starts. */
    private fun chunk(s: LibrarySnapshot, rest: Rest, wrap: Boolean): Pair<List<MediaItem>, Int> {
        val out = ArrayList<MediaItem>(CHUNK)
        var next = rest.next
        var looked = 0
        while (out.size < CHUNK && looked < rest.trackIds.size) {
            if (next >= rest.trackIds.size) {
                if (!wrap) break
                next = 0
            }
            val t = s.trackIndex(rest.trackIds[next++])
            looked++
            if (t >= 0) out += items.track(s, t, rest.context)
        }
        return out to next
    }

    /** Songs left to play after the current one, counted up to [REFILL_AT] + 1, in play order. */
    private fun remaining(player: Player): Int {
        if (!player.shuffleModeEnabled) return player.mediaItemCount - 1 - player.currentMediaItemIndex
        val timeline = player.currentTimeline
        var index = player.currentMediaItemIndex
        var n = 0
        while (n <= REFILL_AT) {
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
            if (index == C.INDEX_UNSET) break
            n++
        }
        return n
    }

    /** The window of a queue: tracks [first] until [last], exclusive. */
    data class Span(val first: Int, val last: Int)

    companion object {
        /** Queues up to this long go to the player whole. */
        const val MAX_WHOLE = 400

        /** Songs in the first window of a longer queue, including up to [BEFORE] before the start. */
        const val WINDOW = 300
        const val BEFORE = 20

        const val REFILL_AT = 50
        const val CHUNK = 200
        const val KEEP_BEHIND = 20

        /** The first window of a queue of [size] songs starting at [start]. */
        fun span(size: Int, start: Int): Span {
            if (size <= MAX_WHOLE) return Span(0, size)
            val first = (start - BEFORE).coerceAtLeast(0)
            return Span(first, minOf(size, first + WINDOW).coerceAtLeast(start + 1))
        }
    }
}
