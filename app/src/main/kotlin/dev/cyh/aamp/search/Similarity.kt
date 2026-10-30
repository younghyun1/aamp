package dev.cyh.aamp.search

import kotlin.math.max
import kotlin.math.min

/**
 * Scores how well a query names a library entry, from 0 (unrelated) to 1 (identical after
 * normalization). The strongest applicable signal wins:
 *
 * - identical text: 1.0
 * - the whole query as a run of words inside the entry, as when typing a partial name:
 *   0.6 to 0.9, by how much of the entry it covers
 * - word alignment: the entry's words matched in order against the query's, allowing partial
 *   words ("just" for "justice") and near misses ("metalica" for "metallica"), scored as the
 *   balance (F1) between how much of the entry and how much of the query is matched: up to 0.95.
 *   A one-word title inside a six-word query matches little of the query and scores low; a full
 *   album title followed by a garbled artist name scores high.
 * - shared character trigrams: up to 0.8, for a misrecognized single word
 *
 * Recognized speech is unreliable: Gemini turned "...And Justice for All, Metallica" into
 * "and just for all my intelligents", which must still find the album.
 */
object Similarity {
    fun score(query: MatchKey, target: MatchKey): Float {
        if (query.isEmpty || target.isEmpty) return 0f
        if (query.text == target.text) return 1f

        var best = 0f
        val q = query.tokens
        val t = target.tokens
        if (containsRun(t, q)) best = 0.6f + 0.3f * q.size / t.size
        best = max(best, 0.95f * alignmentF1(q, t))
        best = max(best, 0.8f * sortedDice(query.trigrams, target.trigrams))
        return best
    }

    /** True when [needle] appears as a contiguous run of tokens inside [haystack]. */
    private fun containsRun(haystack: Array<String>, needle: Array<String>): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (start in 0..haystack.size - needle.size) {
            for (k in needle.indices) {
                if (haystack[start + k] != needle[k]) continue@outer
            }
            return true
        }
        return false
    }

    /**
     * Best in-order alignment of the two token sequences (a weighted longest common subsequence),
     * as F1 of the matched weight over each side's length.
     */
    private fun alignmentF1(q: Array<String>, t: Array<String>): Float {
        if (q.isEmpty() || t.isEmpty()) return 0f
        val cols = t.size + 1
        val dp = FloatArray((q.size + 1) * cols)
        for (i in 1..q.size) {
            for (j in 1..t.size) {
                val diagonal = dp[(i - 1) * cols + j - 1] + tokenSimilarity(q[i - 1], t[j - 1])
                dp[i * cols + j] = max(diagonal, max(dp[(i - 1) * cols + j], dp[i * cols + j - 1]))
            }
        }
        val matched = dp[q.size * cols + t.size]
        if (matched <= 0f) return 0f
        val precision = matched / q.size
        val recall = matched / t.size
        return 2f * precision * recall / (precision + recall)
    }

    /**
     * 1 for equal words, 0.85 when one is a prefix of the other (three letters at least), the
     * edit-distance ratio for near misses of four letters or more when it is at least 0.75, else 0.
     */
    internal fun tokenSimilarity(a: String, b: String): Float {
        if (a == b) return 1f
        val shorter = min(a.length, b.length)
        if (shorter >= 3 && (a.startsWith(b) || b.startsWith(a))) return PREFIX_SIMILARITY
        val longer = max(a.length, b.length)
        if (shorter < 4 || longer - shorter > 2) return 0f
        val ratio = 1f - editDistance(a, b).toFloat() / longer
        return if (ratio >= MIN_NEAR_MISS) ratio else 0f
    }

    /** Levenshtein distance with two rolling rows. */
    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(substitution, min(previous[j] + 1, current[j - 1] + 1))
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /** Dice coefficient of two sorted, distinct arrays, by merge. */
    private fun sortedDice(a: LongArray, b: LongArray): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        var i = 0
        var j = 0
        var common = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { common++; i++; j++ }
                a[i] < b[j] -> i++
                else -> j++
            }
        }
        return 2f * common / (a.size + b.size)
    }

    private const val PREFIX_SIMILARITY = 0.85f
    private const val MIN_NEAR_MISS = 0.75f
}
