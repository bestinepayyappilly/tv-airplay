package io.github.bestinepayyappilly.tvairplay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Airplay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bestinepayyappilly.tvairplay.R

private val Base = Color(0xFF09070F)
private val GlowCore = Color(0xFF8B5CF6)
private val GlowMid = Color(0xFF5B2DB3)
private val GlowEdge = Color(0xFF2A1450)

/**
 * Full-screen cover from the moment a sender sets up a session until its first mirrored frame
 * is on screen: a purple glow rising from the bottom, the sender's name and a spinner.
 * Drawn above the video surface, so the decoder renders underneath while it shows.
 */
@Composable
fun ConnectingScreen(visible: Boolean, senderName: String?) {
    AnimatedVisibility(
        visible = visible,
        // no fade in: it covers the video, which must not show through first
        enter = EnterTransition.None,
        exit = fadeOut(tween(450)),
    ) {
        val glow = rememberInfiniteTransition(label = "glow")
        val breathe by glow.animateFloat(
            initialValue = 0.92f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breathe",
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Base)
                .drawBehind {
                    // centred on the bottom edge, fading out around the middle of the screen
                    val radius = size.height * 0.95f * breathe
                    drawRect(
                        Brush.radialGradient(
                            0f to GlowCore.copy(alpha = 0.85f),
                            0.35f to GlowMid.copy(alpha = 0.45f),
                            0.7f to GlowEdge.copy(alpha = 0.15f),
                            1f to Color.Transparent,
                            center = Offset(size.width / 2f, size.height),
                            radius = radius,
                        )
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 96.dp).padding(bottom = 48.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Airplay,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(28.dp))
                Text(
                    text = senderName ?: stringResource(R.string.connecting_title),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.connecting_subtitle),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(44.dp))
                CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.85f),
                    trackColor = Color.White.copy(alpha = 0.12f),
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
}
