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
        val genre: String = "",
    )

    private val albums = listOf(
        AlbumSpec(1, "Kill 'Em All", "Metallica", "Metallica", 1983,
            listOf("Hit the Lights" to null, "The Four Horsemen" to null, "Motorbreath" to null, "Seek & Destroy" to null),
            genre = "Thrash Metal"),
        AlbumSpec(2, "Ride the Lightning", "Metallica", "", 1984,
            listOf("Fight Fire with Fire" to null, "Ride the Lightning" to null, "For Whom the Bell Tolls" to null, "Fade to Black" to null),
            genre = "Thrash Metal"),
        AlbumSpec(3, "Master of Puppets (Remastered)", "Metallica", "Metallica", 1986,
            // Genres differing only in case are one genre.
            listOf("Battery" to null, "Master of Puppets" to null, "The Thing That Should Not Be" to null), genre = "thrash metal"),
        AlbumSpec(4, "...And Justice for All", "Metallica", "Metallica", 1988,
            listOf("Blackened" to null, "...And Justice for All" to null, "Eye of the Beholder" to null, "One" to null),
            genre = "Thrash Metal"),
        AlbumSpec(5, "Metallica", "Metallica", "Metallica", 1991,
            listOf("Enter Sandman" to null, "Sad but True" to null, "Nothing Else Matters" to null), genre = "Heavy Metal"),
        AlbumSpec(6, "Don't Play That Song!", "Ben E. King", "", 1962,
            listOf("Stand by Me" to null, "Don't Play That Song (You Lied)" to null), genre = "Soul"),
        AlbumSpec(7, "Ace of Spades", "Motörhead", "Motörhead", 1980,
            listOf("Ace of Spades" to null, "Love Me Like a Reptile" to null), genre = "Heavy Metal"),
        AlbumSpec(8, "Now That's What I Call Music", "", "", 2001,
            listOf("Song A" to "Artist A", "Song B" to "Artist B"), compilation = true),
        // A one-word title that a long, garbled query contains ("and just for all my intelligents").
        AlbumSpec(9, "The Bends", "Radiohead", "Radiohead", 1995,
            listOf("Planet Telex" to null, "The Bends" to null, "High and Dry" to null, "Fake Plastic Trees" to null,
                "Bones" to null, "Nice Dream" to null, "Just" to null), genre = "Alternative"),
        // A second song with the same name as the studio album.
        AlbumSpec(10, "Live in Seoul", "Metallica", "Metallica", 2017,
            listOf("Creeping Death" to null, "And Justice For All" to null)),
        // Two albums with one title, told apart only by the artist; and an artist name ("Queen")
        // that ends another artist's song title ("Dancing Queen").
        AlbumSpec(11, "Greatest Hits", "Queen", "Queen", 1981,
            listOf("Bohemian Rhapsody" to null, "Another One Bites the Dust" to null), genre = "Rock"),
        AlbumSpec(12, "Greatest Hits", "ABBA", "ABBA", 1975,
            listOf("Dancing Queen" to null, "Waterloo" to null), genre = "Pop"),
        // An artist named like a kind word, and a title that starts with one.
        AlbumSpec(13, "Music from Big Pink", "The Band", "The Band", 1968,
            listOf("The Weight" to null, "Chest Fever" to null)),
        AlbumSpec(14, "Band on the Run", "Wings", "Wings", 1973,
            listOf("Band on the Run" to null, "Jet" to null)),
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
                // Later fixtures count as added later, for Recently added.
                dateAddedSec = album.id * 1_000L,
                genre = album.genre,
            )
        }
    }.reversed() // MediaStore order is arbitrary; the builder must not depend on it.

    fun build(): LoadedLibrary = LibraryBuilder.build(rows())

    fun LoadedLibrary.album(title: String): Int = snapshot.albumTitles.indexOf(title).also { check(it >= 0) { title } }

    /** The album titled [title] by [artist], for titles several artists share. */
    fun LoadedLibrary.album(title: String, artist: String): Int =
        snapshot.albumTitles.indices.first { snapshot.albumTitles[it] == title && snapshot.artistNames[snapshot.albumArtist[it]] == artist }

    fun LoadedLibrary.track(title: String): Int = snapshot.trackTitles.indexOf(title).also { check(it >= 0) { title } }

    fun LoadedLibrary.artist(name: String): Int = snapshot.artistNames.indexOf(name).also { check(it >= 0) { name } }
}
