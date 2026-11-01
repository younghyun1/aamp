package dev.cyh.aamp.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.ProgressStateWithTickInterval
import androidx.media3.ui.compose.state.rememberCurrentMediaItemState
import androidx.media3.ui.compose.state.rememberNextButtonState
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberPreviousButtonState
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import androidx.media3.ui.compose.state.rememberRepeatButtonState
import androidx.media3.ui.compose.state.rememberShuffleButtonState
import dev.cyh.aamp.R
import dev.cyh.aamp.browse.MediaId
import dev.cyh.aamp.browse.MediaIds
import dev.cyh.aamp.library.LibrarySnapshot
import androidx.media3.session.R as Media3R

/**
 * The playing track with a seek bar and the basic controls. The artist and album names open the
 * artist's albums and the album's songs, as they do in Android Auto. Draws nothing while the
 * queue is empty.
 */
@OptIn(UnstableApi::class) // Media3's Compose state holders
@Composable
internal fun NowPlayingBar(
    player: Player,
    snapshot: LibrarySnapshot?,
    covers: CoverLoader,
    onOpenAlbum: (albumId: Long) -> Unit,
    onOpenArtist: (artistKey: Long) -> Unit,
) {
    val current = rememberCurrentMediaItemState(player)
    val item = current.mediaItem ?: return
    val colors = LocalColors.current
    val metadata = current.mediaMetadata
    val track = (MediaIds.parse(item.mediaId) as? MediaId.Track)?.let { snapshot?.trackIndex(it.trackId) }?.takeIf { it >= 0 }
    val albumId = if (snapshot != null && track != null) snapshot.albumIds[snapshot.trackAlbum[track]] else null
    val artistKey = if (snapshot != null && track != null) snapshot.artistKeys[snapshot.trackArtist[track]] else null
    val coverSize = 52.dp
    val coverPx = with(LocalDensity.current) { coverSize.roundToPx() }

    Column(Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val openAlbum = Modifier.clickable(enabled = albumId != null, role = Role.Button) { albumId?.let(onOpenAlbum) }
            if (albumId != null) {
                AlbumCover(albumId, covers, coverPx, openAlbum.width(coverSize), cornerRadius = 6)
            } else {
                Spacer(Modifier.width(coverSize))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                BasicText(metadata.title?.toString().orEmpty(), style = colors.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row {
                    BasicText(
                        metadata.artist?.toString().orEmpty(),
                        style = colors.caption.copy(color = colors.accent),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clickable(enabled = artistKey != null, role = Role.Button) { artistKey?.let(onOpenArtist) },
                    )
                    BasicText(" · ", style = colors.caption)
                    BasicText(
                        metadata.albumTitle?.toString().orEmpty(),
                        style = colors.caption.copy(color = colors.accent),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).then(openAlbum),
                    )
                }
            }
        }
        val scope = rememberCoroutineScope()
        val progress = rememberProgressStateWithTickInterval(player, PROGRESS_TICK_MS, scope)
        SeekBar(player, progress)
        ProgressTimes(progress)
        Controls(player)
    }
}

/**
 * A thin progress line; tap or drag to seek. The position is read while drawing, so a progress
 * tick redraws the line without recomposing anything.
 */
@OptIn(UnstableApi::class)
@Composable
private fun SeekBar(player: Player, progress: ProgressStateWithTickInterval) {
    val colors = LocalColors.current
    var dragFraction by remember { mutableFloatStateOf(NOT_DRAGGING) }
    fun seek(fraction: Float) {
        val duration = progress.durationMs
        if (duration > 0 && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            player.seekTo((fraction.coerceIn(0f, 1f) * duration).toLong())
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(player) { detectTapGestures { seek(it.x / size.width) } }
            .pointerInput(player) {
                detectHorizontalDragGestures(
                    onDragStart = { dragFraction = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        seek(dragFraction)
                        dragFraction = NOT_DRAGGING
                    },
                    onDragCancel = { dragFraction = NOT_DRAGGING },
                ) { change, _ ->
                    change.consume()
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            }
            .drawBehind {
                val duration = progress.durationMs
                val fraction = when {
                    dragFraction != NOT_DRAGGING -> dragFraction
                    duration > 0 -> (progress.currentPositionMs.toFloat() / duration).coerceIn(0f, 1f)
                    else -> 0f
                }
                val line = 4.dp.toPx()
                val top = (size.height - line) / 2
                val radius = CornerRadius(line / 2)
                drawRoundRect(colors.placeholder, Offset(0f, top), Size(size.width, line), radius)
                drawRoundRect(colors.accent, Offset(0f, top), Size(size.width * fraction, line), radius)
                drawCircle(colors.accent, 6.dp.toPx(), Offset(size.width * fraction, size.height / 2))
            },
    )
}

/** Elapsed and total time; its own composable so each progress tick recomposes only these two texts. */
@OptIn(UnstableApi::class)
@Composable
private fun ProgressTimes(progress: ProgressStateWithTickInterval) {
    val colors = LocalColors.current
    Row {
        BasicText(formatDuration(progress.currentPositionMs), style = colors.caption)
        Spacer(Modifier.weight(1f))
        BasicText(formatDuration(progress.durationMs), style = colors.caption)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Controls(player: Player) {
    val colors = LocalColors.current
    val shuffle = rememberShuffleButtonState(player)
    val previous = rememberPreviousButtonState(player)
    val playPause = rememberPlayPauseButtonState(player)
    val next = rememberNextButtonState(player)
    val repeat = rememberRepeatButtonState(player, REPEAT_ORDER)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            if (shuffle.shuffleOn) Media3R.drawable.media3_icon_shuffle_on else Media3R.drawable.media3_icon_shuffle_off,
            stringResource(if (shuffle.shuffleOn) R.string.control_shuffle_on else R.string.control_shuffle_off),
            shuffle::onClick,
            enabled = shuffle.isEnabled,
            tint = if (shuffle.shuffleOn) colors.accent else colors.text,
        )
        IconButton(Media3R.drawable.media3_icon_previous, stringResource(R.string.ui_previous), previous::onClick, enabled = previous.isEnabled)
        IconButton(
            if (playPause.showPlay) Media3R.drawable.media3_icon_play else Media3R.drawable.media3_icon_pause,
            stringResource(if (playPause.showPlay) R.string.ui_play else R.string.ui_pause),
            playPause::onClick,
            enabled = playPause.isEnabled,
            tint = colors.onAccent,
            background = colors.accent,
            size = 56.dp,
        )
        IconButton(Media3R.drawable.media3_icon_next, stringResource(R.string.ui_next), next::onClick, enabled = next.isEnabled)
        val (icon, label) = when (repeat.repeatModeState) {
            Player.REPEAT_MODE_ALL -> Media3R.drawable.media3_icon_repeat_all to R.string.control_repeat_all
            Player.REPEAT_MODE_ONE -> Media3R.drawable.media3_icon_repeat_one to R.string.control_repeat_one
            else -> Media3R.drawable.media3_icon_repeat_off to R.string.control_repeat_off
        }
        IconButton(
            icon,
            stringResource(label),
            repeat::onClick,
            enabled = repeat.isEnabled,
            tint = if (repeat.repeatModeState == Player.REPEAT_MODE_OFF) colors.text else colors.accent,
        )
    }
}

private const val PROGRESS_TICK_MS = 500L
private const val NOT_DRAGGING = -1f

/** Same order as the Android Auto repeat button: off, all, one. */
private val REPEAT_ORDER = listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE)
