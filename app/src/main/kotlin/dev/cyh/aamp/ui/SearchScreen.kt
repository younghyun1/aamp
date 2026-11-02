package dev.cyh.aamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import dev.cyh.aamp.R
import dev.cyh.aamp.browse.MediaId
import dev.cyh.aamp.browse.TrackContext
import dev.cyh.aamp.library.LoadedLibrary
import dev.cyh.aamp.library.Playlist
import dev.cyh.aamp.search.LibrarySearch
import dev.cyh.aamp.search.LibrarySearch.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Search as you type ([LibrarySearch]) over songs, albums, artists, genres and playlists, the
 * kinds ordered by their best match. Songs play within their album; the rest open their page.
 *
 * Opened from a playlist's "Add songs" ([addTo]), it works like Apple Music's playlist editor:
 * each song and album has a button that puts it in that playlist, or takes it out again, without
 * leaving the search. Otherwise a song's button opens the playlist picker.
 */
@Composable
internal fun SearchScreen(
    library: LoadedLibrary,
    playlists: List<Playlist>,
    covers: CoverLoader,
    player: Player?,
    playingTrackId: Long?,
    addTo: Playlist?,
    onOpen: (Screen) -> Unit,
    onAddToPlaylist: (trackIds: LongArray) -> Unit,
    onToggleIn: (playlist: Playlist, trackIds: LongArray, contained: Boolean) -> Unit,
) {
    val colors = LocalColors.current
    val s = library.snapshot
    val field = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // The index is built once per library and playlists, off the main thread, and closed with them.
    val engine by produceState<LibrarySearch?>(null, library, playlists) {
        val built = withContext(Dispatchers.Default) { LibrarySearch.build(library, playlists) }
        value = built
        awaitDispose { built.close() }
    }
    val query = field.text.toString()
    val results by produceState(LibrarySearch.Results.EMPTY, engine, query) {
        val search = engine ?: return@produceState
        // A keystroke arriving within this time cancels the search before it starts.
        delay(TYPING_PAUSE_MS)
        value = withContext(Dispatchers.Default) { search.search(query) }
    }
    val thumbPx = with(LocalDensity.current) { THUMB.roundToPx() }
    val inTarget = remember(addTo) { addTo?.trackIds?.toHashSet() ?: emptySet() }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (field.text.isEmpty()) BasicText(stringResource(R.string.ui_search_hint), style = colors.body.copy(color = colors.muted))
            BasicTextField(
                state = field,
                textStyle = colors.body,
                cursorBrush = SolidColor(colors.accent),
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        when {
            engine == null -> Note(stringResource(R.string.ui_search_preparing))
            query.isBlank() -> Unit
            results.isEmpty -> Note(stringResource(R.string.ui_search_none))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            for (kind in results.order) {
                if (addTo != null && (kind == Kind.PLAYLIST || kind == Kind.GENRE || kind == Kind.ARTIST)) continue
                val hits = results[kind]
                val shown = minOf(hits.size, SHOWN[kind] ?: 5)
                item(key = "header:${kind.name}") { Section(kind) }
                items(shown, key = { "${kind.name}:${hits[it]}" }) { i ->
                    val index = hits[i]
                    when (kind) {
                        Kind.SONG -> {
                            val trackId = s.trackIds[index]
                            val album = s.trackAlbum[index]
                            TrackRow(
                                number = null,
                                title = s.trackTitles[index],
                                subtitle = s.artistNames[s.trackArtist[index]] + " · " + s.albumTitles[album],
                                durationMs = s.trackDurationMs[index],
                                playing = trackId == playingTrackId,
                                onClick = { player?.playId(MediaId.Track(trackId, TrackContext.InAlbum(s.albumIds[album]))) },
                                onLongClick = { onAddToPlaylist(longArrayOf(trackId)) },
                                trailing = {
                                    if (addTo != null) {
                                        val contained = trackId in inTarget
                                        AddToggle(contained) { onToggleIn(addTo, longArrayOf(trackId), contained) }
                                    } else {
                                        AddButton { onAddToPlaylist(longArrayOf(trackId)) }
                                    }
                                },
                            )
                        }
                        Kind.ALBUM -> {
                            val first = s.albumFirstTrack[index]
                            val ids = remember(s, index) { s.trackIds.copyOfRange(first, first + s.albumTrackCount[index]) }
                            EntryRow(
                                title = s.albumTitles[index],
                                subtitle = s.artistNames[s.albumArtist[index]],
                                albumId = s.albumIds[index],
                                covers = covers,
                                thumbPx = thumbPx,
                                onClick = { onOpen(Screen.Album(s.albumIds[index])) },
                                trailing = {
                                    if (addTo != null) {
                                        val contained = ids.all { it in inTarget }
                                        AddToggle(contained) { onToggleIn(addTo, ids, contained) }
                                    } else {
                                        AddButton { onAddToPlaylist(ids) }
                                    }
                                },
                            )
                        }
                        Kind.ARTIST -> EntryRow(
                            title = s.artistNames[index],
                            subtitle = null,
                            albumId = s.albumIds[s.artistCoverAlbum(index)],
                            covers = covers,
                            thumbPx = thumbPx,
                            onClick = { onOpen(Screen.Artist(s.artistKeys[index])) },
                        )
                        Kind.GENRE -> EntryRow(
                            title = s.genreNames[index],
                            subtitle = pluralStringResource(R.plurals.browse_song_count, s.genreTrackCount(index), s.genreTrackCount(index)),
                            albumId = null,
                            covers = covers,
                            thumbPx = thumbPx,
                            onClick = { onOpen(Screen.Genre(s.genreKeys[index])) },
                        )
                        Kind.PLAYLIST -> playlists.getOrNull(index)?.let { playlist ->
                            EntryRow(
                                title = playlist.name,
                                subtitle = pluralStringResource(R.plurals.browse_song_count, playlist.trackIds.size, playlist.trackIds.size),
                                albumId = null,
                                covers = covers,
                                thumbPx = thumbPx,
                                onClick = { onOpen(Screen.Playlist(playlist.id)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(kind: Kind) {
    val colors = LocalColors.current
    val title = when (kind) {
        Kind.SONG -> R.string.browse_songs
        Kind.ALBUM -> R.string.browse_albums
        Kind.ARTIST -> R.string.browse_artists
        Kind.GENRE -> R.string.ui_genres
        Kind.PLAYLIST -> R.string.browse_playlists
    }
    BasicText(
        stringResource(title),
        style = colors.caption.copy(color = colors.accent, fontWeight = FontWeight.Medium),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** An album, artist, genre or playlist: an optional cover, a title and a second line. */
@Composable
private fun EntryRow(
    title: String,
    subtitle: String?,
    albumId: Long?,
    covers: CoverLoader,
    thumbPx: Int,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = if (trailing != null) 4.dp else 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (albumId != null) {
            AlbumCover(albumId, covers, thumbPx, Modifier.width(THUMB), cornerRadius = 6)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            BasicText(title, style = colors.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) BasicText(subtitle, style = colors.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

/** Opens the playlist picker for a song or album. */
@Composable
internal fun AddButton(onClick: () -> Unit) {
    IconButton(R.drawable.ic_playlist_add, stringResource(R.string.ui_add_to_playlist), onClick, tint = LocalColors.current.muted, size = 40.dp)
}

/** Puts a song or album in the playlist being edited, or, checked, takes it out. */
@Composable
private fun AddToggle(contained: Boolean, onClick: () -> Unit) {
    val colors = LocalColors.current
    IconButton(
        if (contained) R.drawable.ic_check else R.drawable.ic_playlist_add,
        stringResource(if (contained) R.string.ui_in_playlist else R.string.ui_add_to_playlist),
        onClick,
        tint = if (contained) colors.accent else colors.muted,
        size = 40.dp,
    )
}

@Composable
private fun Note(text: String) {
    BasicText(text, style = LocalColors.current.caption, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

private val THUMB = 48.dp
private const val TYPING_PAUSE_MS = 40L

/** Rows shown per kind; the index returns more, but a phone screen shows only so many. */
private val SHOWN = mapOf(Kind.SONG to 30, Kind.ALBUM to 8, Kind.ARTIST to 8, Kind.GENRE to 5, Kind.PLAYLIST to 5)
