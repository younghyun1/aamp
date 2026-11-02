package dev.cyh.aamp.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ogg.OggExtractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.wav.WavExtractor

/**
 * Builds the audio-only ExoPlayer. Only the audio renderer and the containers a local music
 * library uses are created, so R8 can drop video, text and streaming code entirely.
 *
 * Buffers are sized for local files. ExoPlayer's defaults keep up to 50 seconds of compressed
 * audio and allow up to 12.8 MB for it, to ride out network stalls; a file on the phone reads
 * in milliseconds, so 10 to 30 seconds and at most 4 MB (about 30 seconds of 1,000 kbps FLAC)
 * is plenty, and playback starts once half a second is read.
 */
@OptIn(UnstableApi::class)
internal object AudioPlayer {
    fun create(context: Context): ExoPlayer {
        val renderers = RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf(
                MediaCodecAudioRenderer(
                    context,
                    MediaCodecSelector.DEFAULT,
                    handler,
                    audioListener,
                    DefaultAudioSink.Builder(context).build(),
                ),
            )
        }
        val extractors = ExtractorsFactory {
            arrayOf(
                FlacExtractor(),
                Mp3Extractor(),
                Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED),
                OggExtractor(),
                WavExtractor(),
            )
        }
        val sources = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context), extractors)
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, RESTART_BUFFER_MS)
            .setTargetBufferBytes(MAX_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        val player = ExoPlayer.Builder(context, renderers, sources)
            .setLoadControl(loadControl)
            .setAudioAttributes(attributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        // Let the audio DSP decode while the CPU sleeps, where the device supports it. Gapless
        // support is required so offload never costs gaps between album tracks.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(
                AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                    .setIsGaplessSupportRequired(true)
                    .build(),
            )
            .build()
        return player
    }

    private const val MIN_BUFFER_MS = 10_000
    private const val MAX_BUFFER_MS = 30_000
    private const val START_BUFFER_MS = 500
    private const val RESTART_BUFFER_MS = 1_000
    private const val MAX_BUFFER_BYTES = 4 * 1024 * 1024
}
