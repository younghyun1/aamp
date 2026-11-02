package dev.cyh.aamp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.input.clearText
import dev.cyh.aamp.search.TextNormalizer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import dev.cyh.aamp.R
import dev.cyh.aamp.browse.MediaId
import dev.cyh.aamp.browse.TrackContext
import dev.cyh.aamp.library.LibrarySnapshot
import dev.cyh.aamp.library.Playlist

/** Every playlist with its song count, after a row that makes a new one. */
@Composable
internal fun PlaylistList(snapshot: LibrarySnapshot, playlists: List<Playlist>, onOpen: (playlistId: Long) -> Unit, onCreate: () -> Unit) {
    val colors = LocalColors.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
        item(key = NEW_KEY) {
            BasicText(
                stringResource(R.string.ui_new_playlist),
                style = colors.body.copy(color = colors.accent),
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onCreate).padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
        items(playlists.size, key = { playlists[it].id }) { i ->
            val playlist = playlists[i]
            val songs = remember(snapshot, playlist) { playlist.tracksIn(snapshot).size }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onOpen(playlist.id) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                BasicText(playlist.name, style = colors.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(pluralStringResource(R.plurals.browse_song_count, songs, songs), style = colors.caption)
            }
        }
    }
}

/**
 * A playlist's songs in order. Tapping one plays the playlist from it; the cross removes it.
 * Songs no longer in the library are not shown but keep their place.
 */
@Composable
internal fun PlaylistScreen(
    snapshot: LibrarySnapshot,
    playlist: Playlist,
    player: Player?,
    playingTrackId: Long?,
    onRemove: (position: Int) -> Unit,
    onDelete: () -> Unit,
    onAddSongs: () -> Unit,
) {
    val colors = LocalColors.current
    // Positions in the playlist of the songs still in the library, and those songs.
    val shown = remember(snapshot, playlist) {
        playlist.trackIds.indices.filter { snapshot.trackIndex(playlist.trackIds[it]) >= 0 }.toIntArray()
    }
    val totalMs = remember(snapshot, playlist) { shown.sumOf { snapshot.trackDurationMs[snapshot.trackIndex(playlist.trackIds[it])] } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = HEADER_KEY) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val songs = pluralStringResource(R.plurals.browse_song_count, shown.size, shown.size)
                BasicText("$songs · ${formatDuration(totalMs)}", style = colors.caption)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (shown.isNotEmpty()) {
                        PillButton(stringResource(R.string.ui_play), onClick = {
                            player?.shuffleModeEnabled = false
                            player?.playId(MediaId.Playlist(playlist.id))
                        })
                        PillButton(stringResource(R.string.ui_shuffle), filled = false, onClick = {
                            player?.shuffleModeEnabled = true
                            player?.playId(MediaId.Playlist(playlist.id))
                        })
                    }
                    PillButton(stringResource(R.string.ui_add_songs), onClick = onAddSongs, filled = shown.isEmpty())
                    PillButton(stringResource(R.string.ui_delete), onClick = onDelete, filled = false)
                }
            }
        }
        if (shown.isEmpty()) {
            item { BasicText(stringResource(R.string.ui_playlist_empty), style = colors.caption, modifier = Modifier.padding(horizontal = 16.dp)) }
        }
        // Keys are positions: a song may appear twice, and removing one shifts the rest.
        items(shown.size, key = { "${shown[it]}" }) { i ->
            val position = shown[i]
            val trackId = playlist.trackIds[position]
            val t = snapshot.trackIndex(trackId)
            TrackRow(
                number = null,
                title = snapshot.trackTitles[t],
                subtitle = snapshot.artistNames[snapshot.trackArtist[t]] + " · " + snapshot.albumTitles[snapshot.trackAlbum[t]],
                durationMs = snapshot.trackDurationMs[t],
                playing = trackId == playingTrackId,
                onClick = { player?.playId(MediaId.Track(trackId, TrackContext.InPlaylist(playlist.id))) },
                trailing = { IconButton(R.drawable.ic_close, stringResource(R.string.ui_remove_from_playlist), { onRemove(position) }, size = 40.dp) },
            )
        }
    }
}

/**
 * Puts songs in playlists, as Spotify's and Apple Music's pickers do: recently changed playlists
 * first, a check on those that already have the songs, and a tap that adds them or, on a checked
 * one, takes them out again. The panel stays open so several playlists can be ticked, until Done.
 * One field filters the list and names a new playlist. With no songs (the Playlists tab's "New
 * playlist") it only makes one.
 */
@Composable
internal fun AddToPlaylistPanel(
    playlists: List<Playlist>,
    songs: LongArray,
    onToggle: (playlist: Playlist, contained: Boolean) -> Unit,
    onCreate: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalColors.current
    val field = rememberTextFieldState()
    val text = field.text.toString().trim()
    val adding = songs.isNotEmpty()
    val shown = remember(playlists, text) {
        val key = TextNormalizer.normalize(text)
        playlists.sortedByDescending { it.updatedMillis }.filter { key.isEmpty() || TextNormalizer.normalize(it.name).contains(key) }
    }
    val canCreate = text.isNotEmpty() && playlists.none { it.name.equals(text, ignoreCase = true) }
    val create = {
        if (canCreate) {
            onCreate(text)
            field.clearText()
        }
    }
    Panel(onDismiss) {
        BasicText(
            stringResource(if (adding) R.string.ui_add_to_playlist else R.string.ui_new_playlist),
            style = colors.title,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.background)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (field.text.isEmpty()) {
                BasicText(
                    stringResource(if (adding) R.string.ui_find_playlist else R.string.ui_playlist_name),
                    style = colors.body.copy(color = colors.muted),
                )
            }
            BasicTextField(
                state = field,
                textStyle = colors.body,
                cursorBrush = SolidColor(colors.accent),
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                onKeyboardAction = { create() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
            if (canCreate) {
                item(key = NEW_KEY) {
                    BasicText(
                        stringResource(R.string.ui_create_named, text),
                        style = colors.body.copy(color = colors.accent, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = create).padding(16.dp),
                    )
                }
            }
            if (adding) {
                items(shown.size, key = { shown[it].id }) { i ->
                    val playlist = shown[i]
                    val contained = remember(playlist, songs) { playlist.containsAll(songs) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Checkbox) { onToggle(playlist, contained) }
                            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            BasicText(playlist.name, style = colors.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val count = playlist.trackIds.size
                            BasicText(pluralStringResource(R.plurals.browse_song_count, count, count), style = colors.caption)
                        }
                        val description = stringResource(if (contained) R.string.ui_in_playlist else R.string.ui_add_to_playlist)
                        Box(Modifier.size(48.dp).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
                            Image(
                                painterResource(if (contained) R.drawable.ic_check else R.drawable.ic_playlist_add),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(if (contained) colors.accent else colors.muted),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            if (adding) {
                PillButton(stringResource(R.string.ui_done), onClick = onDismiss)
            } else {
                PillButton(stringResource(R.string.ui_create), onClick = { if (canCreate) onCreate(text) })
            }
        }
    }
}

/** Asks before something that cannot be undone. */
@Composable
internal fun ConfirmPanel(message: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalColors.current
    Panel(onDismiss) {
        BasicText(message, style = colors.body, modifier = Modifier.padding(16.dp))
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            PillButton(stringResource(R.string.ui_cancel), onClick = onDismiss, filled = false)
            PillButton(action, onClick = onConfirm)
        }
    }
}

/**
 * A card over a dimmed screen, drawn in the window rather than as a dialog so it appears without
 * an animation. Tapping outside or back dismisses it.
 */
@Composable
private fun Panel(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val colors = LocalColors.current
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(SCRIM)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                // Taps inside the card must not reach the scrim.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            content()
        }
    }
}

private val SCRIM = Color(0x99000000)
private const val HEADER_KEY = "header"
private const val NEW_KEY = "new"
