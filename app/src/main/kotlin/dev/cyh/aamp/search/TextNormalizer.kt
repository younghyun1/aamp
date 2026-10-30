package dev.cyh.aamp.search

import java.text.Normalizer
import java.util.Locale

/**
 * Reduces titles, names and recognized speech to one comparable form.
 *
 * Library text and spoken queries both pass through here, so case, accents, punctuation,
 * bracketed edition suffixes and a leading "the" never decide a match:
 * "...And Justice for All" and "and justice for all" both become `and justice for all`,
 * "Kill 'Em All" becomes `kill em all`, and "Motörhead" becomes `motorhead`.
 */
object TextNormalizer {
    // Bracketed suffixes that name an edition rather than a different work, e.g. "(2018 Remaster)".
    private val editionSuffix =
        Regex("""[(\[][^)\]]*\b(?:remaster(?:ed)?|deluxe|edition|version|anniversary|expanded|bonus)\b[^)\]]*[)\]]""")
    // Only the combining diacritical blocks used for accents on Latin, Greek and Cyrillic letters.
    // Other nonspacing marks carry meaning (Japanese voicing marks, Thai and Devanagari vowels).
    private val combiningMarks = Regex("""[̀-ͯ᪰-᫿᷀-᷿⃐-⃿︠-︯]+""")

    /** Words only: lowercase, no accents or punctuation, single spaces. Keeps a leading "the". */
    fun words(text: String): String {
        if (text.isBlank()) return ""
        val lowered = text.lowercase(Locale.ROOT)
        val withoutEditions = editionSuffix.replace(lowered, " ")
        // NFKD splits accented letters into base letter plus combining mark and folds compatibility
        // forms (full-width letters, ligatures); NFC afterwards recomposes Hangul syllables.
        val decomposed = Normalizer.normalize(withoutEditions, Normalizer.Form.NFKD)
        val composed = Normalizer.normalize(combiningMarks.replace(decomposed, ""), Normalizer.Form.NFC)

        val out = StringBuilder(composed.length)
        var pendingSpace = false
        var i = 0
        while (i < composed.length) {
            val cp = composed.codePointAt(i)
            i += Character.charCount(cp)
            when {
                Character.isLetterOrDigit(cp) -> {
                    if (pendingSpace && out.isNotEmpty()) out.append(' ')
                    pendingSpace = false
                    out.appendCodePoint(cp)
                }
                // Remaining marks (Thai and Devanagari vowel signs, for example) belong to the
                // letter before them.
                isMark(cp) -> if (!pendingSpace && out.isNotEmpty()) out.appendCodePoint(cp)
                // Apostrophes join their word: "Kill 'Em" and "kill em" must agree.
                cp == '\''.code || cp == '’'.code || cp == '‘'.code || cp == '`'.code || cp == 'ʼ'.code -> Unit
                cp == '&'.code || cp == '+'.code -> {
                    if (out.isNotEmpty()) out.append(' ')
                    out.append("and")
                    pendingSpace = true
                }
                else -> pendingSpace = true
            }
        }
        return out.toString()
    }

    /** The matching key: [words] without a leading "the", unless "the" is all there is. */
    fun normalize(text: String): String {
        val w = words(text)
        return if (w.startsWith("the ") && w.length > 4) w.substring(4) else w
    }

    /**
     * Splits normalized text into match tokens. Words in scripts written without spaces
     * (Han, Hiragana, Katakana) become overlapping character bigrams, so a partial name still
     * shares tokens with the full one.
     */
    fun tokens(normalized: String): Array<String> {
        if (normalized.isEmpty()) return emptyArray()
        val out = ArrayList<String>()
        for (word in normalized.split(' ')) {
            if (word.isEmpty()) continue
            if (!word.codePoints().anyMatch(::isUnspacedScript)) {
                out += word
                continue
            }
            val cps = word.codePoints().toArray()
            if (cps.size == 1) {
                out += word
            } else {
                for (j in 0 until cps.size - 1) out += String(cps, j, 2)
            }
        }
        return out.toTypedArray()
    }

    private fun isMark(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    private fun isUnspacedScript(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA -> true
        else -> false
    }
}
