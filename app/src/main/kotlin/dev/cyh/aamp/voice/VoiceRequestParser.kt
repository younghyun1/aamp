package dev.cyh.aamp.voice

import dev.cyh.aamp.search.TextNormalizer

/**
 * Turns a [VoiceQuery] into a [PlayRequest].
 *
 * Structured hints win when they name something: a focus of album with an album name is an album
 * request, whatever the text says. Otherwise the text is read for "album", "song", "songs by" and
 * similar markers; anything unmarked is [RequestKind.ANY] and the resolver decides.
 */
object VoiceRequestParser {
    // Values of android.intent.extra.focus (MediaStore entry content types), copied here so the
    // parser stays free of Android types.
    const val FOCUS_ALBUM = "vnd.android.cursor.item/album"
    const val FOCUS_ARTIST = "vnd.android.cursor.item/artist"
    const val FOCUS_SONG = "vnd.android.cursor.item/audio"
    const val FOCUS_GENRE = "vnd.android.cursor.item/genre"
    const val FOCUS_PLAYLIST = "vnd.android.cursor.item/playlist"
    const val FOCUS_ANY = "vnd.android.cursor.item/*"

    private val leadingVerbs = listOf(
        listOf("play"), listOf("listen", "to"), listOf("put", "on"), listOf("start", "playing"), listOf("start"),
    )
    private val appNames = listOf(listOf("aamp"), listOf("a", "amp"), listOf("aa", "mp"), listOf("amp"))
    private val appPrepositions = setOf("on", "in", "with", "using", "from")
    private val everything = setOf(
        listOf("music"), listOf("some", "music"), listOf("my", "music"), listOf("songs"), listOf("some", "songs"),
        listOf("something"), listOf("anything"), listOf("everything"), listOf("all"),
    )
    private val albumPrefixes = listOf(listOf("the", "album"), listOf("album"), listOf("the", "record"), listOf("record"))
    private val songPrefixes = listOf(listOf("the", "song"), listOf("song"), listOf("the", "track"), listOf("track"))
    private val artistPrefixes = listOf(
        listOf("songs", "by"), listOf("music", "by"), listOf("tracks", "by"), listOf("everything", "by"),
        listOf("anything", "by"), listOf("something", "by"), listOf("the", "artist"), listOf("artist"),
        listOf("the", "band"), listOf("band"),
    )

    fun parse(query: VoiceQuery): PlayRequest {
        val words = TextNormalizer.words(query.query).split(' ').filter { it.isNotEmpty() }.toMutableList()
        stripPrefix(words, leadingVerbs)
        val shuffle = words.firstOrNull() == "shuffle"
        if (shuffle) words.removeAt(0)
        stripAppName(words)

        fromHints(query, shuffle)?.let { return it }

        if (words.isEmpty() || words in everything) return if (shuffle) PlayRequest.ShuffleAll else PlayRequest.Resume
        if (words.first() == "some" && words.size > 1) words.removeAt(0)

        return when {
            stripPrefix(words, albumPrefixes) -> search(RequestKind.ALBUM, words, shuffle)
            words.size > 1 && words.last() == "album" -> search(RequestKind.ALBUM, words.dropLast(1), shuffle)
            stripPrefix(words, songPrefixes) -> search(RequestKind.SONG, words, shuffle)
            stripPrefix(words, artistPrefixes) -> search(RequestKind.ARTIST, words, shuffle = true)
            else -> search(RequestKind.ANY, words, shuffle)
        }
    }

    private fun fromHints(query: VoiceQuery, shuffle: Boolean): PlayRequest? {
        val album = query.album?.takeIf { it.isNotBlank() }
        val artist = query.artist?.takeIf { it.isNotBlank() }
        val title = query.title?.takeIf { it.isNotBlank() }
        return when (query.focus) {
            FOCUS_ALBUM if album != null -> PlayRequest.Search(RequestKind.ALBUM, album, artist, shuffle)
            FOCUS_SONG if title != null -> PlayRequest.Search(RequestKind.SONG, title, artist, shuffle)
            FOCUS_ARTIST if artist != null -> PlayRequest.Search(RequestKind.ARTIST, artist, null, shuffle = true)
            // Hints without a usable focus still help when the text says nothing.
            else if query.query.isBlank() -> when {
                album != null -> PlayRequest.Search(RequestKind.ALBUM, album, artist, shuffle)
                title != null -> PlayRequest.Search(RequestKind.SONG, title, artist, shuffle)
                artist != null -> PlayRequest.Search(RequestKind.ARTIST, artist, null, shuffle = true)
                else -> null
            }
            else -> null
        }
    }

    private fun search(kind: RequestKind, words: List<String>, shuffle: Boolean): PlayRequest =
        if (words.isEmpty()) {
            if (shuffle) PlayRequest.ShuffleAll else PlayRequest.Resume
        } else {
            PlayRequest.Search(kind, words.joinToString(" "), artist = null, shuffle = shuffle)
        }

    /** Removes the first matching prefix from [words]; returns whether one matched. */
    private fun stripPrefix(words: MutableList<String>, prefixes: List<List<String>>): Boolean {
        for (prefix in prefixes) {
            if (words.size >= prefix.size && words.subList(0, prefix.size) == prefix) {
                repeat(prefix.size) { words.removeAt(0) }
                return true
            }
        }
        return false
    }

    /** Drops a trailing "on aamp" (or how speech recognition spells it). */
    private fun stripAppName(words: MutableList<String>) {
        for (name in appNames) {
            val end = words.size - name.size
            if (end >= 1 && words.subList(end, words.size) == name && words[end - 1] in appPrepositions) {
                repeat(name.size + 1) { words.removeAt(words.size - 1) }
                return
            }
        }
    }
}
