package io.github.bestinepayyappilly.tvairplay.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

fun Context.isTvDevice(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType ==
        Configuration.UI_MODE_TYPE_TELEVISION

@Composable
fun isTv(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) { ctx.isTvDevice() }
}

// the focused control lights up as a whole (tinted fill, slight lift), not just an outline
// scale = false for full-width rows and controls inside a group, where growing overlaps neighbors
fun Modifier.dpadFocus(shape: Shape = RoundedCornerShape(12.dp), scale: Boolean = true): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    focusHighlight(focused, shape, scale).onFocusChanged { focused = it.isFocused }
}

fun Modifier.focusHighlight(focused: Boolean, shape: Shape, scale: Boolean = true): Modifier = composed {
    val amount by animateFloatAsState(if (focused) 1f else 0f, tween(140), label = "focus")
    val tint = MaterialTheme.colorScheme.primary
    graphicsLayer {
        val s = if (scale) 1f + 0.03f * amount else 1f
        scaleX = s
        scaleY = s
    }.drawWithContent {
        drawContent()
        if (amount > 0f) {
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(outline, tint.copy(alpha = 0.16f * amount))
            drawOutline(outline, Color.White.copy(alpha = 0.04f * amount))
        }
    }
}

fun Modifier.dpadAdjust(onLeft: () -> Unit, onRight: () -> Unit): Modifier = composed {
    val focus = LocalFocusManager.current
    onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionLeft -> { onLeft(); true }
            Key.DirectionRight -> { onRight(); true }
            // slider
            Key.DirectionUp -> { focus.moveFocus(FocusDirection.Up); true }
            Key.DirectionDown -> { focus.moveFocus(FocusDirection.Down); true }
            else -> false
        }
    }
}
