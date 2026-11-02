package dev.cyh.aamp.library

import dev.cyh.aamp.search.SearchIndex

/**
 * The whole music library as immutable parallel arrays. Entries are addressed by index:
 * track `t`, album `a` and artist `r` index every array of their kind.
 *
 * - Tracks are laid out album by album, in album order, then by disc and track number, so an
 *   album's tracks are one contiguous range.
 * - Albums are ordered by normalized title, then album artist.
 * - Artists are ordered by normalized name and include both track artists and album artists.
 *
 * The arrays are never written after construction; a rescan builds a new snapshot.
 */
class LibrarySnapshot internal constructor(
    val trackIds: LongArray,
    val trackTitles: Array<String>,
    val trackArtist: IntArray,
    val trackAlbum: IntArray,
    val trackDisc: IntArray,
    val trackNumber: IntArray,
    val trackDurationMs: LongArray,
    /** Genre of each track, or -1 for none. */
    val trackGenre: IntArray,
    val albumIds: LongArray,
    val albumTitles: Array<String>,
    val albumArtist: IntArray,
    val albumYear: IntArray,
    val albumFirstTrack: IntArray,
    val albumTrackCount: IntArray,
    val artistNames: Array<String>,
    val artistKeys: LongArray,
    internal val artistAlbumOffsets: IntArray,
    internal val artistAlbumList: IntArray,
    internal val artistTrackOffsets: IntArray,
    internal val artistTrackList: IntArray,
    /** Genres ordered by normalized name; keys are stable across rescans like artist keys. */
    val genreNames: Array<String>,
    val genreKeys: LongArray,
    internal val genreAlbumOffsets: IntArray,
    internal val genreAlbumList: IntArray,
    internal val genreTrackOffsets: IntArray,
    internal val genreTrackList: IntArray,
    /** Track indices ordered by normalized title, for the Songs list. */
    val tracksByTitle: IntArray,
    /** Album indices, most recently added first. */
    val albumsByRecent: IntArray,
    /** Alphabetical sections of albums, artists, and tracks in title order. */
    val albumLetters: LetterIndex,
    val artistLetters: LetterIndex,
    val trackLetters: LetterIndex,
) {
    private val trackById = LongIntMap.indexOf(trackIds)
    private val albumById = LongIntMap.indexOf(albumIds)
    private val artistByKey = LongIntMap.indexOf(artistKeys)
    private val genreByKey = LongIntMap.indexOf(genreKeys)

    val trackCount: Int get() = trackIds.size
    val albumCount: Int get() = albumIds.size
    val artistCount: Int get() = artistNames.size
    val genreCount: Int get() = genreNames.size

    /** Track indices of album [album], in play order. */
    fun albumTracks(album: Int): IntArray {
        val first = albumFirstTrack[album]
        return IntArray(albumTrackCount[album]) { first + it }
    }

    /** Albums whose album artist is [artist], oldest first. */
    fun artistAlbums(artist: Int): IntArray =
        artistAlbumList.copyOfRange(artistAlbumOffsets[artist], artistAlbumOffsets[artist + 1])

    /** Tracks by [artist] as track artist or album artist, in album order. */
    fun artistTracks(artist: Int): IntArray =
        artistTrackList.copyOfRange(artistTrackOffsets[artist], artistTrackOffsets[artist + 1])

    fun artistAlbumCount(artist: Int): Int = artistAlbumOffsets[artist + 1] - artistAlbumOffsets[artist]

    fun artistTrackCount(artist: Int): Int = artistTrackOffsets[artist + 1] - artistTrackOffsets[artist]

    /**
     * The album whose cover stands for [artist]: their newest album, or for an artist who only
     * appears on other artists' albums, the album of their first track.
     */
    fun artistCoverAlbum(artist: Int): Int {
        val albums = artistAlbumOffsets[artist + 1]
        if (albums > artistAlbumOffsets[artist]) return artistAlbumList[albums - 1]
        return trackAlbum[artistTrackList[artistTrackOffsets[artist]]]
    }

    /** Albums with at least one track in [genre], in title order. */
    fun genreAlbums(genre: Int): IntArray = genreAlbumList.copyOfRange(genreAlbumOffsets[genre], genreAlbumOffsets[genre + 1])

    /** Tracks in [genre], in album order. */
    fun genreTracks(genre: Int): IntArray = genreTrackList.copyOfRange(genreTrackOffsets[genre], genreTrackOffsets[genre + 1])

    fun genreAlbumCount(genre: Int): Int = genreAlbumOffsets[genre + 1] - genreAlbumOffsets[genre]

    fun genreTrackCount(genre: Int): Int = genreTrackOffsets[genre + 1] - genreTrackOffsets[genre]

    /** Index of the track with MediaStore id [trackId], or -1. */
    fun trackIndex(trackId: Long): Int = trackById[trackId]

    /** Index of the album with MediaStore album id [albumId], or -1. */
    fun albumIndex(albumId: Long): Int = albumById[albumId]

    /** Index of the artist with stable key [artistKey], or -1. */
    fun artistIndex(artistKey: Long): Int = artistByKey[artistKey]

    /** Index of the genre with stable key [genreKey], or -1. */
    fun genreIndex(genreKey: Long): Int = genreByKey[genreKey]
}

/** A snapshot and the search index built from it; always replaced together. */
class LoadedLibrary(val snapshot: LibrarySnapshot, val search: SearchIndex)
