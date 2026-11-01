package dev.cyh.aamp.ui

import android.content.Context
import android.graphics.ImageDecoder
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.cyh.aamp.artwork.ArtworkCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Album covers for the phone screen, decoded at the size they are drawn into hardware bitmaps
 * (uploaded to the GPU once, off the Java heap) and kept in a memory cache bounded by
 * [MAX_BYTES]. The source files come from [ArtworkCache], which Android Auto shares.
 */
internal class CoverLoader(private val context: Context) {
    /** One album drawn at one size: the grid and the album page each get a sharp cover. */
    private data class Key(val albumId: Long, val sizePx: Int)

    private val cache = object : LruCache<Key, ImageBitmap>(MAX_BYTES) {
        override fun sizeOf(key: Key, value: ImageBitmap): Int = value.width * value.height * BYTES_PER_PIXEL
    }

    // A fast fling requests dozens of covers at once; a few decoders keep up without
    // starving other I/O.
    private val decoders = Dispatchers.IO.limitedParallelism(DECODERS)

    fun cached(albumId: Long, sizePx: Int): ImageBitmap? = cache.get(Key(albumId, sizePx))

    /** The cover of [albumId] scaled to at most [sizePx] on its longer side, or null when it has none. */
    suspend fun load(albumId: Long, sizePx: Int): ImageBitmap? {
        val key = Key(albumId, sizePx)
        cache.get(key)?.let { return it }
        return withContext(decoders) {
            val file = ArtworkCache.fileFor(context, albumId) ?: return@withContext null
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val longer = maxOf(info.size.width, info.size.height)
                    if (longer > sizePx) {
                        decoder.setTargetSize(info.size.width * sizePx / longer, info.size.height * sizePx / longer)
                    }
                }.asImageBitmap().also { cache.put(key, it) }
            } catch (e: IOException) {
                Log.w(TAG, "Could not decode cover of album $albumId", e)
                null
            }
        }
    }

    /** Drops every decoded cover; called when the screen is no longer visible. */
    fun clear() = cache.evictAll()

    private companion object {
        const val TAG = "Aamp"
        const val MAX_BYTES = 32 * 1024 * 1024
        const val BYTES_PER_PIXEL = 4
        const val DECODERS = 4
    }
}

/** A square cover with a placeholder until it loads. */
@Composable
internal fun AlbumCover(albumId: Long, covers: CoverLoader, sizePx: Int, modifier: Modifier = Modifier, cornerRadius: Int = 8) {
    val bitmap by produceState(covers.cached(albumId, sizePx), albumId, sizePx) {
        // Clear a previous album's cover while this one loads.
        value = covers.cached(albumId, sizePx) ?: run {
            value = null
            covers.load(albumId, sizePx)
        }
    }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(LocalColors.current.placeholder),
    ) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}
