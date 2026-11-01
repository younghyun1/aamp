package dev.cyh.aamp.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberCurrentMediaItemState
import dev.cyh.aamp.R
import dev.cyh.aamp.aamp
import dev.cyh.aamp.artwork.ArtworkCache
import dev.cyh.aamp.browse.MediaId
import dev.cyh.aamp.browse.MediaIds
import dev.cyh.aamp.library.LibrarySnapshot
import dev.cyh.aamp.library.LibraryState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** A page of the phone screen; pages stack, and back returns to the previous one. */
internal sealed interface Screen {
    data object Albums : Screen
    data object Status : Screen
    data class Album(val albumId: Long) : Screen
    data class Artist(val artistKey: Long) : Screen
}

/**
 * The phone screen: every album as a cover grid, album and artist pages, the playing track with
 * controls, and a status page with permissions and the voice log.
 */
@Composable
internal fun AampApp() {
    val context = LocalContext.current
    val app = context.aamp
    val colors = LocalColors.current
    val libraryState by app.library.state.collectAsStateWithLifecycle()
    val snapshot = (libraryState as? LibraryState.Ready)?.library?.snapshot
    val controller = rememberPlaybackController()
    val covers = remember { CoverLoader(context.applicationContext) }
    LifecycleStartEffect(covers) { onStopOrDispose { covers.clear() } }

    var accessCheck by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        accessCheck++ // Access can change in system settings while the screen is away.
        onPauseOrDispose {}
    }
    val access = remember(accessCheck) { context.access() }
    val rescan = {
        app.library.rescan()
        // Albums marked as coverless may have covers now; changed artwork shows up too.
        app.appScope.launch(Dispatchers.IO) { ArtworkCache.clear(app) }
        covers.clear()
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        accessCheck++
        rescan()
    }
    val requestAccess = { permissions.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_IMAGES)) }

    val stack = rememberSaveable(saver = StackSaver) { mutableStateListOf<Screen>(Screen.Albums) }
    val open: (Screen) -> Unit = { if (stack.last() != it) stack += it }
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }
    val screen = stack.last()
    val playingTrackId = currentTrackId(controller)

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        Header(
            title = title(screen, snapshot),
            canGoBack = stack.size > 1,
            onBack = { stack.removeAt(stack.lastIndex) },
            onStatus = if (screen != Screen.Status) ({ open(Screen.Status) }) else null,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (screen) {
                Screen.Status -> StatusScreen(libraryState, access, requestAccess, rescan)
                Screen.Albums -> when {
                    snapshot == null -> LibraryMessage(libraryState, requestAccess, rescan)
                    snapshot.albumCount == 0 -> Message(stringResource(R.string.ui_no_music))
                    else -> AlbumGrid(
                        snapshot,
                        albums = null,
                        covers = covers,
                        onPlay = { controller?.playId(MediaId.Album(snapshot.albumIds[it])) },
                        onOpen = { open(Screen.Album(snapshot.albumIds[it])) },
                    )
                }
                is Screen.Album -> {
                    val album = snapshot?.albumIndex(screen.albumId) ?: -1
                    if (snapshot != null && album >= 0) {
                        AlbumScreen(snapshot, album, covers, controller, playingTrackId, onOpenArtist = { open(Screen.Artist(it)) })
                    } else {
                        Message(stringResource(R.string.ui_not_found))
                    }
                }
                is Screen.Artist -> {
                    val artist = snapshot?.artistIndex(screen.artistKey) ?: -1
                    if (snapshot != null && artist >= 0) {
                        ArtistScreen(snapshot, artist, covers, controller, playingTrackId, onOpenAlbum = { open(Screen.Album(it)) })
                    } else {
                        Message(stringResource(R.string.ui_not_found))
                    }
                }
            }
        }
        if (controller != null) {
            NowPlayingBar(
                controller,
                snapshot,
                covers,
                onOpenAlbum = { open(Screen.Album(it)) },
                onOpenArtist = { open(Screen.Artist(it)) },
            )
        }
        // Behind the navigation bar, in the color of whatever is above it.
        Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars).background(colors.surface))
    }
}

@Composable
private fun Header(title: String, canGoBack: Boolean, onBack: () -> Unit, onStatus: (() -> Unit)?) {
    val colors = LocalColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canGoBack) IconButton(R.drawable.ic_back, stringResource(R.string.ui_back), onBack) else Spacer(Modifier.width(12.dp))
        BasicText(title, style = colors.heading, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onStatus != null) IconButton(R.drawable.ic_info, stringResource(R.string.ui_status), onStatus)
    }
}

@Composable
private fun title(screen: Screen, snapshot: LibrarySnapshot?): String = when (screen) {
    Screen.Albums -> stringResource(R.string.app_name)
    Screen.Status -> stringResource(R.string.ui_status)
    is Screen.Album -> snapshot?.albumIndex(screen.albumId)?.takeIf { it >= 0 }?.let { snapshot.albumTitles[it] }.orEmpty()
    is Screen.Artist -> snapshot?.artistIndex(screen.artistKey)?.takeIf { it >= 0 }?.let { snapshot.artistNames[it] }.orEmpty()
}

/** What the albums page shows until the library is ready. */
@Composable
private fun LibraryMessage(state: LibraryState, onRequestAccess: () -> Unit, onRescan: () -> Unit) {
    when (state) {
        LibraryState.PermissionMissing -> Message(stringResource(R.string.ui_permission_missing)) {
            PillButton(stringResource(R.string.ui_grant_access), onRequestAccess)
        }
        is LibraryState.Failed -> Message(libraryText(state)) { PillButton(stringResource(R.string.ui_rescan), onRescan) }
        else -> Message(libraryText(state))
    }
}

@Composable
private fun Message(text: String, action: (@Composable () -> Unit)? = null) {
    val colors = LocalColors.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(text, style = colors.body.copy(textAlign = TextAlign.Center))
        action?.invoke()
    }
}

/** The MediaStore id of the playing track, for highlighting it in lists. */
@OptIn(UnstableApi::class)
@Composable
private fun currentTrackId(player: Player?): Long? {
    if (player == null) return null
    val item = rememberCurrentMediaItemState(player).mediaItem ?: return null
    return (MediaIds.parse(item.mediaId) as? MediaId.Track)?.trackId
}

private fun Context.access(): Access = Access(
    music = checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED,
    covers = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED,
)

/** Saves the page stack across configuration changes and process death as strings. */
private val StackSaver = Saver<SnapshotStateList<Screen>, List<String>>(
    save = { stack -> stack.map(::encode) },
    restore = { saved -> mutableStateListOf<Screen>().apply { saved.mapNotNullTo(this, ::decode) }.ifEmpty { mutableStateListOf(Screen.Albums) } },
)

private fun encode(screen: Screen): String = when (screen) {
    Screen.Albums -> "albums"
    Screen.Status -> "status"
    is Screen.Album -> "album:${screen.albumId}"
    is Screen.Artist -> "artist:${screen.artistKey}"
}

private fun decode(raw: String): Screen? = when {
    raw == "albums" -> Screen.Albums
    raw == "status" -> Screen.Status
    raw.startsWith("album:") -> raw.removePrefix("album:").toLongOrNull()?.let(Screen::Album)
    raw.startsWith("artist:") -> raw.removePrefix("artist:").toLongOrNull()?.let(Screen::Artist)
    else -> null
}
