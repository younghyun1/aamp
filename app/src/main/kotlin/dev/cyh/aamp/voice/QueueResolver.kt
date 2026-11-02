package dev.cyh.aamp.voice

import dev.cyh.aamp.library.LoadedLibrary
import dev.cyh.aamp.library.Playlist
import dev.cyh.aamp.search.Hit
import dev.cyh.aamp.search.KindIndex
import dev.cyh.aamp.search.MatchKey
import dev.cyh.aamp.search.Similarity
import dev.cyh.aamp.search.TextNormalizer
import java.util.Locale
import kotlin.random.Random

/** Where a queue came from; decides the media ids of its items. */
sealed interface QueueSource {
    data class Album(val album: Int) : QueueSource
    data class Artist(val artist: Int) : QueueSource
    data class Genre(val genre: Int) : QueueSource
    data class Playlist(val id: Long) : QueueSource
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
 * - Genre: every track in the genre, shuffled.
 * - Playlist: the playlist in order, or shuffled when asked.
 * - Any: whichever matches best. A playlist named closely (Gemini drops "playlist" like it drops
 *   "album") wins over everything; an artist wins ties with a genre, album or song; a genre wins
 *   ties with an album or song; and an album wins ties and near ties with a song, so a request
 *   that names both an album and its title track is taken as the album.
 */
class QueueResolver(
    private val library: LoadedLibrary,
    private val random: Random,
    private val playlists: List<Playlist> = emptyList(),
) {
    private val snapshot = library.snapshot
    private val search = library.search
    private val playlistIndex by lazy { KindIndex.build(Array(playlists.size) { MatchKey.of(playlists[it].name) }) }

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

    /** Every track in [genre]: shuffled, or in album order from [startTrack]. */
    fun genre(genre: Int, shuffle: Boolean, startTrack: Int = -1): Resolution.Queue {
        val tracks = snapshot.genreTracks(genre)
        if (shuffle) tracks.shuffleWith(random)
        val start = tracks.indexOf(startTrack).coerceAtLeast(0)
        val order = if (shuffle) "shuffled" else "in album order"
        return Resolution.Queue(tracks, start, QueueSource.Genre(genre), "genre ${snapshot.genreNames[genre]}, $order")
    }

    /**
     * Playlist [playlist] in order from [startTrack] (its first occurrence), or shuffled; null when
     * none of its songs is in the library.
     */
    fun playlist(playlist: Playlist, shuffle: Boolean, startTrack: Int = -1): Resolution.Queue? {
        val tracks = playlist.tracksIn(snapshot)
        if (tracks.isEmpty()) return null
        if (shuffle) tracks.shuffleWith(random)
        val start = tracks.indexOf(startTrack).coerceAtLeast(0)
        val order = if (shuffle) "shuffled" else "in order"
        return Resolution.Queue(tracks, start, QueueSource.Playlist(playlist.id), "playlist ${playlist.name}, $order")
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
                    ?: asSaid(request)
                    ?: Resolution.NoMatch(request.phrase)
            RequestKind.SONG ->
                bestTrack(readings)?.let { song(it.index, request.shuffle).scored(it) }
                    ?: bestAlbum(readings)?.let { album(it.index).scored(it) }
                    ?: asSaid(request)
                    ?: Resolution.NoMatch(request.phrase)
            RequestKind.ARTIST ->
                bestName(nameKeys(request.phrase), search.artists)?.let { artist(it.index, shuffle = true).scored(it) }
                    ?: asSaid(request)
                    ?: resolveAny(request, readings)
            RequestKind.GENRE ->
                bestName(nameKeys(request.phrase), search.genres)?.let { genre(it.index, shuffle = true).scored(it) }
                    ?: asSaid(request)
                    ?: resolveAny(request, readings)
            RequestKind.PLAYLIST ->
                bestName(nameKeys(request.phrase), playlistIndex)
                    ?.let { hit -> playlist(playlists[hit.index], request.shuffle)?.scored(hit) }
                    ?: asSaid(request)
                    ?: resolveAny(request, readings)
            RequestKind.ANY -> resolveAny(request, readings)
        }
    }

    /**
     * A request whose kind word found nothing, read again as said with no kind: "play band on the
     * run" names no artist "on the run", so it is the album Band on the Run. Null when the parser
     * took no word out.
     */
    private fun asSaid(request: PlayRequest.Search): Resolution? {
        val whole = request.whole ?: return null
        return resolveSearch(PlayRequest.Search(RequestKind.ANY, whole, request.artist, request.shuffle, request.album))
    }

    private fun resolveAny(request: PlayRequest.Search, readings: List<Reading>): Resolution {
        val album = bestAlbum(readings)
        val track = bestTrack(readings)
        val names = if (request.artist == null) nameKeys(request.phrase) else emptyList()
        val artist = bestName(names, search.artists)
        val genre = bestName(names, search.genres)
        val playlist = bestName(names, playlistIndex)
        val a = album?.score ?: 0f
        val t = track?.score ?: 0f
        val r = artist?.score ?: 0f
        val g = genre?.score ?: 0f
        val p = playlist?.score ?: 0f
        if (playlist != null && p >= PLAYLIST_MIN && p >= maxOf(r, a, t, g)) {
            playlist(playlists[playlist.index], request.shuffle)?.let { return it.scored(playlist) }
        }
        return when {
            artist != null && r >= a && r >= t && r >= g -> artist(artist.index, shuffle = true).scored(artist)
            genre != null && g >= a && g >= t -> genre(genre.index, shuffle = true).scored(genre)
            album != null && a >= t - MARGIN -> album(album.index).scored(album)
            track != null -> song(track.index, request.shuffle).scored(track)
            else -> Resolution.NoMatch(request.phrase)
        }
    }

    /**
     * The phrase read as the name of an artist, genre or playlist: as said, and without words that
     * only say what kind of thing it names ("metallica songs", "rock music", "my road trip"). The
     * shortened form weighs [UNMARKED_WEIGHT], so an album titled "Love Songs" beats an artist
     * named Love.
     */
    private fun nameKeys(phrase: String): List<Pair<MatchKey, Float>> {
        val words = TextNormalizer.words(phrase).split(' ').filter { it.isNotEmpty() }
        var from = 0
        var to = words.size
        if (to - from > 1 && words[to - 1] in NAME_SUFFIXES) to--
        if (to - from > 1 && words[from] == "my") from++
        val keys = arrayListOf(MatchKey.of(phrase) to 1f)
        if (from > 0 || to < words.size) keys += MatchKey.of(words.subList(from, to).joinToString(" ")) to UNMARKED_WEIGHT
        return keys
    }

    private fun bestName(names: List<Pair<MatchKey, Float>>, index: KindIndex): Hit? {
        val best = Best(MIN_SCORE)
        for ((key, weight) in names) index.best(key, MIN_SCORE)?.let { best.offer(it.index, it.score * weight) }
        return best.hit()
    }

    /** The song, then the rest of its album; shuffled album when asked. */
    private fun song(track: Int, shuffle: Boolean): Resolution.Queue {
        val queue = album(snapshot.trackAlbum[track], startTrack = track)
        if (!shuffle) return queue
        val rest = queue.tracks.filter { it != track }.toIntArray().also { it.shuffleWith(random) }
        return Resolution.Queue(intArrayOf(track) + rest, 0, queue.source, queue.summary + ", rest shuffled")
    }

    /**
     * One way to read a request: a [title], possibly by a spoken [artist] that names the library
     * [artists], possibly a song on [album] (a snapshot index, or -1). [weight] ranks guesses below
     * readings the request states outright. A [global] reading searches every album and track;
     * the others only those of [artists] or [album].
     */
    private class Reading(
        val title: MatchKey,
        val artist: MatchKey?,
        val artists: List<Hit> = emptyList(),
        val album: Int = -1,
        val weight: Float = 1f,
        val global: Boolean = true,
    )

    /** The readings of [request]; with an album hint, a song on that album is read first. */
    private fun readings(request: PlayRequest.Search): List<Reading> {
        val readings = readings(request.phrase, request.artist, albumSplits = request.album == null)
        val album = request.album?.let { bestAlbum(readings(it, request.artist, albumSplits = false)) } ?: return readings
        return listOf(Reading(MatchKey.of(request.phrase), request.artist?.let(MatchKey::of), album = album.index, global = false)) + readings
    }

    /**
     * The ways to read [phrase]. With an artist hint there is one. Otherwise the whole phrase is a
     * title, and these splits are added when the library confirms the split-off part:
     *
     * - "X by Y" at every separator, so "Stand by Me by Ben E. King" splits after "Me" while
     *   "Stand by Me" alone stays a title because "me" names no artist. "By the band Y" reads as Y.
     * - "X from Y", "X off the album Y": song X on album Y ([albumSplits] only).
     * - "X Y" and "Y X" without a separator, as when Gemini drops "by", and the possessive "Y's X",
     *   which arrives as "ys x": a leading or trailing run of words that closely matches an artist.
     */
    private fun readings(phrase: String, artistHint: String?, albumSplits: Boolean): List<Reading> {
        if (artistHint != null) {
            val artist = MatchKey.of(artistHint)
            return listOf(Reading(MatchKey.of(phrase), artist, artistsNamed(artist, ARTIST_SPLIT_MIN, fuzzy = true)))
        }
        val words = TextNormalizer.words(phrase).split(' ').filter { it.isNotEmpty() }
        val readings = arrayListOf(Reading(MatchKey.of(phrase), null))
        val n = words.size
        for (i in 1 until n - 1) {
            val title = words.subList(0, i)
            val rest = words.subList(i + 1, n)
            if (words[i] in Phrases.artistSeparators) {
                val artist = Phrases.withoutFiller(rest, Phrases.artistKinds)
                byArtist(title, artist, ARTIST_SPLIT_MIN, weight = 1f, explicit = true)?.let(readings::add)
            }
            if (albumSplits && words[i] in Phrases.albumSeparators) readings += onAlbum(title, rest)
        }
        for (k in 1..minOf(MAX_ARTIST_WORDS, n - 1)) {
            byArtist(words.subList(0, n - k), words.subList(n - k, n), UNMARKED_ARTIST_MIN, UNMARKED_WEIGHT, explicit = false)
                ?.let(readings::add)
            leadingArtist(words.subList(k, n), words.subList(0, k))?.let(readings::add)
        }
        return readings
    }

    /**
     * Title [title] by [artist] when [artist] names a library artist at [minScore] or better. An
     * [explicit] artist, introduced by "by" or similar, may be misheard, so it is matched fuzzily
     * and its reading also searches the whole library; an unmarked one must share a word with the
     * artist's name and limits the search to that artist.
     */
    private fun byArtist(title: List<String>, artist: List<String>, minScore: Float, weight: Float, explicit: Boolean): Reading? {
        if (title.isEmpty() || artist.isEmpty()) return null
        val artistKey = MatchKey.of(artist.joinToString(" "))
        val named = artistsNamed(artistKey, minScore, fuzzy = explicit)
        if (named.isEmpty()) return null
        return Reading(MatchKey.of(title.joinToString(" ")), artistKey, named, weight = weight, global = explicit)
    }

    /** A leading artist, also as a possessive: "metallica's kill em all" arrives as "metallicas kill em all". */
    private fun leadingArtist(title: List<String>, artist: List<String>): Reading? {
        val plain = byArtist(title, artist, UNMARKED_ARTIST_MIN, UNMARKED_WEIGHT, explicit = false)
        val last = artist.last()
        if (last.length < 3 || !last.endsWith('s')) return plain
        val owner = artist.subList(0, artist.size - 1) + last.dropLast(1)
        val possessive = byArtist(title, owner, UNMARKED_ARTIST_MIN, UNMARKED_WEIGHT, explicit = false) ?: return plain
        return if (plain == null || possessive.artists[0].score > plain.artists[0].score) possessive else plain
    }

    /**
     * Song [title] on the album named by [rest]. The album must match closely unless the request
     * says "the album". The song part may itself end in "by Y", which the album already settles,
     * so its words before each artist separator are read too.
     */
    private fun onAlbum(title: List<String>, rest: List<String>): List<Reading> {
        val albumWords = Phrases.withoutFiller(rest, Phrases.albumFillers)
        val explicit = albumWords.size < rest.size
        val album = bestAlbum(readings(albumWords.joinToString(" "), null, albumSplits = false)) ?: return emptyList()
        if (album.score < if (explicit) MIN_SCORE else ALBUM_SPLIT_MIN) return emptyList()
        val weight = if (explicit) 1f else UNMARKED_WEIGHT
        val readings = arrayListOf(Reading(MatchKey.of(title.joinToString(" ")), null, album = album.index, weight = weight, global = false))
        for (j in 1 until title.size) {
            if (title[j] in Phrases.artistSeparators) {
                readings += Reading(MatchKey.of(title.subList(0, j).joinToString(" ")), null, album = album.index, weight = weight, global = false)
            }
        }
        return readings
    }

    /** Library artists [artist] may name, best first. */
    private fun artistsNamed(artist: MatchKey, minScore: Float, fuzzy: Boolean): List<Hit> =
        search.artists.top(artist, MAX_NAMED_ARTISTS, minScore, fuzzy)

    private fun bestAlbum(readings: List<Reading>): Hit? {
        val best = Best(MIN_SCORE)
        for (r in readings) {
            if (r.album >= 0) continue // a song on a named album, not an album
            if (r.global) {
                search.albums.best(r.title, MIN_SCORE) { a, s -> r.weight * withArtist(s, r.artist, snapshot.albumArtist[a], -1) }
                    ?.let { best.offer(it.index, it.score) }
            }
            for (named in r.artists) {
                for (a in snapshot.artistAlbums(named.index)) {
                    val s = Similarity.score(r.title, search.albums.keys[a])
                    if (s >= MIN_SCORE) best.offer(a, r.weight * withArtist(s, r.artist, snapshot.albumArtist[a], -1))
                }
            }
        }
        return best.hit()
    }

    private fun bestTrack(readings: List<Reading>): Hit? {
        val best = Best(MIN_SCORE)
        for (r in readings) {
            if (r.album >= 0) {
                val first = snapshot.albumFirstTrack[r.album]
                for (t in first until first + snapshot.albumTrackCount[r.album]) offerTrack(best, r, t)
                continue
            }
            if (r.global) {
                search.tracks.best(r.title, MIN_SCORE) { t, s -> r.weight * trackScore(s, r.artist, t) }
                    ?.let { best.offer(it.index, it.score) }
            }
            for (named in r.artists) {
                for (t in snapshot.artistTracks(named.index)) offerTrack(best, r, t)
            }
        }
        return best.hit()
    }

    private fun offerTrack(best: Best, reading: Reading, track: Int) {
        val s = Similarity.score(reading.title, search.tracks.keys[track])
        if (s >= MIN_SCORE) best.offer(track, reading.weight * trackScore(s, reading.artist, track))
    }

    private fun trackScore(score: Float, spoken: MatchKey?, track: Int): Float =
        withArtist(score, spoken, snapshot.trackArtist[track], snapshot.albumArtist[snapshot.trackAlbum[track]])

    /** The highest score offered at or above a floor; the first offer wins ties. */
    private class Best(private var score: Float) {
        private var index = -1

        fun offer(index: Int, score: Float) {
            if (score > this.score || (this.index < 0 && score >= this.score)) {
                this.index = index
                this.score = score
            }
        }

        fun hit(): Hit? = if (index >= 0) Hit(index, score) else null
    }

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

        /**
         * How well the text after "by" must match an artist to be read as one. Low enough for a
         * misrecognized name ("metalica" scores about 0.67); short words such as the "me" in
         * "Stand by Me" score far below it against real artist names.
         */
        const val ARTIST_SPLIT_MIN = 0.6f

        /**
         * How well an unmarked run of words must match an artist ("ride the lightning metallica").
         * High, because nothing in the request says it is an artist: "maiden" alone must not
         * read as Iron Maiden and split "iron maiden" into a title and an artist.
         */
        const val UNMARKED_ARTIST_MIN = 0.8f

        /** How well the text after "from" must match an album when the request does not say "album". */
        const val ALBUM_SPLIT_MIN = 0.8f

        /** Weight of readings the request does not state outright, so a whole-phrase title wins ties. */
        const val UNMARKED_WEIGHT = 0.97f

        /** How closely an unmarked request must name a playlist for the playlist to play. */
        const val PLAYLIST_MIN = 0.9f

        /** Trailing words that say what kind of thing a name is: "metallica songs", "rock music". */
        private val NAME_SUFFIXES = setOf("music", "songs", "tracks", "stuff")

        /** Longest artist name, in words, tried without a separator. */
        private const val MAX_ARTIST_WORDS = 5

        /** Artists one spoken name is matched against: the best few, for near-duplicate names. */
        private const val MAX_NAMED_ARTISTS = 3
    }
}
