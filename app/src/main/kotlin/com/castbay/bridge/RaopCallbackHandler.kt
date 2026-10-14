package com.castbay.bridge

interface RaopCallbackHandler {
    fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean)
    fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean)
    fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float)
    fun onVolumeChange(volume: Float)
    fun onClientVolume(): Float
    fun onAudioTeardown()
    fun onConnectionInit()
    fun onConnectionDestroy()
    fun onConnectionReset(reason: Int)
    fun onDisplayPin(pin: String)
    fun onMetadata(data: ByteArray)
    fun onCoverArt(data: ByteArray)
    fun onProgress(start: Long, curr: Long, end: Long)
    fun onDacpId(dacpId: String, activeRemote: String)
    fun onMirrorRunning(running: Boolean)
    // video (hls), distinct from mirroring and raop audio
    fun onVideoPlay(location: String, startPositionSeconds: Float)
    fun onVideoScrub(positionSeconds: Float)
    fun onVideoRate(rate: Float)
    fun onVideoStop()
    /** A new sender connected and took over ("Allow new connections"); the old one was dropped. */
    fun onSenderReplaced()
    // fired per sender GET /playback-info poll; polling starts before /play
    fun onVideoSessionPoll()
    // sender's device name and model, sent when it sets up a session
    fun onClientInfo(name: ByteArray, model: ByteArray)
    // AirPlay video v2 (macOS QuickTime): event channel port to advertise in SETUP (0 = none),
    // a type-130 playback stream, and POST /command bodies as XML plists; returns an XML reply
    fun onAv2EventPort(): Int
    fun onAv2Stream(streamId: Long)
    fun onAv2Command(streamId: Long, xml: ByteArray): ByteArray?
    // the /info reply as an XML plist, and RECORD (after which v2 senders expect updateInfo)
    fun onAv2Info(xml: ByteArray)
    fun onAv2Record()
    // HomeKit transient pairing finished; the 64-byte secret keys the encrypted event channel
    fun onHapPaired(secret: ByteArray)
}
