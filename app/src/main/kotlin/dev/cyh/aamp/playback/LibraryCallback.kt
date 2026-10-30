package dev.cyh.aamp.playback

import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.cyh.aamp.R
import dev.cyh.aamp.browse.BrowseItems
import dev.cyh.aamp.browse.MediaId
import dev.cyh.aamp.browse.MediaIds
import dev.cyh.aamp.browse.TrackContext
import dev.cyh.aamp.library.Library
import dev.cyh.aamp.library.LibraryState
import dev.cyh.aamp.library.LoadedLibrary
import dev.cyh.aamp.search.MatchKey
import dev.cyh.aamp.voice.PlayRequest
import dev.cyh.aamp.voice.QueueResolver
import dev.cyh.aamp.voice.Resolution
import dev.cyh.aamp.voice.VoiceLog
import dev.cyh.aamp.voice.VoiceLogEntry
import dev.cyh.aamp.voice.VoiceQuery
import dev.cyh.aamp.voice.VoiceRequestParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Serves Android Auto: the browse tree, in-app search, and every request to play something.
 *
 * Media3 calls these methods on the main thread. Anything that touches the library runs on
 * [Dispatchers.Default] and answers through a future, so the main thread never waits on a scan
 * or a match. Failures become failed futures or library errors; Android Auto shows their message.
 */
@OptIn(UnstableApi::class)
internal class LibraryCallback(
    private val context: Context,
    private val library: Library,
    private val voiceLog: VoiceLog,
    private val scope: CoroutineScope,
) : MediaLibrarySession.Callback {
    private val items = BrowseItems(context)

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(items.root(), params))

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future(Dispatchers.Default) {
        withLibrary { loaded ->
            val item = MediaIds.parse(mediaId)?.let { items.item(loaded, it) }
            if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.Default) {
        withLibrary { loaded ->
            val children = MediaIds.parse(parentId)?.let { items.children(loaded, it, page, pageSize) }
            if (children != null) LibraryResult.ofItemList(children, params) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = scope.future(Dispatchers.Default) {
        withLibrary { loaded ->
            val count = searchResults(loaded, query).size
            if (query.isNotBlank()) {
                withContext(Dispatchers.Main) { session.notifySearchResultChanged(browser, query, count, params) }
            }
            LibraryResult.ofVoid(params)
        }
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.Default) {
        withLibrary { loaded ->
            val results = searchResults(loaded, query)
            val from = page.toLong() * pageSize
            val pageItems = if (page < 0 || pageSize <= 0 || from >= results.size) emptyList()
            else results.subList(from.toInt(), minOf(results.size.toLong(), from + pageSize).toInt())
            LibraryResult.ofItemList(pageItems, params)
        }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> {
        val single = mediaItems.singleOrNull()
        return if (single != null && single.isSearchRequest()) {
            playFromSearch(mediaSession, controller, single)
        } else {
            playSelection(mediaItems, startIndex, startPositionMs)
        }
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> = scope.future(Dispatchers.Default) {
        val loaded = loadedOrFail()
        mediaItems.flatMap { expand(loaded, it) }
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaItemsWithStartPosition> = scope.future(Dispatchers.Default) {
        val loaded = loadedOrFail()
        val queue = QueueResolver(loaded, Random.Default).shuffleAll()
        MediaItemsWithStartPosition(items.queue(loaded.snapshot, queue), queue.startIndex, C.TIME_UNSET)
    }

    /**
     * A voice request: Android Auto's `playFromSearch(query, extras)`, which Media3 delivers as a
     * single item carrying only request metadata. Every request is logged with what it resolved to.
     */
    private fun playFromSearch(
        session: MediaSession,
        controller: ControllerInfo,
        item: MediaItem,
    ): ListenableFuture<MediaItemsWithStartPosition> {
        val started = System.nanoTime()
        val metadata = item.requestMetadata
        val query = voiceQuery(metadata.searchQuery.orEmpty(), metadata.extras)
        val request = VoiceRequestParser.parse(query)
        fun log(outcome: String) = voiceLog.record(
            VoiceLogEntry(
                timeMillis = System.currentTimeMillis(),
                caller = controller.packageName,
                query = query.query,
                hints = describe(metadata.extras),
                parsed = request.toString(),
                outcome = outcome,
                latencyMs = (System.nanoTime() - started) / 1_000_000,
            ),
        )

        val player = session.player
        if (request == PlayRequest.Resume && player.mediaItemCount > 0) {
            log("kept the current queue")
            val current = List(player.mediaItemCount) { player.getMediaItemAt(it) }
            return Futures.immediateFuture(MediaItemsWithStartPosition(current, player.currentMediaItemIndex, player.currentPosition))
        }
        // The resolved queue is already in the order it should play; a shuffle left on from
        // earlier would scramble an album.
        player.shuffleModeEnabled = false

        return scope.future(Dispatchers.Default) {
            val loaded = when (val state = library.await(LIBRARY_WAIT)) {
                is LibraryState.Ready -> state.library
                else -> {
                    log("library unavailable: $state")
                    fail(session, stateError(state))
                }
            }
            when (val resolution = QueueResolver(loaded, Random.Default).resolve(request)) {
                is Resolution.Queue -> {
                    log(resolution.summary)
                    MediaItemsWithStartPosition(items.queue(loaded.snapshot, resolution), resolution.startIndex, C.TIME_UNSET)
                }
                is Resolution.NoMatch -> {
                    log("no match for \"${resolution.phrase}\"")
                    fail(session, SessionError(SessionError.ERROR_NOT_SUPPORTED, context.getString(R.string.error_no_match, resolution.phrase)))
                }
                Resolution.EmptyLibrary -> {
                    log("library is empty")
                    fail(session, SessionError(SessionError.ERROR_INVALID_STATE, context.getString(R.string.error_library_unavailable)))
                }
            }
        }
    }

    /** Items picked in the browse tree or search results, or a queue a controller sends whole. */
    private fun playSelection(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> = scope.future(Dispatchers.Default) {
        val loaded = loadedOrFail()
        val single = mediaItems.singleOrNull()?.let { MediaIds.parse(it.mediaId) }
        val queue = single?.let { queueFor(loaded, it) }
        if (queue != null) {
            MediaItemsWithStartPosition(items.queue(loaded.snapshot, queue), queue.startIndex, C.TIME_UNSET)
        } else {
            val expanded = mediaItems.flatMap { expand(loaded, it) }
            if (expanded.isEmpty()) throw RequestFailed("nothing playable among ${mediaItems.size} items")
            MediaItemsWithStartPosition(expanded, startIndex, startPositionMs)
        }
    }

    /** The queue that picking [id] should start: a track plays within the list it was picked from. */
    private fun queueFor(loaded: LoadedLibrary, id: MediaId): Resolution.Queue? {
        val s = loaded.snapshot
        val resolver = QueueResolver(loaded, Random.Default)
        return when (id) {
            is MediaId.Track -> {
                val t = s.trackIndex(id.trackId).takeIf { it >= 0 } ?: return null
                when (val c = id.context) {
                    is TrackContext.InAlbum -> resolver.album(s.albumIndex(c.albumId).takeIf { it >= 0 } ?: s.trackAlbum[t], t)
                    is TrackContext.InArtist -> s.artistIndex(c.artistKey).takeIf { it >= 0 }
                        ?.let { resolver.artist(it, shuffle = false, startTrack = t) }
                        ?: resolver.album(s.trackAlbum[t], t)
                    TrackContext.InSongs -> resolver.songs(t)
                    TrackContext.None -> resolver.album(s.trackAlbum[t], t)
                }
            }
            is MediaId.Album -> s.albumIndex(id.albumId).takeIf { it >= 0 }?.let { resolver.album(it) }
            is MediaId.Artist -> s.artistIndex(id.artistKey).takeIf { it >= 0 }?.let { resolver.artist(it, shuffle = true) }
            MediaId.ShuffleAll -> resolver.shuffleAll()
            MediaId.Root, MediaId.Albums, MediaId.Artists, MediaId.Songs -> null
        }
    }

    /** The playable tracks behind one item, for queue additions; unknown items pass through if playable. */
    private fun expand(loaded: LoadedLibrary, item: MediaItem): List<MediaItem> {
        val s = loaded.snapshot
        return when (val id = MediaIds.parse(item.mediaId)) {
            is MediaId.Track -> s.trackIndex(id.trackId).takeIf { it >= 0 }?.let { listOf(items.track(s, it, id.context)) }
            is MediaId.Album -> s.albumIndex(id.albumId).takeIf { it >= 0 }?.let { a ->
                s.albumTracks(a).map { items.track(s, it, TrackContext.InAlbum(id.albumId)) }
            }
            is MediaId.Artist -> s.artistIndex(id.artistKey).takeIf { it >= 0 }?.let { r ->
                s.artistTracks(r).map { items.track(s, it, TrackContext.InArtist(id.artistKey)) }
            }
            else -> null
        } ?: if (item.localConfiguration != null) listOf(item) else emptyList()
    }

    /** Search results for Android Auto, grouped as albums, artists, then songs. */
    private fun searchResults(loaded: LoadedLibrary, query: String): List<MediaItem> {
        val key = MatchKey.of(query)
        if (key.isEmpty) return emptyList()
        val s = loaded.snapshot
        val search = loaded.search
        return buildList {
            search.albums.top(key, MAX_ALBUM_RESULTS, SEARCH_MIN_SCORE).forEach { add(items.album(s, it.index, items.searchAlbumsGroup)) }
            search.artists.top(key, MAX_ARTIST_RESULTS, SEARCH_MIN_SCORE).forEach { add(items.artist(s, it.index, items.searchArtistsGroup)) }
            search.tracks.top(key, MAX_SONG_RESULTS, SEARCH_MIN_SCORE).forEach {
                add(items.track(s, it.index, TrackContext.None, items.searchSongsGroup))
            }
        }
    }

    private suspend inline fun <T : Any> withLibrary(block: (LoadedLibrary) -> LibraryResult<T>): LibraryResult<T> =
        when (val state = library.await(LIBRARY_WAIT)) {
            is LibraryState.Ready -> block(state.library)
            else -> LibraryResult.ofError(stateError(state))
        }

    private suspend fun loadedOrFail(): LoadedLibrary = when (val state = library.await(LIBRARY_WAIT)) {
        is LibraryState.Ready -> state.library
        else -> throw RequestFailed("library unavailable: $state")
    }

    private fun stateError(state: LibraryState): SessionError = when (state) {
        LibraryState.PermissionMissing ->
            SessionError(SessionError.ERROR_PERMISSION_DENIED, context.getString(R.string.error_permission_missing))
        else -> SessionError(SessionError.ERROR_INVALID_STATE, context.getString(R.string.error_library_unavailable))
    }

    /** Shows [error] on connected controllers (Android Auto displays the message) and fails the request. */
    private suspend fun fail(session: MediaSession, error: SessionError): Nothing {
        withContext(Dispatchers.Main) { session.sendError(error) }
        throw RequestFailed(error.message)
    }

    /** Thrown inside a future to fail it; Media3 then ignores the request. */
    private class RequestFailed(message: String) : Exception(message)

    companion object {
        /** How long a request waits for the first scan after a cold start. */
        private val LIBRARY_WAIT = 5.seconds
        private const val SEARCH_MIN_SCORE = 0.5f
        private const val MAX_ALBUM_RESULTS = 8
        private const val MAX_ARTIST_RESULTS = 5
        private const val MAX_SONG_RESULTS = 25

        /**
         * Media3 turns `playFromSearch` into one item with a search query (possibly empty) and the
         * default media id. Items from the browse tree always carry one of our ids instead.
         */
        private fun MediaItem.isSearchRequest(): Boolean =
            requestMetadata.searchQuery != null || (mediaId == MediaItem.DEFAULT_MEDIA_ID && localConfiguration == null)

        private fun voiceQuery(query: String, extras: Bundle?): VoiceQuery = VoiceQuery(
            query = query,
            focus = extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS),
            album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
            artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
            title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE),
            genre = extras?.getString(MediaStore.EXTRA_MEDIA_GENRE),
        )

        /** Every extra as `key=value`, for the log; this is how we learn which hints Gemini sends. */
        @Suppress("DEPRECATION") // Bundle.get is the only way to read values of unknown type.
        private fun describe(extras: Bundle?): String =
            extras?.keySet()?.sorted()?.joinToString(", ") { "$it=${extras.get(it)}" }.orEmpty()
    }
}
