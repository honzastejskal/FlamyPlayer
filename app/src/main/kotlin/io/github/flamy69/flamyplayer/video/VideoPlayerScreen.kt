package io.github.flamy69.flamyplayer.video

import android.content.ActivityNotFoundException
import android.content.res.Configuration
import android.media.AudioManager
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenLockRotation
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import io.github.flamy69.flamyplayer.R
import io.github.flamy69.flamyplayer.data.OrientationMode
import io.github.flamy69.flamyplayer.data.SubtitleStyle
import io.github.flamy69.flamyplayer.data.VideoResizeMode
import io.github.flamy69.flamyplayer.ui.focusFrame
import io.github.flamy69.flamyplayer.ui.formatTime
import io.github.flamy69.flamyplayer.ui.rememberIsTv
import io.github.flamy69.flamyplayer.ui.tryRequestFocus
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

private enum class Panel { NONE, AUDIO, SUBTITLES }

private enum class Gesture { BRIGHTNESS, VOLUME }

/** The bubble in the middle of the screen that confirms a gesture. */
private class Indicator(val icon: ImageVector, val text: String?)

/** Remembers the last tap, to tell a double tap from two separate ones. */
private class TapMemory {
    var time = 0L
    var leftHalf = false
    var controlsWereVisible = true
}

private const val CONTROLS_TIMEOUT_MS = 3_500L
private const val INDICATOR_TIMEOUT_MS = 700L
private const val NOTICE_TIMEOUT_MS = 8_000L
private const val DOUBLE_TAP_MS = 300L
private const val SEEK_STEP_MS = 10_000L

/** How many presses of an arrow take a setting slider from one end to the other. */
private const val SLIDER_STEPS = 20f

/** Swipes that start this close to the top or bottom edge belong to the system. */
private const val EDGE_FRACTION = 0.1f

/** How much of the screen height a swipe has to cover to go from 0 to 100 %. */
private const val SWIPE_RANGE_FRACTION = 0.6f

@Composable
fun VideoPlayerScreen(viewModel: VideoPlayerViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cues by viewModel.cues.collectAsStateWithLifecycle()
    val subtitleStyle by viewModel.settings.subtitleStyle.collectAsStateWithLifecycle()

    val activity = LocalActivity.current as? VideoPlayerActivity
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var controlsVisible by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var resizeMode by remember { mutableStateOf(viewModel.settings.resizeMode) }
    var orientation by remember { mutableStateOf(viewModel.settings.orientation) }
    var indicator by remember { mutableStateOf<Indicator?>(null) }
    var swiping by remember { mutableStateOf(false) }
    // Bumped whenever the viewer touches a control, which restarts the hide timer.
    var interactions by remember { mutableIntStateOf(0) }
    val tapMemory = remember { TapMemory() }
    val trickPlaying = state.trickSpeed != TrickPlay.NORMAL

    // On a TV everything is done with the remote control, so focus has to be in the right place.
    val isTv = rememberIsTv()
    val playFocus = remember { FocusRequester() }
    val audioFocus = remember { FocusRequester() }
    val subtitlesFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    var controlsFocused by remember { mutableStateOf(false) }
    var panelFocused by remember { mutableStateOf(false) }
    // Bumped to move focus into the controls once they are on screen.
    var focusControlsRequests by remember { mutableIntStateOf(0) }
    var openedPanel by remember { mutableStateOf(Panel.NONE) }

    LaunchedEffect(controlsVisible, state.isPlaying, trickPlaying, panel, interactions) {
        if (controlsVisible && (state.isPlaying || trickPlaying) && panel == Panel.NONE) {
            delay(CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }
    LaunchedEffect(indicator, swiping) {
        if (indicator != null && !swiping) {
            delay(INDICATOR_TIMEOUT_MS)
            indicator = null
        }
    }
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(NOTICE_TIMEOUT_MS)
            viewModel.dismissNotice()
        }
    }
    LaunchedEffect(state.error) {
        if (state.error != null) controlsVisible = true
    }
    LaunchedEffect(controlsVisible) {
        // Whatever had focus in the controls is gone with them.
        if (!controlsVisible) controlsFocused = false
    }
    LaunchedEffect(focusControlsRequests) {
        if (focusControlsRequests > 0) playFocus.tryRequestFocus()
    }
    LaunchedEffect(panel) {
        panelFocused = false
        if (isTv) {
            if (panel != Panel.NONE) {
                panelFocus.tryRequestFocus()
            } else if (controlsVisible) {
                // Back to the button that opened the panel.
                when (openedPanel) {
                    Panel.AUDIO -> audioFocus.tryRequestFocus()
                    Panel.SUBTITLES -> subtitlesFocus.tryRequestFocus()
                    Panel.NONE -> Unit
                }
            }
        }
        openedPanel = panel
    }

    val view = LocalView.current
    DisposableEffect(view, state.isPlaying, trickPlaying) {
        view.keepScreenOn = state.isPlaying || trickPlaying
        onDispose { view.keepScreenOn = false }
    }

    fun focusControls() {
        controlsVisible = true
        focusControlsRequests++
    }

    fun seekByStep(forward: Boolean) {
        viewModel.stopTrickPlay()
        viewModel.seekBy(if (forward) SEEK_STEP_MS else -SEEK_STEP_MS)
        indicator = Indicator(if (forward) Icons.Rounded.Forward10 else Icons.Rounded.Replay10, null)
    }

    // The remote control. Keys that are used here on the way down are also swallowed on the way
    // up, so that nothing else sees half a key press.
    val keysInUse = remember { HashSet<Int>() }
    val handleKey by rememberUpdatedState<(KeyEvent) -> Boolean> { event ->
        val code = event.keyCode
        if (event.action != KeyEvent.ACTION_DOWN) return@rememberUpdatedState keysInUse.remove(code)
        if (controlsVisible) interactions++
        // Holding a button down repeats it; only the arrows are meant to be held.
        val first = event.repeatCount == 0
        // Nothing has focus while the controls are hidden, or at first when they show up.
        val idle = panel == Panel.NONE && (!controlsVisible || !controlsFocused)
        val used = when (code) {
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (first) viewModel.fastForward()
                true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (first) viewModel.rewind()
                true
            }
            // Otherwise play and pause are left to the media session.
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> viewModel.stopTrickPlay()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> when {
                viewModel.stopTrickPlay() -> true
                idle -> {
                    if (first) {
                        viewModel.togglePlay()
                        focusControls()
                    }
                    true
                }
                else -> false
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (idle) seekByStep(forward = code == KeyEvent.KEYCODE_DPAD_RIGHT)
                idle
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> when {
                panel != Panel.NONE && !panelFocused -> panelFocus.tryRequestFocus()
                idle -> {
                    focusControls()
                    true
                }
                else -> false
            }
            else -> false
        }
        if (used) keysInUse += code
        used
    }
    DisposableEffect(activity) {
        activity?.keyHandler = { event -> handleKey(event) }
        onDispose { activity?.keyHandler = null }
    }

    val pickSubtitleFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.addSubtitleFile(uri)
    }

    fun changeOrientation(mode: OrientationMode) {
        orientation = mode
        viewModel.settings.orientation = mode
        activity?.setOrientation(mode)
    }

    val resizeLabels = mapOf(
        VideoResizeMode.FIT to stringResource(R.string.video_resize_fit),
        VideoResizeMode.CROP to stringResource(R.string.video_resize_crop),
        VideoResizeMode.STRETCH to stringResource(R.string.video_resize_stretch),
    )

    // Back closes what is open, one thing at a time; the last handler that is enabled wins. On a
    // phone the controls stay out of this: Back leaves the player even while they show.
    BackHandler(enabled = isTv && controlsVisible) { controlsVisible = false }
    BackHandler(enabled = trickPlaying) { viewModel.stopTrickPlay() }
    BackHandler(enabled = panel != Panel.NONE) { panel = Panel.NONE }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        VideoSurface(viewModel.player, resizeMode)

        SubtitleLayer(
            cues = cues,
            style = subtitleStyle,
            // With the settings open there should always be something to look at.
            sample = if (panel == Panel.SUBTITLES) stringResource(R.string.video_subtitle_sample) else null,
        )

        GestureLayer(
            onTap = { leftHalf ->
                val now = SystemClock.uptimeMillis()
                if (now - tapMemory.time < DOUBLE_TAP_MS && tapMemory.leftHalf == leftHalf) {
                    // The first tap of a double tap has already toggled the controls: undo that.
                    controlsVisible = tapMemory.controlsWereVisible
                    viewModel.seekBy(if (leftHalf) -SEEK_STEP_MS else SEEK_STEP_MS)
                    indicator = Indicator(if (leftHalf) Icons.Rounded.Replay10 else Icons.Rounded.Forward10, null)
                } else {
                    tapMemory.controlsWereVisible = controlsVisible
                    if (panel != Panel.NONE) panel = Panel.NONE else controlsVisible = !controlsVisible
                }
                tapMemory.time = now
                tapMemory.leftHalf = leftHalf
            },
            levelOf = { gesture ->
                when (gesture) {
                    Gesture.BRIGHTNESS -> activity?.windowBrightness ?: 0.5f
                    Gesture.VOLUME -> {
                        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
                    }
                }
            },
            onLevel = { gesture, level ->
                val percent = "${(level * 100).roundToInt()} %"
                when (gesture) {
                    Gesture.BRIGHTNESS -> {
                        activity?.windowBrightness = level
                        indicator = Indicator(Icons.Rounded.BrightnessMedium, percent)
                    }
                    Gesture.VOLUME -> {
                        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).roundToInt(), 0)
                        indicator = Indicator(Icons.AutoMirrored.Rounded.VolumeUp, percent)
                    }
                }
            },
            onSwipingChange = { active, gesture ->
                swiping = active
                if (!active && gesture == Gesture.BRIGHTNESS) {
                    activity?.let { viewModel.settings.brightness = it.windowBrightness }
                }
            },
        )

        // Jumping waits for data all the time; the speed indicator says enough then.
        if (state.isLoading && state.error == null && !trickPlaying) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
            Controls(
                state = state,
                position = { viewModel.positionMs },
                // A TV neither turns nor has a sensor for it.
                showRotation = !isTv,
                focus = ControlsFocus(playFocus, audioFocus, subtitlesFocus),
                onFocusChange = { controlsFocused = it },
                orientationLocked = orientation != OrientationMode.AUTO,
                onInteraction = { interactions++ },
                onClose = onClose,
                onShowAudio = { panel = Panel.AUDIO },
                onShowSubtitles = { panel = Panel.SUBTITLES },
                onTogglePlay = {
                    if (!viewModel.stopTrickPlay()) viewModel.togglePlay()
                },
                onPrevious = viewModel::previous,
                onNext = viewModel::next,
                onSeek = viewModel::seekTo,
                onToggleOrientationLock = {
                    changeOrientation(
                        when {
                            orientation != OrientationMode.AUTO -> OrientationMode.AUTO
                            landscape -> OrientationMode.LANDSCAPE
                            else -> OrientationMode.PORTRAIT
                        },
                    )
                },
                onRotate = {
                    changeOrientation(if (landscape) OrientationMode.PORTRAIT else OrientationMode.LANDSCAPE)
                },
                onCycleResize = {
                    val modes = VideoResizeMode.entries
                    val next = modes[(resizeMode.ordinal + 1) % modes.size]
                    resizeMode = next
                    viewModel.settings.resizeMode = next
                    indicator = Indicator(Icons.Rounded.AspectRatio, resizeLabels[next])
                },
            )
        }

        indicator?.let { IndicatorBubble(it, Modifier.align(Alignment.Center)) }

        if (trickPlaying) {
            TrickPlayIndicator(
                speed = state.trickSpeed,
                position = { viewModel.positionMs },
                durationMs = state.durationMs,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp),
            )
        }

        state.error?.let { message ->
            ErrorMessage(message, onRetry = viewModel::retry, modifier = Modifier.align(Alignment.Center))
        }

        state.notice?.let { message ->
            Notice(
                text = message,
                onDismiss = viewModel::dismissNotice,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // Focus stays inside an open panel until it is closed.
        val panelModifier = Modifier
            .align(Alignment.TopEnd)
            .onFocusChanged { panelFocused = it.hasFocus }
            .focusRequester(panelFocus)
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup()
        when (panel) {
            Panel.AUDIO -> AudioPanel(
                tracks = state.audioTracks,
                onSelect = viewModel::selectAudio,
                onClose = { panel = Panel.NONE },
                modifier = panelModifier,
            )

            Panel.SUBTITLES -> SubtitlePanel(
                state = state,
                style = subtitleStyle,
                onSelect = viewModel::selectSubtitle,
                onDisable = viewModel::disableSubtitles,
                onOpenFile = {
                    panel = Panel.NONE
                    try {
                        pickSubtitleFile.launch(arrayOf("*/*"))
                    } catch (_: ActivityNotFoundException) {
                        // No file picker on this device; nothing to offer then.
                    }
                },
                onStyleChange = viewModel.settings::setSubtitleStyle,
                onClose = { panel = Panel.NONE },
                modifier = panelModifier,
            )

            Panel.NONE -> Unit
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(player: Player, resizeMode: VideoResizeMode) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                // Only the picture is used; controls and subtitles are drawn by this screen.
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                subtitleView?.visibility = View.GONE
                setShutterBackgroundColor(AndroidColor.BLACK)
                this.player = player
            }
        },
        update = { playerView ->
            playerView.resizeMode = when (resizeMode) {
                VideoResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                VideoResizeMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                VideoResizeMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
        },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Draws the subtitles over the whole screen, not just over the picture, so that they can be
 * moved into the black bars of a wide film.
 */
@OptIn(UnstableApi::class)
@Composable
private fun SubtitleLayer(cues: List<Cue>, style: SubtitleStyle, sample: String?) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Sized by the shorter side, so that the text stays the same when the phone is turned.
        val textSizePx = min(constraints.maxWidth, constraints.maxHeight) *
            SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * style.scale
        val arranged = remember(cues, style.position, sample) { arrangeCues(cues, style.position, sample) }
        AndroidView(
            factory = { context -> SubtitleView(context) },
            update = { subtitleView ->
                subtitleView.setStyle(
                    CaptionStyleCompat(
                        style.color,
                        if (style.background) 0xB3000000.toInt() else AndroidColor.TRANSPARENT,
                        AndroidColor.TRANSPARENT,
                        if (style.background) CaptionStyleCompat.EDGE_TYPE_NONE else CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                        AndroidColor.BLACK,
                        null,
                    ),
                )
                subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
                subtitleView.setCues(arranged)
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Applies the viewer's placement to the subtitles of the moment: all text is joined into one
 * block at the chosen height, and colors and sizes baked into the file are dropped in favor of
 * the chosen ones. Picture subtitles (as on DVDs and Blu-rays) are passed through untouched.
 */
@OptIn(UnstableApi::class)
private fun arrangeCues(cues: List<Cue>, position: Float, sample: String?): List<Cue> {
    val pictures = cues.filter { it.bitmap != null }
    val texts = cues.mapNotNull { cue -> cue.text?.takeIf { cue.bitmap == null && it.isNotBlank() } }
    val text: CharSequence = when {
        texts.isNotEmpty() -> {
            val joined = SpannableStringBuilder()
            texts.forEachIndexed { index, line ->
                if (index > 0) joined.append('\n')
                joined.append(line)
            }
            for (span in joined.getSpans(0, joined.length, Any::class.java)) {
                if (span is ForegroundColorSpan || span is BackgroundColorSpan ||
                    span is AbsoluteSizeSpan || span is RelativeSizeSpan
                ) {
                    joined.removeSpan(span)
                }
            }
            joined
        }
        pictures.isEmpty() && sample != null -> sample
        else -> return pictures
    }
    return pictures + Cue.Builder()
        .setText(text)
        .setLine(1f - position, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_END)
        .build()
}

/**
 * Taps and swipes on the picture: a tap shows or hides the controls, a double tap on either
 * half skips 10 seconds, a vertical swipe changes brightness (left half) or volume (right half).
 */
@Composable
private fun GestureLayer(
    onTap: (leftHalf: Boolean) -> Unit,
    levelOf: (Gesture) -> Float,
    onLevel: (Gesture, Float) -> Unit,
    onSwipingChange: (active: Boolean, gesture: Gesture) -> Unit,
) {
    val currentOnTap by rememberUpdatedState(onTap)
    val currentLevelOf by rememberUpdatedState(levelOf)
    val currentOnLevel by rememberUpdatedState(onLevel)
    val currentOnSwipingChange by rememberUpdatedState(onSwipingChange)

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // No double tap callback on purpose: with one, every single tap would be held
                // back until it is certain that no second tap follows.
                detectTapGestures(onTap = { offset -> currentOnTap(offset.x < size.width / 2f) })
            }
            .pointerInput(Unit) {
                var gesture: Gesture? = null
                var level = 0f
                val finish = {
                    gesture?.let { currentOnSwipingChange(false, it) }
                    gesture = null
                }
                detectVerticalDragGestures(
                    onDragStart = { start ->
                        val nearEdge = start.y < size.height * EDGE_FRACTION ||
                            start.y > size.height * (1 - EDGE_FRACTION)
                        gesture = when {
                            nearEdge -> null
                            start.x < size.width / 2f -> Gesture.BRIGHTNESS
                            else -> Gesture.VOLUME
                        }
                        gesture?.let {
                            level = currentLevelOf(it)
                            currentOnSwipingChange(true, it)
                        }
                    },
                    onDragEnd = finish,
                    onDragCancel = finish,
                    onVerticalDrag = { change, dragAmount ->
                        gesture?.let {
                            change.consume()
                            level = (level - dragAmount / (size.height * SWIPE_RANGE_FRACTION)).coerceIn(0f, 1f)
                            currentOnLevel(it, level)
                        }
                    },
                )
            },
    )
}

/** The controls the remote control is sent to. */
private class ControlsFocus(val play: FocusRequester, val audio: FocusRequester, val subtitles: FocusRequester)

@Composable
private fun Controls(
    state: VideoUiState,
    position: () -> Long,
    showRotation: Boolean,
    focus: ControlsFocus,
    onFocusChange: (Boolean) -> Unit,
    orientationLocked: Boolean,
    onInteraction: () -> Unit,
    onClose: () -> Unit,
    onShowAudio: () -> Unit,
    onShowSubtitles: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleOrientationLock: () -> Unit,
    onRotate: () -> Unit,
    onCycleResize: () -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .onFocusChanged { onFocusChange(it.hasFocus) },
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose, modifier = Modifier.focusFrame(CircleShape, Color.White)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back))
                }
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                )
                IconButton(
                    onClick = onShowAudio,
                    modifier = Modifier
                        .focusRequester(focus.audio)
                        .focusFrame(CircleShape, Color.White),
                ) {
                    Icon(Icons.Rounded.Audiotrack, stringResource(R.string.video_audio_tracks))
                }
                IconButton(
                    onClick = onShowSubtitles,
                    modifier = Modifier
                        .focusRequester(focus.subtitles)
                        .focusFrame(CircleShape, Color.White),
                ) {
                    Icon(Icons.Rounded.Subtitles, stringResource(R.string.video_subtitles))
                }
            }

            // An error message takes this place when playback has failed.
            if (state.error == null) {
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { onInteraction(); onPrevious() },
                        modifier = Modifier
                            .size(56.dp)
                            .focusFrame(CircleShape, Color.White),
                    ) {
                        Icon(
                            Icons.Rounded.SkipPrevious,
                            stringResource(R.string.cd_previous),
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    FilledIconButton(
                        onClick = { onInteraction(); onTogglePlay() },
                        modifier = Modifier
                            .size(72.dp)
                            .focusRequester(focus.play)
                            .focusFrame(CircleShape, Color.White),
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(if (state.isPlaying) R.string.cd_pause else R.string.cd_play),
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    IconButton(
                        onClick = { onInteraction(); onNext() },
                        enabled = state.hasNext,
                        modifier = Modifier
                            .size(56.dp)
                            .focusFrame(CircleShape, Color.White),
                    ) {
                        Icon(
                            Icons.Rounded.SkipNext,
                            stringResource(R.string.cd_next),
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                SeekRow(state.durationMs, position, onInteraction, onSeek)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showRotation) {
                        IconButton(onClick = { onInteraction(); onToggleOrientationLock() }) {
                            Icon(
                                imageVector = Icons.Rounded.ScreenLockRotation,
                                contentDescription = stringResource(
                                    if (orientationLocked) R.string.video_unlock_rotation else R.string.video_lock_rotation,
                                ),
                                // Lit while the lock is on, like the shuffle toggle of the music player.
                                tint = if (orientationLocked) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.White.copy(alpha = 0.6f)
                                },
                            )
                        }
                        IconButton(onClick = { onInteraction(); onRotate() }) {
                            Icon(Icons.Rounded.ScreenRotation, stringResource(R.string.video_rotate))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { onInteraction(); onCycleResize() },
                        modifier = Modifier.focusFrame(CircleShape, Color.White),
                    ) {
                        Icon(Icons.Rounded.AspectRatio, stringResource(R.string.video_resize))
                    }
                }
            }
        }
    }
}

@Composable
private fun SeekRow(durationMs: Long, position: () -> Long, onInteraction: () -> Unit, onSeek: (Long) -> Unit) {
    var positionMs by remember { mutableLongStateOf(position()) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            positionMs = position()
            delay(250)
        }
    }
    val fraction = dragFraction
        ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatTime(dragFraction?.let { (it * durationMs).toLong() } ?: positionMs),
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = fraction,
            onValueChange = {
                dragFraction = it
                onInteraction()
            },
            onValueChangeFinished = {
                dragFraction?.let { target ->
                    val targetMs = (target * durationMs).toLong()
                    positionMs = targetMs
                    onSeek(targetMs)
                }
                dragFraction = null
            },
            enabled = durationMs > 0,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .focusFrame(RoundedCornerShape(50), Color.White)
                // On the remote control the arrows skip like they do with the controls hidden.
                .remoteSlider { direction ->
                    onInteraction()
                    val targetMs = (position() + direction * SEEK_STEP_MS).coerceIn(0, durationMs)
                    positionMs = targetMs
                    onSeek(targetMs)
                },
        )
        Text(text = formatTime(durationMs), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun IndicatorBubble(indicator: Indicator, modifier: Modifier = Modifier) {
    Surface(
        color = Color.Black.copy(alpha = 0.65f),
        contentColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(indicator.icon, contentDescription = null, modifier = Modifier.size(32.dp))
            if (indicator.text != null) {
                Spacer(Modifier.width(12.dp))
                Text(indicator.text, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Speed and position while fast forwarding or rewinding, e.g. "8× 0:42:10 / 1:45:00". */
@Composable
private fun TrickPlayIndicator(speed: Int, position: () -> Long, durationMs: Long, modifier: Modifier = Modifier) {
    var positionMs by remember { mutableLongStateOf(position()) }
    LaunchedEffect(Unit) {
        while (true) {
            positionMs = position()
            delay(100)
        }
    }
    val time = if (durationMs > 0) formatTime(positionMs) + " / " + formatTime(durationMs) else formatTime(positionMs)
    IndicatorBubble(
        Indicator(
            icon = if (speed < 0) Icons.Rounded.FastRewind else Icons.Rounded.FastForward,
            text = "${abs(speed)}×  $time",
        ),
        modifier,
    )
}

/**
 * Keys for a slider on a remote control: left and right call [onStep] with -1 or 1, up and
 * down move on to the next control. A slider would otherwise keep all four arrows to itself,
 * and nobody could get past it.
 */
private fun Modifier.remoteSlider(onStep: (direction: Int) -> Unit): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val currentOnStep by rememberUpdatedState(onStep)
    onPreviewKeyEvent { event ->
        val down = event.type == KeyEventType.KeyDown
        when (event.key) {
            Key.DirectionLeft -> if (down) currentOnStep(-1)
            Key.DirectionRight -> if (down) currentOnStep(1)
            Key.DirectionUp -> if (down) focusManager.moveFocus(FocusDirection.Up)
            Key.DirectionDown -> if (down) focusManager.moveFocus(FocusDirection.Down)
            else -> return@onPreviewKeyEvent false
        }
        true
    }
}

@Composable
private fun ErrorMessage(text: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(32.dp)
            .widthIn(max = 420.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(16.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

@Composable
private fun Notice(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onDismiss,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 96.dp)
            .widthIn(max = 480.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun AudioPanel(
    tracks: List<TrackOption>,
    onSelect: (TrackOption) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPanel(stringResource(R.string.video_audio_tracks), onClose, modifier) {
        if (tracks.isEmpty()) {
            Text(
                text = stringResource(R.string.video_no_audio_tracks),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        for (track in tracks) {
            TrackRow(
                label = if (track.supported) {
                    track.label
                } else {
                    stringResource(R.string.video_track_unsupported, track.label)
                },
                selected = track.selected,
                enabled = track.supported,
                onClick = { onSelect(track) },
            )
        }
    }
}

@Composable
private fun SubtitlePanel(
    state: VideoUiState,
    style: SubtitleStyle,
    onSelect: (TrackOption) -> Unit,
    onDisable: () -> Unit,
    onOpenFile: () -> Unit,
    onStyleChange: (SubtitleStyle) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPanel(stringResource(R.string.video_subtitles), onClose, modifier) {
        val anySelected = state.subtitlesEnabled && state.subtitleTracks.any { it.selected }
        TrackRow(
            label = stringResource(R.string.video_subtitles_off),
            selected = !anySelected,
            onClick = onDisable,
        )
        for (track in state.subtitleTracks) {
            TrackRow(
                label = if (track.supported) {
                    track.label
                } else {
                    stringResource(R.string.video_track_unsupported, track.label)
                },
                selected = state.subtitlesEnabled && track.selected,
                enabled = track.supported,
                onClick = { onSelect(track) },
            )
        }
        TextButton(
            onClick = onOpenFile,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .focusFrame(),
        ) {
            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.video_subtitle_open_file))
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        LabeledSlider(
            label = stringResource(R.string.video_subtitle_size),
            value = style.scale,
            range = SubtitleStyle.MIN_SCALE..SubtitleStyle.MAX_SCALE,
            onChange = { onStyleChange(style.copy(scale = it)) },
        )
        LabeledSlider(
            label = stringResource(R.string.video_subtitle_position),
            value = style.position,
            range = 0f..SubtitleStyle.MAX_POSITION,
            onChange = { onStyleChange(style.copy(position = it)) },
        )

        Text(
            text = stringResource(R.string.video_subtitle_color),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 20.dp, top = 4.dp),
        )
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (color in SubtitleStyle.COLORS) {
                val selected = color == style.color
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(color))
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                        )
                        .focusFrame(CircleShape, MaterialTheme.colorScheme.onSurface)
                        .clickable { onStyleChange(style.copy(color = color)) },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusFrame()
                .clickable { onStyleChange(style.copy(background = !style.background)) }
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.video_subtitle_background),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = style.background, onCheckedChange = null)
        }
    }
}

/** A translucent card in the corner; the picture and the subtitles stay visible next to it. */
@Composable
private fun SettingsPanel(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp)
            .widthIn(max = 340.dp),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 20.dp),
                )
                IconButton(onClick = onClose, modifier = Modifier.focusFrame(CircleShape)) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.action_close))
                }
            }
            content()
        }
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusFrame()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            },
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier
                .focusFrame(RoundedCornerShape(50))
                .remoteSlider { direction ->
                    val step = (range.endInclusive - range.start) / SLIDER_STEPS
                    onChange((value + direction * step).coerceIn(range))
                },
        )
    }
}
