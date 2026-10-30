package dev.cyh.aamp

import dev.cyh.aamp.library.LibraryBuilder
import dev.cyh.aamp.library.LoadedLibrary
import dev.cyh.aamp.library.RawTrack

/** A small library with the shapes that make matching hard: title tracks, punctuation, accents, compilations. */
object TestLibrary {
    private class AlbumSpec(
        val id: Long,
        val title: String,
        val artist: String,
        val albumArtist: String,
        val year: Int,
        val tracks: List<Pair<String, String?>>,
        val compilation: Boolean = false,
    )

    private val albums = listOf(
        AlbumSpec(1, "Kill 'Em All", "Metallica", "Metallica", 1983,
            listOf("Hit the Lights" to null, "The Four Horsemen" to null, "Motorbreath" to null, "Seek & Destroy" to null)),
        AlbumSpec(2, "Ride the Lightning", "Metallica", "", 1984,
            listOf("Fight Fire with Fire" to null, "Ride the Lightning" to null, "For Whom the Bell Tolls" to null, "Fade to Black" to null)),
        AlbumSpec(3, "Master of Puppets (Remastered)", "Metallica", "Metallica", 1986,
            listOf("Battery" to null, "Master of Puppets" to null, "The Thing That Should Not Be" to null)),
        AlbumSpec(4, "...And Justice for All", "Metallica", "Metallica", 1988,
            listOf("Blackened" to null, "...And Justice for All" to null, "Eye of the Beholder" to null, "One" to null)),
        AlbumSpec(5, "Metallica", "Metallica", "Metallica", 1991,
            listOf("Enter Sandman" to null, "Sad but True" to null, "Nothing Else Matters" to null)),
        AlbumSpec(6, "Don't Play That Song!", "Ben E. King", "", 1962,
            listOf("Stand by Me" to null, "Don't Play That Song (You Lied)" to null)),
        AlbumSpec(7, "Ace of Spades", "Motörhead", "Motörhead", 1980,
            listOf("Ace of Spades" to null, "Love Me Like a Reptile" to null)),
        AlbumSpec(8, "Now That's What I Call Music", "", "", 2001,
            listOf("Song A" to "Artist A", "Song B" to "Artist B"), compilation = true),
    )

    fun rows(): List<RawTrack> = albums.flatMap { album ->
        album.tracks.mapIndexed { i, (title, trackArtist) ->
            RawTrack(
                id = album.id * 100 + i + 1,
                title = title,
                artist = trackArtist ?: album.artist,
                album = album.title,
                albumId = album.id,
                albumArtist = album.albumArtist,
                trackNumber = i + 1,
                discNumber = 1,
                year = album.year,
                durationMs = 240_000,
                isCompilation = album.compilation,
            )
        }
    }.reversed() // MediaStore order is arbitrary; the builder must not depend on it.

    fun build(): LoadedLibrary = LibraryBuilder.build(rows())

    fun LoadedLibrary.album(title: String): Int = snapshot.albumTitles.indexOf(title).also { check(it >= 0) { title } }

    fun LoadedLibrary.track(title: String): Int = snapshot.trackTitles.indexOf(title).also { check(it >= 0) { title } }

    fun LoadedLibrary.artist(name: String): Int = snapshot.artistNames.indexOf(name).also { check(it >= 0) { name } }
}
