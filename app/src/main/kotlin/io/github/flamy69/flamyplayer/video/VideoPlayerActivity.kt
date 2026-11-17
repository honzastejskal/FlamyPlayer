package io.github.flamy69.flamyplayer.video

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.flamy69.flamyplayer.data.OrientationMode
import io.github.flamy69.flamyplayer.ui.FlamyTheme

/**
 * The full screen video player. A separate activity, so that its orientation lock, brightness
 * and hidden system bars end with it and never leak into the rest of the app.
 */
class VideoPlayerActivity : ComponentActivity() {

    private val viewModel: VideoPlayerViewModel by viewModels()

    /**
     * Set by the player screen to see the keys of a remote control or keyboard before anything
     * else does; returns whether it used the key. Coming first matters for the fast forward and
     * rewind buttons: whatever the window leaves unused goes on to the media session, which
     * would only jump by a few seconds.
     */
    var keyHandler: ((KeyEvent) -> Boolean)? = null

    // The method is public Android API; lint mistakes it for the restricted one of
    // androidx.core.app.ComponentActivity, which ComponentActivity of androidx.activity extends.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        keyHandler?.invoke(event) == true || super.dispatchKeyEvent(event)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (viewModel.isEmpty) {
            finish()
            return
        }

        volumeControlStream = AudioManager.STREAM_MUSIC
        if (Build.VERSION.SDK_INT >= 28) {
            // Let the picture use the area next to a camera cutout.
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()
        setOrientation(viewModel.settings.orientation)
        viewModel.settings.brightness.takeIf { it >= 0f }?.let { windowBrightness = it }

        setContent {
            FlamyTheme(darkTheme = true) {
                VideoPlayerScreen(viewModel = viewModel, onClose = ::finish)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onStart()
    }

    override fun onStop() {
        viewModel.onStop()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The bars come back with dialogs and the volume panel.
        if (hasFocus) hideSystemBars()
    }

    fun setOrientation(mode: OrientationMode) {
        requestedOrientation = when (mode) {
            // Follows the sensor even when auto-rotate is switched off for the rest of the phone.
            OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /** The brightness of this window from 0 to 1; the system setting is left alone. */
    var windowBrightness: Float
        get() {
            val own = window.attributes.screenBrightness
            if (own >= 0f) return own
            // Only an estimate: the system value is not linear and ignores adaptive brightness.
            val system = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            return (system / 255f).coerceIn(0f, 1f)
        }
        set(value) {
            window.attributes = window.attributes.apply {
                // Zero would switch the backlight off completely on some phones.
                screenBrightness = value.coerceIn(0.01f, 1f)
            }
        }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
