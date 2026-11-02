package dev.cyh.aamp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import dev.cyh.aamp.R
import dev.cyh.aamp.browse.MediaId
import dev.cyh.aamp.browse.TrackContext
import dev.cyh.aamp.library.LibrarySnapshot

/** Every artist by name, each with the cover of their newest album; tapping one opens it. */
@Composable
internal fun ArtistList(snapshot: LibrarySnapshot, covers: CoverLoader, onOpen: (artistKey: Long) -> Unit) {
    val sections = remember(snapshot) { RailSections.of(snapshot.artistLetters) }
    val state = rememberLazyListState()
    val thumbSize = 48.dp
    val thumbPx = with(LocalDensity.current) { thumbSize.roundToPx() }
    WithLetterRail(sections, scrollTo = { state.scrollToItem(it) }) { rail ->
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(end = rail, top = 4.dp, bottom = 16.dp)) {
            items(snapshot.artistCount, key = { snapshot.artistKeys[it] }) { r ->
                val colors = LocalColors.current
                val albums = snapshot.artistAlbumCount(r)
                val summary = if (albums > 0) pluralStringResource(R.plurals.browse_album_count, albums, albums)
                else pluralStringResource(R.plurals.browse_song_count, snapshot.artistTrackCount(r), snapshot.artistTrackCount(r))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onOpen(snapshot.artistKeys[r]) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AlbumCover(snapshot.albumIds[snapshot.artistCoverAlbum(r)], covers, thumbPx, Modifier.width(thumbSize), cornerRadius = 6)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        BasicText(snapshot.artistNames[r], style = colors.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicText(summary, style = colors.caption)
                    }
                }
            }
        }
    }
}

/** Every song by title; tapping one plays the list from it, as Android Auto's Songs tab does. */
@Composable
internal fun SongList(snapshot: LibrarySnapshot, player: Player?, playingTrackId: Long?, onAddToPlaylist: (trackIds: LongArray) -> Unit) {
    val sections = remember(snapshot) {
        // Section members are track indices; the list is in title order, so map tracks to rows.
        val rowOf = IntArray(snapshot.trackCount)
        for ((row, track) in snapshot.tracksByTitle.withIndex()) rowOf[track] = row
        RailSections.of(snapshot.trackLetters) { rowOf[it] }
    }
    val state = rememberLazyListState()
    WithLetterRail(sections, scrollTo = { state.scrollToItem(it) }) { rail ->
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(end = rail, bottom = 16.dp)) {
            items(snapshot.trackCount, key = { snapshot.trackIds[snapshot.tracksByTitle[it]] }) { row ->
                val t = snapshot.tracksByTitle[row]
                TrackRow(
                    number = null,
                    title = snapshot.trackTitles[t],
                    subtitle = snapshot.artistNames[snapshot.trackArtist[t]] + " · " + snapshot.albumTitles[snapshot.trackAlbum[t]],
                    durationMs = snapshot.trackDurationMs[t],
                    playing = snapshot.trackIds[t] == playingTrackId,
                    onClick = { player?.playId(MediaId.Track(snapshot.trackIds[t], TrackContext.InSongs)) },
                    onLongClick = { onAddToPlaylist(longArrayOf(snapshot.trackIds[t])) },
                )
            }
        }
    }
}

/** Every genre by name with its album and song counts; tapping one opens it. */
@Composable
internal fun GenreList(snapshot: LibrarySnapshot, onOpen: (genreKey: Long) -> Unit) {
    val colors = LocalColors.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
        items(snapshot.genreCount, key = { snapshot.genreKeys[it] }) { g ->
            val albums = pluralStringResource(R.plurals.browse_album_count, snapshot.genreAlbumCount(g), snapshot.genreAlbumCount(g))
            val songs = pluralStringResource(R.plurals.browse_song_count, snapshot.genreTrackCount(g), snapshot.genreTrackCount(g))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onOpen(snapshot.genreKeys[g]) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                BasicText(snapshot.genreNames[g], style = colors.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText("$albums · $songs", style = colors.caption)
            }
        }
    }
}
