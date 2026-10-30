package dev.cyh.aamp.voice

/**
 * A voice request as Android Auto delivers it: the recognized text plus the optional structured
 * hints from the `android.intent.extra.*` media extras. Blank hints are treated as absent.
 */
data class VoiceQuery(
    val query: String,
    val focus: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val title: String? = null,
    val genre: String? = null,
)

enum class RequestKind { ALBUM, ARTIST, SONG, ANY }

/** What a voice request asks for, before it is matched against the library. */
sealed interface PlayRequest {
    /** Keep playing the current queue, or shuffle the library when nothing is queued. */
    data object Resume : PlayRequest

    /** Shuffle the whole library. */
    data object ShuffleAll : PlayRequest

    /**
     * Find and play [phrase]. Without a separate [artist] hint the phrase may still end in
     * "by <artist>", which the resolver splits only when the artist exists in the library.
     */
    data class Search(
        val kind: RequestKind,
        val phrase: String,
        val artist: String?,
        val shuffle: Boolean,
    ) : PlayRequest
}
