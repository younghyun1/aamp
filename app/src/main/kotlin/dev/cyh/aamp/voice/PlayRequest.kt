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

enum class RequestKind { ALBUM, ARTIST, SONG, GENRE, PLAYLIST, ANY }

/** What a voice request asks for, before it is matched against the library. */
sealed interface PlayRequest {
    /** Keep playing the current queue, or shuffle the library when nothing is queued. */
    data object Resume : PlayRequest

    /** Shuffle the whole library. */
    data object ShuffleAll : PlayRequest

    /**
     * Find and play [phrase]. Without a separate [artist] hint the phrase may still name an artist
     * or album ("X by Y", "Y's X", "X from Y"), which the resolver splits off only when the library
     * has a matching artist or album. [album] is a structured hint naming the album a song is on.
     *
     * [whole] is the request as said, kind word included, when the parser took one out: "play band
     * on the run" is first an artist request for "on the run", and when no artist matches, a
     * request for "band on the run" of any kind.
     */
    data class Search(
        val kind: RequestKind,
        val phrase: String,
        val artist: String?,
        val shuffle: Boolean,
        val album: String? = null,
        val whole: String? = null,
    ) : PlayRequest
}
