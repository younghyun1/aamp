package dev.cyh.aamp.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
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

        val player = ExoPlayer.Builder(context, renderers, sources)
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
}
