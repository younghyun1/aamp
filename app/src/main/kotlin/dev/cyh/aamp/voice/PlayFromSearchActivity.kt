package dev.cyh.aamp.voice

import android.app.Activity
import android.app.SearchManager
import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.cyh.aamp.playback.PlaybackService
import java.util.concurrent.ExecutionException

/**
 * Replays a voice request from an intent, through the same session path Android Auto uses:
 *
 * ```
 * adb shell am start -a android.media.action.MEDIA_PLAY_FROM_SEARCH -n dev.cyh.aamp/.voice.PlayFromSearchActivity \
 *   --es query "album and justice for all by metallica" \
 *   --es android.intent.extra.focus vnd.android.cursor.item/album \
 *   --es android.intent.extra.album "...And Justice for All" --es android.intent.extra.artist Metallica
 * ```
 *
 * Gemini never sends this intent; it exists so requests can be tested without a car.
 */
class PlayFromSearchActivity : Activity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val query = intent.getStringExtra(SearchManager.QUERY).orEmpty()
        val extras = intent.extras?.let(::Bundle) ?: Bundle()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val controller = future.get()
                val request = MediaItem.RequestMetadata.Builder().setSearchQuery(query).setExtras(extras).build()
                controller.setMediaItem(MediaItem.Builder().setRequestMetadata(request).build())
                controller.prepare()
                controller.play()
            } catch (e: ExecutionException) {
                Log.w(VoiceLog.TAG, "Could not connect to the playback service", e)
            }
            finish()
        }, mainExecutor)
    }

    override fun onDestroy() {
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onDestroy()
    }
}
