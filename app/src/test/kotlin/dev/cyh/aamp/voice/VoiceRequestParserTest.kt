package dev.cyh.aamp.voice

import dev.cyh.aamp.voice.PlayRequest.Search
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceRequestParserTest {
    private fun parse(text: String) = VoiceRequestParser.parse(VoiceQuery(text))

    @Test
    fun `album marker before the name`() {
        assertEquals(
            Search(RequestKind.ALBUM, "and justice for all by metallica", null, false),
            parse("play album And Justice For All by Metallica"),
        )
        assertEquals(
            Search(RequestKind.ALBUM, "and justice for all by metallica", null, false),
            parse("Play the album ...And Justice for All by Metallica"),
        )
    }

    @Test
    fun `album marker after the name`() {
        assertEquals(Search(RequestKind.ALBUM, "master of puppets", null, false), parse("play master of puppets album"))
    }

    @Test
    fun `song and artist markers`() {
        assertEquals(Search(RequestKind.SONG, "one by metallica", null, false), parse("play the song One by Metallica"))
        assertEquals(Search(RequestKind.ARTIST, "metallica", null, true), parse("play songs by Metallica"))
        assertEquals(Search(RequestKind.ARTIST, "metallica", null, true), parse("shuffle songs by metallica"))
    }

    @Test
    fun `markers inside the request`() {
        assertEquals(Search(RequestKind.ALBUM, "metallicas kill em all", null, false), parse("play Metallica's album Kill 'Em All"))
        assertEquals(Search(RequestKind.ALBUM, "master of puppets by metallica", null, false), parse("play Master of Puppets album by Metallica"))
        assertEquals(Search(RequestKind.SONG, "the enter sandman by metallica", null, false), parse("play the Enter Sandman song by Metallica"))
        // "From the album" names the album a song is on; the resolver splits it.
        assertEquals(Search(RequestKind.ANY, "one from the album and justice for all", null, false), parse("play One from the album ...And Justice for All"))
        assertEquals(Search(RequestKind.SONG, "one off the album and justice for all", null, false), parse("play the song One off the album ...And Justice for All"))
    }

    @Test
    fun `artist and album phrasings with from`() {
        assertEquals(Search(RequestKind.ARTIST, "metallica", null, true), parse("play songs from Metallica"))
        assertEquals(Search(RequestKind.ARTIST, "metallica", null, true), parse("play something from Metallica"))
        assertEquals(Search(RequestKind.ALBUM, "ride the lightning", null, false), parse("play songs from the album Ride the Lightning"))
    }

    @Test
    fun `unmarked requests are left to the resolver`() {
        assertEquals(Search(RequestKind.ANY, "metallica", null, false), parse("play Metallica"))
        assertEquals(Search(RequestKind.ANY, "stand by me", null, false), parse("play Stand by Me"))
        assertEquals(Search(RequestKind.ANY, "metallica", null, false), parse("play some Metallica"))
    }

    @Test
    fun `app name suffix is dropped`() {
        assertEquals(Search(RequestKind.ANY, "kill em all by metallica", null, false), parse("play Kill 'Em All by Metallica on aamp"))
        assertEquals(Search(RequestKind.ANY, "kill em all", null, false), parse("play kill em all on a amp"))
        // "on" inside a title is not an app suffix.
        assertEquals(Search(RequestKind.ANY, "dancing on the ceiling", null, false), parse("play Dancing on the Ceiling"))
    }

    @Test
    fun `requests for nothing in particular`() {
        assertEquals(PlayRequest.Resume, parse(""))
        assertEquals(PlayRequest.Resume, parse("play some music"))
        assertEquals(PlayRequest.Resume, parse("play music on aamp"))
        assertEquals(PlayRequest.ShuffleAll, parse("shuffle"))
        assertEquals(PlayRequest.ShuffleAll, parse("shuffle my music"))
    }

    @Test
    fun `hints with a focus win over the text`() {
        val album = VoiceQuery(
            query = "and justice for all metallica",
            focus = VoiceRequestParser.FOCUS_ALBUM,
            album = "...And Justice for All",
            artist = "Metallica",
        )
        assertEquals(Search(RequestKind.ALBUM, "...And Justice for All", "Metallica", false), VoiceRequestParser.parse(album))

        val song = VoiceQuery(query = "one", focus = VoiceRequestParser.FOCUS_SONG, title = "One", artist = "Metallica")
        assertEquals(Search(RequestKind.SONG, "One", "Metallica", false), VoiceRequestParser.parse(song))

        val songOnAlbum = song.copy(album = "Live in Seoul")
        assertEquals(Search(RequestKind.SONG, "One", "Metallica", false, "Live in Seoul"), VoiceRequestParser.parse(songOnAlbum))

        val artist = VoiceQuery(query = "metallica", focus = VoiceRequestParser.FOCUS_ARTIST, artist = "Metallica")
        assertEquals(Search(RequestKind.ARTIST, "Metallica", null, true), VoiceRequestParser.parse(artist))
    }

    @Test
    fun `a focus without its field falls back to the text`() {
        val query = VoiceQuery(query = "play kill em all", focus = VoiceRequestParser.FOCUS_ALBUM)
        assertEquals(Search(RequestKind.ANY, "kill em all", null, false), VoiceRequestParser.parse(query))
    }

    @Test
    fun `hints without a focus help only when the text is empty`() {
        assertEquals(
            Search(RequestKind.ALBUM, "Kill 'Em All", null, false),
            VoiceRequestParser.parse(VoiceQuery(query = "", album = "Kill 'Em All")),
        )
        assertEquals(
            Search(RequestKind.ANY, "metallica", null, false),
            VoiceRequestParser.parse(VoiceQuery(query = "play metallica", album = "Kill 'Em All")),
        )
    }
}
