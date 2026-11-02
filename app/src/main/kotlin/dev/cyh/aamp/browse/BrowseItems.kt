package dev.cyh.aamp.browse

import android.content.ContentUris
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.annotation.PluralsRes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import dev.cyh.aamp.R
import dev.cyh.aamp.artwork.ArtworkProvider
import dev.cyh.aamp.library.LetterIndex
import dev.cyh.aamp.library.LibrarySnapshot
import dev.cyh.aamp.library.LoadedLibrary
import dev.cyh.aamp.library.Playlist
import dev.cyh.aamp.voice.QueueSource
import dev.cyh.aamp.voice.Resolution

/**
 * Builds the media items Android Auto browses and plays.
 *
 * Root tabs: Albums (Recently added, then the albums), Artists, Playlists and Songs (a "Shuffle
 * all" item, then every song by title). Android Auto shows at most four tabs, so Recently added
 * lives inside Albums. Albums, Artists and Songs are split into alphabetical
 * sections once they hold more than [SECTION_THRESHOLD] entries, because Android Auto neither
 * pages nor lets a driver scroll far. Albums and artists are both browsable and playable.
 *
 * In the playback view, a track's artist (subtitle) links to that artist's albums and its album
 * (description) links to the album's tracks.
 */
@OptIn(UnstableApi::class) // Android Auto content style keys
internal class BrowseItems(private val context: Context) {
    private val appName = context.getString(R.string.app_name)
    private val albumsTitle = context.getString(R.string.browse_albums)
    private val artistsTitle = context.getString(R.string.browse_artists)
    private val recentTitle = context.getString(R.string.browse_recent)
    private val songsTitle = context.getString(R.string.browse_songs)
    private val playlistsTitle = context.getString(R.string.browse_playlists)
    private val shuffleAllTitle = context.getString(R.string.browse_shuffle_all)
    val searchAlbumsGroup: String = context.getString(R.string.search_group_albums)
    val searchArtistsGroup: String = context.getString(R.string.search_group_artists)
    val searchSongsGroup: String = context.getString(R.string.search_group_songs)

    fun root(): MediaItem = folder(MediaId.Root, appName, null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, null)

    /** The item for [id], or null when it names nothing in the library. */
    fun item(library: LoadedLibrary, playlists: List<Playlist>, id: MediaId): MediaItem? {
        val s = library.snapshot
        return when (id) {
            MediaId.Root -> root()
            MediaId.Albums, MediaId.Artists, MediaId.Playlists, MediaId.Songs ->
                rootChildren(s).firstOrNull { it.mediaId == MediaIds.encode(id) }
            MediaId.Recent -> recent()
            is MediaId.Playlist -> playlists.firstOrNull { it.id == id.playlistId }?.let { playlist(s, it) }
            MediaId.ShuffleAll -> shuffleAll()
            is MediaId.ShuffleArtist -> s.artistIndex(id.artistKey).takeIf { it >= 0 }?.let { shuffleArtist(s, it) }
            is MediaId.AlbumSection -> s.albumLetters.sectionOf(id.label).takeIf { it >= 0 }?.let { albumSection(s, it) }
            is MediaId.ArtistSection -> s.artistLetters.sectionOf(id.label).takeIf { it >= 0 }?.let { artistSection(s, it) }
            is MediaId.SongSection -> s.trackLetters.sectionOf(id.label).takeIf { it >= 0 }?.let { songSection(s, it) }
            is MediaId.Album -> s.albumIndex(id.albumId).takeIf { it >= 0 }?.let { album(s, it) }
            is MediaId.Artist -> s.artistIndex(id.artistKey).takeIf { it >= 0 }?.let { artist(s, it) }
            is MediaId.Genre -> s.genreIndex(id.genreKey).takeIf { it >= 0 }?.let { genre(s, it) }
            is MediaId.Track -> s.trackIndex(id.trackId).takeIf { it >= 0 }?.let { track(s, it, id.context) }
        }
    }

    /** One page of the children of [parent], or null when [parent] is not browsable. */
    fun children(library: LoadedLibrary, playlists: List<Playlist>, parent: MediaId, page: Int, pageSize: Int): List<MediaItem>? {
        val s = library.snapshot
        return when (parent) {
            MediaId.Root -> rootChildren(s).let { tabs -> paged(tabs.size, page, pageSize) { tabs[it] } }
            // Recently added first, then the sections or albums.
            MediaId.Albums ->
                if (s.albumCount > SECTION_THRESHOLD) {
                    paged(s.albumLetters.size + 1, page, pageSize) { i -> if (i == 0) recent() else albumSection(s, i - 1) }
                } else {
                    paged(s.albumCount + 1, page, pageSize) { i -> if (i == 0) recent() else album(s, i - 1) }
                }
            MediaId.Playlists -> paged(playlists.size, page, pageSize) { playlist(s, playlists[it]) }
            is MediaId.Playlist -> {
                val playlist = playlists.firstOrNull { it.id == parent.playlistId } ?: return null
                val tracks = playlist.tracksIn(s)
                val context = TrackContext.InPlaylist(playlist.id)
                paged(tracks.size, page, pageSize) { track(s, tracks[it], context) }
            }
            MediaId.Artists ->
                if (s.artistCount > SECTION_THRESHOLD) paged(s.artistLetters.size, page, pageSize) { artistSection(s, it) }
                else paged(s.artistCount, page, pageSize) { artist(s, it) }
            MediaId.Recent -> {
                val recent = s.albumsByRecent
                paged(minOf(recent.size, RECENT_LIMIT), page, pageSize) { album(s, recent[it]) }
            }
            MediaId.Songs ->
                if (s.trackCount > SECTION_THRESHOLD) {
                    paged(s.trackLetters.size + 1, page, pageSize) { i -> if (i == 0) shuffleAll() else songSection(s, i - 1) }
                } else {
                    paged(s.trackCount + 1, page, pageSize) { i ->
                        if (i == 0) shuffleAll() else track(s, s.tracksByTitle[i - 1], TrackContext.InSongs)
                    }
                }
            is MediaId.AlbumSection -> {
                val members = sectionMembers(s.albumLetters, parent.label) ?: return null
                paged(members.size, page, pageSize) { album(s, members[it]) }
            }
            is MediaId.ArtistSection -> {
                val members = sectionMembers(s.artistLetters, parent.label) ?: return null
                paged(members.size, page, pageSize) { artist(s, members[it]) }
            }
            is MediaId.SongSection -> {
                val members = sectionMembers(s.trackLetters, parent.label) ?: return null
                paged(members.size, page, pageSize) { track(s, members[it], TrackContext.InSongs) }
            }
            is MediaId.Album -> {
                val album = s.albumIndex(parent.albumId).takeIf { it >= 0 } ?: return null
                val tracks = s.albumTracks(album)
                paged(tracks.size, page, pageSize) { track(s, tracks[it], TrackContext.InAlbum(parent.albumId)) }
            }
            // A shuffle item first, since a browsed-into artist offers no other way to play it; then
            // the albums, or for artists who only appear on compilations, their songs.
            is MediaId.Artist -> {
                val artist = s.artistIndex(parent.artistKey).takeIf { it >= 0 } ?: return null
                val albums = s.artistAlbums(artist)
                if (albums.isNotEmpty()) {
                    paged(albums.size + 1, page, pageSize) { i -> if (i == 0) shuffleArtist(s, artist) else album(s, albums[i - 1]) }
                } else {
                    val tracks = s.artistTracks(artist)
                    val context = TrackContext.InArtist(parent.artistKey)
                    paged(tracks.size + 1, page, pageSize) { i -> if (i == 0) shuffleArtist(s, artist) else track(s, tracks[i - 1], context) }
                }
            }
            is MediaId.Genre -> {
                val genre = s.genreIndex(parent.genreKey).takeIf { it >= 0 } ?: return null
                val albums = s.genreAlbums(genre)
                paged(albums.size, page, pageSize) { album(s, albums[it]) }
            }
            MediaId.ShuffleAll, is MediaId.ShuffleArtist, is MediaId.Track -> null
        }
    }

    /** The context a queue's items carry: the list it came from, which decides what plays around them. */
    fun contextOf(s: LibrarySnapshot, source: QueueSource): TrackContext = when (source) {
        is QueueSource.Album -> TrackContext.InAlbum(s.albumIds[source.album])
        is QueueSource.Artist -> TrackContext.InArtist(s.artistKeys[source.artist])
        is QueueSource.Genre -> TrackContext.InGenre(s.genreKeys[source.genre])
        is QueueSource.Playlist -> TrackContext.InPlaylist(source.id)
        QueueSource.Songs -> TrackContext.InSongs
        QueueSource.Library -> TrackContext.None
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
            .setArtworkUri(ArtworkProvider.albumUri(s.albumIds[a]))
            .setIsBrowsable(true)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
            .setExtras(extras(group = group))
            .build()
        return MediaItem.Builder().setMediaId(MediaIds.encode(MediaId.Album(s.albumIds[a]))).setMediaMetadata(metadata).build()
    }

    fun artist(s: LibrarySnapshot, r: Int, group: String? = null): MediaItem {
        val albums = s.artistAlbums(r)
        val subtitle = if (albums.isNotEmpty()) count(R.plurals.browse_album_count, albums.size)
        else count(R.plurals.browse_song_count, s.artistTrackCount(r))
        val metadata = MediaMetadata.Builder()
            .setTitle(s.artistNames[r])
            .setArtist(s.artistNames[r])
            .setSubtitle(subtitle)
            .setArtworkUri(ArtworkProvider.albumUri(s.albumIds[s.artistCoverAlbum(r)]))
            .setIsBrowsable(true)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
            .setExtras(extras(browsable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM, group = group))
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
            .setDescription(s.albumTitles[album])
            .setAlbumTitle(s.albumTitles[album])
            .setAlbumArtist(s.artistNames[s.albumArtist[album]])
            .setTrackNumber(s.trackNumber[t].takeIf { it > 0 })
            .setDiscNumber(s.trackDisc[t])
            .setDurationMs(s.trackDurationMs[t].takeIf { it > 0 })
            .setReleaseYear(s.albumYear[album].takeIf { it > 0 })
            .setArtworkUri(ArtworkProvider.albumUri(s.albumIds[album]))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(trackExtras(s, t, group))
            .build()
        return MediaItem.Builder()
            .setMediaId(MediaIds.encode(MediaId.Track(s.trackIds[t], context)))
            .setUri(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, s.trackIds[t]))
            .setMediaMetadata(metadata)
            .build()
    }

    /** A playlist: browsing lists its songs, playing plays them in order. */
    fun playlist(s: LibrarySnapshot, p: Playlist): MediaItem {
        val tracks = p.tracksIn(s)
        val metadata = MediaMetadata.Builder()
            .setTitle(p.name)
            .setSubtitle(count(R.plurals.browse_song_count, tracks.size))
            // The first song's cover stands in for the playlist.
            .setArtworkUri(tracks.firstOrNull()?.let { ArtworkProvider.albumUri(s.albumIds[s.trackAlbum[it]]) })
            .setIsBrowsable(true)
            .setIsPlayable(tracks.isNotEmpty())
            .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
            .build()
        return MediaItem.Builder().setMediaId(MediaIds.encode(MediaId.Playlist(p.id))).setMediaMetadata(metadata).build()
    }

    /** A genre: browsing lists its albums, playing shuffles its songs. Not in the root tabs. */
    fun genre(s: LibrarySnapshot, g: Int): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(s.genreNames[g])
            .setGenre(s.genreNames[g])
            .setSubtitle(count(R.plurals.browse_song_count, s.genreTrackCount(g)))
            .setIsBrowsable(true)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_GENRE)
            .setExtras(extras(browsable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM))
            .build()
        return MediaItem.Builder().setMediaId(MediaIds.encode(MediaId.Genre(s.genreKeys[g]))).setMediaMetadata(metadata).build()
    }

    private fun albumSection(s: LibrarySnapshot, section: Int): MediaItem = folder(
        MediaId.AlbumSection(s.albumLetters.labels[section]),
        s.albumLetters.labels[section],
        count(R.plurals.browse_album_count, s.albumLetters.members(section).size),
        MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
        extras(browsable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM),
    )

    private fun artistSection(s: LibrarySnapshot, section: Int): MediaItem = folder(
        MediaId.ArtistSection(s.artistLetters.labels[section]),
        s.artistLetters.labels[section],
        count(R.plurals.browse_artist_count, s.artistLetters.members(section).size),
        MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
        extras(browsable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM),
    )

    private fun songSection(s: LibrarySnapshot, section: Int): MediaItem = folder(
        MediaId.SongSection(s.trackLetters.labels[section]),
        s.trackLetters.labels[section],
        count(R.plurals.browse_song_count, s.trackLetters.members(section).size),
        MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        extras(playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM),
    )

    private fun shuffleArtist(s: LibrarySnapshot, r: Int): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(context.getString(R.string.browse_shuffle_artist, s.artistNames[r]))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
            .build()
        return MediaItem.Builder().setMediaId(MediaIds.encode(MediaId.ShuffleArtist(s.artistKeys[r]))).setMediaMetadata(metadata).build()
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

    /** The four root tabs. A tab's style describes its children: sections are lists, albums a grid. */
    private fun rootChildren(s: LibrarySnapshot): List<MediaItem> {
        val list = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        val grid = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        return listOf(
            folder(MediaId.Albums, albumsTitle, null, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                extras(browsable = if (s.albumCount > SECTION_THRESHOLD) list else grid)),
            folder(MediaId.Artists, artistsTitle, null, MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, extras(browsable = list)),
            folder(MediaId.Playlists, playlistsTitle, null, MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS, extras(browsable = list)),
            folder(MediaId.Songs, songsTitle, null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, extras(browsable = list, playable = list)),
        )
    }

    /** The newest albums, as a grid. */
    private fun recent(): MediaItem = folder(
        MediaId.Recent,
        recentTitle,
        null,
        MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
        extras(browsable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM),
    )

    private fun folder(id: MediaId, title: String, subtitle: String?, mediaType: Int, extras: Bundle?): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
            .setExtras(extras)
            .build()
        return MediaItem.Builder().setMediaId(MediaIds.encode(id)).setMediaMetadata(metadata).build()
    }

    /**
     * Display hints for Android Auto: how this item's browsable and playable children are drawn,
     * and the search result group it belongs to. Null when there is nothing to say.
     */
    private fun extras(browsable: Int? = null, playable: Int? = null, group: String? = null): Bundle? {
        if (browsable == null && playable == null && group == null) return null
        return Bundle().apply {
            browsable?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it) }
            playable?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, it) }
            group?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
        }
    }

    /**
     * A track's search group plus the playback view links: the subtitle (artist) opens the artist,
     * the description (album) opens the album. Android Auto resolves the ids through onGetItem.
     */
    private fun trackExtras(s: LibrarySnapshot, t: Int, group: String?): Bundle = Bundle(3).apply {
        group?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
        putString(KEY_SUBTITLE_LINK_MEDIA_ID, MediaIds.encode(MediaId.Artist(s.artistKeys[s.trackArtist[t]])))
        putString(KEY_DESCRIPTION_LINK_MEDIA_ID, MediaIds.encode(MediaId.Album(s.albumIds[s.trackAlbum[t]])))
    }

    /** The name Android Auto's queue view shows for a queue whose first item is [first]. */
    fun queueTitle(s: LibrarySnapshot?, playlists: List<Playlist>, first: MediaItem): String? {
        val id = MediaIds.parse(first.mediaId) as? MediaId.Track ?: return null
        return when (val c = id.context) {
            is TrackContext.InAlbum ->
                s?.albumIndex(c.albumId)?.takeIf { it >= 0 }?.let { s.albumTitles[it] } ?: first.mediaMetadata.albumTitle?.toString()
            is TrackContext.InArtist -> s?.artistIndex(c.artistKey)?.takeIf { it >= 0 }?.let { s.artistNames[it] }
            is TrackContext.InGenre -> s?.genreIndex(c.genreKey)?.takeIf { it >= 0 }?.let { s.genreNames[it] }
            is TrackContext.InPlaylist -> playlists.firstOrNull { it.id == c.playlistId }?.name
            TrackContext.InSongs -> songsTitle
            TrackContext.None -> shuffleAllTitle
        }
    }

    private fun sectionMembers(index: LetterIndex, label: String): IntArray? =
        index.sectionOf(label).takeIf { it >= 0 }?.let(index::members)

    private fun count(@PluralsRes plural: Int, n: Int): String = context.resources.getQuantityString(plural, n, n)

    /** Items [page] * [pageSize] up to the next page boundary, without overflowing on huge page sizes. */
    private inline fun paged(total: Int, page: Int, pageSize: Int, item: (Int) -> MediaItem): List<MediaItem> {
        val from = page.toLong() * pageSize
        if (page < 0 || pageSize <= 0 || from >= total) return emptyList()
        val to = minOf(total.toLong(), from + pageSize).toInt()
        val start = from.toInt()
        return List(to - start) { item(start + it) }
    }

    private companion object {
        /** Lists longer than this are split into alphabetical sections. */
        const val SECTION_THRESHOLD = 40

        /** How many albums Recently added shows. */
        const val RECENT_LIMIT = 60

        // androidx.car.app.mediaextensions.MetadataExtras keys, copied rather than depending on the
        // car app library for two strings.
        const val KEY_SUBTITLE_LINK_MEDIA_ID = "androidx.car.app.mediaextensions.KEY_SUBTITLE_LINK_MEDIA_ID"
        const val KEY_DESCRIPTION_LINK_MEDIA_ID = "androidx.car.app.mediaextensions.KEY_DESCRIPTION_LINK_MEDIA_ID"
    }
}
