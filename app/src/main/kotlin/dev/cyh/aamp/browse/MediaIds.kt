package dev.cyh.aamp.browse

/**
 * Media ids shared with Android Auto. They identify browse nodes and playable items by stable
 * MediaStore ids (tracks, albums), name hashes (artists) or section labels, never by snapshot
 * index, so ids held by Android Auto stay valid across rescans.
 *
 * A track id carries the list it was picked from (`track:42@album:7`), because picking a track
 * from an album should queue that album, and picking it from Songs should queue the Songs list.
 */
sealed interface MediaId {
    data object Root : MediaId
    data object Albums : MediaId
    data object Artists : MediaId
    data object Recent : MediaId
    data object Songs : MediaId
    data object ShuffleAll : MediaId
    /** Every track by an artist, shuffled: the first child of an artist, which Android Auto cannot play from inside. */
    data class ShuffleArtist(val artistKey: Long) : MediaId
    data class AlbumSection(val label: String) : MediaId
    data class ArtistSection(val label: String) : MediaId
    data class SongSection(val label: String) : MediaId
    data class Album(val albumId: Long) : MediaId
    data class Artist(val artistKey: Long) : MediaId
    data class Track(val trackId: Long, val context: TrackContext) : MediaId
}

sealed interface TrackContext {
    data class InAlbum(val albumId: Long) : TrackContext
    data class InArtist(val artistKey: Long) : TrackContext
    data object InSongs : TrackContext
    data object None : TrackContext
}

object MediaIds {
    private const val ROOT = "root"
    private const val ALBUMS = "albums"
    private const val ARTISTS = "artists"
    private const val RECENT = "recent"
    private const val SONGS = "songs"
    private const val SHUFFLE_ALL = "shuffle:all"
    private const val SHUFFLE_ARTIST = "shuffle:artist:"
    private const val ALBUM_SECTION = "albums:"
    private const val ARTIST_SECTION = "artists:"
    private const val SONG_SECTION = "songs:"
    private const val ALBUM = "album:"
    private const val ARTIST = "artist:"
    private const val TRACK = "track:"

    fun encode(id: MediaId): String = when (id) {
        MediaId.Root -> ROOT
        MediaId.Albums -> ALBUMS
        MediaId.Artists -> ARTISTS
        MediaId.Recent -> RECENT
        MediaId.Songs -> SONGS
        MediaId.ShuffleAll -> SHUFFLE_ALL
        is MediaId.ShuffleArtist -> SHUFFLE_ARTIST + id.artistKey
        is MediaId.AlbumSection -> ALBUM_SECTION + id.label
        is MediaId.ArtistSection -> ARTIST_SECTION + id.label
        is MediaId.SongSection -> SONG_SECTION + id.label
        is MediaId.Album -> ALBUM + id.albumId
        is MediaId.Artist -> ARTIST + id.artistKey
        is MediaId.Track -> TRACK + id.trackId + when (val c = id.context) {
            is TrackContext.InAlbum -> "@" + ALBUM + c.albumId
            is TrackContext.InArtist -> "@" + ARTIST + c.artistKey
            TrackContext.InSongs -> "@" + SONGS
            TrackContext.None -> ""
        }
    }

    /** Parses an id produced by [encode]; null for anything else. */
    fun parse(raw: String): MediaId? = when {
        raw == ROOT -> MediaId.Root
        raw == ALBUMS -> MediaId.Albums
        raw == ARTISTS -> MediaId.Artists
        raw == RECENT -> MediaId.Recent
        raw == SONGS -> MediaId.Songs
        raw == SHUFFLE_ALL -> MediaId.ShuffleAll
        raw.startsWith(SHUFFLE_ARTIST) -> raw.removePrefix(SHUFFLE_ARTIST).toLongOrNull()?.let(MediaId::ShuffleArtist)
        raw.startsWith(ALBUM_SECTION) -> raw.removePrefix(ALBUM_SECTION).ifEmpty { null }?.let(MediaId::AlbumSection)
        raw.startsWith(ARTIST_SECTION) -> raw.removePrefix(ARTIST_SECTION).ifEmpty { null }?.let(MediaId::ArtistSection)
        raw.startsWith(SONG_SECTION) -> raw.removePrefix(SONG_SECTION).ifEmpty { null }?.let(MediaId::SongSection)
        raw.startsWith(ALBUM) -> raw.removePrefix(ALBUM).toLongOrNull()?.let(MediaId::Album)
        raw.startsWith(ARTIST) -> raw.removePrefix(ARTIST).toLongOrNull()?.let(MediaId::Artist)
        raw.startsWith(TRACK) -> parseTrack(raw.removePrefix(TRACK))
        else -> null
    }

    private fun parseTrack(rest: String): MediaId.Track? {
        val at = rest.indexOf('@')
        val trackId = (if (at < 0) rest else rest.substring(0, at)).toLongOrNull() ?: return null
        if (at < 0) return MediaId.Track(trackId, TrackContext.None)
        val context = when (val parent = parse(rest.substring(at + 1))) {
            is MediaId.Album -> TrackContext.InAlbum(parent.albumId)
            is MediaId.Artist -> TrackContext.InArtist(parent.artistKey)
            MediaId.Songs -> TrackContext.InSongs
            else -> return null
        }
        return MediaId.Track(trackId, context)
    }
}
