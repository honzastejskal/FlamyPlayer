package io.github.flamy69.flamyplayer.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

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

/**
 * On a TV, moves focus into a list as soon as it is shown, to the line [index] (which is
 * scrolled into view) or, when [index] is `null`, to wherever [requester] sits. Runs again
 * whenever [key] changes, e.g. for every new folder. Without this, focus stays wherever it was
 * when the old content disappeared, often down in the navigation bar.
 */
@Composable
fun FocusOnShow(key: Any?, requester: FocusRequester, listState: LazyListState? = null, index: Int? = null) {
    val isTv = rememberIsTv()
    LaunchedEffect(key) {
        if (!isTv) return@LaunchedEffect
        if (listState != null && index != null && index >= 0 &&
            listState.layoutInfo.visibleItemsInfo.none { it.index == index }
        ) {
            listState.scrollToItem(index)
        }
        // The line is laid out with the next frame, and some content is still loading: try
        // again for a moment.
        repeat(FOCUS_ATTEMPTS) {
            withFrameNanos { }
            if (requester.tryRequestFocus()) return@LaunchedEffect
            delay(FOCUS_RETRY_MS)
        }
    }
}

private const val FOCUS_ATTEMPTS = 20
private const val FOCUS_RETRY_MS = 100L

/**
 * On a TV, lets up and down leave a single line text field like any other element. The field
 * would keep these arrows for its cursor, and the remote control has no other way out.
 */
fun Modifier.leaveWithUpDown(): Modifier = composed {
    if (!rememberIsTv()) return@composed this
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
            // With [forTv] the keyboard only comes when asked for.
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                keyboard?.show()
                keyboard != null
            }
            else -> false
        }
    }
}

/**
 * On a TV, keeps the keyboard away until OK is pressed on the text field. Some TV keyboards
 * cover half the screen and come straight back after Back while the field has focus, so a
 * field that got focus by itself, or on the way past it, would trap the remote control.
 */
@Composable
fun KeyboardOptions.forTv(): KeyboardOptions = if (rememberIsTv()) copy(showKeyboardOnFocus = false) else this

/** Asks for focus; false when the element is not on screen (yet), which is no error here. */
fun FocusRequester.tryRequestFocus(): Boolean = try {
    requestFocus()
} catch (_: IllegalStateException) {
    false
}
