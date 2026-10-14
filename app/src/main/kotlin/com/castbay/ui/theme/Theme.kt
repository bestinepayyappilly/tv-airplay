package com.castbay.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.castbay.ui.isTvDevice

@Composable
fun AirPlayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val ctx = LocalContext.current
    val tv = remember(ctx) { ctx.isTvDevice() }
    val colorScheme = when {
        // the TV shell (TvShell) is dark glass over a soft neutral glow; match it everywhere on TV
        tv -> TvColors
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(colorScheme = colorScheme) {
        // no touch on TV: drop the 48dp touch-target padding so the focus highlight hugs each control
        if (tv) CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp, content = content)
        else content()
    }
}

// monochrome: white accents on neutral black, so cover art is the only color on screen
private val TvColors = darkColorScheme(
    primary = Color(0xFFF4F4F5),
    onPrimary = Color(0xFF0B0B0C),
    primaryContainer = Color(0xFF2E2E31),
    onPrimaryContainer = Color(0xFFF4F4F5),
    secondary = Color(0xFFD4D4D8),
    onSecondary = Color(0xFF0B0B0C),
    secondaryContainer = Color(0xFF27272A),
    onSecondaryContainer = Color(0xFFF4F4F5),
    background = Color(0xFF080808),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF101010),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFFA1A1A1),
    surfaceContainer = Color(0xFF161616),
    surfaceContainerHigh = Color(0xFF1E1E1E),
    surfaceContainerHighest = Color(0xFF262626),
    outline = Color(0xFF424242),
    outlineVariant = Color(0xFF2A2A2A),
)
