package dev.cyh.aamp.library

import dev.cyh.aamp.TestLibrary
import dev.cyh.aamp.TestLibrary.track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class PlaylistsTest {
    @get:Rule
    val dir = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun stop() = scope.cancel()

    @Test
    fun `playlists round trip through the store`() {
        val store = PlaylistStore(dir.root.resolve("playlists.bin"))
        val lists = listOf(Playlist(1, "Road Trip", longArrayOf(101, 102, 101)), Playlist(-7, "빈 목록", LongArray(0)))
        assertTrue(store.write(lists))
        val read = store.read()
        assertEquals(listOf(1L, -7L), read.map { it.id })
        assertEquals(listOf("Road Trip", "빈 목록"), read.map { it.name })
        assertArrayEquals(longArrayOf(101, 102, 101), read[0].trackIds)
    }

    @Test
    fun `missing and foreign files read as no playlists`() {
        assertEquals(emptyList<Playlist>(), PlaylistStore(dir.root.resolve("absent.bin")).read())
        val foreign = dir.newFile("foreign.bin").apply { writeBytes(byteArrayOf(9, 9, 9, 9, 0, 0, 0, 1)) }
        assertEquals(emptyList<Playlist>(), PlaylistStore(foreign).read())
    }

    @Test
    fun `edits apply in order and persist`() = runBlocking {
        val file = dir.root.resolve("playlists.bin")
        val playlists = Playlists(PlaylistStore(file), scope, Random(1))
        val id = playlists.create("  Road Trip  ", longArrayOf(1, 2))
        playlists.add(id, longArrayOf(3, 2))
        playlists.removeAt(id, 1)
        playlists.create("   ") // refused: blank
        val other = playlists.create("Gone")
        playlists.delete(other)
        playlists.idle()
        val expected = longArrayOf(1, 3, 2)
        assertEquals(listOf("Road Trip"), playlists.all.value.map { it.name })
        assertArrayEquals(expected, playlists.all.value.single().trackIds)
        // A new instance reads what the first wrote.
        val reloaded = Playlists(PlaylistStore(file), scope).await()
        assertArrayEquals(expected, reloaded.single().trackIds)
    }

    @Test
    fun `songs missing from the library are skipped`() {
        val library = TestLibrary.build()
        val s = library.snapshot
        val one = library.track("One")
        val playlist = Playlist(1, "Mix", longArrayOf(s.trackIds[one], 999_999, s.trackIds[one]))
        assertArrayEquals(intArrayOf(one, one), playlist.tracksIn(s))
    }
}
