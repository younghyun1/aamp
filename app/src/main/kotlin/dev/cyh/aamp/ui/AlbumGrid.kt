package dev.cyh.aamp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cyh.aamp.R
import dev.cyh.aamp.library.LibrarySnapshot

/**
 * Album covers in a grid. Tapping a cover opens the album's songs; a long press plays it.
 *
 * [albums] lists snapshot album indices to show; null shows every album in title order with a
 * letter rail along the edge for jumping to a section. [header] spans the full width above.
 */
@Composable
internal fun AlbumGrid(
    snapshot: LibrarySnapshot,
    albums: IntArray?,
    covers: CoverLoader,
    onOpen: (album: Int) -> Unit,
    onPlay: (album: Int) -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    val count = albums?.size ?: snapshot.albumCount
    // Albums are in title order, so an album's index is its grid position.
    val sections = remember(snapshot, albums, header) {
        if (albums == null && header == null) RailSections.of(snapshot.albumLetters) else null
    }
    val gridState = rememberLazyGridState()
    WithLetterRail(sections, scrollTo = { gridState.scrollToItem(it) }) { rail ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val usable = maxWidth - PADDING * 2 - rail
            val columns = ((usable + SPACING) / (MIN_CELL + SPACING)).toInt().coerceAtLeast(MIN_COLUMNS)
            val cellPx = with(LocalDensity.current) { ((usable - SPACING * (columns - 1)) / columns).roundToPx() }
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
                    AlbumTile(snapshot, albums?.get(i) ?: i, covers, cellPx, onOpen, onPlay)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class) // combinedClickable's label parameters
@Composable
private fun AlbumTile(snapshot: LibrarySnapshot, album: Int, covers: CoverLoader, sizePx: Int, onOpen: (Int) -> Unit, onPlay: (Int) -> Unit) {
    val colors = LocalColors.current
    Column(
        Modifier.combinedClickable(
            onClickLabel = stringResource(R.string.ui_show_songs),
            onLongClickLabel = stringResource(R.string.ui_play),
            onLongClick = { onPlay(album) },
            onClick = { onOpen(album) },
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

private val PADDING = 16.dp
private val SPACING = 12.dp
private val MIN_CELL = 104.dp

/** Three covers across even on a narrow phone; wider screens fit more. */
private const val MIN_COLUMNS = 3
private const val HEADER_KEY = "header"
private const val ALBUM_TYPE = 0
