package dev.cyh.aamp.browse

import dev.cyh.aamp.voice.VoiceLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaIdsTest {
    @Test
    fun `ids round trip`() {
        val ids = listOf(
            MediaId.Root, MediaId.Albums, MediaId.Artists, MediaId.Recent, MediaId.Songs, MediaId.ShuffleAll,
            MediaId.ShuffleArtist(-12),
            MediaId.AlbumSection("A"), MediaId.AlbumSection("#"), MediaId.ArtistSection("ㅂ"), MediaId.SongSection("К"),
            MediaId.Album(7), MediaId.Artist(-3_750_763_034_362_895_579),
            MediaId.Track(42, TrackContext.None),
            MediaId.Track(42, TrackContext.InAlbum(7)),
            MediaId.Track(42, TrackContext.InArtist(-12)),
            MediaId.Track(42, TrackContext.InSongs),
            MediaId.Genre(-99), MediaId.Track(42, TrackContext.InGenre(-99)),
        )
        for (id in ids) assertEquals(id, MediaIds.parse(MediaIds.encode(id)))
        assertEquals("track:42@album:7", MediaIds.encode(MediaId.Track(42, TrackContext.InAlbum(7))))
    }

    @Test
    fun `foreign ids are rejected`() {
        assertNull(MediaIds.parse(""))
        assertNull(MediaIds.parse("album:x"))
        assertNull(MediaIds.parse("track:1@root"))
        assertNull(MediaIds.parse("albums:"))
        assertNull(MediaIds.parse("something"))
        assertNull(MediaIds.parse("shuffle:artist:"))
    }

    @Test
    fun `voice log lines round trip and stay single line`() {
        val entry = VoiceLogEntry(1, "com.google.android.projection.gearhead", "play\tone\nby metallica", "focus=album", "Search", "album", 12)
        val line = entry.toLine()
        assertEquals(1, line.lines().size)
        assertEquals(entry.copy(query = "play one by metallica"), VoiceLogEntry.parse(line))
        assertNull(VoiceLogEntry.parse("garbage"))
    }
}
