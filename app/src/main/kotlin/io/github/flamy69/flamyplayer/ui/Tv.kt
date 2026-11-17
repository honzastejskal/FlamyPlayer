package io.github.flamy69.flamyplayer.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.runtime.DisposableEffect
import android.view.WindowManager
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
 * Which text field of a dialog is being written in on a TV. A text field opens the keyboard as
 * soon as it gets focus, also the first field of a dialog by itself, and some TV keyboards
 * cover half the screen and keep the arrows: the remote control would be trapped. So on a TV
 * the fields are read only, which keeps the keyboard away, until OK is pressed on one.
 */
class TvTextFields {
    var editing: Any? by mutableStateOf(null)

    /** Whether the field [key] is read only for now; never on a phone. */
    fun readOnly(isTv: Boolean, key: Any): Boolean = isTv && editing != key
}

@Composable
fun rememberTvTextFields(): TvTextFields = remember { TvTextFields() }

/**
 * On a TV: OK on the text field [key] makes it writable and opens the keyboard; leaving the
 * field makes it read only again. Up and down leave the field like any other element; the
 * field would keep these arrows for its cursor.
 */
fun Modifier.tvTextField(fields: TvTextFields, key: Any): Modifier = composed {
    if (!rememberIsTv()) return@composed this
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(fields.editing) {
        if (fields.editing == key) {
            withFrameNanos { } // writable from the next frame on
            keyboard?.show()
        }
    }
    onFocusChanged { if (!it.hasFocus && fields.editing == key) fields.editing = null }
        .onPreviewKeyEvent { event ->
            val ok = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
            // Letting go of OK would open the keyboard as well, also when the press opened the
            // dialog: OK is handled here, when pressed.
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent ok
            when {
                event.key == Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                event.key == Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                ok -> {
                    if (fields.editing == key) keyboard?.show() else fields.editing = key
                    true
                }
                else -> false
            }
        }
}

/**
 * Call inside a dialog with text fields. On a TV, Android itself opens the keyboard whenever a
 * window with a text field gets focus, the dialog's own first and again after every Back that
 * closed the keyboard, which traps the remote control in it. This leaves the keyboard to [forTv].
 */
@Composable
fun KeyboardOnlyOnRequestInDialog() {
    if (!rememberIsTv()) return
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    DisposableEffect(window) {
        val before = window.attributes.softInputMode
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                (before and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST),
        )
        onDispose { window.setSoftInputMode(before) }
    }
}

/** Asks for focus; false when the element is not on screen (yet), which is no error here. */
fun FocusRequester.tryRequestFocus(): Boolean = try {
    requestFocus()
} catch (_: IllegalStateException) {
    false
}
