package dev.cyh.aamp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import dev.cyh.aamp.R
import dev.cyh.aamp.browse.MediaId
import dev.cyh.aamp.browse.TrackContext
import dev.cyh.aamp.library.LibrarySnapshot

/** An album's songs; tapping one plays the album from it. */
@Composable
internal fun AlbumScreen(
    snapshot: LibrarySnapshot,
    album: Int,
    covers: CoverLoader,
    player: Player?,
    playingTrackId: Long?,
    onOpenArtist: (artistKey: Long) -> Unit,
) {
    val colors = LocalColors.current
    val albumId = snapshot.albumIds[album]
    val first = snapshot.albumFirstTrack[album]
    val count = snapshot.albumTrackCount[album]
    val multiDisc = count > 0 && snapshot.trackDisc[first + count - 1] > 1
    val totalMs = remember(snapshot, album) { (first until first + count).sumOf { snapshot.trackDurationMs[it] } }
    val coverSize = 140.dp
    val coverPx = with(LocalDensity.current) { coverSize.roundToPx() }
    val artist = snapshot.albumArtist[album]

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = HEADER_KEY) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                AlbumCover(albumId, covers, coverPx, Modifier.width(coverSize))
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText(snapshot.albumTitles[album], style = colors.title, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    BasicText(
                        snapshot.artistNames[artist],
                        style = colors.body.copy(color = colors.accent),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(role = Role.Button) { onOpenArtist(snapshot.artistKeys[artist]) },
                    )
                    val songs = pluralStringResource(R.plurals.browse_song_count, count, count)
                    val year = snapshot.albumYear[album]
                    BasicText(
                        listOfNotNull(year.takeIf { it > 0 }?.toString(), songs, formatDuration(totalMs)).joinToString(" · "),
                        style = colors.caption,
                    )
                    Spacer(Modifier.height(4.dp))
                    PillButton(stringResource(R.string.ui_play), onClick = { player?.playId(MediaId.Album(albumId)) })
                }
            }
        }
        items(count, key = { snapshot.trackIds[first + it] }) { i ->
            val t = first + i
            val number = snapshot.trackNumber[t].takeIf { it > 0 } ?: (i + 1)
            TrackRow(
                number = if (multiDisc) "${snapshot.trackDisc[t]}-$number" else number.toString(),
                title = snapshot.trackTitles[t],
                subtitle = snapshot.artistNames[snapshot.trackArtist[t]].takeIf { snapshot.trackArtist[t] != artist },
                durationMs = snapshot.trackDurationMs[t],
                playing = snapshot.trackIds[t] == playingTrackId,
                onClick = { player?.playId(MediaId.Track(snapshot.trackIds[t], TrackContext.InAlbum(albumId))) },
            )
        }
    }
}

/**
 * An artist's albums, or for an artist who only appears on compilations, their songs. A shuffle
 * button plays everything by the artist.
 */
@Composable
internal fun ArtistScreen(
    snapshot: LibrarySnapshot,
    artist: Int,
    covers: CoverLoader,
    player: Player?,
    playingTrackId: Long?,
    onOpenAlbum: (albumId: Long) -> Unit,
) {
    val artistKey = snapshot.artistKeys[artist]
    val albums = remember(snapshot, artist) { snapshot.artistAlbums(artist) }
    val header = @Composable {
        val colors = LocalColors.current
        val summary = if (albums.isNotEmpty()) pluralStringResource(R.plurals.browse_album_count, albums.size, albums.size)
        else pluralStringResource(R.plurals.browse_song_count, snapshot.artistTrackCount(artist), snapshot.artistTrackCount(artist))
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(summary, style = colors.caption, modifier = Modifier.weight(1f))
            PillButton(stringResource(R.string.ui_shuffle), onClick = { player?.playId(MediaId.ShuffleArtist(artistKey)) })
        }
    }
    if (albums.isNotEmpty()) {
        AlbumGrid(
            snapshot,
            albums,
            covers,
            onPlay = { player?.playId(MediaId.Album(snapshot.albumIds[it])) },
            onOpen = { onOpenAlbum(snapshot.albumIds[it]) },
            header = header,
        )
        return
    }
    val tracks = remember(snapshot, artist) { snapshot.artistTracks(artist) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = HEADER_KEY) { Row(Modifier.padding(horizontal = 16.dp)) { header() } }
        items(tracks.size, key = { snapshot.trackIds[tracks[it]] }) { i ->
            val t = tracks[i]
            TrackRow(
                number = (i + 1).toString(),
                title = snapshot.trackTitles[t],
                subtitle = snapshot.albumTitles[snapshot.trackAlbum[t]],
                durationMs = snapshot.trackDurationMs[t],
                playing = snapshot.trackIds[t] == playingTrackId,
                onClick = { player?.playId(MediaId.Track(snapshot.trackIds[t], TrackContext.InArtist(artistKey))) },
            )
        }
    }
}

@Composable
private fun TrackRow(number: String, title: String, subtitle: String?, durationMs: Long, playing: Boolean, onClick: () -> Unit) {
    val colors = LocalColors.current
    val titleStyle = if (playing) colors.body.copy(color = colors.accent, fontWeight = FontWeight.Medium) else colors.body
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(number, style = colors.caption, modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1f)) {
            BasicText(title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) BasicText(subtitle, style = colors.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (durationMs > 0) BasicText(formatDuration(durationMs), style = colors.caption, modifier = Modifier.padding(start = 12.dp))
    }
}

private const val HEADER_KEY = "header"
