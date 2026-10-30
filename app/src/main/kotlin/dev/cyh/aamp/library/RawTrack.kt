package dev.cyh.aamp.library

/**
 * One audio row as read from MediaStore, before grouping into albums and artists.
 * Missing text is an empty string; missing numbers are 0.
 */
class RawTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val albumArtist: String,
    val trackNumber: Int,
    val discNumber: Int,
    val year: Int,
    val durationMs: Long,
    val isCompilation: Boolean,
    /** When MediaStore first indexed the file, in seconds since the epoch. */
    val dateAddedSec: Long,
)
