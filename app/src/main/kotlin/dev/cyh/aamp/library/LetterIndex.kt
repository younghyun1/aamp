package dev.cyh.aamp.library

/**
 * Alphabetical sections over entries that are already in display order. Labels are "A" to "Z"
 * for Latin letters, the initial consonant for Hangul (tense consonants folded into the plain
 * ones, as Korean dictionaries do), the uppercased first letter for other scripts, and "#" for
 * anything starting with a digit. Sections are ordered A to Z, then other scripts, then "#".
 *
 * Android Auto neither pages long lists nor shows more than a limited number of items per level
 * while driving, so large libraries are browsed one section at a time.
 */
class LetterIndex private constructor(val labels: Array<String>, private val members: Array<IntArray>) {
    val size: Int get() = labels.size

    /** Entry indices in section [section], in display order. */
    fun members(section: Int): IntArray = members[section]

    /** The section labelled [label], or -1. */
    fun sectionOf(label: String): Int = labels.indexOf(label)

    companion object {
        /** Groups [order] (entry indices in display order) by the label of each entry's normalized key. */
        fun build(order: IntArray, keyOf: (Int) -> String): LetterIndex {
            val groups = LinkedHashMap<String, MutableList<Int>>()
            for (entry in order) groups.getOrPut(labelOf(keyOf(entry))) { ArrayList() } += entry
            val labels = groups.keys.sortedWith(compareBy<String>({ rank(it) }, { it }))
            return LetterIndex(
                labels.toTypedArray(),
                Array(labels.size) { groups.getValue(labels[it]).toIntArray() },
            )
        }

        /** The section label for a normalized key (lowercase, no accents or punctuation). */
        fun labelOf(key: String): String {
            if (key.isEmpty()) return DIGITS
            val cp = key.codePointAt(0)
            return when {
                cp in 'a'.code..'z'.code -> (cp - 32).toChar().toString()
                Character.isDigit(cp) -> DIGITS
                cp in HANGUL_FIRST..HANGUL_LAST -> HANGUL_INITIALS[(cp - HANGUL_FIRST) / HANGUL_PER_INITIAL]
                Character.isLetter(cp) -> String(Character.toChars(Character.toUpperCase(cp)))
                else -> DIGITS
            }
        }

        private fun rank(label: String): Int = when {
            label.length == 1 && label[0] in 'A'..'Z' -> 0
            label == DIGITS -> 2
            else -> 1
        }

        const val DIGITS = "#"
        private const val HANGUL_FIRST = 0xAC00
        private const val HANGUL_LAST = 0xD7A3

        // Syllables per initial consonant: 21 vowels times 28 finals.
        private const val HANGUL_PER_INITIAL = 21 * 28

        // The 19 initial consonants in syllable order, with ㄲ ㄸ ㅃ ㅆ ㅉ folded into ㄱ ㄷ ㅂ ㅅ ㅈ.
        private val HANGUL_INITIALS = arrayOf(
            "ㄱ", "ㄱ", "ㄴ", "ㄷ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅂ", "ㅅ", "ㅅ", "ㅇ", "ㅈ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ",
        )
    }
}
