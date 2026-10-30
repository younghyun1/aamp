package dev.cyh.aamp.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface LibraryState {
    data object Loading : LibraryState
    data object PermissionMissing : LibraryState
    class Ready(val library: LoadedLibrary, val stats: ScanStats) : LibraryState
    data class Failed(val message: String) : LibraryState
}

data class ScanStats(val tracks: Int, val albums: Int, val artists: Int, val durationMs: Long, val finishedAtMillis: Long)

/**
 * The process-wide music library. Scans MediaStore on creation and again whenever MediaStore
 * reports a change, and publishes each result as an immutable [LibraryState].
 *
 * While a rescan runs, the previous snapshot keeps serving requests. All scanning runs on one
 * background thread at a time; [rescan] must be called from the main thread.
 */
class Library(private val context: Context, private val scope: CoroutineScope) {
    private val scanDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val _state = MutableStateFlow<LibraryState>(LibraryState.Loading)
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var pendingChange: Job? = null

    // Written only on scanDispatcher.
    private var lastSignature: MediaStoreSignature? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            // MediaStore sends bursts of notifications while it scans; act once things settle.
            pendingChange?.cancel()
            pendingChange = scope.launch {
                delay(CHANGE_SETTLE)
                rescan(force = false)
            }
        }
    }

    init {
        context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        rescan(force = true)
    }

    /** Starts a scan, replacing any running one. Without [force] it is skipped when MediaStore is unchanged. */
    fun rescan(force: Boolean = true) {
        scanJob?.cancel()
        scanJob = scope.launch(scanDispatcher) { scan(force) }
    }

    /** The current state once the first scan has finished, or [LibraryState.Loading] after [timeout]. */
    suspend fun await(timeout: Duration): LibraryState =
        withTimeoutOrNull(timeout) { state.first { it !is LibraryState.Loading } } ?: LibraryState.Loading

    private suspend fun scan(force: Boolean) {
        if (context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            lastSignature = null
            _state.value = LibraryState.PermissionMissing
            return
        }
        val signature = readSignature()
        if (!force && signature != null && signature == lastSignature && _state.value is LibraryState.Ready) return

        val started = System.nanoTime()
        when (val result = MediaStoreScanner.scan(context.contentResolver)) {
            is ScanResult.Success -> {
                currentCoroutineContext().ensureActive()
                val loaded = LibraryBuilder.build(result.rows)
                currentCoroutineContext().ensureActive()
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                val snapshot = loaded.snapshot
                val stats = ScanStats(snapshot.trackCount, snapshot.albumCount, snapshot.artistCount, elapsedMs, System.currentTimeMillis())
                lastSignature = signature
                _state.value = LibraryState.Ready(loaded, stats)
                Log.i(TAG, "Scanned ${stats.tracks} tracks, ${stats.albums} albums, ${stats.artists} artists in $elapsedMs ms")
            }
            ScanResult.PermissionDenied -> {
                lastSignature = null
                _state.value = LibraryState.PermissionMissing
            }
            is ScanResult.Failure -> {
                Log.w(TAG, "Scan failed: ${result.message}")
                // A stale library is more useful than none.
                if (_state.value !is LibraryState.Ready) _state.value = LibraryState.Failed(result.message)
            }
        }
    }

    /** MediaStore's change counters, or null when they cannot be read; the scan then always runs. */
    private fun readSignature(): MediaStoreSignature? = try {
        MediaStoreSignature.read(context)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Could not read MediaStore generations", e)
        null
    } catch (e: IllegalStateException) {
        Log.w(TAG, "Could not read MediaStore generations", e)
        null
    }

    companion object {
        const val TAG = "AampLibrary"
        private val CHANGE_SETTLE = 2.seconds
    }
}
