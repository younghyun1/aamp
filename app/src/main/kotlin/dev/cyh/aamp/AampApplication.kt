package dev.cyh.aamp

import android.app.Application
import android.content.Context
import dev.cyh.aamp.library.Library
import dev.cyh.aamp.voice.VoiceLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Holds the process-wide library and voice log. Both are created on first use, so process
 * start does no work until the playback service or the phone screen needs them.
 */
class AampApplication : Application() {
    /** Scope for work that belongs to the process rather than one component: scans and log writes. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val library: Library by lazy { Library(this, appScope) }

    val voiceLog: VoiceLog by lazy { VoiceLog(File(noBackupFilesDir, "voice-requests.log"), appScope) }
}

val Context.aamp: AampApplication get() = applicationContext as AampApplication
