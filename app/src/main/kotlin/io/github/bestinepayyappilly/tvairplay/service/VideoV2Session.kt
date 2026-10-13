// The playback message formats (playbackInfo, property, seek, playbackState, error) are adapted
// from DiPlay's VideoInCar.kt and CarPlayVideo.kt (https://github.com/shihabal3amri/DiPlay,
// Shihab, GPL-3.0).

package io.github.bestinepayyappilly.tvairplay.service

import android.net.Uri
import android.util.Log
import io.github.bestinepayyappilly.tvairplay.bridge.NativeBridge
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * AirPlay video v2, the protocol macOS QuickTime uses to cast a file:
 * - the sender pairs (HomeKit transient), opens an event channel to the port we give in SETUP
 *   and, after RECORD, waits for `updateInfo` (our /info) on it;
 * - it sets up a type-130 stream and drives playback with POST /command whose params.data is a
 *   plist message: insertPlayQueueItem, setRate, seek, playbackInfo, property, stop ...;
 * - answers and notifications go back over the event channel as POST /command with
 *   X-Apple-StreamID and the same {params: {data}} wrapping.
 *
 * A local file is served by the Mac itself over TLS 1.2 PSK (Content-Location https://...), keyed
 * from the pairing secret; ExoPlayer reads it through a loopback proxy (hap/psk_proxy.c).
 */
class VideoV2Session(private val player: Player) {

    /** The app's AirPlay video player, as the session drives it. */
    interface Player {
        fun play(url: String, startSeconds: Float)
        fun setRate(rate: Float)
        fun seek(seconds: Float)
        fun stop()
        /** null when nothing is loaded */
        fun state(): State?
    }

    data class State(
        val ready: Boolean,
        val playing: Boolean,
        val positionSeconds: Double,
        val durationSeconds: Double,
        val bufferedSeconds: Double,
    )

    @Volatile private var server: ServerSocket? = null
    @Volatile private var channel: Socket? = null
    // last /info reply (XML plist), repeated to the sender as updateInfo
    @Volatile private var info: String? = null
    @Volatile private var recorded = false
    // secret of the last HomeKit pairing; the event channel and the media key derive from it
    @Volatile private var pairSecret: ByteArray? = null
    @Volatile private var cipher: HapChannelCipher? = null
    // the channel updateInfo went out on; once per channel
    @Volatile private var infoSentOn: Socket? = null
    private var cseq = 0
    // every event-channel write goes through this one thread: never the main thread, and in
    // order, since each frame's nonce is a counter the sender must see without gaps
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "Av2EventWrite").apply { isDaemon = true } }

    // the queued item and the stream its commands arrive on
    @Volatile private var streamId: Long = -1
    @Volatile private var itemUuid: Any? = null
    @Volatile private var lastPlaying: Boolean? = null
    private var proxy = 0L

    // listening port for the event channel, opened on first use
    @Synchronized fun eventPort(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = try { ServerSocket(0) } catch (e: Exception) {
            Log.w(TAG, "event channel unavailable", e)
            return 0
        }
        server = s
        thread(name = "Av2Events", isDaemon = true) { _acceptLoop(s) }
        Log.i(TAG, "event channel listening on ${s.localPort}")
        return s.localPort
    }

    fun onPaired(secret: ByteArray) {
        pairSecret = secret
        Log.i(TAG, "HomeKit pairing complete; event channel will be encrypted")
    }

    // the sender left: a later legacy-paired session must not inherit this pairing's keys
    fun onSessionEnd() {
        _endItem(stopPlayer = false)
        synchronized(this) {
            pairSecret = null
            recorded = false
            // its channel and cipher belong to this session: a new one must not write with them
            try { channel?.close() } catch (_: Exception) {}
            channel = null
            cipher = null
        }
    }

    fun onInfo(xml: ByteArray) {
        // a qualifier request gets only the TXT record back; keep the full dictionary
        val s = String(xml, Charsets.UTF_8)
        if ("<key>features</key>" in s) info = s
    }

    fun onRecord() {
        recorded = true
        _sendUpdateInfo()
    }

    fun onStream(id: Long) {
        Log.i(TAG, "playback stream set up: id=$id")
    }

    /** POST /command from the sender. Answered with an empty 200; replies go over the event channel. */
    fun onCommand(streamId: Long, xml: ByteArray): ByteArray? {
        val body = try { PlistXml.parse(String(xml, Charsets.UTF_8)) as? Map<*, *> } catch (e: Exception) {
            Log.w(TAG, "unreadable command", e)
            null
        } ?: return null
        val data = (body["params"] as? Map<*, *>)?.get("data") as? ByteArray
        if (streamId < 0 || data == null) {
            Log.i(TAG, "session command ${body["type"]} keys=${(body["params"] as? Map<*, *>)?.keys}")
            return null
        }
        val message = try { PlistXml.parseBinary(data) as? Map<*, *> } catch (e: Exception) {
            Log.w(TAG, "unreadable stream message", e)
            null
        } ?: return null
        try {
            _handle(streamId, message)
        } catch (e: Exception) {
            // a message we fail on must never take the session down
            Log.w(TAG, "message ${message["type"]} failed", e)
        }
        return null
    }

    /** The player started, paused or ended; tells the sender at once, as Apple's receiver does. */
    fun onPlayerChanged(playing: Boolean) {
        val sid = streamId
        if (sid < 0 || itemUuid == null || lastPlaying == playing) return
        lastPlaying = playing
        _send(sid, if (playing) {
            linkedMapOf("type" to "playbackState", "name" to "playing", "item" to linkedMapOf("uuid" to itemUuid))
        } else {
            linkedMapOf("type" to "playbackState", "name" to "paused")
        })
    }

    private fun _handle(sid: Long, message: Map<*, *>) {
        streamId = sid
        val type = message["type"] as? String
        val id = message["messageID"]
        val request = message["kind"] == "request"
        Log.i(TAG, "message $type kind=${message["kind"]} id=$id")
        when (type) {
            "insertPlayQueueItem" -> {
                _play(message["item"] as? Map<*, *>)
                if (request) _ack(sid, type, id)
            }
            "setRate" -> {
                val rate = (message["rate"] as? Number)?.toFloat() ?: 0f
                player.setRate(rate)
                if (request) _ack(sid, type, id)
            }
            "seek" -> {
                _seconds(message["time"] as? Map<*, *>)?.let { player.seek(it.toFloat()) }
                if (request) _ack(sid, type, id)
            }
            "playbackInfo" -> if (request) _send(sid, _playbackInfo(id))
            "property" -> if (request) _send(sid, _property(id, message["property"]))
            "stop", "removePlayQueueItem" -> {
                _endItem(stopPlayer = true)
                if (request) _ack(sid, type, id)
            }
            else -> if (request) _ack(sid, type, id)
        }
    }

    private fun _play(item: Map<*, *>?) {
        val location = item?.get("Content-Location") as? String ?: run {
            Log.w(TAG, "queue item without a location: ${item?.keys}")
            return
        }
        val start = (item["Start-Position-Seconds"] as? Number)?.toDouble()
            ?: _seconds(item["Start-Position"] as? Map<*, *>) ?: 0.0
        val url = _playableUrl(location) ?: return
        itemUuid = item["uuid"]
        lastPlaying = null
        Log.i(TAG, "queue item ${item["mediaType"]} from ${Uri.parse(location).host} @ ${start}s")
        player.play(url, start.toFloat())
    }

    // https from the sender itself is its TLS-PSK file server: route it through the local proxy
    private fun _playableUrl(location: String): String? {
        val uri = Uri.parse(location)
        if (uri.scheme == "http") return location
        if (uri.scheme != "https") {
            Log.w(TAG, "unsupported item scheme ${uri.scheme}")
            return null
        }
        val secret = pairSecret ?: run {
            Log.w(TAG, "https item without a pairing secret")
            return null
        }
        val host = uri.host?.trim('[', ']') ?: return null
        val port = if (uri.port > 0) uri.port else 443
        synchronized(this) {
            if (proxy != 0L) NativeBridge.pskProxyStop(proxy)
            proxy = NativeBridge.pskProxyStart(host, port, HapChannelCipher.mediaPsk(secret))
            if (proxy == 0L) {
                Log.w(TAG, "TLS-PSK proxy failed to start")
                return null
            }
            val path = uri.encodedPath ?: "/"
            val query = uri.encodedQuery?.let { "?$it" } ?: ""
            return "http://127.0.0.1:${NativeBridge.pskProxyPort(proxy)}$path$query"
        }
    }

    private fun _endItem(stopPlayer: Boolean) {
        if (stopPlayer && itemUuid != null) player.stop()
        itemUuid = null
        lastPlaying = null
        synchronized(this) {
            if (proxy != 0L) NativeBridge.pskProxyStop(proxy)
            proxy = 0L
        }
    }

    // the answer to playbackInfo: fields in "info", times as CMTime, ranges as {start, duration}
    private fun _playbackInfo(id: Any?): Map<String, Any?> {
        val s = player.state()
        val ready = s?.ready == true
        val info = linkedMapOf<String, Any?>(
            "item" to linkedMapOf("uuid" to itemUuid),
            "rate" to if (s?.playing == true) 1.0 else 0.0,
            "readyToPlay" to ready,
            "playbackLikelyToKeepUp" to ready,
        )
        if (s != null && ready) {
            info["position"] = _cmTime(s.positionSeconds)
            info["duration"] = _cmTime(s.durationSeconds)
            info["seekableTimeRanges"] = listOf(_range(0.0, s.durationSeconds))
            info["loadedTimeRanges"] = listOf(_range(0.0, s.bufferedSeconds))
        }
        info["playbackState"] = when {
            s == null || !ready -> "loading"
            s.playing -> "playing"
            else -> "paused"
        }
        return linkedMapOf("info" to info, "kind" to "response", "type" to "playbackInfo", "messageID" to id)
    }

    private fun _property(id: Any?, key: Any?): Map<String, Any?> {
        val s = player.state()
        val ready = s?.ready == true
        val value: Any? = when (key) {
            "hasEnabledAudio" -> true
            "muted" -> false
            "seekableTimeRanges" -> if (ready) listOf(_range(0.0, s!!.durationSeconds)) else null
            "loadedTimeRanges" -> if (ready) listOf(_range(0.0, s!!.bufferedSeconds)) else null
            else -> null
        }
        return linkedMapOf("key" to key, "value" to value, "kind" to "response", "type" to "property", "messageID" to id)
    }

    private fun _ack(sid: Long, type: String?, id: Any?) {
        if (id != null) _send(sid, linkedMapOf("type" to type, "kind" to "response", "messageID" to id))
    }

    private fun _cmTime(seconds: Double): Map<String, Any?> =
        linkedMapOf("value" to Math.round(seconds * 1000), "timescale" to 1000L, "flags" to 1L, "epoch" to 0L)

    private fun _range(start: Double, duration: Double) =
        linkedMapOf("start" to _cmTime(start), "duration" to _cmTime(duration))

    private fun _seconds(time: Map<*, *>?): Double? {
        val value = (time?.get("value") as? Number)?.toDouble() ?: return null
        val scale = (time["timescale"] as? Number)?.toDouble()?.takeIf { it > 0 } ?: return null
        return value / scale
    }

    // a stream message to the sender: POST /command with X-Apple-StreamID and {params: {data}}
    private fun _send(sid: Long, message: Map<String, Any?>) {
        val data = PlistXml.toBinary(message) ?: return
        val body = PlistXml.toBinary(linkedMapOf("params" to linkedMapOf("data" to data))) ?: return
        _postBinary(body, "X-Apple-StreamID: $sid\r\n")
    }

    // receiver -> sender request on the event channel, as POST /command with a binary plist body
    private fun _postBinary(body: ByteArray, extraHeaders: String = "", onSent: (() -> Unit)? = null) {
        writer.execute { if (_writeNow(body, extraHeaders)) onSent?.invoke() }
    }

    @Synchronized private fun _writeNow(body: ByteArray, extraHeaders: String): Boolean {
        val sock = channel ?: return false
        val head = "POST /command RTSP/1.0\r\n" + extraHeaders +
            "CSeq: ${cseq++}\r\n" +
            "Content-Type: application/x-apple-binary-plist\r\n" +
            "Content-Length: ${body.size}\r\n\r\n"
        val plain = head.toByteArray(Charsets.US_ASCII) + body
        return try {
            val wire = cipher?.encrypt(plain) ?: plain
            sock.getOutputStream().apply { write(wire); flush() }
            true
        } catch (e: Exception) {
            // the nonce counter has moved on: the channel can't be used again
            Log.w(TAG, "event channel write failed; closing it", e)
            try { sock.close() } catch (_: Exception) {}
            false
        }
    }

    private fun _sendUpdateInfo() {
        val sock = channel
        if (!recorded || sock == null || infoSentOn === sock) return
        infoSentOn = sock
        val value = info?.let { PlistXml.parse(it) } ?: run {
            Log.w(TAG, "no /info reply cached; cannot send updateInfo")
            return
        }
        val body = PlistXml.toBinary(linkedMapOf("type" to "updateInfo", "value" to value)) ?: return
        _postBinary(body) { Log.i(TAG, "sent updateInfo") }
    }

    @Synchronized fun stop() {
        _endItem(stopPlayer = false)
        recorded = false
        pairSecret = null
        cipher = null
        try { channel?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        channel = null
        server = null
    }

    private fun _acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val sock = try { s.accept() } catch (_: Exception) { return }
            Log.i(TAG, "event channel connected from ${sock.inetAddress.hostAddress}")
            val newCipher = pairSecret?.let {
                HapChannelCipher(it, "Events-Salt", "Events-Read-Encryption-Key", "Events-Write-Encryption-Key")
            }
            // channel and cipher change together: a write in between would use the old key
            synchronized(this) {
                channel?.let { try { it.close() } catch (_: Exception) {} }
                channel = sock
                cipher = newCipher
            }
            Log.i(TAG, "event channel ${if (newCipher != null) "encrypted" else "plaintext"}")
            thread(name = "Av2EventRead", isDaemon = true) { _readLoop(sock.getInputStream()) }
            // RECORD may already have happened
            _sendUpdateInfo()
        }
    }

    // the sender only answers our requests here; log the status line of each answer
    private fun _readLoop(input: InputStream) {
        val buf = ByteArray(4096)
        val pending = ByteArrayOutputStream()
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                val data = cipher?.decrypt(buf, n) ?: buf.copyOf(n)
                if (data.isEmpty()) continue
                pending.write(data)
                val text = pending.toString("ISO-8859-1")
                if ("\r\n\r\n" in text) {
                    Log.i(TAG, "event channel: ${text.substringBefore("\r\n")}")
                    pending.reset()
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "event channel closed: ${e.message}")
        }
        Log.i(TAG, "event channel EOF")
    }

    private companion object {
        const val TAG = "VideoV2"
    }
}
