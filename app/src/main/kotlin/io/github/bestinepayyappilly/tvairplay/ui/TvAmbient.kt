package io.github.bestinepayyappilly.tvairplay.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

internal val FallbackGlows = listOf(Color(0xFF9A9A9A), Color(0xFF6B6B6B), Color(0xFF4A4A4A))

/**
 * Idle now-playing view: pitch black, slow glows tinted from the cover, the cover square and
 * centered, and the title alone. The caller shows it after a few quiet seconds of playback.
 */
@Composable
fun TvAmbientNowPlaying(cover: Bitmap?, title: String, modifier: Modifier = Modifier) {
    val palette = rememberCoverGlows(cover) ?: FallbackGlows
    // cross-fade the glows when the track (and cover) changes
    val c0 by animateColorAsState(palette[0], tween(1200), label = "glow0")
    val c1 by animateColorAsState(palette[1], tween(1200), label = "glow1")
    val c2 by animateColorAsState(palette[2], tween(1200), label = "glow2")

    val drift = rememberInfiniteTransition(label = "drift")
    val t by drift.animateFloat(
        0f, (2 * Math.PI).toFloat(),
        infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart),
        label = "t",
    )

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .drawBehind {
                val w = size.width
                val h = size.height
                fun glow(color: Color, cx: Float, cy: Float, r: Float, a: Float) = drawRect(
                    Brush.radialGradient(
                        listOf(color.copy(alpha = a), Color.Transparent),
                        center = Offset(cx, cy),
                        radius = r,
                    )
                )
                glow(c0, w * (0.28f + 0.08f * cos(t)), h * (0.30f + 0.10f * sin(t)), h * 0.95f, 0.30f)
                glow(c1, w * (0.74f + 0.07f * sin(t * 2)), h * (0.72f + 0.08f * cos(t)), h * 0.90f, 0.26f)
                glow(c2, w * (0.52f + 0.10f * sin(t + 2f)), h * (0.92f + 0.05f * cos(t * 2)), h * 0.70f, 0.18f)
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxHeight().padding(vertical = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .aspectRatio(1f)
                    .drawBehind {
                        // soft halo in the cover's own color so it sits in the light
                        drawRect(
                            Brush.radialGradient(
                                listOf(c0.copy(alpha = 0.35f), Color.Transparent),
                                center = center,
                                radius = size.minDimension * 0.95f,
                            ),
                            topLeft = Offset(-size.width * 0.5f, -size.height * 0.5f),
                            size = size * 2f,
                        )
                    }
                    .background(Color(0xFF111111)),
                contentAlignment = Alignment.Center,
            ) {
                if (cover != null) {
                    Image(
                        bitmap = remember(cover) { cover.asImageBitmap() },
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                        tint = Color.White.copy(alpha = 0.3f),
                    )
                }
            }
            Spacer(Modifier.height(36.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.7f),
            )
        }
    }
}

/** Glow colors for a cover, or null without one; computed once per bitmap. */
@Composable
fun rememberCoverGlows(cover: Bitmap?): List<Color>? = remember(cover) { cover?.let(::_glowColors) }

// three glow colors from the cover: the most common saturated hues, brightened to read on black
private fun _glowColors(cover: Bitmap): List<Color> {
    val small = Bitmap.createScaledBitmap(cover, 24, 24, true)
    val buckets = HashMap<Int, FloatArray>() // hue bucket -> sum r, g, b, weight
    val hsv = FloatArray(3)
    for (y in 0 until small.height) for (x in 0 until small.width) {
        val p = small.getPixel(x, y)
        android.graphics.Color.colorToHSV(p, hsv)
        if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue
        val weight = hsv[1] * hsv[2]
        val acc = buckets.getOrPut((hsv[0] / 30f).toInt()) { FloatArray(4) }
        acc[0] += android.graphics.Color.red(p) * weight
        acc[1] += android.graphics.Color.green(p) * weight
        acc[2] += android.graphics.Color.blue(p) * weight
        acc[3] += weight
    }
    if (small != cover) small.recycle()
    val picked = buckets.values.sortedByDescending { it[3] }.take(3).map { acc ->
        val c = android.graphics.Color.rgb(
            (acc[0] / acc[3]).toInt(), (acc[1] / acc[3]).toInt(), (acc[2] / acc[3]).toInt()
        )
        android.graphics.Color.colorToHSV(c, hsv)
        hsv[1] = hsv[1].coerceAtLeast(0.55f)
        hsv[2] = hsv[2].coerceAtLeast(0.85f)
        Color(android.graphics.Color.HSVToColor(hsv))
    }
    return when (picked.size) {
        0 -> FallbackGlows
        1 -> listOf(picked[0], picked[0], FallbackGlows[0])
        2 -> listOf(picked[0], picked[1], picked[0])
        else -> picked
    }
}
