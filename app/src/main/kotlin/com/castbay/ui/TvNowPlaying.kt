package com.castbay.ui

import androidx.annotation.OptIn as AndroidxOptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.material3.buttons.NextButton
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.buttons.PreviousButton
import com.castbay.R
import com.castbay.viewmodel.MainViewModel
import kotlinx.coroutines.delay

/**
 * TV now-playing: a large cover on the left; title, artist and album on the right, a thin
 * progress line, and the transport and volume controls at the bottom right.
 */
@Composable
@AndroidxOptIn(UnstableApi::class)
fun TvNowPlaying(viewModel: MainViewModel) {
    val track by viewModel.trackInfo.collectAsState()
    val player = viewModel.dacpPlayer

    Row(Modifier.fillMaxSize().padding(28.dp)) {
        Box(
            Modifier
                .fillMaxHeight()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            val cover = track.coverArt
            if (cover != null) {
                Image(
                    bitmap = remember(cover) { cover.asImageBitmap() },
                    contentDescription = stringResource(R.string.cd_cover_art),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    Icons.Default.MusicNote, null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
        }

        Spacer(Modifier.width(32.dp))

        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text(
                track.title.ifEmpty { stringResource(R.string.unknown_track) },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (track.artist.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    track.artist,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (track.album.isNotEmpty() && track.album != track.title) {
                Spacer(Modifier.height(2.dp))
                Text(
                    track.album,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.weight(1f))

            if (player != null) {
                ProgressLine(player)
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { viewModel.audioVolumeDown() }, modifier = Modifier.dpadFocus(CircleShape)) {
                        Icon(Icons.AutoMirrored.Rounded.VolumeDown, stringResource(R.string.cd_volume_down))
                    }
                    IconButton(onClick = { viewModel.audioMuteToggle() }, modifier = Modifier.dpadFocus(CircleShape)) {
                        Icon(Icons.AutoMirrored.Rounded.VolumeOff, stringResource(R.string.cd_mute))
                    }
                    IconButton(onClick = { viewModel.audioVolumeUp() }, modifier = Modifier.dpadFocus(CircleShape)) {
                        Icon(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.cd_volume_up))
                    }
                    Spacer(Modifier.width(16.dp))
                    PreviousButton(player, modifier = Modifier.dpadFocus(CircleShape))
                    PlayPauseButton(player, modifier = Modifier.size(52.dp).dpadFocus(CircleShape), iconSize = 32.dp)
                    NextButton(player, modifier = Modifier.dpadFocus(CircleShape))
                }
            }
        }
    }
}

// a 4dp line with elapsed and total time beneath, YouTube style; not focusable since AirPlay
// audio can't be seeked from the receiver
@Composable
private fun ProgressLine(player: Player) {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
            delay(500)
        }
    }
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val fill = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(4.dp).drawBehind {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = r)
                drawRoundRect(fill, size = Size(size.width * fraction, size.height), cornerRadius = r)
            }
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val style = MaterialTheme.typography.labelMedium
            val color = MaterialTheme.colorScheme.onSurfaceVariant
            Text(_clock(position), style = style, color = color)
            Text(if (duration > 0) _clock(duration) else "--:--", style = style, color = color)
        }
    }
}

private fun _clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}
