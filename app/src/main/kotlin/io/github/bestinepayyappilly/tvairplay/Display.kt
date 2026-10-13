package io.github.bestinepayyappilly.tvairplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

// physical panel resolution
fun Context.realDisplaySize(): Pair<Int, Int> {
    val mode = getSystemService(DisplayManager::class.java)
        ?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
    return if (mode != null) mode.physicalWidth to mode.physicalHeight
    else resources.displayMetrics.let { it.widthPixels to it.heightPixels }
}

// scale (w, h) down to fit (maxW, maxH), keeping aspect and even dimensions
fun fitWithin(w: Int, h: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
    if (w <= maxW && h <= maxH) return w to h
    val f = minOf(maxW.toDouble() / w, maxH.toDouble() / h)
    return ((w * f).toInt() and 1.inv()) to ((h * f).toInt() and 1.inv())
}
