package io.github.bestinepayyappilly.tvairplay.renderer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import io.github.bestinepayyappilly.tvairplay.renderer.DecoderSelector.Companion.videoCaps

class VideoRenderer(ctx: Context) {

    private val lock = Object()
    private val stages = LatencyStages()
    private val pipeline = VideoPipeline().also { p ->
        p.onDrawn = { frameShown = true; stages.drawn(it / 1000) }
    }
    val selector = DecoderSelector(ctx)
    private var avcDecoder: MediaCodecInfo? = null
    private var hevcDecoder: MediaCodecInfo? = null
    private var maxFps = 0
    private var codec: MediaCodec? = null
    private var displaySurface: Surface? = null
    private var currentH265 = false
    private var videoWidth = 0
    private var videoHeight = 0
    // size the running codec was configured for
    private var codecWidth = 0
    private var codecHeight = 0
    private var firstFrameQueued = false
    // pipeline was torn down, so its display binding must be restored on restart
    private var pipelineReleased = false
    // direct: decode straight into the display surface, which the compositor puts on the video
    // plane (no GL pass; ~25 ms less on the OnePlus). With no display, the decoder renders into the
    // GL pipeline instead, which keeps the last frame to paint when a display comes back.
    // Otherwise everything goes through the pipeline.
    @Volatile var directRender = true
    // surface the codec renders to in direct mode
    @Volatile private var outputSurface: Surface? = null

    // stats
    @Volatile var fps = 0; private set
    @Volatile var bitrateBps = 0L; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var codecName = ""; private set
    @Volatile var droppedFrames = 0L; private set
    @Volatile var framePacingJitterUs = 0L; private set
    // a decoded frame was released for display since the session started
    @Volatile var frameShown = false; private set
    // frame arrival to decoded-frame release, mean and max over the last second
    @Volatile var decodeLatencyUs = 0L; private set
    @Volatile var decodeLatencyMaxUs = 0L; private set

    var enforceSdr = true
    var keyAllowFrameDrop = true
    var scheduledOutputBufferRelease = true
    var benchmarkLog = false
    var benchmarkLogCallback: ((String) -> Unit)? = null
    private var _framesThisSec = 0
    private var _bytesThisSec = 0L
    private var _lastStatReset = 0L
    private val _frameIntervalsNs = LongArray(120)
    private var _frameIntervalIdx = 0
    private var _frameIntervalCount = 0
    private var _lastOutputFrameNs = 0L
    // anchors that map decoder PTS (us) to System.nanoTime() for scheduled rendering
    private var _ptsBaseUs = Long.MIN_VALUE
    private var _wallBaseNs = 0L
    // output is drained on its own thread so a decoded frame shows as soon as it's ready,
    // not when the next input frame arrives (a static mirrored screen sends few frames)
    private var _drainThread: Thread? = null
    @Volatile private var _draining = false
    // arrival time per queued pts, matched when the decoded frame comes out
    private val _arrivalPts = LongArray(64)
    private val _arrivalNs = LongArray(64)
    private var _arrivalIdx = 0
    private var _latSumUs = 0L
    private var _latMaxUs = 0L
    private var _latCount = 0

    fun setResolution(w: Int, h: Int) {
        videoWidth = w
        videoHeight = h
        pipeline.setVideoSize(w, h)
    }

    // doesn't restart codec: direct mode re-points it, pipeline mode re-points the GL blit
    fun setSurface(surface: Surface) = synchronized(lock) {
        displaySurface = surface
        if (!directRender) return@synchronized pipeline.setDisplaySurface(surface)
        val c = codec ?: return@synchronized
        if (outputSurface === surface) return@synchronized
        // a still sender sends no new frame, so show the last one before going direct
        if (outputSurface === pipeline.inputSurface) pipeline.paintAndRelease(surface)
        _switchOutput(c, surface)
    }

    // runs inside surfaceDestroyed, so the codec lets go of the surface before it dies
    fun clearSurface(surface: Surface) = synchronized(lock) {
        if (displaySurface !== surface) return@synchronized
        displaySurface = null
        if (directRender) codec?.let { c -> _awaySurface()?.let { _switchOutput(c, it) } }
        else pipeline.setDisplaySurface(null)
    }

    private fun _switchOutput(c: MediaCodec, target: Surface) {
        if (target === outputSurface) return
        try {
            c.setOutputSurface(target)
            outputSurface = target
            Log.i(TAG, "output -> ${if (target === displaySurface) "display" else "pipeline"}")
        } catch (e: Exception) {
            // decoder can't switch: fall back to the GL pipeline from the next keyframe
            Log.w(TAG, "setOutputSurface failed; using the GL pipeline", e)
            directRender = false
            stopCodec()
        }
    }

    // the pipeline's input, started on demand; used in direct mode while there's no display
    private fun _awaySurface(): Surface? {
        pipeline.start()
        pipelineReleased = false
        pipeline.setVideoSize(videoWidth, videoHeight)
        return pipeline.inputSurface
    }

    fun selectDecoders(w: Int, h: Int, fps: Int, h265: Boolean): Boolean = synchronized(lock) {
        avcDecoder = selector.avc()
        hevcDecoder = if (h265) selector.hevc(avcDecoder, w, h, fps) else null
        maxFps = fps
        Log.i(TAG, "decoders: avc=${avcDecoder?.name} hevc=${hevcDecoder?.name}")
        hevcDecoder != null
    }

    // unknown limits default to 1080p
    fun maxResolution(): Pair<Int, Int> =
        listOfNotNull(avcDecoder?.let { it to DecoderSelector.AVC }, hevcDecoder?.let { it to DecoderSelector.HEVC })
            .map { (info, mime) ->
                runCatching { info.videoCaps(mime).let { it.supportedWidths.upper to it.supportedHeights.upper } }
                    .getOrDefault(1920 to 1080)
            }
            .reduceOrNull { (w1, h1), (w2, h2) -> minOf(w1, w2) to minOf(h1, h2) } ?: (1920 to 1080)

    // codec per mirror session; pipeline persists across sessions
    fun startSession() = synchronized(lock) { _resetStats() }

    // frees the GL thread, EGL context and texture while idle; startCodec brings them back
    fun trimIdle() = synchronized(lock) {
        if (codec != null || pipelineReleased) return@synchronized
        pipeline.release()
        pipelineReleased = true
        Log.i(TAG, "idle: released video pipeline")
    }

    fun stopSession() = synchronized(lock) {
        stopCodec()
        frameShown = false
    }

    private fun _resetStats() {
        fps = 0; bitrateBps = 0; frameCount = 0; codecName = ""
        droppedFrames = 0; framePacingJitterUs = 0
        decodeLatencyUs = 0; decodeLatencyMaxUs = 0
        frameShown = false
        stages.reset()
        _framesThisSec = 0; _bytesThisSec = 0
    }

    private fun _updateStats(size: Int) {
        val now = System.currentTimeMillis()
        if (now - _lastStatReset >= 1000) {
            fps = _framesThisSec
            bitrateBps = _bytesThisSec * 8
            framePacingJitterUs = _computeFramePacingJitterUs()
            synchronized(_arrivalPts) {
                decodeLatencyUs = if (_latCount > 0) _latSumUs / _latCount else 0
                decodeLatencyMaxUs = _latMaxUs
                _latSumUs = 0; _latMaxUs = 0; _latCount = 0
            }
            _framesThisSec = 0
            _bytesThisSec = 0
            _lastStatReset = now
            if (benchmarkLog) _emitBenchmarkLine()
        }
        _framesThisSec++
        _bytesThisSec += size
        frameCount++
    }

    private fun _emitBenchmarkLine() {
        val msg = "fps=$fps bitrate=${bitrateBps / 1000}kbps " +
            "jitter=${framePacingJitterUs}us latency=${decodeLatencyUs / 1000}/${decodeLatencyMaxUs / 1000}ms " +
            "${stages.summary()} frames=$frameCount " +
            "dropped=$droppedFrames codec=$codecName " +
            "res=${videoWidth}x${videoHeight}"
        Log.i(BENCH_TAG, msg)
        benchmarkLogCallback?.invoke(msg)
    }

    fun feedFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        synchronized(lock) {
            _updateStats(data.size)
            if (videoWidth == 0 || videoHeight == 0) return
            if (benchmarkLog) stages.arrived(ntpTimeNs / 1000)

            // a sender that resizes mid-session sends a keyframe with it. Restart there, or an adaptive
            // decoder keeps its old buffer size and the scaler blends the padding into the edges
            val resized = codec != null && (codecWidth != videoWidth || codecHeight != videoHeight) &&
                _isKeyframe(data, isH265)
            if (resized) Log.i(TAG, "video resized to ${videoWidth}x$videoHeight, restarting codec")
            if (codec == null || isH265 != currentH265 || resized) {
                // a stale reference frame decodes to corruption, so wait for a keyframe to (re)start
                if (!_isKeyframe(data, isH265)) {
                    if (codec != null) stopCodec()
                    return
                }
                stopCodec()
            }

            try {
                if (codec == null) startCodec(isH265)
                _feedToCodec(data, ntpTimeNs)
            } catch (e: Exception) {
                Log.w(TAG, "Codec error, resetting", e)
                stopCodec()
            }
        }
    }

    private fun _feedToCodec(data: ByteArray, ntpTimeNs: Long) {
        val c = codec ?: return
        // dropping a frame desyncs decoder until the next keyframe, but source would only send one on (re)connect
        val retries = if (firstFrameQueued) FEED_RETRIES else FIRST_FEED_RETRIES
        repeat(retries) {
            val idx = c.dequeueInputBuffer(FEED_WAIT_US)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                buf.put(data)
                val ptsUs = ntpTimeNs / 1000
                synchronized(_arrivalPts) {
                    val i = _arrivalIdx++ % _arrivalPts.size
                    _arrivalPts[i] = ptsUs
                    _arrivalNs[i] = System.nanoTime()
                }
                c.queueInputBuffer(idx, 0, data.size, ptsUs, 0)
                if (benchmarkLog) stages.queued(ptsUs)
                firstFrameQueued = true
                return
            }
        }
        droppedFrames++
        Log.w(TAG, "Decoder input queue full; dropping frame. drops=$droppedFrames")
    }

    private fun _isKeyframe(data: ByteArray, isH265: Boolean): Boolean {
        if (data.size < 5) return false
        var i = 0
        while (i <= data.size - 5) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val key = if (isH265) {
                    val type = (data[i + 4].toInt() shr 1) and 0x3F
                    type == 19 || type == 20 || type == 21 || type == 32 || type == 33
                } else {
                    val type = data[i + 4].toInt() and 0x1F
                    type == 5 || type == 7
                }
                if (key) return true
            }
            i++
        }
        return false
    }

    private fun startCodec(h265: Boolean) {
        val s = if (directRender) {
            displaySurface ?: _awaySurface() ?: return
        } else {
            pipeline.start()
            if (pipelineReleased) {
                pipelineReleased = false
                displaySurface?.let { pipeline.setDisplaySurface(it) }
            }
            pipeline.setVideoSize(videoWidth, videoHeight)
            pipeline.inputSurface ?: return
        }
        outputSurface = if (directRender) s else null
        codecWidth = videoWidth
        codecHeight = videoHeight
        currentH265 = h265
        val mime = if (h265) DecoderSelector.HEVC else DecoderSelector.AVC
        val info = (if (h265) hevcDecoder else avcDecoder) ?: error("no decoder selected for $mime")

        firstFrameQueued = false
        try {
            _startWithLadder(info, mime, s, h265)
        } catch (e: Exception) {
            // strict hw decoders reject configs beyond their real limits
            val sw = selector.software(mime, videoWidth, videoHeight) ?: throw e
            Log.w(TAG, "Hardware decoder failed, trying software fallback", e)
            _startWithLadder(sw, mime, s, h265)
        }
        Log.i(TAG, "Video codec started: $mime ${videoWidth}x${videoHeight} ($codecName)")
    }

    private fun _startWithLadder(info: MediaCodecInfo, mime: String, s: Surface, h265: Boolean) {
        var tryNum = 0
        while (true) {
            val format = _format(mime, info)
            val more = selector.lowLatencyOptions(format, info, mime, tryNum)
            try {
                _startDecoder(MediaCodec.createByCodecName(info.name), format, s, h265)
                return
            } catch (e: Exception) {
                if (!more) throw e
                Log.w(TAG, "configure try $tryNum failed: $format", e)
                tryNum++
            }
        }
    }

    private fun _format(mime: String, info: MediaCodecInfo) = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight).apply {
        setInteger(MediaFormat.KEY_FRAME_RATE, maxFps)
        if (selector.adaptive(info, mime)) {
            setInteger(MediaFormat.KEY_MAX_WIDTH, videoWidth)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, videoHeight)
        }
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(videoWidth * videoHeight * 3 / 4, 1024 * 1024))
        if (enforceSdr) {
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, if (keyAllowFrameDrop) 1 else 0)
        }
    }

    private fun _startDecoder(c: MediaCodec, format: MediaFormat, surface: Surface, h265: Boolean) {
        try {
            c.configure(format, surface, null, 0)
            c.start()
        } catch (e: Exception) {
            try { c.release() } catch (_: Exception) {}
            throw e
        }
        codec = c
        codecName = (if (h265) "H.265" else "H.264") + " (${c.name})"
        if (directRender && benchmarkLog) {
            // in direct mode the "draw" stage ends when the frame is shown, not at a GL swap
            c.setOnFrameRenderedListener({ _, ptsUs, ns -> stages.drawn(ptsUs, ns) }, null)
        }
        _draining = true
        _drainThread = Thread({ _drainLoop(c) }, "VideoDrain").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    private fun stopCodec() {
        // the drain thread never takes lock, and it wakes at least every DRAIN_WAIT_US
        _draining = false
        _drainThread?.join()
        _drainThread = null
        _frameIntervalIdx = 0
        _frameIntervalCount = 0
        _lastOutputFrameNs = 0L
        _ptsBaseUs = Long.MIN_VALUE
        _wallBaseNs = 0L
        codec?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        codec = null
        outputSurface = null
    }

    private fun _drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (_draining) {
                val idx = c.dequeueOutputBuffer(info, DRAIN_WAIT_US)
                if (idx < 0) continue
                _release(c, idx, info)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "drain stopped", e)
        }
    }

    private fun _release(c: MediaCodec, idx: Int, info: MediaCodec.BufferInfo) {
        // pipeline mode reports this from its draw; frames into the hidden pipeline don't count
        if (directRender && outputSurface === displaySurface) frameShown = true
        _recordOutputFrameTime()
        _recordLatency(info.presentationTimeUs)
        if (benchmarkLog) stages.released(info.presentationTimeUs)
        if (directRender && outputSurface === displaySurface && !scheduledOutputBufferRelease) {
            // the default timestamp is the NTP-based pts, which the compositor would treat as a
            // desired present time on the wrong clock; "now" means show at the next vsync
            c.releaseOutputBuffer(idx, System.nanoTime())
        } else if (scheduledOutputBufferRelease) {
            // schedule frame at VSYNC matching its NTP presentation time
            val ptsUs = info.presentationTimeUs
            if (_ptsBaseUs == Long.MIN_VALUE) {
                _ptsBaseUs = ptsUs
                _wallBaseNs = System.nanoTime()
            }
            c.releaseOutputBuffer(idx, _wallBaseNs + (ptsUs - _ptsBaseUs) * 1000L)
        } else {
            c.releaseOutputBuffer(idx, true)
        }
    }

    private fun _recordLatency(ptsUs: Long) = synchronized(_arrivalPts) {
        val i = _arrivalPts.indexOf(ptsUs)
        if (i < 0) return@synchronized
        val us = (System.nanoTime() - _arrivalNs[i]) / 1000
        _arrivalPts[i] = Long.MIN_VALUE
        _latSumUs += us
        _latCount++
        if (us > _latMaxUs) _latMaxUs = us
    }

    fun release() = synchronized(lock) {
        stopCodec()
        pipeline.release()
        pipelineReleased = true
        _resetStats()
    }

    private fun _recordOutputFrameTime() {
        val now = System.nanoTime()
        if (_lastOutputFrameNs > 0) {
            _frameIntervalsNs[_frameIntervalIdx % _frameIntervalsNs.size] = now - _lastOutputFrameNs
            _frameIntervalIdx++
            _frameIntervalCount++
        }
        _lastOutputFrameNs = now
    }

    private fun _computeFramePacingJitterUs(): Long {
        val count = _frameIntervalCount.coerceAtMost(_frameIntervalsNs.size)
        if (count < 2) return 0

        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until count) {
            val interval = _frameIntervalsNs[i].toDouble()
            sum += interval
            sumSq += interval * interval
        }
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return (kotlin.math.sqrt(variance.coerceAtLeast(0.0)) / 1000.0).toLong()
    }

    companion object {
        private const val TAG = "VideoRenderer"
        private const val BENCH_TAG = "BENCHMARK"
        private const val FEED_WAIT_US = 20_000L
        private const val FEED_RETRIES = 10
        private const val FIRST_FEED_RETRIES = 50
        private const val DRAIN_WAIT_US = 10_000L
    }
}
