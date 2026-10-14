package io.github.bestinepayyappilly.tvairplay.service

import android.util.Log

/**
 * Now-playing info an AirPlay 2 sender pushes as a session-level POST /command once the receiver
 * advertises feature bit 50 (MetadataFeatures_3). The info is a MediaRemote dictionary,
 * kMRMediaRemoteNowPlayingInfo* keys, nested under params; we search for it rather than
 * hard-code the nesting, which isn't documented.
 */
data class NowPlayingCommand(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val elapsedMs: Long?,
    val rate: Double?,
    val artwork: ByteArray?,
) {
    companion object {
        private const val TAG = "NowPlaying"
        private const val PREFIX = "kMRMediaRemoteNowPlayingInfo"

        fun parse(body: Map<*, *>): NowPlayingCommand? {
            val info = _find(body) ?: return null
            fun str(k: String) = (info[PREFIX + k] as? String)?.takeIf { it.isNotBlank() }
            fun num(k: String) = (info[PREFIX + k] as? Number)?.toDouble()
            val cmd = NowPlayingCommand(
                title = str("Title"),
                artist = str("Artist"),
                album = str("Album"),
                durationMs = num("Duration")?.let { (it * 1000).toLong() },
                elapsedMs = num("ElapsedTime")?.let { (it * 1000).toLong() },
                rate = num("PlaybackRate"),
                artwork = info[PREFIX + "ArtworkData"] as? ByteArray,
            )
            // what senders actually send isn't documented; log the shape while bit 50 is experimental
            Log.i(TAG, "${body["type"]}: keys=${info.keys.map { it.toString().removePrefix(PREFIX) }} " +
                "title=${cmd.title} dur=${cmd.durationMs} elapsed=${cmd.elapsedMs} rate=${cmd.rate} art=${cmd.artwork?.size}")
            return cmd
        }

        // the first dictionary holding kMRMediaRemoteNowPlayingInfo* keys, depth-first
        private fun _find(node: Any?, depth: Int = 0): Map<*, *>? {
            if (depth > 6) return null
            return when (node) {
                is Map<*, *> ->
                    if (node.keys.any { (it as? String)?.startsWith(PREFIX) == true }) node
                    else node.values.firstNotNullOfOrNull { _find(it, depth + 1) }
                is List<*> -> node.firstNotNullOfOrNull { _find(it, depth + 1) }
                else -> null
            }
        }
    }
}
