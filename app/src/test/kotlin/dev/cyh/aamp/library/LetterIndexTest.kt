package dev.cyh.aamp.library

import dev.cyh.aamp.TestLibrary
import dev.cyh.aamp.TestLibrary.album
import dev.cyh.aamp.search.TextNormalizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LetterIndexTest {
    private fun label(text: String) = LetterIndex.labelOf(TextNormalizer.normalize(text))

    @Test
    fun `latin titles file under their first letter after normalization`() {
        assertEquals("A", label("...And Justice for All"))
        assertEquals("B", label("The Bends"))
        assertEquals("E", label("Élan"))
        assertEquals("K", label("kill 'em all"))
    }

    @Test
    fun `digits file under hash`() {
        assertEquals("#", label("1999"))
        assertEquals("#", label(""))
    }

    @Test
    fun `hangul files under its initial consonant with tense ones folded`() {
        assertEquals("ㅂ", label("방탄소년단"))
        assertEquals("ㄱ", label("까치"))
        assertEquals("ㅎ", label("혁오"))
    }

    @Test
    fun `other scripts file under their uppercased first letter`() {
        assertEquals("К", label("кино"))
        assertEquals("ガ", label("ガンダム"))
    }

    @Test
    fun `sections are a to z, then other scripts, then hash`() {
        val keys = listOf("zebra", "1999", "방탄", "apple", "кино", "banana", "가요").map(TextNormalizer::normalize)
        val index = LetterIndex.build(IntArray(keys.size) { it }) { keys[it] }
        assertEquals(listOf("A", "B", "Z", "К", "ㄱ", "ㅂ", "#"), index.labels.toList())
        assertArrayEquals(intArrayOf(3), index.members(index.sectionOf("A")))
        assertEquals(-1, index.sectionOf("Q"))
    }

    @Test
    fun `library sections and recently added`() {
        val library = TestLibrary.build()
        val s = library.snapshot
        assertEquals(listOf("A", "B", "D", "G", "K", "L", "M", "N", "R"), s.albumLetters.labels.toList())
        val a = s.albumLetters.members(s.albumLetters.sectionOf("A")).map { s.albumTitles[it] }
        assertEquals(listOf("Ace of Spades", "...And Justice for All"), a)
        assertEquals(s.trackCount, (0 until s.trackLetters.size).sumOf { s.trackLetters.members(it).size })
        assertEquals(library.album("Band on the Run"), s.albumsByRecent.first())
        assertEquals(library.album("Kill 'Em All"), s.albumsByRecent.last())
    }
}
