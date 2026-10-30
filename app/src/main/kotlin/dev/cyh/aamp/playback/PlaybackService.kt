package dev.cyh.aamp.playback

import android.app.PendingIntent
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import dev.cyh.aamp.aamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The media service Android Auto binds to. Owns the player and the library session; Media3 runs
 * the mediaPlayback foreground service and the media notification while something plays.
 */
class PlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val app = aamp
        val builder = MediaLibrarySession.Builder(
            this,
            AudioPlayer.create(this),
            LibraryCallback(this, app.library, app.voiceLog, scope),
        )
        // Tapping the notification opens the phone screen. The launch intent avoids naming the
        // activity class, which would load UI code on the Android Auto path.
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setSessionActivity(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE))
        }
        session = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }
}
