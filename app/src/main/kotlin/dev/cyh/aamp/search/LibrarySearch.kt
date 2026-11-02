package dev.cyh.aamp.search

import dev.cyh.aamp.library.LoadedLibrary
import dev.cyh.aamp.library.Playlist
import org.apache.lucene.analysis.core.WhitespaceAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BoostQuery
import org.apache.lucene.search.DisjunctionMaxQuery
import org.apache.lucene.search.FuzzyQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.PrefixQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.ByteBuffersDirectory
import java.io.Closeable

/**
 * Search as you type over songs, albums, artists, genres and playlists, on Apache Lucene 8.11.
 * Lucene 9 and later call Java module APIs while loading core classes, which Android's runtime
 * does not have.
 *
 * Text goes through aamp's own normalization first ([TextNormalizer]: case, accents, punctuation,
 * and Han and Kana as bigrams), with Hangul split into letters ([Hangul.letters]) so a half-typed
 * syllable still matches; Lucene then indexes whitespace-separated words. Each query word matches
 * a word of an entry exactly, by prefix when it is the word still being typed, by initial
 * consonants when it is only those ("ㅁㅌㄹㅋ"), and with up to one typo from 5 letters and two
 * from 9, the first letter fixed (Lucene's Levenshtein-automaton [FuzzyQuery]). Every word must
 * match; when nothing does, the last word may be left out.
 *
 * Results are ranked by Lucene's BM25 score with exact words above prefixes above typos, a title
 * above an artist above an album, and an entry whose title is exactly the query first. The index
 * lives in memory; build a new one when the library or the playlists change, and [close] it.
 */
class LibrarySearch private constructor(
    private val reader: DirectoryReader,
    private val searcher: IndexSearcher,
) : Closeable {
    enum class Kind(val tag: String, val fields: List<Pair<String, Float>>) {
        SONG("song", listOf(TITLE_FIELD to 3f, ARTIST_FIELD to 1.5f, ALBUM_FIELD to 1f)),
        ALBUM("album", listOf(TITLE_FIELD to 3f, ARTIST_FIELD to 1.5f)),
        ARTIST("artist", listOf(TITLE_FIELD to 3f)),
        GENRE("genre", listOf(TITLE_FIELD to 3f)),
        PLAYLIST("playlist", listOf(TITLE_FIELD to 3f)),
    }

    /**
     * Matches per kind, best first: snapshot indices for songs, albums, artists and genres,
     * positions in the playlist list for playlists. [order] lists the kinds by their best match.
     */
    class Results(val byKind: Map<Kind, IntArray>, val order: List<Kind>) {
        operator fun get(kind: Kind): IntArray = byKind[kind] ?: IntArray(0)

        val isEmpty: Boolean get() = byKind.values.all { it.isEmpty() }

        companion object {
            val EMPTY = Results(emptyMap(), emptyList())
        }
    }

    fun search(query: String, limit: Int = DEFAULT_LIMIT): Results {
        val words = words(query)
        if (words.isEmpty()) return Results.EMPTY
        val typing = !query.endsWith(' ')
        // The query as a whole title, stop words kept, for the exact-title boost.
        val whole = indexed(TextNormalizer.normalize(query))
        for (required in words.size downTo maxOf(1, words.size - 1)) {
            val results = searchAll(words, typing, whole, required, limit)
            if (!results.isEmpty) return results
        }
        return Results.EMPTY
    }

    private fun searchAll(words: List<String>, typing: Boolean, whole: String, required: Int, limit: Int): Results {
        val byKind = LinkedHashMap<Kind, IntArray>()
        val best = HashMap<Kind, Float>()
        val exact = HashSet<Kind>()
        for (kind in Kind.entries) {
            val query = BooleanQuery.Builder()
                .add(TermQuery(Term(KIND, kind.tag)), Occur.FILTER)
                .add(BoostQuery(TermQuery(Term(EXACT, whole)), EXACT_BOOST), Occur.SHOULD)
            for ((i, word) in words.withIndex()) query.add(word(kind, word, typing && i == words.lastIndex), Occur.SHOULD)
            // The exact-title clause is optional; the word clauses carry the minimum.
            val built = query.setMinimumNumberShouldMatch(required).build()
            val hits = searcher.search(built, limit).scoreDocs
            if (hits.isEmpty()) continue
            byKind[kind] = IntArray(hits.size) { searcher.doc(hits[it].doc).getField(INDEX).numericValue().toInt() }
            best[kind] = hits[0].score
            if (searcher.count(BooleanQuery.Builder().add(TermQuery(Term(KIND, kind.tag)), Occur.FILTER).add(TermQuery(Term(EXACT, whole)), Occur.FILTER).build()) > 0) {
                exact += kind
            }
        }
        // Kinds with an entry named exactly as the query lead, artists first; then by best score,
        // since an album's title and artist can both match where an artist has one field.
        val order = byKind.keys.sortedWith(
            compareBy<Kind> { if (it in exact) PRIORITY.indexOf(it) else PRIORITY.size }.thenByDescending { best[it] }.thenBy { PRIORITY.indexOf(it) },
        )
        return Results(byKind, order)
    }

    /** One query word against [kind]'s fields: the best of exact, prefix, initials and typo matches. */
    private fun word(kind: Kind, word: String, typing: Boolean): Query {
        val alternatives = ArrayList<Query>()
        val budget = typoBudget(word.length)
        for ((field, weight) in kind.fields) {
            alternatives += BoostQuery(TermQuery(Term(field, word)), weight * EXACT_WORD)
            if (typing) alternatives += BoostQuery(PrefixQuery(Term(field, word)), weight * PREFIX_WORD)
            if (typing && Hangul.isInitials(word)) alternatives += BoostQuery(PrefixQuery(Term(field + INITIALS, word)), weight * PREFIX_WORD)
            if (budget > 0) {
                alternatives += BoostQuery(
                    FuzzyQuery(Term(field, word), budget, /* prefixLength= */ 1, MAX_EXPANSIONS, /* transpositions= */ true),
                    weight * TYPO_WORD,
                )
            }
        }
        return DisjunctionMaxQuery(alternatives, TIE_BREAKER)
    }

    override fun close() = reader.close()

    companion object {
        const val DEFAULT_LIMIT = 50

        private const val KIND = "kind"
        private const val INDEX = "index"
        private const val TITLE_FIELD = "title"
        private const val ARTIST_FIELD = "artist"
        private const val ALBUM_FIELD = "album"
        private const val EXACT = "exact"
        private const val INITIALS = "_initials"

        private const val EXACT_BOOST = 10f
        private const val EXACT_WORD = 2f
        private const val PREFIX_WORD = 1.5f
        private const val TYPO_WORD = 1f
        private const val TIE_BREAKER = 0.1f
        private const val MAX_EXPANSIONS = 50

        /** Order of kinds whose best matches score equal. */
        private val PRIORITY = listOf(Kind.ARTIST, Kind.ALBUM, Kind.PLAYLIST, Kind.SONG, Kind.GENRE)

        /** Typos allowed in a word of [length] letters, as Meilisearch's defaults: none below 5, one below 9. */
        internal fun typoBudget(length: Int): Int = when {
            length < 5 -> 0
            length < 9 -> 1
            else -> 2
        }

        fun build(library: LoadedLibrary, playlists: List<Playlist>): LibrarySearch {
            val s = library.snapshot
            val keys = library.search
            val directory = ByteBuffersDirectory()
            IndexWriter(directory, IndexWriterConfig(WhitespaceAnalyzer()).setOpenMode(IndexWriterConfig.OpenMode.CREATE)).use { writer ->
                fun add(kind: Kind, index: Int, title: String, artist: String? = null, album: String? = null) {
                    val doc = Document()
                    doc.add(StringField(KIND, kind.tag, Field.Store.NO))
                    doc.add(StoredField(INDEX, index))
                    doc.add(StringField(EXACT, indexed(title), Field.Store.NO))
                    text(doc, TITLE_FIELD, title)
                    artist?.let { text(doc, ARTIST_FIELD, it) }
                    album?.let { text(doc, ALBUM_FIELD, it) }
                    writer.addDocument(doc)
                }
                for (t in 0 until s.trackCount) {
                    add(Kind.SONG, t, keys.tracks.keys[t].text, keys.artists.keys[s.trackArtist[t]].text, keys.albums.keys[s.trackAlbum[t]].text)
                }
                for (a in 0 until s.albumCount) add(Kind.ALBUM, a, keys.albums.keys[a].text, keys.artists.keys[s.albumArtist[a]].text)
                for (r in 0 until s.artistCount) add(Kind.ARTIST, r, keys.artists.keys[r].text)
                for (g in 0 until s.genreCount) add(Kind.GENRE, g, keys.genres.keys[g].text)
                for ((i, p) in playlists.withIndex()) add(Kind.PLAYLIST, i, TextNormalizer.normalize(p.name))
            }
            val reader = DirectoryReader.open(directory)
            return LibrarySearch(reader, IndexSearcher(reader))
        }

        /** A normalized field as indexed words, with Hangul words also by their initials. */
        private fun text(doc: Document, field: String, normalized: String) {
            doc.add(TextField(field, indexed(normalized), Field.Store.NO))
            val initials = TextNormalizer.tokens(normalized).mapNotNull(Hangul::initials)
            if (initials.isNotEmpty()) doc.add(TextField(field + INITIALS, initials.joinToString(" "), Field.Store.NO))
        }

        private fun indexed(normalized: String): String = TextNormalizer.tokens(normalized).joinToString(" ", transform = Hangul::letters)

        /** Query words in index form; stop words are dropped unless they are all there is or still being typed. */
        private fun words(query: String): List<String> {
            val tokens = TextNormalizer.tokens(TextNormalizer.normalize(query)).map(Hangul::letters)
            val kept = tokens.filterIndexed { i, word -> i == tokens.lastIndex || word !in STOP_WORDS }
            return kept.ifEmpty { tokens }.take(MAX_WORDS)
        }

        private val STOP_WORDS = setOf("the", "a", "an", "of", "and")
        private const val MAX_WORDS = 8
    }
}
