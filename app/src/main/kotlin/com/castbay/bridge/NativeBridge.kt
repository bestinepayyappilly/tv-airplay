package com.castbay.bridge

object NativeBridge {
    init {
        System.loadLibrary("airplay_native")
    }

    external fun nativeInit(
        callback: RaopCallbackHandler,
        hwAddr: ByteArray,
        name: String,
        keyFile: String,
        nohold: Boolean,
        requirePin: Boolean
    ): Long

    external fun nativeStart(handle: Long, port: Int): Int
    external fun nativeStop(handle: Long)
    external fun nativeDestroy(handle: Long)

    external fun nativeSetDisplaySize(handle: Long, w: Int, h: Int, fps: Int)

    // binary plist <-> XML plist (UTF-8), for AirPlay video v2 messages; null if unparseable
    external fun plistXmlToBin(xml: ByteArray): ByteArray?
    external fun plistBinToXml(bin: ByteArray): ByteArray?

    // loopback HTTP proxy to a sender's TLS 1.2 PSK media server (0 = failed to start)
    external fun pskProxyStart(host: String, port: Int, psk: ByteArray): Long
    external fun pskProxyPort(handle: Long): Int
    external fun pskProxyStop(handle: Long)
    external fun nativeSetPlist(handle: Long, key: String, value: Int)
    external fun nativeSetH265Enabled(handle: Long, enabled: Boolean)
    external fun nativeSetCodecs(handle: Long, alac: Boolean, aac: Boolean)
    external fun nativeSetHlsEnabled(handle: Long, enabled: Boolean)
    // feature bit 50: senders send now-playing info as /command bplists
    external fun nativeSetNowPlayingInfo(handle: Long, enabled: Boolean)
    external fun nativeSetLang(handle: Long, requested: String, subtitles: String, system: String)
    external fun nativeSetAudioEnabled(handle: Long, enabled: Boolean)
    external fun nativeUpdatePlaybackInfo(
        handle: Long, position: Float, duration: Float, rate: Float, readyToPlay: Boolean
    )

    external fun nativeGetRaopTxtRecords(handle: Long): Map<String, String>?
    external fun nativeGetAirplayTxtRecords(handle: Long): Map<String, String>?
    external fun nativeGetRaopServiceName(handle: Long): String?
    external fun nativeGetServerName(handle: Long): String?

    external fun nativeSetDefaultStreamValues(sampleRate: Int, framesPerBurst: Int)
    external fun nativeServerAudioConfigure(handle: Long, cushionMs: Int, percentilePct: Int,
                                            oboeBufferFrames: Int, forceSwAlac: Boolean,
                                            realtimePriority: Boolean, lowLatency: Boolean,
                                            benchmarkLog: Boolean): Boolean
    external fun nativeServerAudioStart(handle: Long): Boolean
    external fun nativeServerAudioStop(handle: Long)
    external fun nativeServerAudioFormat(handle: Long, ct: Int, spf: Int)
    // fills direct buffer with packed debug snapshot; false if audio isn't running
    external fun nativeServerAudioDebug(handle: Long, buf: java.nio.ByteBuffer): Boolean
    // ms since the audio stream last carried sound (a paused sender streams silence); -1 if unknown
    external fun nativeServerAudioSilentMs(handle: Long): Long
}
