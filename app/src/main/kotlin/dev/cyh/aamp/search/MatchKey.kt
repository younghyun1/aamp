package dev.cyh.aamp.search

/**
 * Precomputed matching form of one title or name: normalized text, tokens, and sorted distinct
 * character trigrams for fuzzy comparison. Built once per library entry and once per query.
 */
class MatchKey private constructor(
    val text: String,
    val tokens: Array<String>,
    val trigrams: LongArray,
) {
    val isEmpty: Boolean get() = text.isEmpty()

    override fun toString(): String = text

    companion object {
        val EMPTY = MatchKey("", emptyArray(), LongArray(0))

        fun of(raw: String): MatchKey = ofNormalized(TextNormalizer.normalize(raw))

        /** A key saved earlier: its normalized text and trigrams, so neither is computed again. */
        internal fun restore(normalized: String, trigrams: LongArray): MatchKey =
            if (normalized.isEmpty()) EMPTY else MatchKey(normalized, TextNormalizer.tokens(normalized), trigrams)

        fun ofNormalized(normalized: String): MatchKey =
            if (normalized.isEmpty()) EMPTY
            else MatchKey(normalized, TextNormalizer.tokens(normalized), trigramsOf(normalized))

        /**
         * Trigrams over the text padded with two leading spaces and one trailing space, so word
         * starts weigh more than word ends. Each trigram packs three UTF-16 units into a Long.
         */
        private fun trigramsOf(text: String): LongArray {
            val padded = "  $text "
            val grams = LongArray(padded.length - 2) { i ->
                (padded[i].code.toLong() shl 32) or (padded[i + 1].code.toLong() shl 16) or padded[i + 2].code.toLong()
            }
            grams.sort()
            var distinct = 0
            for (i in grams.indices) {
                if (i == 0 || grams[i] != grams[i - 1]) grams[distinct++] = grams[i]
            }
            return grams.copyOf(distinct)
        }
    }
}
