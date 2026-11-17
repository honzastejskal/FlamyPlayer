package io.github.flamy69.flamyplayer.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Whether the app runs on a TV, where it is used with a remote control instead of touch. */
val Context.isTv: Boolean
    get() = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

@Composable
fun rememberIsTv(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.isTv }
}

/**
 * A clear frame around the element while it has focus, so that it can be seen from the sofa
 * where the remote control is. Goes before the `clickable` or `focusable` it frames. Touch never
 * focuses anything, so on a phone nothing changes.
 */
fun Modifier.focusFrame(shape: Shape = RoundedCornerShape(12.dp), color: Color? = null): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val frameColor = color ?: MaterialTheme.colorScheme.primary
    onFocusChanged { focused = it.isFocused }
        .border(width = 3.dp, color = if (focused) frameColor else Color.Transparent, shape = shape)
}

/** Asks for focus; false when the element is not on screen (yet), which is no error here. */
fun FocusRequester.tryRequestFocus(): Boolean = try {
    requestFocus()
} catch (_: IllegalStateException) {
    false
}
