package io.github.bestinepayyappilly.tvairplay.ui

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView

// shared surface for mirroring (VideoRenderer) and airplay video (AirPlayVideoPlayer)
@Composable
fun VideoSurfaceView(
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
    aspectRatio: Float = 16f / 9f,
    // false = caller sizes the surface (content-scale modes)
    applyAspectRatio: Boolean = true,
    // black strips (px) over the top and bottom edges. A decoder rendering straight to the video
    // plane can show a few rows of padding there; the app window is drawn above the SurfaceView
    maskEdgesPx: Int = 0,
    modifier: Modifier = Modifier
) {
    val callbacks = remember {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                onSurfaceAvailable(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {
                onSurfaceAvailable(holder.surface)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                onSurfaceDestroyed(holder.surface)
            }
        }
    }

    val sized = if (applyAspectRatio) {
        modifier
            .aspectRatio(aspectRatio, matchHeightConstraintsFirst = aspectRatio < 1f)
            .fillMaxSize()
    } else {
        modifier
    }
    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).also {
                it.holder.addCallback(callbacks)
            }
        },
        modifier = if (maskEdgesPx <= 0) sized else sized.drawWithContent {
            drawContent()
            val h = maskEdgesPx.toFloat()
            drawRect(Color.Black, Offset.Zero, Size(size.width, h))
            drawRect(Color.Black, Offset(0f, size.height - h), Size(size.width, h))
        }
    )
}
