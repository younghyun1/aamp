package dev.cyh.aamp.playback

import android.app.PendingIntent
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import dev.cyh.aamp.R
import dev.cyh.aamp.aamp
import dev.cyh.aamp.browse.MediaId
import dev.cyh.aamp.browse.MediaIds
import dev.cyh.aamp.widget.NowPlayingWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File

/**
 * The media service Android Auto binds to. Owns the player and the library session; Media3 runs
 * the mediaPlayback foreground service and the media notification while something plays.
 *
 * Besides play/pause and previous/next, which Android Auto draws for every media app, the session
 * offers shuffle and repeat buttons. Android Auto shows only app-provided buttons for those, so
 * they are media button preferences, rebuilt whenever the mode changes so the icon shows the
 * current state.
 *
 * The queue is saved on every track change and pause, so playback can resume after the process
 * dies; see [LibraryCallback.onPlaybackResumption]. The home screen widget gets every change of
 * track or play state.
 */
@OptIn(UnstableApi::class) // Command buttons that carry a player command parameter
class PlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queueWriter = Dispatchers.IO.limitedParallelism(1)
    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val app = aamp
        val queueStore = QueueStore(File(noBackupFilesDir, "queue.bin"))
        val player = AudioPlayer.create(this)
        val callback = LibraryCallback(this, app.library, app.playlists, app.voiceLog, scope, queueStore)
        val builder = MediaLibrarySession.Builder(this, player, callback)
            .setMediaButtonPreferences(controlButtons(player))
        // Tapping the notification opens the phone screen. The launch intent avoids naming the
        // activity class, which would load UI code on the Android Auto path.
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setSessionActivity(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE))
        }
        val built = builder.build()
        session = built
        // Android Auto caches browse levels; tell it when playlists change.
        scope.launch {
            app.playlists.all.drop(1).collect { lists ->
                built.notifyChildrenChanged(MediaIds.encode(MediaId.Playlists), lists.size, null)
                for (p in lists) built.notifyChildrenChanged(MediaIds.encode(MediaId.Playlist(p.id)), p.trackIds.size, null)
            }
        }
        player.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                built.setMediaButtonPreferences(controlButtons(player))
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                built.setMediaButtonPreferences(controlButtons(player))
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) callback.updateQueueTitle(player)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                saveQueue(player, queueStore)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveQueue(player, queueStore)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(*WIDGET_EVENTS)) updateWidget(player)
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        NowPlayingWidget.stopped(this)
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Captures the queue on the main thread and writes it in the background. Writes run in the
     * process scope so one queued just before the service stops still completes.
     */
    private fun saveQueue(player: Player, store: QueueStore) {
        val count = player.mediaItemCount
        if (count == 0 || count > QueueStore.MAX_ITEMS) return
        val saved = QueueStore.Saved(
            mediaIds = List(count) { player.getMediaItemAt(it).mediaId },
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition.coerceAtLeast(0),
        )
        aamp.appScope.launch(queueWriter) { store.write(saved) }
    }

    private fun updateWidget(player: Player) {
        val metadata = player.mediaMetadata
        val state = if (player.currentMediaItem == null) null else NowPlayingWidget.State(
            title = metadata.title?.toString(),
            artist = metadata.artist?.toString(),
            artworkUri = metadata.artworkUri,
            playing = !Util.shouldShowPlayButton(player),
        )
        NowPlayingWidget.show(this, state)
    }

    /** Shuffle and repeat buttons showing the current mode; each tap moves to the next mode. */
    private fun controlButtons(player: Player): List<CommandButton> {
        val shuffle = if (player.shuffleModeEnabled) {
            button(CommandButton.ICON_SHUFFLE_ON, R.string.control_shuffle_on, Player.COMMAND_SET_SHUFFLE_MODE, false)
        } else {
            button(CommandButton.ICON_SHUFFLE_OFF, R.string.control_shuffle_off, Player.COMMAND_SET_SHUFFLE_MODE, true)
        }
        val repeat = when (player.repeatMode) {
            Player.REPEAT_MODE_ALL ->
                button(CommandButton.ICON_REPEAT_ALL, R.string.control_repeat_all, Player.COMMAND_SET_REPEAT_MODE, Player.REPEAT_MODE_ONE)
            Player.REPEAT_MODE_ONE ->
                button(CommandButton.ICON_REPEAT_ONE, R.string.control_repeat_one, Player.COMMAND_SET_REPEAT_MODE, Player.REPEAT_MODE_OFF)
            else ->
                button(CommandButton.ICON_REPEAT_OFF, R.string.control_repeat_off, Player.COMMAND_SET_REPEAT_MODE, Player.REPEAT_MODE_ALL)
        }
        return listOf(shuffle, repeat)
    }

    private companion object {
        /** Changes the widget shows: the track, its metadata, and whether it plays. */
        val WIDGET_EVENTS = intArrayOf(
            Player.EVENT_MEDIA_ITEM_TRANSITION,
            Player.EVENT_MEDIA_METADATA_CHANGED,
            Player.EVENT_PLAY_WHEN_READY_CHANGED,
            Player.EVENT_PLAYBACK_STATE_CHANGED,
        )
    }

    private fun button(icon: Int, name: Int, command: Int, next: Any): CommandButton =
        CommandButton.Builder(icon)
            .setDisplayName(getString(name))
            .setPlayerCommand(command, next)
            .build()
}
