package dev.cyh.aamp.search

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {
    @Test
    fun `leading dots and case disappear`() {
        assertEquals("and justice for all", TextNormalizer.normalize("...And Justice for All"))
    }

    @Test
    fun `apostrophes join their word`() {
        assertEquals("kill em all", TextNormalizer.normalize("Kill 'Em All"))
        assertEquals("dont play that song", TextNormalizer.normalize("Don’t Play That Song!"))
    }

    @Test
    fun `latin accents are stripped`() {
        assertEquals("motorhead", TextNormalizer.normalize("Motörhead"))
        assertEquals("beyonce", TextNormalizer.normalize("Beyoncé"))
    }

    @Test
    fun `ampersand and plus read as and`() {
        assertEquals("simon and garfunkel", TextNormalizer.normalize("Simon & Garfunkel"))
        assertEquals("seek and destroy", TextNormalizer.normalize("Seek&Destroy"))
        assertEquals("guns and roses", TextNormalizer.normalize("Guns + Roses"))
    }

    @Test
    fun `edition suffixes are ignored but other brackets stay`() {
        assertEquals("master of puppets", TextNormalizer.normalize("Master of Puppets (Remastered)"))
        assertEquals("ride the lightning", TextNormalizer.normalize("Ride the Lightning [Deluxe Box Set]"))
        assertEquals("one live", TextNormalizer.normalize("One (Live)"))
    }

    @Test
    fun `leading the is optional`() {
        assertEquals("beatles", TextNormalizer.normalize("The Beatles"))
        assertEquals("the beatles", TextNormalizer.words("The Beatles"))
        assertEquals("the", TextNormalizer.normalize("The"))
    }

    @Test
    fun `compatibility forms fold`() {
        assertEquals("abc", TextNormalizer.normalize("ＡＢＣ"))
    }

    @Test
    fun `non latin text keeps its marks and composition`() {
        assertEquals("방탄소년단", TextNormalizer.normalize("방탄소년단"))
        assertEquals("ガンダム", TextNormalizer.normalize("ガンダム"))
        assertEquals("สวัสดี", TextNormalizer.normalize("สวัสดี"))
    }

    @Test
    fun `unspaced scripts tokenize as bigrams`() {
        assertArrayEquals(arrayOf("東京", "京事", "事変"), TextNormalizer.tokens("東京事変"))
        assertArrayEquals(arrayOf("and", "justice"), TextNormalizer.tokens("and justice"))
    }

    @Test
    fun `misrecognized words still score as a match`() {
        val score = Similarity.score(MatchKey.of("metalica"), MatchKey.of("Metallica"))
        assertTrue("score $score", score >= 0.6f)
    }

    @Test
    fun `a partial title scores below a full one`() {
        val partial = Similarity.score(MatchKey.of("justice"), MatchKey.of("...And Justice for All"))
        val full = Similarity.score(MatchKey.of("and justice for all"), MatchKey.of("...And Justice for All"))
        assertEquals(1f, full)
        assertTrue("partial $partial", partial in 0.6f..0.9f)
    }
}
