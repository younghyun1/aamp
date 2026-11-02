package dev.cyh.aamp.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QueueStoreTest {
    @get:Rule
    val dir = TemporaryFolder()

    @Test
    fun `queue round trips`() {
        val store = QueueStore(dir.newFile("queue.bin"))
        val ids = listOf("track:1@album:7", "track:2@album:7", "track:3@album:7")
        store.write(QueueStore.Saved(ids, index = 1, positionMs = 42_000))
        val read = store.read()
        assertEquals(ids, read?.mediaIds)
        assertEquals(1, read?.index)
        assertEquals(42_000L, read?.positionMs)
    }

    @Test
    fun `missing, foreign and truncated files read as nothing`() {
        assertNull(QueueStore(dir.root.resolve("absent.bin")).read())
        val foreign = dir.newFile("foreign.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)) }
        assertNull(QueueStore(foreign).read())
        val file = dir.newFile("truncated.bin")
        QueueStore(file).write(QueueStore.Saved(listOf("track:1"), 0, 0))
        file.writeBytes(file.readBytes().copyOf(file.length().toInt() - 4))
        assertNull(QueueStore(file).read())
    }

    @Test
    fun `oversized queues are not saved`() {
        val store = QueueStore(dir.newFile("big.bin"))
        assertFalse(store.write(QueueStore.Saved(List(QueueStore.MAX_ITEMS + 1) { "track:$it" }, 0, 0)))
    }

    @Test
    fun `the rest of a long queue round trips`() {
        val store = QueueStore(dir.newFile("rest.bin"))
        val rest = QueueStore.SavedRest(LongArray(1000) { it + 1L }, "@songs", next = 300, lastHanded = "track:300@songs")
        store.write(QueueStore.Saved(listOf("track:299@songs", "track:300@songs"), 0, 5, rest))
        val read = store.read()?.rest
        assertEquals("@songs", read?.context)
        assertEquals(300, read?.next)
        assertEquals("track:300@songs", read?.lastHanded)
        assertEquals(1000, read?.trackIds?.size)
        assertEquals(1000L, read?.trackIds?.last())
    }

    @Test
    fun `version 1 files still read, without a rest`() {
        val file = dir.newFile("v1.bin")
        java.io.DataOutputStream(file.outputStream()).use { out ->
            out.writeInt(0x41510001)
            out.writeInt(1)
            out.writeUTF("track:1@album:7")
            out.writeInt(0)
            out.writeLong(1234)
        }
        val read = QueueStore(file).read()
        assertEquals(listOf("track:1@album:7"), read?.mediaIds)
        assertEquals(1234L, read?.positionMs)
        assertNull(read?.rest)
    }
}
