package dev.cyh.aamp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cyh.aamp.R
import dev.cyh.aamp.aamp
import dev.cyh.aamp.library.LibraryState
import dev.cyh.aamp.voice.VoiceLogEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Settings, kept to what there is to set or check: the library with a rescan button, music and
 * photo access, and the voice request log, collapsed unless asked for.
 */
@Composable
internal fun SettingsScreen(
    libraryState: LibraryState,
    access: Access,
    onRequestAccess: () -> Unit,
    onRescan: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalColors.current
    val entries by context.aamp.voiceLog.recent.collectAsStateWithLifecycle()
    var showLog by rememberSaveable { mutableStateOf(false) }
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Section(stringResource(R.string.settings_library)) }
        item {
            val (summary, detail) = when (libraryState) {
                is LibraryState.Ready -> with(libraryState.stats) {
                    val counts = listOf(
                        pluralStringResource(R.plurals.browse_song_count, tracks, tracks),
                        pluralStringResource(R.plurals.browse_album_count, albums, albums),
                        pluralStringResource(R.plurals.browse_artist_count, artists, artists),
                    ).joinToString(" · ")
                    val time = clock.format(Instant.ofEpochMilli(finishedAtMillis))
                    counts to stringResource(if (loaded) R.string.settings_loaded else R.string.settings_scanned, time, durationMs)
                }
                else -> libraryText(libraryState) to null
            }
            Setting(summary, detail) { PillButton(stringResource(R.string.ui_rescan), onRescan, filled = false) }
        }

        item { Section(stringResource(R.string.settings_access)) }
        item { Setting(stringResource(R.string.settings_music), null) { Allowed(access.music, onRequestAccess) } }
        item {
            Setting(stringResource(R.string.settings_covers), stringResource(R.string.settings_covers_detail)) {
                Allowed(access.covers, onRequestAccess)
            }
        }

        item { Section(stringResource(R.string.settings_diagnostics)) }
        item {
            Setting(stringResource(R.string.settings_voice_log), pluralStringResource(R.plurals.settings_request_count, entries.size, entries.size)) {
                BasicText(
                    stringResource(if (showLog) R.string.settings_hide else R.string.settings_show),
                    style = colors.body.copy(color = colors.accent, fontWeight = FontWeight.Medium),
                    modifier = Modifier.clickable(role = Role.Button) { showLog = !showLog }.padding(8.dp),
                )
            }
        }
        if (showLog) {
            if (entries.isEmpty()) {
                item { BasicText(stringResource(R.string.settings_voice_empty), style = colors.caption, modifier = Modifier.padding(horizontal = 16.dp)) }
            } else {
                items(entries) { BasicText(describe(it), style = colors.mono, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
            }
        }

        item { Section(stringResource(R.string.settings_about)) }
        item { Setting(stringResource(R.string.settings_version), null) { BasicText(version, style = colors.body.copy(color = colors.muted)) } }
    }
}

/** Whether music access and the optional photo access (for cover.jpg files) are granted. */
internal data class Access(val music: Boolean, val covers: Boolean)

@Composable
internal fun libraryText(state: LibraryState): String = when (state) {
    LibraryState.Loading -> stringResource(R.string.ui_library_loading)
    LibraryState.PermissionMissing -> stringResource(R.string.ui_permission_missing)
    is LibraryState.Ready -> pluralStringResource(R.plurals.browse_song_count, state.stats.tracks, state.stats.tracks)
    is LibraryState.Failed -> stringResource(R.string.ui_library_failed, state.message)
}

@Composable
private fun Section(title: String) {
    val colors = LocalColors.current
    BasicText(
        title,
        style = colors.caption.copy(color = colors.accent, fontWeight = FontWeight.Medium),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

/** One setting: a label, an optional second line, and its value or action at the end. */
@Composable
private fun Setting(label: String, detail: String?, end: @Composable () -> Unit) {
    val colors = LocalColors.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            BasicText(label, style = colors.body)
            if (detail != null) BasicText(detail, style = colors.caption)
        }
        end()
    }
}

/** "Allowed", or a button that asks for access. */
@Composable
private fun Allowed(granted: Boolean, onRequest: () -> Unit) {
    val colors = LocalColors.current
    if (granted) BasicText(stringResource(R.string.settings_allowed), style = colors.body.copy(color = colors.muted))
    else PillButton(stringResource(R.string.settings_allow), onRequest)
}

private val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
private val timestamp = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

private fun describe(e: VoiceLogEntry): String = buildString {
    append(timestamp.format(Instant.ofEpochMilli(e.timeMillis))).append("  ").append(e.caller).append('\n')
    append("said:   \"").append(e.query).append("\"\n")
    if (e.hints.isNotEmpty()) append("hints:  ").append(e.hints).append('\n')
    append("read:   ").append(e.parsed).append('\n')
    append("played: ").append(e.outcome).append("  (").append(e.latencyMs).append(" ms)")
}
