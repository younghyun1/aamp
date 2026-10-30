package dev.cyh.aamp.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.cyh.aamp.R
import dev.cyh.aamp.aamp
import dev.cyh.aamp.artwork.ArtworkCache
import dev.cyh.aamp.library.LibraryState
import dev.cyh.aamp.voice.VoiceLogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The phone screen: grants music access, shows the library and every voice request received.
 * Built from platform widgets; nothing here runs on the Android Auto path.
 */
class MainActivity : Activity() {
    private var uiScope: CoroutineScope? = null
    private lateinit var permissionText: TextView
    private lateinit var grantButton: Button
    private lateinit var libraryText: TextView
    private lateinit var voiceText: TextView
    private val timeFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
    }

    override fun onStart() {
        super.onStart()
        renderPermission()
        val app = aamp
        uiScope = MainScope().apply {
            launch { app.library.state.collect(::renderLibrary) }
            launch { app.voiceLog.recent.collect(::renderVoiceLog) }
        }
    }

    override fun onStop() {
        uiScope?.cancel()
        uiScope = null
        super.onStop()
    }

    @Deprecated("Platform callback; the activity result API needs AndroidX")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_AUDIO) return
        renderPermission()
        val app = aamp
        app.library.rescan()
        // Albums marked as coverless before photo access was granted may have folder covers now.
        app.appScope.launch(Dispatchers.IO) { ArtworkCache.clear(app) }
    }

    private fun has(permission: String): Boolean = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun renderPermission() {
        val music = has(Manifest.permission.READ_MEDIA_AUDIO)
        val covers = has(Manifest.permission.READ_MEDIA_IMAGES)
        permissionText.text = buildString {
            append(getString(if (music) R.string.ui_permission_granted else R.string.ui_permission_missing))
            append('\n')
            append(getString(if (covers) R.string.ui_covers_granted else R.string.ui_covers_missing))
        }
        grantButton.visibility = if (music && covers) View.GONE else View.VISIBLE
    }

    private fun renderLibrary(state: LibraryState) {
        libraryText.text = when (state) {
            LibraryState.Loading -> getString(R.string.ui_library_loading)
            LibraryState.PermissionMissing -> getString(R.string.ui_permission_missing)
            is LibraryState.Ready -> with(state.stats) {
                getString(R.string.ui_library_stats, tracks, albums, artists, durationMs)
            }
            is LibraryState.Failed -> getString(R.string.ui_library_failed, state.message)
        }
    }

    private fun renderVoiceLog(entries: List<VoiceLogEntry>) {
        voiceText.text = if (entries.isEmpty()) {
            getString(R.string.ui_voice_empty)
        } else {
            entries.joinToString("\n\n") { e ->
                buildString {
                    append(timeFormat.format(Instant.ofEpochMilli(e.timeMillis))).append("  ").append(e.caller).append('\n')
                    append("said:   \"").append(e.query).append("\"\n")
                    if (e.hints.isNotEmpty()) append("hints:  ").append(e.hints).append('\n')
                    append("read:   ").append(e.parsed).append('\n')
                    append("played: ").append(e.outcome).append("  (").append(e.latencyMs).append(" ms)")
                }
            }
        }
    }

    private fun buildContent(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        permissionText = TextView(this)
        grantButton = Button(this).apply {
            setText(R.string.ui_grant_access)
            setOnClickListener { requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_IMAGES), REQUEST_AUDIO) }
        }
        libraryText = TextView(this).apply { setPadding(0, pad, 0, 0) }
        val rescan = Button(this).apply {
            setText(R.string.ui_rescan)
            setOnClickListener {
                val app = aamp
                app.library.rescan()
                // A manual rescan also drops cached covers, so changed artwork shows up.
                app.appScope.launch(Dispatchers.IO) { ArtworkCache.clear(app) }
            }
        }
        val voiceTitle = TextView(this).apply {
            setText(R.string.ui_voice_title)
            setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
            setPadding(0, pad, 0, pad / 2)
        }
        voiceText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            listOf(permissionText, grantButton, libraryText, rescan, voiceTitle, voiceText).forEach(::addView)
        }
        return ScrollView(this).apply {
            addView(column)
            // Edge-to-edge is mandatory for this target SDK; keep content clear of the system bars.
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
    }

    private companion object {
        const val REQUEST_AUDIO = 1
    }
}
