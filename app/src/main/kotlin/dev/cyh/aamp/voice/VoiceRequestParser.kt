package dev.cyh.aamp.voice

import dev.cyh.aamp.search.TextNormalizer

/**
 * Turns a [VoiceQuery] into a [PlayRequest].
 *
 * Structured hints win when they name something: a focus of album with an album name is an album
 * request, whatever the text says. Otherwise the text is read for "album", "song", "songs by",
 * "genre", "playlist" and similar markers, before, after or inside the name ("Metallica's album
 * Kill 'Em All", "my road trip playlist", "the singer Adele", "Metallica the band"); anything
 * unmarked is [RequestKind.ANY] and the resolver decides. Splitting the rest into title, artist and
 * album is the resolver's job, because only the library can tell "Stand by Me" from "X by Y".
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
    private val albumPrefixes = listOf(
        listOf("the", "album"), listOf("album"), listOf("the", "record"), listOf("record"),
        listOf("songs", "from", "the", "album"), listOf("songs", "from", "album"),
        listOf("music", "from", "the", "album"), listOf("tracks", "from", "the", "album"),
    )
    private val playlistPrefixes = listOf(listOf("the", "playlist"), listOf("playlist"), listOf("my", "playlist"))
    private val genrePrefixes = listOf(listOf("the", "genre"), listOf("genre"))
    private val songPrefixes = listOf(listOf("the", "song"), listOf("song"), listOf("the", "track"), listOf("track"))
    // Followed by an artist, possibly with a kind word: "songs by the band Metallica".
    private val artistPrefixes = listOf(
        listOf("songs", "by"), listOf("music", "by"), listOf("tracks", "by"), listOf("stuff", "by"),
        listOf("everything", "by"), listOf("anything", "by"), listOf("something", "by"),
        listOf("songs", "from"), listOf("music", "from"), listOf("tracks", "from"), listOf("stuff", "from"),
        listOf("everything", "from"), listOf("anything", "from"), listOf("something", "from"),
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
        val said = words.joinToString(" ")
        fun search(kind: RequestKind, words: List<String>, shuffle: Boolean) = search(kind, words, shuffle, said)

        val request = when {
            stripPrefix(words, playlistPrefixes) -> search(RequestKind.PLAYLIST, words, shuffle)
            words.size > 1 && words.last() == "playlist" -> search(RequestKind.PLAYLIST, withoutOwner(words.dropLast(1)), shuffle)
            stripPrefix(words, genrePrefixes) -> search(RequestKind.GENRE, words, shuffle = true)
            words.size > 1 && words.last() == "genre" -> search(RequestKind.GENRE, words.dropLast(1), shuffle = true)
            stripPrefix(words, albumPrefixes) -> search(RequestKind.ALBUM, words, shuffle)
            words.size > 1 && words.last() == "album" -> search(RequestKind.ALBUM, words.dropLast(1), shuffle)
            stripPrefix(words, songPrefixes) -> search(RequestKind.SONG, words, shuffle)
            stripPrefix(words, artistPrefixes) -> search(RequestKind.ARTIST, Phrases.withoutFiller(words, Phrases.artistKinds), shuffle = true)
            stripName(words, Phrases.artistKinds) -> search(RequestKind.ARTIST, words, shuffle = true)
            stripNameSuffix(words, Phrases.artistKinds) -> search(RequestKind.ARTIST, words, shuffle = true)
            removeInfix(words, Phrases.albumWords) -> search(RequestKind.ALBUM, words, shuffle)
            removeInfix(words, Phrases.songWords) -> search(RequestKind.SONG, words, shuffle)
            else -> search(RequestKind.ANY, words, shuffle)
        }
        // A genre or playlist focus without its own field still says what the text names.
        if (request !is PlayRequest.Search || request.kind != RequestKind.ANY) return request
        return when (query.focus) {
            FOCUS_PLAYLIST -> request.copy(kind = RequestKind.PLAYLIST)
            FOCUS_GENRE -> request.copy(kind = RequestKind.GENRE, shuffle = true)
            else -> request
        }
    }

    /** "My road trip" names the playlist "road trip". */
    private fun withoutOwner(words: List<String>): List<String> =
        if (words.size > 1 && (words[0] == "my" || words[0] == "the")) words.drop(1) else words

    private fun fromHints(query: VoiceQuery, shuffle: Boolean): PlayRequest? {
        val album = query.album?.takeIf { it.isNotBlank() }
        val artist = query.artist?.takeIf { it.isNotBlank() }
        val title = query.title?.takeIf { it.isNotBlank() }
        val genre = query.genre?.takeIf { it.isNotBlank() }
        return when (query.focus) {
            FOCUS_GENRE if genre != null -> PlayRequest.Search(RequestKind.GENRE, genre, null, shuffle = true)
            FOCUS_ALBUM if album != null -> PlayRequest.Search(RequestKind.ALBUM, album, artist, shuffle)
            FOCUS_SONG if title != null -> PlayRequest.Search(RequestKind.SONG, title, artist, shuffle, album)
            FOCUS_ARTIST if artist != null -> PlayRequest.Search(RequestKind.ARTIST, artist, null, shuffle = true)
            // Hints without a usable focus still help when the text says nothing.
            else if query.query.isBlank() -> when {
                title != null -> PlayRequest.Search(RequestKind.SONG, title, artist, shuffle, album)
                album != null -> PlayRequest.Search(RequestKind.ALBUM, album, artist, shuffle)
                artist != null -> PlayRequest.Search(RequestKind.ARTIST, artist, null, shuffle = true)
                else -> null
            }
            else -> null
        }
    }

    /** A search for [words], remembering what was [said] when a kind word was taken out. */
    private fun search(kind: RequestKind, words: List<String>, shuffle: Boolean, said: String): PlayRequest {
        if (words.isEmpty()) return if (shuffle) PlayRequest.ShuffleAll else PlayRequest.Resume
        val phrase = words.joinToString(" ")
        val whole = said.takeIf { kind != RequestKind.ANY && it != phrase }
        return PlayRequest.Search(kind, phrase, artist = null, shuffle = shuffle, whole = whole)
    }

    /**
     * Removes a leading kind word ("the singer") when a name follows it; returns whether it did.
     * "The Band" alone stays a name.
     */
    private fun stripName(words: MutableList<String>, kinds: List<List<String>>): Boolean {
        for (kind in kinds) {
            if (words.size > kind.size && words.subList(0, kind.size) == kind) {
                repeat(kind.size) { words.removeAt(0) }
                return true
            }
        }
        return false
    }

    /**
     * Removes a trailing kind word ("Metallica the band", "ABBA group") when a name stands before
     * it; returns whether it did. A lone "the" is no name, so "the band" stays whole.
     */
    private fun stripNameSuffix(words: MutableList<String>, kinds: List<List<String>>): Boolean {
        for (kind in kinds) {
            val name = words.size - kind.size
            if (name < 1 || words.subList(name, words.size) != kind) continue
            if (name == 1 && words[0] == "the") continue
            repeat(kind.size) { words.removeAt(words.size - 1) }
            return true
        }
        return false
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

    /**
     * Removes a kind marker standing inside the request, as in "Metallica's album Kill 'Em All" or
     * "Enter Sandman song by Metallica"; returns whether one was removed. A marker after "from" or
     * "off (the)" names the album a song is on and stays for the resolver.
     */
    private fun removeInfix(words: MutableList<String>, markers: Set<String>): Boolean {
        for (i in 1 until words.size - 1) {
            if (words[i] in markers && !Phrases.followsAlbumSeparator(words, i)) {
                words.removeAt(i)
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
