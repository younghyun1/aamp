package dev.cyh.aamp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cyh.aamp.R
import dev.cyh.aamp.library.LetterIndex
import dev.cyh.aamp.library.LibrarySnapshot
import kotlinx.coroutines.launch

/**
 * Album covers in a grid. Tapping a cover plays the album; a long press opens its songs.
 *
 * [albums] lists snapshot album indices to show; null shows every album in title order with a
 * letter rail along the edge for jumping to a section. [header] spans the full width above.
 */
@Composable
internal fun AlbumGrid(
    snapshot: LibrarySnapshot,
    albums: IntArray?,
    covers: CoverLoader,
    onPlay: (album: Int) -> Unit,
    onOpen: (album: Int) -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    val letters = if (albums == null && header == null && snapshot.albumLetters.size > 1) snapshot.albumLetters else null
    val count = albums?.size ?: snapshot.albumCount
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rail = if (letters != null) RAIL_WIDTH else 0.dp
        val usable = maxWidth - PADDING * 2 - rail
        val columns = ((usable + SPACING) / (MIN_CELL + SPACING)).toInt().coerceAtLeast(1)
        val cellPx = with(LocalDensity.current) { ((usable - SPACING * (columns - 1)) / columns).roundToPx() }
        val gridState = rememberLazyGridState()
        val scope = rememberCoroutineScope()
        var activeSection by remember { mutableIntStateOf(-1) }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            contentPadding = PaddingValues(start = PADDING, end = PADDING + rail, top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(SPACING),
            verticalArrangement = Arrangement.spacedBy(SPACING),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (header != null) item(key = HEADER_KEY, span = { GridItemSpan(maxLineSpan) }) { header() }
            items(count, key = { snapshot.albumIds[albums?.get(it) ?: it] }, contentType = { ALBUM_TYPE }) { i ->
                val album = albums?.get(i) ?: i
                AlbumTile(snapshot, album, covers, cellPx, onPlay, onOpen)
            }
        }

        if (letters != null) {
            LetterRail(
                letters,
                onActive = { activeSection = it },
                // The grid lists albums in title order, so a section's first member is its position.
                onSelect = { section -> scope.launch { gridState.scrollToItem(letters.members(section)[0]) } },
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(RAIL_WIDTH),
            )
            if (activeSection >= 0) SectionBubble(letters.labels[activeSection], Modifier.align(Alignment.Center))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class) // combinedClickable's label parameters
@Composable
private fun AlbumTile(snapshot: LibrarySnapshot, album: Int, covers: CoverLoader, sizePx: Int, onPlay: (Int) -> Unit, onOpen: (Int) -> Unit) {
    val colors = LocalColors.current
    Column(
        Modifier.combinedClickable(
            onClickLabel = stringResource(R.string.ui_play),
            onLongClickLabel = stringResource(R.string.ui_show_songs),
            onLongClick = { onOpen(album) },
            onClick = { onPlay(album) },
        ),
    ) {
        AlbumCover(snapshot.albumIds[album], covers, sizePx, Modifier.fillMaxWidth())
        BasicText(
            snapshot.albumTitles[album],
            style = colors.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        BasicText(snapshot.artistNames[snapshot.albumArtist[album]], style = colors.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Section labels down the edge; touching or dragging over one jumps to that section. */
@Composable
private fun LetterRail(letters: LetterIndex, onActive: (Int) -> Unit, onSelect: (Int) -> Unit, modifier: Modifier) {
    val colors = LocalColors.current
    val haptics = LocalHapticFeedback.current
    val active by rememberUpdatedState(onActive)
    val select by rememberUpdatedState(onSelect)
    Column(
        modifier.pointerInput(letters) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                var current = -1
                fun pick(y: Float) {
                    val section = (y / size.height * letters.size).toInt().coerceIn(0, letters.size - 1)
                    if (section == current) return
                    current = section
                    active(section)
                    select(section)
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                pick(down.position.y)
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()
                    pick(change.position.y)
                }
                active(-1)
            }
        },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val style = colors.caption.copy(color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        for (label in letters.labels) BasicText(label, style = style)
    }
}

/** The section being jumped to, large in the middle of the grid. */
@Composable
private fun SectionBubble(label: String, modifier: Modifier) {
    val colors = LocalColors.current
    Box(
        modifier.size(88.dp).clip(RoundedCornerShape(20.dp)).background(colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = colors.heading.copy(fontSize = 40.sp, textAlign = TextAlign.Center))
    }
}

private val PADDING = 16.dp
private val SPACING = 12.dp
private val MIN_CELL = 104.dp
private val RAIL_WIDTH = 24.dp
private const val HEADER_KEY = "header"
private const val ALBUM_TYPE = 0
