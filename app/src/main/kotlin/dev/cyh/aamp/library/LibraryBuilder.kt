package dev.cyh.aamp.library

import dev.cyh.aamp.search.KindIndex
import dev.cyh.aamp.search.MatchKey
import dev.cyh.aamp.search.SearchIndex
import dev.cyh.aamp.search.TextNormalizer

/** Groups MediaStore rows into albums and artists and builds the snapshot and its search index. */
object LibraryBuilder {
    const val UNKNOWN_ARTIST = "Unknown artist"
    const val UNKNOWN_ALBUM = "Unknown album"
    const val VARIOUS_ARTISTS = "Various Artists"

    private class AlbumGroup(
        val albumId: Long,
        val title: String,
        val titleKey: String,
        val artistName: String,
        val year: Int,
        val addedSec: Long,
        val tracks: List<RawTrack>,
    )

    fun build(rows: List<RawTrack>): LoadedLibrary {
        val albums = groupAlbums(rows)

        // Artists: every track artist and album artist, merged by normalized name.
        val artistDisplay = HashMap<String, String>()
        fun noteArtist(name: String) {
            artistDisplay.putIfAbsent(artistKeyText(name), name)
        }
        for (album in albums) {
            noteArtist(album.artistName)
            for (row in album.tracks) noteArtist(displayArtist(row.artist))
        }
        val artistOrder = artistDisplay.keys.sorted()

        // Genres: merged by normalized name like artists, displayed as first seen.
        val genreDisplay = HashMap<String, String>()
        for (album in albums) {
            for (row in album.tracks) if (row.genre.isNotBlank()) genreDisplay.putIfAbsent(artistKeyText(row.genre), row.genre.trim())
        }
        val genreOrder = genreDisplay.keys.sorted()
        val genreIndexByText = HashMap<String, Int>(genreOrder.size * 2)
        genreOrder.forEachIndexed { i, key -> genreIndexByText[key] = i }
        val artistIndexByText = HashMap<String, Int>(artistOrder.size * 2)
        artistOrder.forEachIndexed { i, key -> artistIndexByText[key] = i }
        fun artistIndex(name: String): Int = artistIndexByText.getValue(artistKeyText(name))

        val trackCount = rows.size
        val trackIds = LongArray(trackCount)
        val trackTitles = arrayOfNulls<String>(trackCount)
        val trackTitleKeys = arrayOfNulls<String>(trackCount)
        val trackArtist = IntArray(trackCount)
        val trackAlbum = IntArray(trackCount)
        val trackDisc = IntArray(trackCount)
        val trackNumber = IntArray(trackCount)
        val trackDuration = LongArray(trackCount)
        val trackGenre = IntArray(trackCount)

        val albumCount = albums.size
        val albumIds = LongArray(albumCount)
        val albumTitles = arrayOfNulls<String>(albumCount)
        val albumArtist = IntArray(albumCount)
        val albumYear = IntArray(albumCount)
        val albumFirst = IntArray(albumCount)
        val albumSize = IntArray(albumCount)

        var t = 0
        for ((a, album) in albums.withIndex()) {
            albumIds[a] = album.albumId
            albumTitles[a] = album.title
            albumArtist[a] = artistIndex(album.artistName)
            albumYear[a] = album.year
            albumFirst[a] = t
            albumSize[a] = album.tracks.size
            for (row in album.tracks) {
                trackIds[t] = row.id
                val title = row.title.ifBlank { "Track ${row.trackNumber}" }
                trackTitles[t] = title
                trackTitleKeys[t] = TextNormalizer.normalize(title)
                trackArtist[t] = artistIndex(displayArtist(row.artist))
                trackAlbum[t] = a
                trackDisc[t] = row.discNumber
                trackNumber[t] = row.trackNumber
                trackDuration[t] = row.durationMs
                trackGenre[t] = if (row.genre.isBlank()) -1 else genreIndexByText.getValue(artistKeyText(row.genre))
                t++
            }
        }

        // Artist to album and artist to track lists, as offset arrays over one flat list.
        val artistCount = artistOrder.size
        val albumsPerArtist = Array(artistCount) { ArrayList<Int>() }
        for (a in 0 until albumCount) albumsPerArtist[albumArtist[a]] += a
        for (list in albumsPerArtist) list.sortWith(compareBy<Int>({ albumYear[it] }, { it }))
        val tracksPerArtist = Array(artistCount) { ArrayList<Int>() }
        for (i in 0 until trackCount) {
            val own = trackArtist[i]
            val credited = albumArtist[trackAlbum[i]]
            tracksPerArtist[own] += i
            if (credited != own) tracksPerArtist[credited] += i
        }
        val (albumOffsets, albumList) = flatten(albumsPerArtist)
        val (trackOffsets, trackList) = flatten(tracksPerArtist)

        // Genre to track and genre to album lists. Tracks are in album order and albums are
        // contiguous, so a genre's albums come out in title order without duplicates.
        val genreCount = genreOrder.size
        val tracksPerGenre = Array(genreCount) { ArrayList<Int>() }
        val albumsPerGenre = Array(genreCount) { ArrayList<Int>() }
        for (i in 0 until trackCount) {
            val g = trackGenre[i]
            if (g < 0) continue
            tracksPerGenre[g] += i
            val albumsOfGenre = albumsPerGenre[g]
            if (albumsOfGenre.lastOrNull() != trackAlbum[i]) albumsOfGenre += trackAlbum[i]
        }
        val (genreTrackOffsets, genreTrackList) = flatten(tracksPerGenre)
        val (genreAlbumOffsets, genreAlbumList) = flatten(albumsPerGenre)

        val titles = trackTitleKeys.requireAll()
        val tracksByTitle = (0 until trackCount).sortedWith(compareBy<Int>({ titles[it] }, { it })).toIntArray()
        // Newest first; albums added in the same scan keep title order.
        val albumsByRecent = (0 until albumCount).sortedWith(compareBy<Int>({ -albums[it].addedSec }, { it })).toIntArray()

        val snapshot = LibrarySnapshot(
            trackIds = trackIds,
            trackTitles = trackTitles.requireAll(),
            trackArtist = trackArtist,
            trackAlbum = trackAlbum,
            trackDisc = trackDisc,
            trackNumber = trackNumber,
            trackDurationMs = trackDuration,
            trackGenre = trackGenre,
            albumIds = albumIds,
            albumTitles = albumTitles.requireAll(),
            albumArtist = albumArtist,
            albumYear = albumYear,
            albumFirstTrack = albumFirst,
            albumTrackCount = albumSize,
            artistNames = Array(artistCount) { artistDisplay.getValue(artistOrder[it]) },
            artistKeys = LongArray(artistCount) { fnv1a64(artistOrder[it]) },
            artistAlbumOffsets = albumOffsets,
            artistAlbumList = albumList,
            artistTrackOffsets = trackOffsets,
            artistTrackList = trackList,
            genreNames = Array(genreCount) { genreDisplay.getValue(genreOrder[it]) },
            genreKeys = LongArray(genreCount) { fnv1a64(genreOrder[it]) },
            genreAlbumOffsets = genreAlbumOffsets,
            genreAlbumList = genreAlbumList,
            genreTrackOffsets = genreTrackOffsets,
            genreTrackList = genreTrackList,
            tracksByTitle = tracksByTitle,
            albumsByRecent = albumsByRecent,
            albumLetters = LetterIndex.build(IntArray(albumCount) { it }) { albums[it].titleKey },
            artistLetters = LetterIndex.build(IntArray(artistCount) { it }) { artistOrder[it] },
            trackLetters = LetterIndex.build(tracksByTitle) { titles[it] },
        )
        val search = SearchIndex(
            albums = KindIndex.build(Array(albumCount) { MatchKey.ofNormalized(albums[it].titleKey) }),
            artists = KindIndex.build(Array(artistCount) { MatchKey.ofNormalized(artistOrder[it]) }),
            tracks = KindIndex.build(Array(trackCount) { MatchKey.ofNormalized(titles[it]) }),
        )
        return LoadedLibrary(snapshot, search)
    }

    private fun groupAlbums(rows: List<RawTrack>): List<AlbumGroup> {
        val byId = LinkedHashMap<Long, MutableList<RawTrack>>()
        for (row in rows) byId.getOrPut(row.albumId) { ArrayList() } += row
        val groups = ArrayList<AlbumGroup>(byId.size)
        for ((albumId, tracks) in byId) {
            tracks.sortWith(
                compareBy<RawTrack>({ it.discNumber }, { it.trackNumber }, { TextNormalizer.normalize(it.title) }, { it.id }),
            )
            val first = tracks.first()
            val title = first.album.ifBlank { UNKNOWN_ALBUM }
            groups += AlbumGroup(
                albumId = albumId,
                title = title,
                titleKey = TextNormalizer.normalize(title),
                artistName = albumArtistOf(tracks),
                year = tracks.maxOf { it.year },
                addedSec = tracks.maxOf { it.dateAddedSec },
                tracks = tracks,
            )
        }
        groups.sortWith(compareBy<AlbumGroup>({ it.titleKey }, { artistKeyText(it.artistName) }, { it.albumId }))
        return groups
    }

    /**
     * The album's credited artist: its album artist tag when any track has one, else the track
     * artist when every track agrees, else "Various Artists".
     */
    private fun albumArtistOf(tracks: List<RawTrack>): String {
        tracks.firstOrNull { it.albumArtist.isNotBlank() }?.let { return it.albumArtist.trim() }
        val first = displayArtist(tracks.first().artist)
        val allSame = tracks.all { artistKeyText(displayArtist(it.artist)) == artistKeyText(first) }
        return if (allSame && !tracks.any { it.isCompilation }) first else VARIOUS_ARTISTS
    }

    private fun displayArtist(name: String): String = name.trim().ifEmpty { UNKNOWN_ARTIST }

    /**
     * Identity of an artist or genre: the normalized name, or the lowercased name if that
     * normalizes to nothing.
     */
    private fun artistKeyText(name: String): String =
        TextNormalizer.normalize(name).ifEmpty { name.trim().lowercase() }

    private fun flatten(lists: Array<ArrayList<Int>>): Pair<IntArray, IntArray> {
        val offsets = IntArray(lists.size + 1)
        for (i in lists.indices) offsets[i + 1] = offsets[i] + lists[i].size
        val flat = IntArray(offsets[lists.size])
        for (i in lists.indices) {
            for ((k, v) in lists[i].withIndex()) flat[offsets[i] + k] = v
        }
        return offsets to flat
    }

    private fun Array<String?>.requireAll(): Array<String> = Array(size) { i ->
        checkNotNull(this[i]) { "entry $i was never filled" }
    }

    /** 64-bit FNV-1a over UTF-16 units: a stable artist or genre id that survives rescans. */
    internal fun fnv1a64(text: String): Long {
        var hash = -0x340d631b7bdddcdbL
        for (ch in text) {
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash
    }
}
