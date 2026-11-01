package dev.cyh.aamp.ui

import android.content.ComponentName
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.cyh.aamp.browse.MediaId
import dev.cyh.aamp.browse.MediaIds
import dev.cyh.aamp.playback.PlaybackService
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException

/**
 * A controller for the playback service, connected while the screen is started and released when
 * it stops, so the service can shut down once nothing plays. Null until connected.
 */
@Composable
internal fun rememberPlaybackController(): MediaController? {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    LifecycleStartEffect(context) {
        var active = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            if (!active) return@addListener
            controller = try {
                future.get()
            } catch (e: ExecutionException) {
                Log.w(TAG, "Could not connect to the playback service", e)
                null
            } catch (_: CancellationException) {
                null
            }
        }, context.mainExecutor)
        onStopOrDispose {
            active = false
            controller = null
            MediaController.releaseFuture(future)
        }
    }
    return controller
}

/**
 * Plays what [id] names. The service turns the id into the queue Android Auto would build for the
 * same pick: an album from its first track, a track within its album, an artist shuffled.
 */
internal fun Player.playId(id: MediaId) {
    setMediaItem(MediaItem.Builder().setMediaId(MediaIds.encode(id)).build())
    prepare()
    play()
}

private const val TAG = "Aamp"
