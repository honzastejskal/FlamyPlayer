package io.github.flamy69.flamyplayer.playback

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import io.github.flamy69.flamyplayer.ui.isTv
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer

/**
 * Decodes with what the phone has built in and, for everything it lacks, with the FFmpeg
 * decoders that come with the app: DTS, AC3, E-AC3, TrueHD, MP2 and ALAC sound, and MPEG-2,
 * H.264, H.265, VP8, VP9 and AV1 pictures.
 *
 * The phone's own decoders come first in the list, so they are used whenever they can play a
 * track: they run on dedicated hardware and are easy on the battery. FFmpeg does its work on
 * the processor and only steps in where there would be silence or a black picture otherwise.
 */
@OptIn(UnstableApi::class)
class MediaRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    init {
        // If the phone's preferred decoder fails to start, try its other ones before giving up.
        setEnableDecoderFallback(true)
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler,
            eventListener, out,
        )
        if (isFfmpegAvailable) out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        // On a TV, older DivX and Xvid films (MPEG-4 Part 2) go to Android's own software decoder
        // first: the hardware decoders of TV chips fail on many of them (seen on Philips and TCL),
        // while the software one copes and SD pictures are light work. If it cannot start, the
        // fallback above still tries the hardware one.
        val selector = if (!context.isTv) {
            mediaCodecSelector
        } else {
            MediaCodecSelector { mimeType, secure, tunneling ->
                val all = mediaCodecSelector.getDecoderInfos(mimeType, secure, tunneling)
                if (mimeType == MimeTypes.VIDEO_MP4V) all.sortedBy { !it.softwareOnly } else all
            }
        }
        super.buildVideoRenderers(
            context, extensionRendererMode, selector, enableDecoderFallback, eventHandler, eventListener,
            allowedVideoJoiningTimeMs, out,
        )
        if (isFfmpegAvailable) {
            out.add(FfmpegVideoRenderer(allowedVideoJoiningTimeMs, eventHandler, eventListener, MAX_DROPPED_FRAMES_TO_NOTIFY))
        }
    }

    companion object {
        private const val MAX_DROPPED_FRAMES_TO_NOTIFY = 50

        /** Whether the FFmpeg libraries could be loaded on this device. */
        val isFfmpegAvailable: Boolean by lazy {
            try {
                FfmpegLibrary.isAvailable()
            } catch (_: LinkageError) {
                false
            }
        }
    }
}
