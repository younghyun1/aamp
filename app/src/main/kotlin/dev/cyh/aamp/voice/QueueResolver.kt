package dev.cyh.aamp.voice

import dev.cyh.aamp.library.LoadedLibrary
import dev.cyh.aamp.search.Hit
import dev.cyh.aamp.search.MatchKey
import dev.cyh.aamp.search.Similarity
import java.util.Locale
import kotlin.random.Random

/** Where a queue came from; decides the media ids of its items. */
sealed interface QueueSource {
    data class Album(val album: Int) : QueueSource
    data class Artist(val artist: Int) : QueueSource
    /** The Songs list, ordered by title. */
    data object Songs : QueueSource
    /** The whole library, shuffled. */
    data object Library : QueueSource
}

sealed interface Resolution {
    /** Play [tracks] (snapshot track indices) in order, starting at [startIndex]. */
    class Queue(val tracks: IntArray, val startIndex: Int, val source: QueueSource, val summary: String) : Resolution

    /** Nothing in the library matched [phrase] well enough. */
    data class NoMatch(val phrase: String) : Resolution

    data object EmptyLibrary : Resolution
}

/**
 * Matches a [PlayRequest] against the library and builds the queue to play.
 *
 * - Album: the album from its first track.
 * - Song: the song, then the rest of its album.
 * - Artist: every track by the artist, shuffled.
 * - Any: whichever of artist, album and song matches best. An artist wins ties, and an album wins
 *   ties and near ties with a song: Gemini drops the word "album" from "play album X", so a
 *   request that names both an album and its title track is taken as the album.
 */
class QueueResolver(private val library: LoadedLibrary, private val random: Random) {
    private val snapshot = library.snapshot
    private val search = library.search

    fun resolve(request: PlayRequest): Resolution {
        if (snapshot.trackCount == 0) return Resolution.EmptyLibrary
        return when (request) {
            PlayRequest.Resume, PlayRequest.ShuffleAll -> shuffleAll()
            is PlayRequest.Search -> resolveSearch(request)
        }
    }

    fun shuffleAll(): Resolution.Queue {
        val tracks = IntArray(snapshot.trackCount) { it }
        tracks.shuffleWith(random)
        return Resolution.Queue(tracks, 0, QueueSource.Library, "whole library, shuffled")
    }

    /** Album [album] from its first track, or from [startTrack] when that is one of its tracks. */
    fun album(album: Int, startTrack: Int = -1): Resolution.Queue {
        val tracks = snapshot.albumTracks(album)
        val start = (startTrack - snapshot.albumFirstTrack[album]).takeIf { it in tracks.indices } ?: 0
        val summary = "album ${snapshot.albumTitles[album]} by ${artistName(snapshot.albumArtist[album])}, track ${start + 1}"
        return Resolution.Queue(tracks, start, QueueSource.Album(album), summary)
    }

    /** Every track by [artist]: shuffled, or in album order from [startTrack]. */
    fun artist(artist: Int, shuffle: Boolean, startTrack: Int = -1): Resolution.Queue {
        val tracks = snapshot.artistTracks(artist)
        if (shuffle) tracks.shuffleWith(random)
        val start = tracks.indexOf(startTrack).coerceAtLeast(0)
        val order = if (shuffle) "shuffled" else "in album order"
        return Resolution.Queue(tracks, start, QueueSource.Artist(artist), "all songs by ${artistName(artist)}, $order")
    }

    /** The Songs list from [startTrack]. */
    fun songs(startTrack: Int): Resolution.Queue {
        val tracks = snapshot.tracksByTitle
        val start = tracks.indexOf(startTrack).coerceAtLeast(0)
        return Resolution.Queue(tracks, start, QueueSource.Songs, "all songs by title")
    }

    private fun resolveSearch(request: PlayRequest.Search): Resolution {
        val readings = readings(request)
        return when (request.kind) {
            RequestKind.ALBUM ->
                bestAlbum(readings)?.let { album(it.index).scored(it) }
                    ?: bestTrack(readings)?.let { album(snapshot.trackAlbum[it.index]).scored(it) }
                    ?: Resolution.NoMatch(request.phrase)
            RequestKind.SONG ->
                bestTrack(readings)?.let { song(it.index, request.shuffle).scored(it) }
                    ?: bestAlbum(readings)?.let { album(it.index).scored(it) }
                    ?: Resolution.NoMatch(request.phrase)
            RequestKind.ARTIST ->
                search.artists.best(MatchKey.of(request.phrase), MIN_SCORE)?.let { artist(it.index, shuffle = true).scored(it) }
                    ?: resolveAny(request, readings)
            RequestKind.ANY -> resolveAny(request, readings)
        }
    }

    private fun resolveAny(request: PlayRequest.Search, readings: List<Reading>): Resolution {
        val album = bestAlbum(readings)
        val track = bestTrack(readings)
        val artist = if (request.artist == null) search.artists.best(MatchKey.of(request.phrase), MIN_SCORE) else null
        val a = album?.score ?: 0f
        val t = track?.score ?: 0f
        val r = artist?.score ?: 0f
        return when {
            artist != null && r >= a && r >= t -> artist(artist.index, shuffle = true).scored(artist)
            album != null && a >= t - MARGIN -> album(album.index).scored(album)
            track != null -> song(track.index, request.shuffle).scored(track)
            else -> Resolution.NoMatch(request.phrase)
        }
    }

    /** The song, then the rest of its album; shuffled album when asked. */
    private fun song(track: Int, shuffle: Boolean): Resolution.Queue {
        val queue = album(snapshot.trackAlbum[track], startTrack = track)
        if (!shuffle) return queue
        val rest = queue.tracks.filter { it != track }.toIntArray().also { it.shuffleWith(random) }
        return Resolution.Queue(intArrayOf(track) + rest, 0, queue.source, queue.summary + ", rest shuffled")
    }

    /** One way to read the request: a title and, possibly, an artist. */
    private class Reading(val title: MatchKey, val artist: MatchKey?)

    /**
     * The ways to read a request. With an artist hint there is one. Otherwise the whole phrase is
     * a title, and "X by Y" is also read as title X by artist Y when Y names a library artist,
     * so "Stand by Me" stays a title. Speech recognition often hears "by" as "my", so "X my Y"
     * gets the same treatment.
     */
    private fun readings(request: PlayRequest.Search): List<Reading> {
        request.artist?.let { return listOf(Reading(MatchKey.of(request.phrase), MatchKey.of(it))) }
        val readings = ArrayList<Reading>(3)
        for (separator in ARTIST_SEPARATORS) {
            val split = request.phrase.lastIndexOf(separator)
            if (split <= 0) continue
            val title = request.phrase.substring(0, split)
            val artistText = request.phrase.substring(split + separator.length)
            if (title.isBlank() || artistText.isBlank()) continue
            val artistKey = MatchKey.of(artistText)
            if (search.artists.best(artistKey, ARTIST_SPLIT_MIN) != null) readings += Reading(MatchKey.of(title), artistKey)
        }
        readings += Reading(MatchKey.of(request.phrase), null)
        return readings
    }

    private fun bestAlbum(readings: List<Reading>): Hit? = readings.mapNotNull { reading ->
        search.albums.best(reading.title, MIN_SCORE) { album, score ->
            withArtist(score, reading.artist, snapshot.albumArtist[album], -1)
        }
    }.maxByOrNull { it.score }

    private fun bestTrack(readings: List<Reading>): Hit? = readings.mapNotNull { reading ->
        search.tracks.best(reading.title, MIN_SCORE) { track, score ->
            withArtist(score, reading.artist, snapshot.trackArtist[track], snapshot.albumArtist[snapshot.trackAlbum[track]])
        }
    }.maxByOrNull { it.score }

    /**
     * Weighs a title score by how well the spoken artist matches the entry's artists: a perfect
     * artist keeps the score, a wrong one scales it to 60%. No spoken artist costs nothing.
     */
    private fun withArtist(score: Float, spoken: MatchKey?, artist: Int, alsoArtist: Int): Float {
        if (spoken == null) return score
        var artistScore = Similarity.score(spoken, search.artists.keys[artist])
        if (alsoArtist >= 0 && alsoArtist != artist) {
            artistScore = maxOf(artistScore, Similarity.score(spoken, search.artists.keys[alsoArtist]))
        }
        return score * (0.6f + 0.4f * artistScore)
    }

    private fun artistName(artist: Int): String = snapshot.artistNames[artist]

    private fun Resolution.Queue.scored(hit: Hit): Resolution.Queue =
        Resolution.Queue(tracks, startIndex, source, "$summary (score ${String.format(Locale.ROOT, "%.2f", hit.score)})")

    private fun IntArray.shuffleWith(random: Random) {
        for (i in lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = this[i]
            this[i] = this[j]
            this[j] = tmp
        }
    }

    companion object {
        /** Weakest combined score that still counts as a match. */
        const val MIN_SCORE = 0.6f

        /** How much better a song must score than an album to be chosen over it. */
        const val MARGIN = 0.05f

        private val ARTIST_SEPARATORS = listOf(" by ", " my ")

        /**
         * How well the text after the last "by" must match an artist to be read as one. Low enough
         * for a misrecognized name ("metalica" scores about 0.67); short words such as the "me" in
         * "Stand by Me" score far below it against real artist names.
         */
        const val ARTIST_SPLIT_MIN = 0.6f
    }
}
