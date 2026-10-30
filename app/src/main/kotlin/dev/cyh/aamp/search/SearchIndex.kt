package dev.cyh.aamp.search

/** Match keys and token postings for albums, artists and tracks, by snapshot index. */
class SearchIndex(val albums: KindIndex, val artists: KindIndex, val tracks: KindIndex)

/** One scored entry: a snapshot index and its score. */
data class Hit(val index: Int, val score: Float)

/**
 * Index over one kind of entry. A query is scored first against the entries that share a token
 * with it; only when none of those is a strong match does it fall back to scoring every entry,
 * which is what catches misrecognized words.
 */
class KindIndex private constructor(
    val keys: Array<MatchKey>,
    private val postings: Map<String, IntArray>,
) {
    val size: Int get() = keys.size

    /**
     * Calls [onScore] for each entry scored against [query]. Entries are skipped only when they
     * share no token with the query and a token candidate already matched strongly.
     */
    fun scan(query: MatchKey, onScore: (index: Int, score: Float) -> Unit) {
        if (query.isEmpty || keys.isEmpty()) return
        val seen = BooleanArray(keys.size)
        var strong = false
        for (token in query.tokens) {
            val list = postings[token] ?: continue
            for (i in list) {
                if (seen[i]) continue
                seen[i] = true
                val score = Similarity.score(query, keys[i])
                if (score >= STRONG_MATCH) strong = true
                onScore(i, score)
            }
        }
        if (strong) return
        for (i in keys.indices) {
            if (!seen[i]) onScore(i, Similarity.score(query, keys[i]))
        }
    }

    /** The best entry for [query] after [weight] adjusts each score, or null if none reaches [minScore]. */
    fun best(query: MatchKey, minScore: Float, weight: (index: Int, score: Float) -> Float = { _, s -> s }): Hit? {
        var bestIndex = -1
        var bestScore = minScore
        scan(query) { i, s ->
            if (s < minScore) return@scan
            val w = weight(i, s)
            if (w > bestScore || (bestIndex < 0 && w >= bestScore)) {
                bestIndex = i
                bestScore = w
            }
        }
        return if (bestIndex >= 0) Hit(bestIndex, bestScore) else null
    }

    /** Up to [limit] entries scoring at least [minScore], best first. */
    fun top(query: MatchKey, limit: Int, minScore: Float): List<Hit> {
        if (limit <= 0) return emptyList()
        val hits = ArrayList<Hit>(limit + 1)
        scan(query) { i, s ->
            if (s < minScore) return@scan
            if (hits.size == limit && s <= hits.last().score) return@scan
            var at = hits.size
            while (at > 0 && hits[at - 1].score < s) at--
            hits.add(at, Hit(i, s))
            if (hits.size > limit) hits.removeAt(hits.size - 1)
        }
        return hits
    }

    companion object {
        /** A score at or above this from the token candidates skips the full fuzzy scan. */
        const val STRONG_MATCH = 0.9f

        fun build(keys: Array<MatchKey>): KindIndex {
            val lists = HashMap<String, IntList>()
            for ((i, key) in keys.withIndex()) {
                for (token in key.tokens) {
                    val list = lists.getOrPut(token) { IntList() }
                    // Tokens can repeat within one key; postings stay distinct.
                    if (list.lastOrMinusOne() != i) list.add(i)
                }
            }
            val postings = HashMap<String, IntArray>(lists.size * 2)
            for ((token, list) in lists) postings[token] = list.toArray()
            return KindIndex(keys, postings)
        }
    }
}

/** Growable IntArray for building postings without boxing. */
private class IntList {
    private var data = IntArray(4)
    private var size = 0

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }

    fun lastOrMinusOne(): Int = if (size == 0) -1 else data[size - 1]

    fun toArray(): IntArray = data.copyOf(size)
}
