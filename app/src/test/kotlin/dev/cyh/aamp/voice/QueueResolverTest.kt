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
    fun `unmarked title track request starts at the song and keeps the album`() {
        assertAlbumFrom(resolve(RequestKind.ANY, "and justice for all by metallica"), "...And Justice for All", "...And Justice for All")
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

    @Test
    fun `parsed spoken requests resolve end to end`() {
        fun play(text: String) = resolver.resolve(VoiceRequestParser.parse(VoiceQuery(text)))
        assertAlbumFrom(play("play album And Justice For All by Metallica"), "...And Justice for All", "Blackened")
        assertAlbumFrom(play("play the album kill em all"), "Kill 'Em All", "Hit the Lights")
        assertAlbumFrom(play("play Fade to Black by Metallica"), "Ride the Lightning", "Fade to Black")
    }
}
