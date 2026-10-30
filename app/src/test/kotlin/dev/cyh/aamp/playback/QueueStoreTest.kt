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
}
