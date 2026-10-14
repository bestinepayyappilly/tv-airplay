package com.castbay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Airplay
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.TabletMac
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One destination in the sidebar. */
class TvNavItem(val label: String, val icon: ImageVector, val selected: Boolean, val onSelect: () -> Unit)

/** What the bottom card shows: the connected sender, or the receiver waiting for one. */
class TvDeviceStatus(val title: String, val subtitle: String, val connected: Boolean, val receiverName: String)

private val Backdrop = Color(0xFF060606)
// idle backdrop light: faint neutral greys, so a playing cover's colors are the only hue
private val GlowLow = Color(0xFF8A8A8A)
private val GlowHigh = Color(0xFF5A5A5A)
private val Connected = Color(0xFF34D399)
private val Idle = Color(0xFF71717A)
private val Ink = Color(0xFF0B0B12)

// collapsed: a 48dp circle per item plus the panel's 10dp padding each side
private val ItemSize = 48.dp
private val PanelPad = 10.dp
private val RailWidth = ItemSize + PanelPad * 2
private val ExpandedWidth = 260.dp
// concentric with the round items: item radius plus the panel padding
private val PanelShape = RoundedCornerShape(ItemSize / 2 + PanelPad)
// shell margins; the content uses the same top and bottom so the cards line up with the panel
internal val TvShellEdge = 24.dp

/**
 * TV layout: a dark backdrop, a glass sidebar on the left that opens while the remote's focus is
 * in it and folds to an icon rail otherwise, and the sender (or the receiver's own state) pinned
 * at its foot. Content fills the rest.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvShell(
    items: List<TvNavItem>,
    status: TvDeviceStatus,
    // colors to light the backdrop with (the playing track's cover); null keeps the neutral grey
    glows: List<Color>? = null,
    content: @Composable () -> Unit,
) {
    val g0 by animateColorAsState(glows?.getOrNull(0) ?: GlowLow, tween(1200), label = "glow0")
    val g1 by animateColorAsState(glows?.getOrNull(1) ?: GlowHigh, tween(1200), label = "glow1")
    val g2 by animateColorAsState(glows?.getOrNull(2) ?: Color.Transparent, tween(1200), label = "glow2")
    val strength by animateFloatAsState(if (glows != null) 1f else 0f, tween(1200), label = "glowStrength")
    var expanded by remember { mutableStateOf(false) }
    // entering the sidebar from the content lands on the current page, not the nearest item
    val requesters = remember(items.size) { List(items.size) { FocusRequester() } }
    val current = items.indexOfFirst { it.selected }.coerceAtLeast(0)
    val width by animateDpAsState(if (expanded) ExpandedWidth else RailWidth, tween(240), label = "sidebar")

    // there's no Surface under the shell, so set the text color a Surface would have set
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Backdrop)
            .drawBehind {
                // light for the glass to catch: low on the left and a wash top right, in neutral
                // grey by default and in the cover's colors (a bit stronger, plus a third) while a track plays
                drawRect(Brush.radialGradient(
                    listOf(g0.copy(alpha = 0.26f + 0.10f * strength), Color.Transparent),
                    center = Offset(size.width * 0.06f, size.height * 0.95f),
                    radius = size.height * 0.95f,
                ))
                drawRect(Brush.radialGradient(
                    listOf(g1.copy(alpha = 0.12f + 0.16f * strength), Color.Transparent),
                    center = Offset(size.width * 0.96f, size.height * 0.02f),
                    radius = size.height * 0.85f,
                ))
                drawRect(Brush.radialGradient(
                    listOf(g2.copy(alpha = 0.18f * strength), Color.Transparent),
                    center = Offset(size.width * 0.70f, size.height * 1.05f),
                    radius = size.height * 0.80f,
                ))
            }
    ) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .padding(start = TvShellEdge, top = TvShellEdge, bottom = TvShellEdge)
                    .width(width)
                    .fillMaxHeight()
                    .tvGlassCard(PanelShape)
                    .onFocusChanged { expanded = it.hasFocus }
                    .focusProperties { enter = { requesters[current] } }
                    .focusGroup()
                    .padding(PanelPad),
            ) {
                Header(status.receiverName, expanded)
                Spacer(Modifier.height(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items.forEachIndexed { i, item -> NavRow(item, expanded, requesters[i]) }
                }
                Spacer(Modifier.weight(1f))
                DeviceCard(status, expanded)
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 16.dp, top = TvShellEdge, end = TvShellEdge, bottom = TvShellEdge)
            ) { content() }
        }
    }
    }
}

/** The sidebar and content cards on TV: dark tinted glass, so the backdrop's glow reads through them. */
fun Modifier.tvGlassCard(shape: Shape): Modifier = this
    .clip(shape)
    .background(Color.Black.copy(alpha = 0.42f))
    .background(Brush.verticalGradient(listOf(Color(0x0FFFFFFF), Color.Transparent)))
    .border(1.dp, Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x0AFFFFFF), Color(0x14FFFFFF))), shape)

@Composable
private fun Header(receiverName: String, expanded: Boolean) {
    // fixed height and single-line text: while the panel widens, wrapping text would grow the row
    // and push the badge down
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(ItemSize)) {
        Box(
            Modifier.size(ItemSize).clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color.White, Color(0xFFA1A1AA)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Airplay, null, tint = Ink, modifier = Modifier.size(22.dp))
        }
        AnimatedVisibility(expanded, enter = fadeIn(tween(180)), exit = fadeOut(tween(90))) {
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    "AirPlay",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    receiverName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NavRow(item: TvNavItem, expanded: Boolean, requester: FocusRequester) {
    var focused by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(if (focused) 1.04f else 1f, tween(140), label = "lift")
    // focused: solid white with dark content; current page: glass; the rest: bare icons.
    // A circle while collapsed, a pill while open.
    val content by animateColorAsState(
        when {
            focused -> Ink
            item.selected -> Color.White
            else -> Color.White.copy(alpha = 0.62f)
        }, tween(140), label = "content",
    )
    val fill by animateColorAsState(
        when {
            focused -> Color.White
            item.selected -> Color.White.copy(alpha = 0.12f)
            else -> Color.Transparent
        }, tween(140), label = "fill",
    )
    val edge by animateColorAsState(
        if (item.selected && !focused) Color.White.copy(alpha = 0.18f) else Color.Transparent,
        tween(140), label = "edge",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(ItemSize)
            .graphicsLayer { scaleX = lift; scaleY = lift }
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, edge, CircleShape)
            .focusRequester(requester)
            // moving through the list previews each page, as TV launchers do
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused && !item.selected) item.onSelect()
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { item.onSelect() }
            // centers the 22dp icon in the 48dp circle
            .padding(horizontal = 13.dp),
    ) {
        Icon(item.icon, null, tint = content, modifier = Modifier.size(22.dp))
        AnimatedVisibility(expanded, enter = fadeIn(tween(180)), exit = fadeOut(tween(90))) {
            Text(
                item.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (focused || item.selected) FontWeight.SemiBold else FontWeight.Medium,
                color = content,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
    }
}

@Composable
private fun DeviceCard(status: TvDeviceStatus, expanded: Boolean) {
    val icon = when {
        !status.connected -> Icons.Rounded.Airplay
        status.title.contains("Mac", ignoreCase = true) -> Icons.Rounded.Laptop
        status.title.contains("iPad", ignoreCase = true) -> Icons.Rounded.TabletMac
        status.title.contains("iPhone", ignoreCase = true) -> Icons.Rounded.PhoneIphone
        else -> Icons.Rounded.Devices
    }
    val accent = if (status.connected) Connected else Idle
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(ItemSize)) {
            Box(Modifier.size(ItemSize), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(ItemSize).clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = Color.White.copy(alpha = 0.92f), modifier = Modifier.size(20.dp))
                }
                Box(
                    Modifier.align(Alignment.BottomEnd)
                        .size(13.dp).clip(CircleShape).background(Backdrop)
                        .padding(3.dp).clip(CircleShape).background(accent)
                )
            }
            AnimatedVisibility(expanded, enter = fadeIn(tween(180)), exit = fadeOut(tween(90))) {
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        status.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        status.subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                        color = accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(accent.copy(alpha = 0.14f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
