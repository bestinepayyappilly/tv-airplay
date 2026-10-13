package io.github.bestinepayyappilly.tvairplay.renderer

// per-frame timing through the receiver, keyed by pts (us):
// arrival -> queued (input wait) -> released (decode) -> drawn (GL blit and swap)
class LatencyStages {

    private val pts = LongArray(SLOTS) { Long.MIN_VALUE }
    private val arrivalNs = LongArray(SLOTS)
    private val queuedNs = LongArray(SLOTS)
    private val releasedNs = LongArray(SLOTS)
    private var next = 0

    private val sum = LongArray(STAGES)
    private val max = LongArray(STAGES)
    private val count = IntArray(STAGES)

    @Synchronized fun arrived(ptsUs: Long) {
        val i = next++ % SLOTS
        pts[i] = ptsUs
        arrivalNs[i] = System.nanoTime()
        queuedNs[i] = 0; releasedNs[i] = 0
    }

    @Synchronized fun queued(ptsUs: Long) = slot(ptsUs)?.let { i ->
        queuedNs[i] = System.nanoTime()
        add(WAIT, queuedNs[i] - arrivalNs[i])
    }

    @Synchronized fun released(ptsUs: Long) = slot(ptsUs)?.let { i ->
        releasedNs[i] = System.nanoTime()
        if (queuedNs[i] > 0) add(DECODE, releasedNs[i] - queuedNs[i])
    }

    // atNs: when it reached the screen (MediaCodec render callbacks arrive batched and late)
    @Synchronized fun drawn(ptsUs: Long, atNs: Long = System.nanoTime()) = slot(ptsUs)?.let { i ->
        val now = atNs
        if (releasedNs[i] > 0) add(DRAW, now - releasedNs[i])
        add(TOTAL, now - arrivalNs[i])
        pts[i] = Long.MIN_VALUE
    }

    // "wait=a/b decode=a/b draw=a/b total=a/b" in ms (mean/max), then resets the window
    @Synchronized fun summary(): String = NAMES.indices.joinToString(" ") { s ->
        val mean = if (count[s] > 0) sum[s] / count[s] else 0
        "%s=%.1f/%.1f".format(NAMES[s], mean / 1e6, max[s] / 1e6)
    }.also { sum.fill(0); max.fill(0); count.fill(0) }

    @Synchronized fun reset() {
        pts.fill(Long.MIN_VALUE)
        sum.fill(0); max.fill(0); count.fill(0)
    }

    private fun slot(ptsUs: Long): Int? = pts.indexOf(ptsUs).takeIf { it >= 0 }

    private fun add(stage: Int, ns: Long) {
        sum[stage] += ns
        count[stage]++
        if (ns > max[stage]) max[stage] = ns
    }

    private companion object {
        const val SLOTS = 64
        const val WAIT = 0; const val DECODE = 1; const val DRAW = 2; const val TOTAL = 3
        const val STAGES = 4
        val NAMES = arrayOf("wait", "decode", "draw", "total")
    }
}
