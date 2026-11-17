package io.github.flamy69.flamyplayer.video

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.session.MediaSession
import io.github.flamy69.flamyplayer.FlamyApp
import io.github.flamy69.flamyplayer.R
import io.github.flamy69.flamyplayer.data.VideoSettings
import io.github.flamy69.flamyplayer.network.JoinDataSource
import io.github.flamy69.flamyplayer.network.NetworkDataSource
import io.github.flamy69.flamyplayer.network.SmbDataSource
import io.github.flamy69.flamyplayer.network.SmbException
import io.github.flamy69.flamyplayer.playback.MediaRenderersFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max

/** One audio or subtitle track the viewer can switch to. */
class TrackOption(
    val group: TrackGroup,
    val index: Int,
    val label: String,
    val language: String?,
    val selected: Boolean,
    val supported: Boolean,
)

data class VideoUiState(
    val title: String = "",
    val isPlaying: Boolean = false,
    /** Waiting for data, either at the start or after a seek. */
    val isLoading: Boolean = true,
    val durationMs: Long = 0,
    val hasNext: Boolean = false,
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList(),
    val subtitlesEnabled: Boolean = true,
    /** Something worth knowing that does not stop playback, e.g. sound the phone cannot decode. */
    val notice: String? = null,
    /** Why playback stopped. */
    val error: String? = null,
    /** Fast forward or rewind with the remote control, see [TrickPlay]. */
    val trickSpeed: Int = TrickPlay.NORMAL,
)

/**
 * Owns the video player. It lives in a view model so that rotating the phone or switching to
 * the dark theme does not interrupt the film.
 */
@OptIn(UnstableApi::class)
class VideoPlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val flamyApp = app as FlamyApp
    val settings: VideoSettings = flamyApp.videoSettings

    private val items: List<VideoItem> = VideoQueue.items
    private var index = VideoQueue.startIndex.coerceIn(0, max(items.size - 1, 0))

    /** `true` after the process was restarted and the list of videos is gone. */
    val isEmpty: Boolean get() = items.isEmpty()

    private val subtitleLoader = SubtitleLoader(app, flamyApp.smb)
    private var subtitles: List<LoadedSubtitle> = emptyList()

    /** Label of a subtitle track to switch to as soon as the player knows about it. */
    private var pendingSubtitleLabel: String? = null

    private var loadJob: Job? = null
    private var session: MediaSession? = null

    private val trickPlay = TrickPlay()
    private var trickJob: Job? = null

    /** Whether the sound was switched off because it could not keep up with double speed. */
    private var trickMuted = false

    val player: ExoPlayer = ExoPlayer.Builder(app, MediaRenderersFactory(app))
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                // Files and content URIs are handled by the default source; shared folders
                // (smb://) and media servers (http://) by ours.
                // (and the pieces of a DVD title, read as one film, by the one around them).
                JoinDataSource.Factory(
                    DefaultDataSource.Factory(
                        app,
                        NetworkDataSource.Factory(
                            smb = SmbDataSource.Factory(flamyApp.smb, settings::server),
                            http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true),
                        ),
                    ),
                ),
                DefaultExtractorsFactory()
                    // DTS sound in Blu-ray style files (.m2ts) is not looked for otherwise.
                    .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
                    // Lets files without a seek table be seeked in all the same.
                    .setConstantBitrateSeekingEnabled(true),
            ),
        )
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val _state = MutableStateFlow(VideoUiState(subtitlesEnabled = settings.subtitlesEnabled))
    val state: StateFlow<VideoUiState> = _state.asStateFlow()

    private val _cues = MutableStateFlow<List<Cue>>(emptyList())
    val cues: StateFlow<List<Cue>> = _cues.asStateFlow()

    /** While jumping, where the jumps have got to, even if the picture lags behind. */
    private var trickPositionMs: Long? = null

    val positionMs: Long get() = trickPositionMs ?: player.currentPosition

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (!isPlaying) savePosition()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update {
                it.copy(
                    isLoading = playbackState == Player.STATE_BUFFERING,
                    durationMs = player.duration.takeIf { duration -> duration != C.TIME_UNSET } ?: 0,
                    error = if (playbackState == Player.STATE_READY) null else it.error,
                )
            }
            if (playbackState == Player.STATE_ENDED) {
                items.getOrNull(index)?.let { settings.clearPosition(it.key) }
                if (index < items.lastIndex) play(index + 1)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            selectPendingSubtitle(tracks)
            _state.update {
                it.copy(
                    audioTracks = options(tracks, C.TRACK_TYPE_AUDIO),
                    subtitleTracks = options(tracks, C.TRACK_TYPE_TEXT),
                    notice = unsupportedNotice(tracks) ?: it.notice,
                )
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            // Sound that is passed through to the TV or the receiver as it is (AC3, DTS) cannot
            // be played faster, and the player falls back to normal speed. Double speed then
            // goes on without sound.
            val wanted = trickPlay.speed.toFloat()
            if (trickPlay.isActive && trickPlay.isRealPlayback && playbackParameters.speed != wanted && !trickMuted) {
                trickMuted = true
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
                player.setPlaybackSpeed(wanted)
            }
        }

        override fun onCues(cueGroup: CueGroup) {
            _cues.value = cueGroup.cues
        }

        override fun onPlayerError(error: PlaybackException) {
            savePosition()
            _state.update { it.copy(isLoading = false, error = describe(error)) }
        }
    }

    /** What headset and Bluetooth buttons control; "next" means the next file of the folder. */
    private val sessionPlayer = object : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
            .build()

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

        override fun seekToNext() = next()
        override fun seekToNextMediaItem() = next()
        override fun seekToPrevious() = previous()
        override fun seekToPreviousMediaItem() = previous()
    }

    init {
        player.addListener(listener)
        if (items.isNotEmpty()) play(index)
        viewModelScope.launch {
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                if (player.isPlaying) savePosition()
            }
        }
    }

    fun togglePlay() {
        when {
            player.playbackState == Player.STATE_ENDED -> {
                player.seekTo(0)
                player.play()
            }
            player.playbackState == Player.STATE_IDLE && _state.value.error != null -> retry()
            player.playWhenReady -> player.pause()
            else -> player.play()
        }
    }

    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    /** One step up the ladder of fast forward speeds, or down the one of rewind speeds. */
    fun fastForward() {
        if (canTrickPlay()) {
            trickPlay.fastForward()
            applyTrickSpeed()
        }
    }

    /** One step up the ladder of rewind speeds, or down the one of fast forward speeds. */
    fun rewind() {
        if (canTrickPlay()) {
            trickPlay.rewind()
            applyTrickSpeed()
        }
    }

    /** Back to normal playback after fast forward or rewind. Returns whether there was any. */
    fun stopTrickPlay(): Boolean {
        if (!trickPlay.isActive) return false
        trickPlay.stop()
        applyTrickSpeed()
        return true
    }

    fun seekBy(deltaMs: Long) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0, duration))
    }

    fun next() {
        if (index < items.lastIndex) play(index + 1)
    }

    /** Back to the start of the video, or to the previous one when already near the start. */
    fun previous() {
        if (player.currentPosition > RESTART_THRESHOLD_MS || index == 0) {
            player.seekTo(0)
        } else {
            play(index - 1)
        }
    }

    /** Tries again after an error, from the position where playback stopped. */
    fun retry() {
        _state.update { it.copy(error = null, isLoading = true) }
        if (player.currentMediaItem == null) play(index) else player.prepare()
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun selectAudio(option: TrackOption) {
        option.language?.let { settings.audioLanguage = it }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(option.group, option.index))
            .build()
    }

    fun selectSubtitle(option: TrackOption) {
        option.language?.let { settings.subtitleLanguage = it }
        setSubtitlesEnabled(true)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(option.group, option.index))
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
    }

    fun disableSubtitles() {
        setSubtitlesEnabled(false)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    /** Adds a subtitle file the viewer picked and switches to it. */
    fun addSubtitleFile(uri: Uri) {
        val item = items.getOrNull(index) ?: return
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { subtitleLoader.loadDocument(uri) }
            if (picked == null) {
                _state.update { it.copy(notice = getApplication<Application>().getString(R.string.video_subtitle_file_failed)) }
                return@launch
            }
            val subtitle = picked.withUniqueLabel(subtitles)
            pendingSubtitleLabel = subtitle.label
            setSubtitlesEnabled(true)
            // The player only learns about subtitle files when a video is loaded, so load it
            // again and carry on from the same place.
            prepare(item, subtitles + subtitle, player.currentPosition)
        }
    }

    /** The player screen became visible. */
    fun onStart() {
        if (session == null && !isEmpty) {
            session = MediaSession.Builder(getApplication(), sessionPlayer).setId(SESSION_ID).build()
        }
    }

    /** The player screen is no longer visible: a video does not keep playing in the background. */
    fun onStop() {
        resetTrickPlay()
        player.pause()
        savePosition()
        session?.release()
        session = null
    }

    override fun onCleared() {
        savePosition()
        session?.release()
        session = null
        player.removeListener(listener)
        player.release()
    }

    private fun play(newIndex: Int) {
        savePosition()
        index = newIndex
        val item = items[index]

        loadJob?.cancel()
        resetTrickPlay()
        player.stop()
        player.clearMediaItems()
        pendingSubtitleLabel = null
        _cues.value = emptyList()
        _state.update {
            it.copy(
                title = item.title,
                isLoading = true,
                durationMs = 0,
                hasNext = index < items.lastIndex,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                notice = null,
                error = null,
            )
        }
        loadJob = viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { subtitleLoader.load(item) }
            prepare(item, found, settings.positions.value[item.key] ?: 0L)
        }
    }

    private fun prepare(item: VideoItem, subtitles: List<LoadedSubtitle>, positionMs: Long) {
        this.subtitles = subtitles
        val mediaItem = MediaItem.Builder()
            .setUri(item.uri)
            .setMediaId(item.key)
            .setSubtitleConfigurations(
                subtitles.map { subtitle ->
                    MediaItem.SubtitleConfiguration.Builder(subtitle.uri)
                        .setMimeType(subtitle.mimeType)
                        .setLanguage(subtitle.language)
                        .setLabel(subtitle.label)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                },
            )
            .build()

        // Start every video from the viewer's general preferences, not from the tracks that
        // were picked by hand in the previous one.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !settings.subtitlesEnabled)
            .setPreferredTextLanguages(
                *listOfNotNull(settings.subtitleLanguage, Locale.getDefault().language).distinct().toTypedArray(),
            )
            .setPreferredAudioLanguages(*listOfNotNull(settings.audioLanguage).toTypedArray())
            .build()

        player.setMediaItem(mediaItem, positionMs)
        player.prepare()
        player.play()
    }

    private fun canTrickPlay(): Boolean =
        player.currentMediaItem != null && _state.value.error == null && player.playbackState != Player.STATE_ENDED

    /**
     * Makes the player follow [trickPlay]. Normal and double speed are played by the player
     * itself. Anything else jumps from key frame to key frame, paused and without sound. A jump
     * only starts once the previous one has arrived, so that a slow network shows fewer
     * pictures, but the position still moves at the chosen speed.
     */
    private fun applyTrickSpeed() {
        trickJob?.cancel()
        trickJob = null
        trickPositionMs = null
        val speed = trickPlay.speed
        _state.update { it.copy(trickSpeed = speed) }
        if (trickPlay.isRealPlayback) {
            player.setSeekParameters(SeekParameters.DEFAULT)
            player.volume = 1f
            if (!trickPlay.isActive) restoreSound()
            player.setPlaybackSpeed(speed.toFloat())
            player.play()
            return
        }
        player.setPlaybackSpeed(1f)
        player.volume = 0f
        player.pause()
        player.setSeekParameters(if (speed < 0) SeekParameters.PREVIOUS_SYNC else SeekParameters.CLOSEST_SYNC)
        trickJob = viewModelScope.launch {
            var target = player.currentPosition
            var last = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(TRICK_STEP_MS)
                val now = SystemClock.elapsedRealtime()
                target = trickPlay.advance(target, now - last, _state.value.durationMs)
                trickPositionMs = target
                last = now
                if (trickPlay.hasReachedLimit(target, _state.value.durationMs)) {
                    // At the start or near the end: carry on from there at normal speed.
                    player.seekTo(target)
                    trickPlay.stop()
                    applyTrickSpeed()
                } else if (player.playbackState != Player.STATE_BUFFERING) {
                    player.seekTo(target)
                }
            }
        }
    }

    /** Leaves fast forward or rewind without starting playback: for another video, or on leaving. */
    private fun resetTrickPlay() {
        if (!trickPlay.isActive) return
        trickJob?.cancel()
        trickJob = null
        trickPositionMs = null
        trickPlay.stop()
        player.setSeekParameters(SeekParameters.DEFAULT)
        player.volume = 1f
        restoreSound()
        player.setPlaybackSpeed(1f)
        _state.update { it.copy(trickSpeed = TrickPlay.NORMAL) }
    }

    /** Switches the sound back on if double speed had to switch it off. */
    private fun restoreSound() {
        if (!trickMuted) return
        trickMuted = false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
    }

    private fun selectPendingSubtitle(tracks: Tracks) {
        val label = pendingSubtitleLabel ?: return
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                if (group.getTrackFormat(i).label == label && group.isTrackSupported(i)) {
                    pendingSubtitleLabel = null
                    if (!group.isTrackSelected(i)) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .build()
                    }
                    return
                }
            }
        }
    }

    private fun setSubtitlesEnabled(enabled: Boolean) {
        settings.subtitlesEnabled = enabled
        _state.update { it.copy(subtitlesEnabled = enabled) }
    }

    /** Remembers where the viewer is, unless the video has barely started or is about over. */
    private fun savePosition() {
        val item = items.getOrNull(index) ?: return
        if (player.currentMediaItem?.mediaId != item.key) return
        val position = player.currentPosition
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val nearEnd = position > duration - max(END_MARGIN_MS, duration / 30)
        if (position < START_MARGIN_MS || nearEnd || player.playbackState == Player.STATE_ENDED) {
            settings.clearPosition(item.key)
        } else {
            settings.savePosition(item.key, position)
        }
    }

    private fun options(tracks: Tracks, type: Int): List<TrackOption> {
        val result = ArrayList<TrackOption>()
        for (group in tracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                result += TrackOption(
                    group = group.mediaTrackGroup,
                    index = i,
                    label = label(format, type, result.size + 1),
                    language = format.language?.takeIf { it != C.LANGUAGE_UNDETERMINED },
                    selected = group.isTrackSelected(i),
                    supported = group.isTrackSupported(i),
                )
            }
        }
        return result
    }

    private fun label(format: Format, type: Int, number: Int): String {
        val language = format.language
            ?.takeIf { it != C.LANGUAGE_UNDETERMINED }
            ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }
            ?.takeIf { it.isNotBlank() }
        val details = if (type == C.TRACK_TYPE_AUDIO) {
            listOfNotNull(codecName(format.sampleMimeType), channelLayout(format.channelCount)).joinToString(" ")
        } else {
            null
        }
        val parts = listOfNotNull(
            language,
            format.label?.takeIf { it.isNotBlank() && !it.equals(language, ignoreCase = true) },
            details?.takeIf { it.isNotEmpty() },
        )
        return parts.joinToString(" · ").ifEmpty {
            getApplication<Application>().getString(R.string.video_track_number, number)
        }
    }

    /** Tells the viewer when the file has sound or picture that this phone has no decoder for. */
    private fun unsupportedNotice(tracks: Tracks): String? {
        fun unsupported(type: Int): Format? {
            val groups = tracks.groups.filter { it.type == type }
            if (groups.isEmpty() || groups.any { it.isSupported }) return null
            return groups.first().getTrackFormat(0)
        }
        val app = getApplication<Application>()
        // If the decoders that come with the app are missing, that is the likely reason.
        val reason = if (MediaRenderersFactory.isFfmpegAvailable) "" else " " + app.getString(R.string.video_decoders_missing)
        unsupported(C.TRACK_TYPE_VIDEO)?.let {
            return app.getString(R.string.video_unsupported_picture, codecName(it.sampleMimeType) ?: "?") + reason
        }
        unsupported(C.TRACK_TYPE_AUDIO)?.let {
            return app.getString(R.string.video_unsupported_sound, codecName(it.sampleMimeType) ?: "?") + reason
        }
        return null
    }

    private fun describe(error: PlaybackException): String {
        val app = getApplication<Application>()
        val network = generateSequence<Throwable>(error) { it.cause }.take(8).filterIsInstance<SmbException>().firstOrNull()
        return when {
            network != null -> app.getString(networkErrorText(network.reason))
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                app.getString(R.string.video_error_unsupported)
            else -> app.getString(R.string.video_error_generic, error.errorCodeName)
        }
    }

    companion object {
        private const val SESSION_ID = "video"
        private const val SAVE_INTERVAL_MS = 10_000L
        private const val RESTART_THRESHOLD_MS = 3_000L
        private const val START_MARGIN_MS = 10_000L
        private const val END_MARGIN_MS = 15_000L

        /** How often fast forward and rewind jump, unless the network is slower. */
        private const val TRICK_STEP_MS = 250L

        fun networkErrorText(reason: SmbException.Reason): Int = when (reason) {
            SmbException.Reason.UNKNOWN_HOST -> R.string.network_error_unknown_host
            SmbException.Reason.UNREACHABLE -> R.string.network_error_unreachable
            SmbException.Reason.LOGIN_FAILED -> R.string.network_error_login
            SmbException.Reason.ACCESS_DENIED -> R.string.network_error_access
            SmbException.Reason.NOT_FOUND -> R.string.network_error_not_found
            SmbException.Reason.OTHER -> R.string.network_error_other
        }

        private fun codecName(mimeType: String?): String? = when (mimeType) {
            null -> null
            "audio/ac3" -> "AC3"
            "audio/eac3", "audio/eac3-joc" -> "E-AC3"
            "audio/ac4" -> "AC4"
            "audio/vnd.dts" -> "DTS"
            "audio/vnd.dts.hd", "audio/vnd.dts.hd;profile=lbr", "audio/vnd.dts.uhd;profile=p2" -> "DTS-HD"
            "audio/true-hd" -> "TrueHD"
            "audio/mp4a-latm" -> "AAC"
            "audio/mpeg", "audio/mpeg-L2", "audio/mpeg-L1" -> "MP3"
            "audio/opus" -> "Opus"
            "audio/vorbis" -> "Vorbis"
            "audio/flac" -> "FLAC"
            "audio/raw" -> "PCM"
            "video/avc" -> "H.264"
            "video/hevc" -> "H.265"
            "video/x-vnd.on2.vp9" -> "VP9"
            "video/x-vnd.on2.vp8" -> "VP8"
            "video/av01" -> "AV1"
            "video/mp4v-es" -> "MPEG-4"
            "video/mpeg2" -> "MPEG-2"
            "video/wvc1" -> "VC-1"
            else -> mimeType.substringAfter('/').uppercase()
        }

        private fun channelLayout(channels: Int): String? = when (channels) {
            Format.NO_VALUE, 0 -> null
            1 -> "1.0"
            2 -> "2.0"
            6 -> "5.1"
            8 -> "7.1"
            else -> "$channels ch"
        }
    }
}
