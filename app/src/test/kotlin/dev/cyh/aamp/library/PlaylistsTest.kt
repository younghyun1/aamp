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

    @Test
    fun `adding only what is missing, removing a song, and change times`() = runBlocking {
        var now = 100L
        val playlists = Playlists(PlaylistStore(dir.root.resolve("p.bin")), scope, Random(1)) { now }
        val id = playlists.create("Mix", longArrayOf(1, 2))
        now = 200L
        playlists.addMissing(id, longArrayOf(2, 3, 3, 4))
        playlists.idle()
        assertArrayEquals(longArrayOf(1, 2, 3, 4), playlists.all.value.single().trackIds)
        assertEquals(200L, playlists.all.value.single().updatedMillis)
        now = 300L
        playlists.addMissing(id, longArrayOf(1)) // Nothing new: the change time stays.
        playlists.removeTrack(id, 2)
        playlists.idle()
        assertArrayEquals(longArrayOf(1, 3, 4), playlists.all.value.single().trackIds)
        assertEquals(300L, playlists.all.value.single().updatedMillis)
        assertEquals(300L, PlaylistStore(dir.root.resolve("p.bin")).read().single().updatedMillis)
    }

    @Test
    fun `version 1 playlist files still read`() {
        val file = dir.newFile("v1.bin")
        java.io.DataOutputStream(file.outputStream()).use { out ->
            out.writeInt(0x41500001)
            out.writeInt(1)
            out.writeLong(5)
            out.writeUTF("Old")
            out.writeInt(1)
            out.writeLong(42)
        }
        val read = PlaylistStore(file).read().single()
        assertEquals("Old", read.name)
        assertArrayEquals(longArrayOf(42), read.trackIds)
        assertEquals(0L, read.updatedMillis)
    }
}
