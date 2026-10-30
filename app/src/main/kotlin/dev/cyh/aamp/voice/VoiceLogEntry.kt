package dev.cyh.aamp.voice

/**
 * One received voice request: who sent it, what it said, how it was read and what played.
 * Stored one per line, fields separated by tabs; tabs and line breaks inside fields become spaces.
 */
data class VoiceLogEntry(
    val timeMillis: Long,
    val caller: String,
    val query: String,
    val hints: String,
    val parsed: String,
    val outcome: String,
    val latencyMs: Long,
) {
    fun toLine(): String = listOf(
        timeMillis.toString(), caller, query, hints, parsed, outcome, latencyMs.toString(),
    ).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ') }

    companion object {
        private const val FIELDS = 7

        /** Parses a line written by [toLine]; null for anything malformed. */
        fun parse(line: String): VoiceLogEntry? {
            val f = line.split('\t')
            if (f.size != FIELDS) return null
            val time = f[0].toLongOrNull() ?: return null
            val latency = f[6].toLongOrNull() ?: return null
            return VoiceLogEntry(time, f[1], f[2], f[3], f[4], f[5], latency)
        }
    }
}
