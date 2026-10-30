package io.mahajan.partymusik.ios

/*
 * Swift-facing driver protocols. The Swift app (iosApp/iosApp/Platform) implements these with AVAudioSession,
 * AVAudioEngine, CallKit and CXCallObserver. Kotlin turns them into the shared ports (IosPlatform), so Swift never has
 * to produce Kotlin Flows or suspend functions.
 *
 * Port types are normalised in Swift to: speaker, receiver, wired, usb, hdmi, bt_a2dp, bt_hfp, bt_le, airplay, car,
 * mic_builtin, other.
 */

data class IosPort(
    val uid: String,
    val name: String,
    val portType: String,
    val channels: Int,
    val isInput: Boolean,
)

/** Implemented in Kotlin; Swift calls it on the main thread whenever OS state changes. */
interface IosEventSink {
    fun routeChanged(reason: String)
    fun mediaServicesReset()
    fun playbackChanged(itemId: String?, playing: Boolean, positionMs: Long, durationMs: Long)
    /** idle | dialing | active | held | ended:<reason> */
    fun callStateChanged(state: String)
    fun mutedChanged(muted: Boolean)
    /** none | ringing | active */
    fun cellularChanged(state: String)
    /** UIDs of output ports currently fed by the multi-route engine. */
    fun multiRouteChanged(activeUids: List<String>)
}

/** Command results are strings: "submitted", "picker", "gone", "unsupported", or "error:<detail>". */
interface IosAudioSessionDriver {
    fun currentOutputs(): List<IosPort>
    fun availableInputs(): List<IosPort>
    fun requestMediaOutput(portType: String, uids: List<String>): String
    fun clearMediaPreference()
    fun requestCommunicationOutput(portType: String, uids: List<String>): String
    fun releaseCommunication()
    fun startMultiRoute(uids: List<String>): String
    fun stopMultiRoute()
    fun setGain(uid: String, gain: Float)
    fun setDelay(uid: String, ms: Int)
    fun showRoutePicker()
}

interface IosPlayerDriver {
    fun load(itemId: String, durationMs: Long)
    fun play()
    fun pause()
    fun seek(positionMs: Long)
}

interface IosCallDriver {
    fun startCall(name: String, uri: String): String
    fun hangup()
    fun setMuted(muted: Boolean)
}

interface IosStringCallback {
    fun onResult(value: String)
}

interface IosProbeCallback {
    fun onResult(recording: FloatArray?, emitFrame: Int)
}

interface IosSystemDriver {
    val model: String
    val osVersion: String
    /** granted | denied | undetermined */
    fun microphonePermission(): String
    fun requestMicrophone(callback: IosStringCallback)
    fun openSettings(target: String)
    fun showPairingHelp()
    fun probe(uid: String, signal: FloatArray, sampleRate: Int, callback: IosProbeCallback)
}

interface IosDrivers {
    val session: IosAudioSessionDriver
    val player: IosPlayerDriver
    val calls: IosCallDriver
    val system: IosSystemDriver
    fun attach(sink: IosEventSink)
}
