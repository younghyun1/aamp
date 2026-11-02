package dev.cyh.aamp.library

import dev.cyh.aamp.search.KindIndex
import dev.cyh.aamp.search.MatchKey
import dev.cyh.aamp.search.SearchIndex
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * The library snapshot and its search keys on disk, so a cold start needs no MediaStore scan.
 *
 * One versioned binary file, replaced atomically, headed by the [MediaStoreSignature] it was built
 * from. Reading maps the file and copies each section into its array in bulk; nothing is
 * normalized, sorted or grouped again, and search keys come back with their trigrams. Only the
 * token postings are rebuilt. A file this version did not write, or a truncated or damaged one,
 * reads as null and the library is scanned instead.
 *
 * Layout: magic, signature, then sections in a fixed order. Arrays are a length and their
 * values; strings are a length and UTF-8 bytes. The magic repeats at the end to catch truncation.
 */
class SnapshotStore(private val file: File) {
    class Saved(val signature: MediaStoreSignature, val library: LoadedLibrary)

    fun write(signature: MediaStoreSignature, library: LoadedLibrary): Boolean {
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            DataOutputStream(BufferedOutputStream(tmp.outputStream(), BUFFER)).use { out -> Writer(out).all(signature, library) }
            tmp.renameTo(file)
        } catch (_: IOException) {
            tmp.delete()
            false
        }
    }

    fun read(): Saved? {
        if (!file.isFile) return null
        return try {
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                Reader(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())).all()
            }
        } catch (_: IOException) {
            null
        } catch (_: BufferUnderflowException) {
            null
        } catch (_: IllegalArgumentException) {
            null // A length or index out of range: damaged.
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    private class Writer(private val out: DataOutputStream) {
        fun all(signature: MediaStoreSignature, library: LoadedLibrary) {
            out.writeInt(MAGIC)
            string(signature.version)
            out.writeInt(signature.generations.size)
            for ((volume, generation) in signature.generations) {
                string(volume)
                out.writeLong(generation)
            }
            val s = library.snapshot
            longs(s.trackIds); strings(s.trackTitles); ints(s.trackArtist); ints(s.trackAlbum); ints(s.trackDisc)
            ints(s.trackNumber); longs(s.trackDurationMs); ints(s.trackGenre)
            longs(s.albumIds); strings(s.albumTitles); ints(s.albumArtist); ints(s.albumYear); ints(s.albumFirstTrack)
            ints(s.albumTrackCount)
            strings(s.artistNames); longs(s.artistKeys); ints(s.artistAlbumOffsets); ints(s.artistAlbumList)
            ints(s.artistTrackOffsets); ints(s.artistTrackList)
            strings(s.genreNames); longs(s.genreKeys); ints(s.genreAlbumOffsets); ints(s.genreAlbumList)
            ints(s.genreTrackOffsets); ints(s.genreTrackList)
            ints(s.tracksByTitle); ints(s.albumsByRecent)
            letters(s.albumLetters); letters(s.artistLetters); letters(s.trackLetters)
            val search = library.search
            keys(search.albums); keys(search.artists); keys(search.tracks); keys(search.genres)
            out.writeInt(MAGIC)
        }

        private fun ints(values: IntArray) {
            out.writeInt(values.size)
            for (v in values) out.writeInt(v)
        }

        private fun longs(values: LongArray) {
            out.writeInt(values.size)
            for (v in values) out.writeLong(v)
        }

        private fun string(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
        }

        private fun strings(values: Array<String>) {
            out.writeInt(values.size)
            for (v in values) string(v)
        }

        private fun letters(index: LetterIndex) {
            strings(index.labels)
            for (members in index.sections) ints(members)
        }

        /** A kind's match keys: their normalized texts, then every key's trigrams. */
        private fun keys(index: KindIndex) {
            strings(Array(index.size) { index.keys[it].text })
            for (key in index.keys) longs(key.trigrams)
        }
    }

    private class Reader(private val buf: ByteBuffer) {
        fun all(): Saved? {
            if (buf.int != MAGIC) return null
            val version = string()
            val volumes = count()
            val generations = LinkedHashMap<String, Long>(volumes * 2)
            repeat(volumes) { generations[string()] = buf.long }
            val signature = MediaStoreSignature(version, generations)

            val trackIds = longs(); val trackTitles = strings(); val trackArtist = ints(); val trackAlbum = ints()
            val trackDisc = ints(); val trackNumber = ints(); val trackDuration = longs(); val trackGenre = ints()
            val albumIds = longs(); val albumTitles = strings(); val albumArtist = ints(); val albumYear = ints()
            val albumFirst = ints(); val albumSize = ints()
            val artistNames = strings(); val artistKeys = longs(); val artistAlbumOffsets = ints(); val artistAlbumList = ints()
            val artistTrackOffsets = ints(); val artistTrackList = ints()
            val genreNames = strings(); val genreKeys = longs(); val genreAlbumOffsets = ints(); val genreAlbumList = ints()
            val genreTrackOffsets = ints(); val genreTrackList = ints()
            val tracksByTitle = ints(); val albumsByRecent = ints()
            val albumLetters = letters(); val artistLetters = letters(); val trackLetters = letters()
            val albumKeys = keys(); val artistMatchKeys = keys(); val trackKeys = keys(); val genreMatchKeys = keys()
            if (buf.int != MAGIC) return null

            val tracks = trackIds.size
            val sameLength = listOf(trackTitles.size, trackArtist.size, trackAlbum.size, trackDisc.size, trackNumber.size,
                trackDuration.size, trackGenre.size, tracksByTitle.size, trackKeys.size).all { it == tracks } &&
                listOf(albumTitles.size, albumArtist.size, albumYear.size, albumFirst.size, albumSize.size, albumsByRecent.size,
                    albumKeys.size).all { it == albumIds.size } &&
                artistNames.size == artistKeys.size && artistMatchKeys.size == artistKeys.size &&
                artistAlbumOffsets.size == artistKeys.size + 1 && artistTrackOffsets.size == artistKeys.size + 1 &&
                genreNames.size == genreKeys.size && genreMatchKeys.size == genreKeys.size &&
                genreAlbumOffsets.size == genreKeys.size + 1 && genreTrackOffsets.size == genreKeys.size + 1
            if (!sameLength) return null

            val snapshot = LibrarySnapshot(
                trackIds = trackIds, trackTitles = trackTitles, trackArtist = trackArtist, trackAlbum = trackAlbum,
                trackDisc = trackDisc, trackNumber = trackNumber, trackDurationMs = trackDuration, trackGenre = trackGenre,
                albumIds = albumIds, albumTitles = albumTitles, albumArtist = albumArtist, albumYear = albumYear,
                albumFirstTrack = albumFirst, albumTrackCount = albumSize,
                artistNames = artistNames, artistKeys = artistKeys, artistAlbumOffsets = artistAlbumOffsets,
                artistAlbumList = artistAlbumList, artistTrackOffsets = artistTrackOffsets, artistTrackList = artistTrackList,
                genreNames = genreNames, genreKeys = genreKeys, genreAlbumOffsets = genreAlbumOffsets,
                genreAlbumList = genreAlbumList, genreTrackOffsets = genreTrackOffsets, genreTrackList = genreTrackList,
                tracksByTitle = tracksByTitle, albumsByRecent = albumsByRecent,
                albumLetters = albumLetters, artistLetters = artistLetters, trackLetters = trackLetters,
            )
            val search = SearchIndex(
                albums = KindIndex.build(albumKeys),
                artists = KindIndex.build(artistMatchKeys),
                tracks = KindIndex.build(trackKeys),
                genres = KindIndex.build(genreMatchKeys),
            )
            return Saved(signature, LoadedLibrary(snapshot, search))
        }

        /** A length that fits in what is left of the file; anything else means damage. */
        private fun count(elementBytes: Int = 1): Int {
            val n = buf.int
            require(n >= 0 && n.toLong() * elementBytes <= buf.remaining()) { "bad length $n" }
            return n
        }

        private fun ints(): IntArray {
            val n = count(Int.SIZE_BYTES)
            val values = IntArray(n)
            buf.asIntBuffer().get(values)
            buf.position(buf.position() + n * Int.SIZE_BYTES)
            return values
        }

        private fun longs(): LongArray {
            val n = count(Long.SIZE_BYTES)
            val values = LongArray(n)
            buf.asLongBuffer().get(values)
            buf.position(buf.position() + n * Long.SIZE_BYTES)
            return values
        }

        private fun string(): String {
            val n = count()
            val bytes = ByteArray(n)
            buf.get(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        private fun strings(): Array<String> = Array(count(Int.SIZE_BYTES)) { string() }

        private fun letters(): LetterIndex {
            val labels = strings()
            return LetterIndex(labels, Array(labels.size) { ints() })
        }

        private fun keys(): Array<MatchKey> {
            val texts = strings()
            return Array(texts.size) { MatchKey.restore(texts[it], longs()) }
        }
    }

    private companion object {
        /** "AL" plus format version 1. */
        const val MAGIC = 0x414C0001
        const val BUFFER = 1 shl 16
    }
}
