package dev.cyh.aamp.voice

/**
 * Words that join the parts of a spoken request, shared by [VoiceRequestParser] and
 * [QueueResolver]. All entries are in [dev.cyh.aamp.search.TextNormalizer.words] form.
 */
internal object Phrases {
    /**
     * Words that can introduce an artist: "X by Y", plus how speech recognition mishears "by".
     * "From" can introduce an artist or an album; the resolver tries both.
     */
    val artistSeparators = setOf("by", "my", "bye", "buy", "from")

    /** Words that can introduce the album a song is on: "X from Y", "X off the album Y". */
    val albumSeparators = setOf("from", "off", "on")

    /**
     * Words that say a name is an artist's, before or after it: "the band Metallica", "Adele the
     * singer", "by the group ABBA". Longer forms first, so "the band" is removed whole.
     */
    val artistKinds = listOf(
        listOf("the", "artist"), listOf("the", "singer"), listOf("the", "band"), listOf("the", "group"),
        listOf("artist"), listOf("singer"), listOf("band"), listOf("group"),
    )

    /** Words between an album separator and the title: "from the album ...". */
    val albumFillers = listOf(
        listOf("the", "album"), listOf("album"), listOf("the", "record"), listOf("record"),
        listOf("the", "ep"), listOf("ep"),
    )

    /** Words that name the kind of request when they stand inside it: "Metallica's album Kill 'Em All". */
    val albumWords = setOf("album", "record")
    val songWords = setOf("song", "track")

    /**
     * [words] without the first matching leading filler, unless removing it leaves nothing:
     * "by the band" names the artist The Band.
     */
    fun withoutFiller(words: List<String>, fillers: List<List<String>>): List<String> {
        for (filler in fillers) {
            if (words.size > filler.size && words.subList(0, filler.size) == filler) return words.subList(filler.size, words.size)
        }
        return words
    }

    /** True when the word at [index] directly follows an album separator, optionally with "the" between. */
    fun followsAlbumSeparator(words: List<String>, index: Int): Boolean {
        if (index >= 1 && words[index - 1] in albumSeparators) return true
        return index >= 2 && words[index - 1] == "the" && words[index - 2] in albumSeparators
    }
}
