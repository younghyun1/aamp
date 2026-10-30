package dev.cyh.aamp.voice

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Record of every voice request the app received. Each entry goes to logcat (tag `AampVoice`),
 * to an in-memory list of the newest [MAX_RECENT] entries, and to a file capped at [MAX_BYTES],
 * so the phone screen can show what Gemini sent even after the process restarts.
 */
class VoiceLog(private val file: File, private val scope: CoroutineScope) {
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val _recent = MutableStateFlow<List<VoiceLogEntry>>(emptyList())

    /** Newest first. */
    val recent: StateFlow<List<VoiceLogEntry>> = _recent.asStateFlow()

    init {
        scope.launch(io) {
            val stored = readStored()
            // Entries recorded while the file was loading are newer than anything stored.
            _recent.update { (it + stored).take(MAX_RECENT) }
        }
    }

    fun record(entry: VoiceLogEntry) {
        val line = entry.toLine()
        Log.i(TAG, line)
        _recent.update { (listOf(entry) + it).take(MAX_RECENT) }
        scope.launch(io) { append(line) }
    }

    private fun readStored(): List<VoiceLogEntry> = try {
        if (!file.exists()) emptyList()
        else file.readLines().asReversed().asSequence().mapNotNull(VoiceLogEntry::parse).take(MAX_RECENT).toList()
    } catch (e: IOException) {
        Log.w(TAG, "Could not read the voice log", e)
        emptyList()
    }

    private fun append(line: String) {
        try {
            file.appendText(line + "\n")
            if (file.length() > MAX_BYTES) trim()
        } catch (e: IOException) {
            Log.w(TAG, "Could not write the voice log", e)
        }
    }

    /** Keeps the newest half of the file, written to a temporary file and renamed into place. */
    private fun trim() {
        val lines = file.readLines()
        val kept = ArrayList<String>()
        var bytes = 0L
        for (line in lines.asReversed()) {
            bytes += line.length + 1
            if (bytes > MAX_BYTES / 2) break
            kept += line
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(kept.asReversed().joinToString("\n", postfix = "\n"))
        if (!tmp.renameTo(file)) throw IOException("rename to ${file.name} failed")
    }

    companion object {
        const val TAG = "AampVoice"
        const val MAX_RECENT = 50
        const val MAX_BYTES = 256L * 1024
    }
}
