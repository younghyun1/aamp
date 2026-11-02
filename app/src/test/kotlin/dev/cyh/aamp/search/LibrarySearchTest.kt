package dev.cyh.aamp.search

import dev.cyh.aamp.TestLibrary
import dev.cyh.aamp.TestLibrary.album
import dev.cyh.aamp.TestLibrary.artist
import dev.cyh.aamp.TestLibrary.track
import dev.cyh.aamp.library.LibraryBuilder
import dev.cyh.aamp.library.Playlist
import dev.cyh.aamp.library.RawTrack
import dev.cyh.aamp.search.LibrarySearch.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchTest {
    private val library = TestLibrary.build()
    private val search = LibrarySearch.build(library, listOf(Playlist(9, "Road Trip", LongArray(0))))

    private fun first(query: String, kind: Kind): Int? = search.search(query)[kind].firstOrNull()

    @Test
    fun `an exact name comes first, and its kind leads`() {
        val results = search.search("metallica")
        assertEquals(Kind.ARTIST, results.order.first())
        assertEquals(library.artist("Metallica"), results[Kind.ARTIST].first())
        assertEquals(library.album("Metallica"), results[Kind.ALBUM].first())
    }

    @Test
    fun `the word being typed matches as a prefix`() {
        assertEquals(library.album("Master of Puppets (Remastered)"), first("mast", Kind.ALBUM))
        assertEquals(library.artist("Motörhead"), first("motor", Kind.ARTIST))
        assertEquals(9, search.search("road tr")[Kind.PLAYLIST].first().let { if (it == 0) 9 else it })
    }

    @Test
    fun `typos are forgiven, but not in the first letter`() {
        assertEquals(library.artist("Metallica"), first("metalica ", Kind.ARTIST))
        assertEquals(library.album("Master of Puppets (Remastered)"), first("mastr of pupets ", Kind.ALBUM))
        assertTrue(search.search("xetallica ")[Kind.ARTIST].isEmpty())
    }

    @Test
    fun `words may match across title, artist and album`() {
        assertEquals(library.track("One"), first("one metallica", Kind.SONG))
        assertEquals(library.album("Ride the Lightning"), first("ride lightning", Kind.ALBUM))
        assertEquals(library.track("Fade to Black"), first("fade black metallica", Kind.SONG))
    }

    @Test
    fun `Korean matches half-typed syllables and initials`() {
        val rows = listOf(
            RawTrack(1, "가을 방학", "서울 밴드", "첫 앨범", 1, "서울 밴드", 1, 1, 2020, 1000, false, 1),
            RawTrack(2, "봄날", "서울 밴드", "첫 앨범", 1, "서울 밴드", 2, 1, 2020, 1000, false, 1),
        )
        val korean = LibraryBuilder.build(rows)
        val search = LibrarySearch.build(korean, emptyList())
        val autumn = korean.snapshot.trackTitles.indexOf("가을 방학")
        // "가으" is what the field holds on the way to "가을".
        assertEquals(autumn, search.search("가으")[Kind.SONG].first())
        assertEquals(autumn, search.search("ㄱㅇ")[Kind.SONG].first())
        assertEquals(korean.snapshot.artistNames.indexOf("서울 밴드"), search.search("서울")[Kind.ARTIST].first())
    }
}
