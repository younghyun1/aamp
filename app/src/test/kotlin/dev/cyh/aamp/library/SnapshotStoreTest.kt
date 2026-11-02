package dev.cyh.aamp.library

import dev.cyh.aamp.TestLibrary
import dev.cyh.aamp.voice.PlayRequest
import dev.cyh.aamp.voice.QueueResolver
import dev.cyh.aamp.voice.RequestKind
import dev.cyh.aamp.voice.Resolution
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class SnapshotStoreTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val signature = MediaStoreSignature("version-1", mapOf("external_primary" to 42L, "abcd-1234" to 7L))

    @Test
    fun `the snapshot round trips with its signature`() {
        val library = TestLibrary.build()
        val store = SnapshotStore(dir.root.resolve("library.bin"))
        assertTrue(store.write(signature, library))
        val saved = store.read()
        assertEquals(signature, saved?.signature)
        val a = library.snapshot
        val b = checkNotNull(saved).library.snapshot

        assertArrayEquals(a.trackIds, b.trackIds)
        assertArrayEquals(a.trackTitles, b.trackTitles)
        assertArrayEquals(a.trackArtist, b.trackArtist)
        assertArrayEquals(a.trackAlbum, b.trackAlbum)
        assertArrayEquals(a.trackDurationMs, b.trackDurationMs)
        assertArrayEquals(a.trackGenre, b.trackGenre)
        assertArrayEquals(a.albumTitles, b.albumTitles)
        assertArrayEquals(a.albumFirstTrack, b.albumFirstTrack)
        assertArrayEquals(a.artistNames, b.artistNames)
        assertArrayEquals(a.artistKeys, b.artistKeys)
        assertArrayEquals(a.genreNames, b.genreNames)
        assertArrayEquals(a.tracksByTitle, b.tracksByTitle)
        assertArrayEquals(a.albumsByRecent, b.albumsByRecent)
        for (r in 0 until a.artistCount) {
            assertArrayEquals(a.artistAlbums(r), b.artistAlbums(r))
            assertArrayEquals(a.artistTracks(r), b.artistTracks(r))
        }
        for (g in 0 until a.genreCount) assertArrayEquals(a.genreTracks(g), b.genreTracks(g))
        assertArrayEquals(a.albumLetters.labels, b.albumLetters.labels)
        for (i in 0 until a.trackLetters.size) assertArrayEquals(a.trackLetters.members(i), b.trackLetters.members(i))
        for (t in 0 until a.trackCount) assertEquals(t, b.trackIndex(a.trackIds[t]))

        // The restored search keys match as the built ones do.
        val built = QueueResolver(library, Random(1))
        val restored = QueueResolver(checkNotNull(saved).library, Random(1))
        for (phrase in listOf("and just for all my intelligents", "kill em all from metallica", "metallicas master of puppets", "rock music")) {
            val x = built.resolve(PlayRequest.Search(RequestKind.ANY, phrase, null, false)) as Resolution.Queue
            val y = restored.resolve(PlayRequest.Search(RequestKind.ANY, phrase, null, false)) as Resolution.Queue
            assertEquals(phrase, x.summary, y.summary)
            assertArrayEquals(x.tracks, y.tracks)
        }
    }

    @Test
    fun `missing, foreign and truncated files read as nothing`() {
        assertNull(SnapshotStore(dir.root.resolve("absent.bin")).read())
        val foreign = dir.newFile("foreign.bin").apply { writeBytes(ByteArray(64) { 7 }) }
        assertNull(SnapshotStore(foreign).read())
        val file = dir.root.resolve("truncated.bin")
        SnapshotStore(file).write(signature, TestLibrary.build())
        file.writeBytes(file.readBytes().copyOf(file.length().toInt() / 2))
        assertNull(SnapshotStore(file).read())
    }
}
