package dev.cyh.aamp.browse

import android.content.ContentUris
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import dev.cyh.aamp.R
import dev.cyh.aamp.library.LibrarySnapshot
import dev.cyh.aamp.library.LoadedLibrary
import dev.cyh.aamp.voice.QueueSource
import dev.cyh.aamp.voice.Resolution

/**
 * Builds the media items Android Auto browses and plays.
 *
 * Root tabs: Albums (artwork grid), Artists, and Songs (a "Shuffle all" item, then every song by
 * title). Albums and artists are both browsable and playable.
 */
@OptIn(UnstableApi::class) // Android Auto content style keys
internal class BrowseItems(private val context: Context) {
    private val appName = context.getString(R.string.app_name)
    private val albumsTitle = context.getString(R.string.browse_albums)
    private val artistsTitle = context.getString(R.string.browse_artists)
    private val songsTitle = context.getString(R.string.browse_songs)
    private val shuffleAllTitle = context.getString(R.string.browse_shuffle_all)
    val searchAlbumsGroup: String = context.getString(R.string.search_group_albums)
    val searchArtistsGroup: String = context.getString(R.string.search_group_artists)
    val searchSongsGroup: String = context.getString(R.string.search_group_songs)

    fun root(): MediaItem = folder(MediaId.Root, appName, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, null)

    /** The item for [id], or null when it names nothing in the library. */
    fun item(library: LoadedLibrary, id: MediaId): MediaItem? {
        val s = library.snapshot
        return when (id) {
            MediaId.Root -> root()
            MediaId.Albums, MediaId.Artists, MediaId.Songs -> rootChildren().firstOrNull { it.mediaId == MediaIds.encode(id) }
            MediaId.ShuffleAll -> shuffleAll()
            is MediaId.Album -> s.albumIndex(id.albumId).takeIf { it >= 0 }?.let { album(s, it) }
            is MediaId.Artist -> s.artistIndex(id.artistKey).takeIf { it >= 0 }?.let { artist(s, it) }
            is MediaId.Track -> s.trackIndex(id.trackId).takeIf { it >= 0 }?.let { track(s, it, id.context) }
        }
    }

    /** One page of the children of [parent], or null when [parent] is not browsable. */
    fun children(library: LoadedLibrary, parent: MediaId, page: Int, pageSize: Int): List<MediaItem>? {
        val s = library.snapshot
        return when (parent) {
            MediaId.Root -> paged(3, page, pageSize) { rootChildren()[it] }
            MediaId.Albums -> paged(s.albumCount, page, pageSize) { album(s, it) }
            MediaId.Artists -> paged(s.artistCount, page, pageSize) { artist(s, it) }
            MediaId.Songs -> paged(s.trackCount + 1, page, pageSize) { i ->
                if (i == 0) shuffleAll() else track(s, s.tracksByTitle[i - 1], TrackContext.InSongs)
            }
            is MediaId.Album -> {
                val album = s.albumIndex(parent.albumId).takeIf { it >= 0 } ?: return null
                val tracks = s.albumTracks(album)
                paged(tracks.size, page, pageSize) { track(s, tracks[it], TrackContext.InAlbum(parent.albumId)) }
            }
            is MediaId.Artist -> {
                val artist = s.artistIndex(parent.artistKey).takeIf { it >= 0 } ?: return null
                val albums = s.artistAlbums(artist)
                paged(albums.size, page, pageSize) { album(s, albums[it]) }
            }
            MediaId.ShuffleAll, is MediaId.Track -> null
        }
    }

    /** Media items for a resolved queue, each carrying the list it belongs to. */
    fun queue(s: LibrarySnapshot, queue: Resolution.Queue): List<MediaItem> {
        val context = when (val source = queue.source) {
            is QueueSource.Album -> TrackContext.InAlbum(s.albumIds[source.album])
            is QueueSource.Artist -> TrackContext.InArtist(s.artistKeys[source.artist])
            QueueSource.Songs -> TrackContext.InSongs
            QueueSource.Library -> TrackContext.None
        }
        return List(queue.tracks.size) { track(s, queue.tracks[it], context) }
    }

    fun album(s: LibrarySnapshot, a: Int, group: String? = null): MediaItem {
        val artist = s.artistNames[s.albumArtist[a]]
        val metadata = MediaMetadata.Builder()
            .setTitle(s.albumTitles[a])
            .setAlbumTitle(s.albumTitles[a])
            .setArtist(artist)
            .setAlbumArtist(artist)
            .setSubtitle(artist)
            .setTotalTrackCount(s.albumTrackCount[a])
            .setReleaseYear(s.albumYear[a].takeIf { it > 0 })
            .setIsBrowsable(true)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
            .setExtras(groupExtras(group))
            .build()
        return MediaItem.Builder().setMediaId(MediaIds.encode(MediaId.Album(s.albumIds[a]))).setMediaMetadata(metadata).build()
    }

    fun artist(s: LibrarySnapshot, r: Int, group: String? = null): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(s.artistNames[r])
            .setArtist(s.artistNames[r])
            .setSubtitle(s.artistAlbumCount(r).let { context.resources.getQuantityString(R.plurals.browse_album_count, it, it) })
            .setIsBrowsable(true)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
            .setExtras(groupExtras(group))
            .build()
        return MediaItem.Builder().setMediaId(MediaIds.encode(MediaId.Artist(s.artistKeys[r]))).setMediaMetadata(metadata).build()
    }

    fun track(s: LibrarySnapshot, t: Int, context: TrackContext, group: String? = null): MediaItem {
        val album = s.trackAlbum[t]
        val artist = s.artistNames[s.trackArtist[t]]
        val metadata = MediaMetadata.Builder()
            .setTitle(s.trackTitles[t])
            .setArtist(artist)
            .setSubtitle(artist)
            .setAlbumTitle(s.albumTitles[album])
            .setAlbumArtist(s.artistNames[s.albumArtist[album]])
            .setTrackNumber(s.trackNumber[t].takeIf { it > 0 })
            .setDiscNumber(s.trackDisc[t])
            .setDurationMs(s.trackDurationMs[t].takeIf { it > 0 })
            .setReleaseYear(s.albumYear[album].takeIf { it > 0 })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(groupExtras(group))
            .build()
        return MediaItem.Builder()
            .setMediaId(MediaIds.encode(MediaId.Track(s.trackIds[t], context)))
            .setUri(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, s.trackIds[t]))
            .setMediaMetadata(metadata)
            .build()
    }

    private fun shuffleAll(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(shuffleAllTitle)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
            .build()
        return MediaItem.Builder().setMediaId(MediaIds.encode(MediaId.ShuffleAll)).setMediaMetadata(metadata).build()
    }

    private fun rootChildren(): List<MediaItem> = listOf(
        folder(MediaId.Albums, albumsTitle, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, contentStyle(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)),
        folder(MediaId.Artists, artistsTitle, MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, contentStyle(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)),
        folder(MediaId.Songs, songsTitle, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, contentStyle(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)),
    )

    private fun folder(id: MediaId, title: String, mediaType: Int, extras: Bundle?): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
            .setExtras(extras)
            .build()
        return MediaItem.Builder().setMediaId(MediaIds.encode(id)).setMediaMetadata(metadata).build()
    }

    /** Asks Android Auto to show browsable children in [browsable] style and playable ones as a list. */
    private fun contentStyle(browsable: Int): Bundle = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, browsable)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

    private fun groupExtras(group: String?): Bundle? = group?.let {
        Bundle().apply { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
    }

    /** Items [page] * [pageSize] up to the next page boundary, without overflowing on huge page sizes. */
    private inline fun paged(total: Int, page: Int, pageSize: Int, item: (Int) -> MediaItem): List<MediaItem> {
        val from = page.toLong() * pageSize
        if (page < 0 || pageSize <= 0 || from >= total) return emptyList()
        val to = minOf(total.toLong(), from + pageSize).toInt()
        val start = from.toInt()
        return List(to - start) { item(start + it) }
    }
}
