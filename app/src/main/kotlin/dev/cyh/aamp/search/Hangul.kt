package dev.cyh.aamp.search

/**
 * Hangul for search as you type. Korean keyboards compose a syllable letter by letter, so while
 * typing "메탈" the field holds "메타" and then "메탈"; compared as syllables, "메타" is not a
 * prefix of "메탈리카". Compared as basic letters (jamo), "ㅁㅔㅌㅏ" is a prefix of "ㅁㅔㅌㅏㄹㄹㅣㅋㅏ".
 * Compound vowels and final clusters are split too ("와" is ㅇㅗㅏ), so every keystroke extends
 * the previous form. Typing only initial consonants ("ㅁㅌㄹㅋ") is a common Korean shorthand;
 * [initials] gives that form of a word. Unicode normalization turns a lone typed letter (ㄱ,
 * U+3131) into its conjoining form (U+1100); both forms map to the same letters here.
 */
object Hangul {
    private const val FIRST = 0xAC00
    private const val LAST = 0xD7A3
    private const val VOWELS = 21
    private const val FINALS = 28

    private const val LEADS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private val VOWEL_JAMO = arrayOf(
        "ㅏ", "ㅐ", "ㅑ", "ㅒ", "ㅓ", "ㅔ", "ㅕ", "ㅖ", "ㅗ", "ㅗㅏ", "ㅗㅐ", "ㅗㅣ", "ㅛ", "ㅜ", "ㅜㅓ", "ㅜㅔ", "ㅜㅣ", "ㅠ", "ㅡ", "ㅡㅣ", "ㅣ",
    )
    private val FINAL_JAMO = arrayOf(
        "", "ㄱ", "ㄲ", "ㄱㅅ", "ㄴ", "ㄴㅈ", "ㄴㅎ", "ㄷ", "ㄹ", "ㄹㄱ", "ㄹㅁ", "ㄹㅂ", "ㄹㅅ", "ㄹㅌ", "ㄹㅍ", "ㄹㅎ",
        "ㅁ", "ㅂ", "ㅂㅅ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ",
    )

    /** Standalone compound letters a keyboard can produce, split the same way. */
    private val COMPOUND = mapOf(
        'ㅘ' to "ㅗㅏ", 'ㅙ' to "ㅗㅐ", 'ㅚ' to "ㅗㅣ", 'ㅝ' to "ㅜㅓ", 'ㅞ' to "ㅜㅔ", 'ㅟ' to "ㅜㅣ", 'ㅢ' to "ㅡㅣ",
        'ㄳ' to "ㄱㅅ", 'ㄵ' to "ㄴㅈ", 'ㄶ' to "ㄴㅎ", 'ㄺ' to "ㄹㄱ", 'ㄻ' to "ㄹㅁ", 'ㄼ' to "ㄹㅂ", 'ㄽ' to "ㄹㅅ",
        'ㄾ' to "ㄹㅌ", 'ㄿ' to "ㄹㅍ", 'ㅀ' to "ㄹㅎ", 'ㅄ' to "ㅂㅅ",
    )

    // Conjoining jamo, which NFKD produces from syllables and from lone letters.
    private const val CONJOINING_LEAD = 0x1100
    private const val CONJOINING_VOWEL = 0x1161
    private const val CONJOINING_FINAL = 0x11A8

    fun isSyllable(c: Char): Boolean = c.code in FIRST..LAST

    private fun conjoining(c: Char): String? = when (c.code) {
        in CONJOINING_LEAD until CONJOINING_LEAD + LEADS.length -> LEADS[c.code - CONJOINING_LEAD].toString()
        in CONJOINING_VOWEL until CONJOINING_VOWEL + VOWEL_JAMO.size -> VOWEL_JAMO[c.code - CONJOINING_VOWEL]
        in CONJOINING_FINAL until CONJOINING_FINAL + FINAL_JAMO.size - 1 -> FINAL_JAMO[c.code - CONJOINING_FINAL + 1]
        else -> null
    }

    /** True when [word] is only initial consonants, the shorthand [initials] answers. */
    fun isInitials(word: String): Boolean = word.isNotEmpty() && word.all { it in LEADS }

    /** [word] with Hangul syllables and compound letters split into basic letters; other characters kept. */
    fun letters(word: String): String {
        if (word.none { isSyllable(it) || it in COMPOUND || conjoining(it) != null }) return word
        val out = StringBuilder(word.length * 3)
        for (c in word) {
            if (isSyllable(c)) {
                val s = c.code - FIRST
                out.append(LEADS[s / (VOWELS * FINALS)])
                out.append(VOWEL_JAMO[s % (VOWELS * FINALS) / FINALS])
                out.append(FINAL_JAMO[s % FINALS])
            } else {
                out.append(COMPOUND[c] ?: conjoining(c) ?: c.toString())
            }
        }
        return out.toString()
    }

    /** The initial consonants of a word made of Hangul syllables ("메탈리카" is "ㅁㅌㄹㅋ"); null for other words. */
    fun initials(word: String): String? {
        if (word.isEmpty() || !word.all(::isSyllable)) return null
        return buildString(word.length) { for (c in word) append(LEADS[(c.code - FIRST) / (VOWELS * FINALS)]) }
    }
}
