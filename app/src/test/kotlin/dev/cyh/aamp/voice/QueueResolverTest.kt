package dev.cyh.aamp.voice

import dev.cyh.aamp.TestLibrary
import dev.cyh.aamp.TestLibrary.album
import dev.cyh.aamp.TestLibrary.artist
import dev.cyh.aamp.TestLibrary.track
import dev.cyh.aamp.library.LibraryBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QueueResolverTest {
    private val library = TestLibrary.build()
    private val snapshot = library.snapshot
    private val resolver = QueueResolver(library, Random(42))

    private fun resolve(kind: RequestKind, phrase: String, artist: String? = null, shuffle: Boolean = false): Resolution =
        resolver.resolve(PlayRequest.Search(kind, phrase, artist, shuffle))

    private fun queue(resolution: Resolution): Resolution.Queue {
        assertTrue("expected a queue, got $resolution", resolution is Resolution.Queue)
        return resolution as Resolution.Queue
    }

    private fun assertAlbumFrom(resolution: Resolution, albumTitle: String, startTitle: String) {
        val q = queue(resolution)
        val album = library.album(albumTitle)
        assertEquals(QueueSource.Album(album), q.source)
        assertArrayEquals(snapshot.albumTracks(album), q.tracks)
        assertEquals(startTitle, snapshot.trackTitles[q.tracks[q.startIndex]])
    }

    @Test
    fun `album request plays the album from the first track`() {
        assertAlbumFrom(resolve(RequestKind.ALBUM, "and justice for all by metallica"), "...And Justice for All", "Blackened")
    }

    @Test
    fun `album request with hints plays the album`() {
        assertAlbumFrom(
            resolve(RequestKind.ALBUM, "...And Justice for All", artist = "Metallica"),
            "...And Justice for All",
            "Blackened",
        )
    }

    @Test
    fun `an album with a same-named title track plays as the album`() {
        // Gemini drops "album" from "play album X", so these arrive unmarked.
        assertAlbumFrom(resolve(RequestKind.ANY, "and justice for all by metallica"), "...And Justice for All", "Blackened")
        assertAlbumFrom(resolve(RequestKind.ANY, "and justice for all"), "...And Justice for All", "Blackened")
        assertAlbumFrom(resolve(RequestKind.ANY, "master of puppets"), "Master of Puppets (Remastered)", "Battery")
        assertAlbumFrom(resolve(RequestKind.ANY, "the bends"), "The Bends", "Planet Telex")
    }

    @Test
    fun `gemini's garbled request still finds the album`() {
        // Recorded from Gemini in Android Auto for "play album And Justice For All by Metallica".
        assertAlbumFrom(resolve(RequestKind.ANY, "and just for all my intelligents"), "...And Justice for All", "Blackened")
    }

    @Test
    fun `a short exact title still plays the song`() {
        assertAlbumFrom(resolve(RequestKind.ANY, "just by radiohead"), "The Bends", "Just")
        assertAlbumFrom(resolve(RequestKind.ANY, "one"), "...And Justice for All", "One")
    }

    @Test
    fun `my is read as by when it introduces an artist`() {
        assertAlbumFrom(resolve(RequestKind.ANY, "kill em all my metallica"), "Kill 'Em All", "Hit the Lights")
    }

    @Test
    fun `edition suffixes and punctuation do not block an album match`() {
        assertAlbumFrom(resolve(RequestKind.ALBUM, "master of puppets"), "Master of Puppets (Remastered)", "Battery")
        assertAlbumFrom(resolve(RequestKind.ALBUM, "kill em all"), "Kill 'Em All", "Hit the Lights")
    }

    @Test
    fun `song request plays the song then the rest of its album`() {
        assertAlbumFrom(resolve(RequestKind.SONG, "one by metallica"), "...And Justice for All", "One")
        assertAlbumFrom(resolve(RequestKind.ANY, "seek and destroy"), "Kill 'Em All", "Seek & Destroy")
    }

    @Test
    fun `by inside a title is not an artist`() {
        assertAlbumFrom(resolve(RequestKind.ANY, "stand by me"), "Don't Play That Song!", "Stand by Me")
    }

    @Test
    fun `misrecognized artist still matches`() {
        assertAlbumFrom(resolve(RequestKind.ALBUM, "ride the lightning by metalica"), "Ride the Lightning", "Fight Fire with Fire")
    }

    @Test
    fun `an exact artist name plays the artist shuffled`() {
        val q = queue(resolve(RequestKind.ANY, "metallica"))
        val metallica = library.artist("Metallica")
        assertEquals(QueueSource.Artist(metallica), q.source)
        assertEquals(snapshot.artistTracks(metallica).toSet(), q.tracks.toSet())
        assertTrue(!q.tracks.contentEquals(snapshot.artistTracks(metallica)))
    }

    @Test
    fun `artist request matches without accents`() {
        val q = queue(resolve(RequestKind.ARTIST, "motorhead", shuffle = true))
        assertEquals(QueueSource.Artist(library.artist("Motörhead")), q.source)
        assertEquals(2, q.tracks.size)
    }

    @Test
    fun `shuffled song request keeps the song first`() {
        val q = queue(resolve(RequestKind.SONG, "enter sandman", shuffle = true))
        assertEquals(library.track("Enter Sandman"), q.tracks[q.startIndex])
        assertEquals(0, q.startIndex)
        assertEquals(snapshot.albumTracks(library.album("Metallica")).toSet(), q.tracks.toSet())
    }

    @Test
    fun `a genre plays its songs shuffled or in album order`() {
        val thrash = snapshot.genreNames.indexOf("Thrash Metal")
        val shuffled = resolver.genre(thrash, shuffle = true)
        assertEquals(QueueSource.Genre(thrash), shuffled.source)
        assertEquals(snapshot.genreTracks(thrash).toSet(), shuffled.tracks.toSet())
        val one = library.track("One")
        val ordered = resolver.genre(thrash, shuffle = false, startTrack = one)
        assertArrayEquals(snapshot.genreTracks(thrash), ordered.tracks)
        assertEquals(one, ordered.tracks[ordered.startIndex])
    }

    @Test
    fun `nothing similar is no match`() {
        assertEquals(Resolution.NoMatch("zzzz qqqq"), resolve(RequestKind.ANY, "zzzz qqqq"))
    }

    @Test
    fun `resume and shuffle play the whole library`() {
        for (request in listOf(PlayRequest.Resume, PlayRequest.ShuffleAll)) {
            val q = queue(resolver.resolve(request))
            assertEquals(QueueSource.Library, q.source)
            assertEquals((0 until snapshot.trackCount).toSet(), q.tracks.toSet())
        }
    }

    @Test
    fun `empty library resolves to nothing`() {
        val empty = QueueResolver(LibraryBuilder.build(emptyList()), Random(1))
        assertEquals(Resolution.EmptyLibrary, empty.resolve(PlayRequest.ShuffleAll))
    }

    private fun assertAlbum(resolution: Resolution, album: Int) {
        val q = queue(resolution)
        assertEquals(QueueSource.Album(album), q.source)
        assertEquals(0, q.startIndex)
    }

    @Test
    fun `artist phrasings other than by`() {
        val killEmAll = "Kill 'Em All"
        for (phrase in listOf(
            "kill em all from metallica",
            "kill em all bye metallica",
            "kill em all buy metallica",
            "kill em all by the band metallica",
            "kill em all by the artist metallica",
        )) {
            assertAlbumFrom(resolve(RequestKind.ANY, phrase), killEmAll, "Hit the Lights")
        }
    }

    @Test
    fun `an artist without a separator is split off when it matches closely`() {
        assertAlbumFrom(resolve(RequestKind.ANY, "ride the lightning metallica"), "Ride the Lightning", "Fight Fire with Fire")
        assertAlbumFrom(resolve(RequestKind.ANY, "metallica ride the lightning"), "Ride the Lightning", "Fight Fire with Fire")
        assertAlbumFrom(resolve(RequestKind.ANY, "enter sandman metallica"), "Metallica", "Enter Sandman")
        assertAlbumFrom(resolve(RequestKind.ANY, "metallica one"), "...And Justice for All", "One")
    }

    @Test
    fun `a possessive artist comes first`() {
        assertAlbumFrom(resolve(RequestKind.ANY, "metallicas master of puppets"), "Master of Puppets (Remastered)", "Battery")
        assertAlbum(resolve(RequestKind.ANY, "queens greatest hits"), library.album("Greatest Hits", "Queen"))
    }

    @Test
    fun `the artist decides between albums with the same title`() {
        val queen = library.album("Greatest Hits", "Queen")
        val abba = library.album("Greatest Hits", "ABBA")
        assertAlbum(resolve(RequestKind.ANY, "greatest hits by abba"), abba)
        assertAlbum(resolve(RequestKind.ANY, "greatest hits by queen"), queen)
        assertAlbum(resolve(RequestKind.ANY, "greatest hits abba"), abba)
        assertAlbum(resolve(RequestKind.ANY, "abba greatest hits"), abba)
        assertAlbum(resolve(RequestKind.ALBUM, "greatest hits", artist = "Queen"), queen)
    }

    @Test
    fun `an artist name ending a title is not split off`() {
        assertAlbumFrom(resolve(RequestKind.ANY, "dancing queen"), "Greatest Hits", "Dancing Queen")
        assertEquals("ABBA", snapshot.artistNames[snapshot.trackArtist[queue(resolve(RequestKind.ANY, "dancing queen")).let { it.tracks[it.startIndex] }]])
        // A three-word artist name is the artist, not "ben e" by "king".
        assertEquals(QueueSource.Artist(library.artist("Ben E. King")), queue(resolve(RequestKind.ANY, "ben e king")).source)
    }

    @Test
    fun `by inside a title and after it`() {
        assertAlbumFrom(resolve(RequestKind.ANY, "stand by me by ben e king"), "Don't Play That Song!", "Stand by Me")
    }

    @Test
    fun `a song from a named album`() {
        assertAlbumFrom(resolve(RequestKind.ANY, "one from the album and justice for all"), "...And Justice for All", "One")
        assertAlbumFrom(resolve(RequestKind.ANY, "one off and justice for all"), "...And Justice for All", "One")
        assertAlbumFrom(resolve(RequestKind.ANY, "one by metallica from the album and justice for all"), "...And Justice for All", "One")
        assertAlbumFrom(resolve(RequestKind.ANY, "one from and justice for all by metallica"), "...And Justice for All", "One")
        // The live album's title track, not the studio one.
        assertAlbumFrom(resolve(RequestKind.ANY, "and justice for all from live in seoul"), "Live in Seoul", "And Justice For All")
    }

    @Test
    fun `a song hint with its album plays the song on that album`() {
        val request = PlayRequest.Search(RequestKind.SONG, "And Justice For All", "Metallica", shuffle = false, album = "Live in Seoul")
        assertAlbumFrom(resolver.resolve(request), "Live in Seoul", "And Justice For All")
    }

    @Test
    fun `parsed spoken requests resolve end to end`() {
        fun play(text: String) = resolver.resolve(VoiceRequestParser.parse(VoiceQuery(text)))
        assertAlbumFrom(play("play album And Justice For All by Metallica"), "...And Justice for All", "Blackened")
        assertAlbumFrom(play("play the album kill em all"), "Kill 'Em All", "Hit the Lights")
        assertAlbumFrom(play("play Fade to Black by Metallica"), "Ride the Lightning", "Fade to Black")
        assertAlbumFrom(play("play Metallica's album Kill 'Em All"), "Kill 'Em All", "Hit the Lights")
        assertAlbumFrom(play("play the Master of Puppets album by Metallica"), "Master of Puppets (Remastered)", "Battery")
        assertAlbumFrom(play("play the song One from the album ...And Justice for All"), "...And Justice for All", "One")
        assertAlbumFrom(play("play songs from the album Ride the Lightning"), "Ride the Lightning", "Fight Fire with Fire")
        assertAlbumFrom(play("play the Enter Sandman song by Metallica"), "Metallica", "Enter Sandman")
    }
}
