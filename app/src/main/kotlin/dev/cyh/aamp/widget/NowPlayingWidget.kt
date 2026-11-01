package dev.cyh.aamp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.widget.RemoteViews
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.PlaybackPendingIntentBuilder
import dev.cyh.aamp.R
import dev.cyh.aamp.playback.PlaybackService
import androidx.media3.session.R as Media3R

/**
 * The home screen widget: cover, title and artist of the playing track with previous, play/pause
 * and next buttons; tapping anywhere else opens aamp.
 *
 * The playback service pushes every change through [show]. The views are RemoteViews, which the
 * launcher inflates, so updating the widget inflates nothing in aamp's process. The buttons send
 * media button intents to the playback service like the media notification's, so play works
 * even when the service is not running: it resumes the saved queue. Without a running service
 * (after a reboot, or when placed while nothing plays) the widget shows its idle text.
 */
class NowPlayingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetManager.updateAppWidget(appWidgetIds, render(context, current))
    }

    /** The playing track as the widget shows it. */
    data class State(val title: String?, val artist: String?, val artworkUri: Uri?, val playing: Boolean)

    companion object {
        // Last state pushed by the playback service in this process; null when nothing is queued.
        @Volatile
        private var current: State? = null

        /** Shows [state] on every placed widget; does nothing when it is already shown. */
        fun show(context: Context, state: State?) {
            if (state == current) return
            current = state
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, render(context, state))
        }

        /** Keeps the last track on the widget, paused, once the playback service stops. */
        fun stopped(context: Context) {
            current?.let { show(context, it.copy(playing = false)) }
        }

        private fun render(context: Context, state: State?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_now_playing)
            views.setTextViewText(R.id.widget_title, state?.title ?: context.getString(R.string.widget_idle_title))
            views.setTextViewText(R.id.widget_artist, state?.artist ?: context.getString(R.string.widget_idle_subtitle))
            val artwork = state?.artworkUri
            if (artwork != null) {
                views.setImageViewUri(R.id.widget_cover, artwork)
            } else {
                views.setImageViewResource(R.id.widget_cover, R.drawable.ic_launcher_foreground)
            }
            val playing = state?.playing == true
            views.setImageViewResource(
                R.id.widget_play_pause,
                if (playing) Media3R.drawable.media3_icon_pause else Media3R.drawable.media3_icon_play,
            )
            views.setContentDescription(R.id.widget_play_pause, context.getString(if (playing) R.string.ui_pause else R.string.ui_play))
            // Play may have to start the service, which then must go foreground; the others only
            // make sense while it runs.
            views.setOnClickPendingIntent(R.id.widget_play_pause, command(context, Player.COMMAND_PLAY_PAUSE, startService = !playing))
            views.setOnClickPendingIntent(R.id.widget_previous, command(context, Player.COMMAND_SEEK_TO_PREVIOUS, startService = false))
            views.setOnClickPendingIntent(R.id.widget_next, command(context, Player.COMMAND_SEEK_TO_NEXT, startService = false))
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                views.setOnClickPendingIntent(android.R.id.background, PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE))
            }
            return views
        }

        @OptIn(UnstableApi::class) // Media3's media button pending intents
        private fun command(context: Context, command: Int, startService: Boolean): PendingIntent =
            PlaybackPendingIntentBuilder(context, command, PlaybackService::class.java)
                .setStartAsForegroundService(startService)
                .build()
    }
}
