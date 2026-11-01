package dev.cyh.aamp.library

import dev.cyh.aamp.TestLibrary
import dev.cyh.aamp.TestLibrary.album
import dev.cyh.aamp.TestLibrary.artist
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryBuilderTest {
    private val library = TestLibrary.build()
    private val snapshot = library.snapshot

    @Test
    fun `albums are ordered by normalized title`() {
        assertEquals(
            listOf(
                // "The Bends" sorts as "bends": a leading "the" is ignored. Equal titles sort by artist.
                "Ace of Spades", "...And Justice for All", "The Bends", "Don't Play That Song!",
                "Greatest Hits", "Greatest Hits", "Kill 'Em All",
                "Live in Seoul", "Master of Puppets (Remastered)", "Metallica", "Now That's What I Call Music",
                "Ride the Lightning",
            ),
            snapshot.albumTitles.toList(),
        )
        assertEquals(listOf("ABBA", "Queen"), snapshot.albumTitles.indices.filter { snapshot.albumTitles[it] == "Greatest Hits" }
            .map { snapshot.artistNames[snapshot.albumArtist[it]] })
    }

    @Test
    fun `album tracks are contiguous and in track order`() {
        val justice = library.album("...And Justice for All")
        val titles = snapshot.albumTracks(justice).map { snapshot.trackTitles[it] }
        assertEquals(listOf("Blackened", "...And Justice for All", "Eye of the Beholder", "One"), titles)
    }

    @Test
    fun `album artist falls back to agreeing track artists, then various artists`() {
        val ride = library.album("Ride the Lightning")
        assertEquals("Metallica", snapshot.artistNames[snapshot.albumArtist[ride]])
        val now = library.album("Now That's What I Call Music")
        assertEquals(LibraryBuilder.VARIOUS_ARTISTS, snapshot.artistNames[snapshot.albumArtist[now]])
    }

    @Test
    fun `artist albums are oldest first`() {
        val metallica = library.artist("Metallica")
        val titles = snapshot.artistAlbums(metallica).map { snapshot.albumTitles[it] }
        assertEquals(
            listOf(
                "Kill 'Em All", "Ride the Lightning", "Master of Puppets (Remastered)", "...And Justice for All",
                "Metallica", "Live in Seoul",
            ),
            titles,
        )
        assertEquals(20, snapshot.artistTracks(metallica).size)
    }

    @Test
    fun `compilation tracks count for both track artist and various artists`() {
        assertEquals(1, snapshot.artistTracks(library.artist("Artist A")).size)
        assertEquals(2, snapshot.artistTracks(library.artist(LibraryBuilder.VARIOUS_ARTISTS)).size)
    }

    @Test
    fun `ids map back to indices`() {
        for (t in 0 until snapshot.trackCount) assertEquals(t, snapshot.trackIndex(snapshot.trackIds[t]))
        for (a in 0 until snapshot.albumCount) assertEquals(a, snapshot.albumIndex(snapshot.albumIds[a]))
        for (r in 0 until snapshot.artistCount) assertEquals(r, snapshot.artistIndex(snapshot.artistKeys[r]))
        assertEquals(-1, snapshot.trackIndex(999_999))
    }

    @Test
    fun `artist keys are stable across builds`() {
        assertArrayEquals(snapshot.artistKeys, TestLibrary.build().snapshot.artistKeys)
    }

    @Test
    fun `empty library builds`() {
        val empty = LibraryBuilder.build(emptyList()).snapshot
        assertEquals(0, empty.trackCount)
        assertEquals(0, empty.albumCount)
    }

    @Test
    fun `genres merge by normalized name and list their albums in title order`() {
        assertEquals(listOf("Alternative", "Heavy Metal", "Pop", "Rock", "Soul", "Thrash Metal"), snapshot.genreNames.toList())
        val thrash = snapshot.genreNames.indexOf("Thrash Metal")
        assertEquals(
            listOf("...And Justice for All", "Kill 'Em All", "Master of Puppets (Remastered)", "Ride the Lightning"),
            snapshot.genreAlbums(thrash).map { snapshot.albumTitles[it] },
        )
        assertEquals(15, snapshot.genreTrackCount(thrash))
        assertEquals(thrash, snapshot.genreIndex(snapshot.genreKeys[thrash]))
        val heavy = snapshot.genreNames.indexOf("Heavy Metal")
        assertEquals(listOf("Ace of Spades", "Metallica"), snapshot.genreAlbums(heavy).map { snapshot.albumTitles[it] })
        // Untagged tracks belong to no genre.
        val live = library.album("Live in Seoul")
        assertEquals(-1, snapshot.trackGenre[snapshot.albumFirstTrack[live]])
    }

    @Test
    fun `an artist's cover is their newest album or the album they appear on`() {
        assertEquals(library.album("Live in Seoul"), snapshot.artistCoverAlbum(library.artist("Metallica")))
        assertEquals(library.album("Now That's What I Call Music"), snapshot.artistCoverAlbum(library.artist("Artist A")))
    }
}
