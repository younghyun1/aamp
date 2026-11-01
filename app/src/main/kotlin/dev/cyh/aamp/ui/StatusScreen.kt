package dev.cyh.aamp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cyh.aamp.R
import dev.cyh.aamp.aamp
import dev.cyh.aamp.library.LibraryState
import dev.cyh.aamp.voice.VoiceLogEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Permissions, library counts, a rescan button and every voice request received. */
@Composable
internal fun StatusScreen(
    libraryState: LibraryState,
    access: Access,
    onRequestAccess: () -> Unit,
    onRescan: () -> Unit,
) {
    val colors = LocalColors.current
    val entries by LocalContext.current.aamp.voiceLog.recent.collectAsStateWithLifecycle()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            val music = stringResource(if (access.music) R.string.ui_permission_granted else R.string.ui_permission_missing)
            val covers = stringResource(if (access.covers) R.string.ui_covers_granted else R.string.ui_covers_missing)
            BasicText("$music\n$covers", style = colors.body)
        }
        if (!access.music || !access.covers) item { PillButton(stringResource(R.string.ui_grant_access), onRequestAccess) }
        item { BasicText(libraryText(libraryState), style = colors.body) }
        item { PillButton(stringResource(R.string.ui_rescan), onRescan) }
        item { BasicText(stringResource(R.string.ui_voice_title), style = colors.title, modifier = Modifier.padding(top = 8.dp)) }
        if (entries.isEmpty()) {
            item { BasicText(stringResource(R.string.ui_voice_empty), style = colors.caption) }
        } else {
            items(entries) { BasicText(describe(it), style = colors.mono) }
        }
    }
}

/** Whether music access and the optional photo access (for cover.jpg files) are granted. */
internal data class Access(val music: Boolean, val covers: Boolean)

@Composable
internal fun libraryText(state: LibraryState): String = when (state) {
    LibraryState.Loading -> stringResource(R.string.ui_library_loading)
    LibraryState.PermissionMissing -> stringResource(R.string.ui_permission_missing)
    is LibraryState.Ready -> with(state.stats) { stringResource(R.string.ui_library_stats, tracks, albums, artists, durationMs) }
    is LibraryState.Failed -> stringResource(R.string.ui_library_failed, state.message)
}

private val timeFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

private fun describe(e: VoiceLogEntry): String = buildString {
    append(timeFormat.format(Instant.ofEpochMilli(e.timeMillis))).append("  ").append(e.caller).append('\n')
    append("said:   \"").append(e.query).append("\"\n")
    if (e.hints.isNotEmpty()) append("hints:  ").append(e.hints).append('\n')
    append("read:   ").append(e.parsed).append('\n')
    append("played: ").append(e.outcome).append("  (").append(e.latencyMs).append(" ms)")
}
