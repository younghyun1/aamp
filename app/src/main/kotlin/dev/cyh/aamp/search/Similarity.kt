package dev.cyh.aamp.search

import kotlin.math.max
import kotlin.math.min

/**
 * Scores how well a query names a library entry, from 0 (unrelated) to 1 (identical after
 * normalization). The strongest applicable signal wins:
 *
 * - identical text: 1.0
 * - one token sequence contained in the other: 0.6 to 0.9, by how much of the longer one it covers
 * - shared tokens (Dice coefficient): up to 0.85
 * - shared character trigrams (Dice coefficient): up to 0.8, which absorbs misrecognized words
 *   such as "metalica" for "metallica"
 */
object Similarity {
    fun score(query: MatchKey, target: MatchKey): Float {
        if (query.isEmpty || target.isEmpty) return 0f
        if (query.text == target.text) return 1f

        var best = 0f
        val q = query.tokens
        val t = target.tokens
        if (containsRun(t, q) || containsRun(q, t)) {
            best = 0.6f + 0.3f * min(q.size, t.size) / max(q.size, t.size)
        }
        best = max(best, 0.85f * tokenDice(q, t))
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

    private fun tokenDice(a: Array<String>, b: Array<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        var common = 0
        for (token in a) {
            if (token in b) common++
        }
        return 2f * min(common, b.size) / (a.size + b.size)
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
}
