package io.mahajan.partymusik.ports

import io.mahajan.partymusik.domain.AppPermission
import io.mahajan.partymusik.domain.AudioEndpoint
import io.mahajan.partymusik.domain.BondedDevice
import io.mahajan.partymusik.domain.DeviceRef
import io.mahajan.partymusik.domain.EndpointId
import io.mahajan.partymusik.domain.PermissionStatus
import io.mahajan.partymusik.domain.RouteCommandResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * Native bridge API (docs/08-native-bridge-api.md).
 * Implemented in Kotlin on Android (shared/androidMain) and via Swift drivers on iOS (shared/iosMain + iosApp).
 * Rules: commands never report "active"; observation flows are the only source of truth for active routes.
 */

/** DEV-001, DEV-002, DEV-004 */
interface AudioDevicePort {
    /** Full snapshot of audio endpoints, re-emitted on every change. */
    val endpoints: Flow<List<AudioEndpoint>>
}

/** MED-001, MED-004, RTE-001, RTE-004 — single-output media routing for app-owned playback. */
interface MediaRoutingPort {
    suspend fun requestOutput(endpoint: EndpointId): RouteCommandResult
    suspend fun clearPreference()
    /** Outputs the app's media is actually routed to, as reported by the OS. */
    val observedRoute: Flow<List<EndpointId>>
}

/** RTE-002, RTE-003, VOIP-002, VOIP-004 */
interface CommRoutingPort {
    suspend fun request(endpoint: EndpointId): RouteCommandResult
    /** VOIP-005: always called when a communication session ends. */
    suspend fun release()
    val observed: Flow<EndpointId?>
}

/** MED-002, MED-008 — fan-out of app media to several outputs. */
interface MultiOutputPort {
    suspend fun start(targets: List<EndpointId>): RouteCommandResult
    suspend fun stop()
    fun setDelay(endpoint: EndpointId, ms: Int)
    fun setGain(endpoint: EndpointId, gain: Float)
    /** Outputs whose sink is confirmed routed by the OS. */
    val observedRoutes: Flow<List<EndpointId>>
}

data class MediaItemRef(val id: String, val title: String, val durationMs: Long)

data class PlaybackStatus(
    val item: MediaItemRef? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val buffering: Boolean = false,
    val error: String? = null,
)

/** MED-003 */
interface PlaybackPort {
    val library: List<MediaItemRef>
    val status: StateFlow<PlaybackStatus>
    suspend fun load(item: MediaItemRef)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
}

data class Peer(val name: String, val uri: String)

sealed interface CallState {
    data object Idle : CallState
    data object Dialing : CallState
    data object Ringing : CallState
    data class Active(val since: Long) : CallState
    data object Held : CallState
    data class Ended(val reason: String) : CallState
}

/** VOIP-001..006 — app-owned calls (Core-Telecom on Android, CallKit on iOS). */
interface VoipPort {
    val callState: StateFlow<CallState>
    val muted: StateFlow<Boolean>
    suspend fun startCall(peer: Peer): RouteCommandResult
    suspend fun hangup()
    suspend fun setMuted(muted: Boolean)
}

enum class CellularCallState { NONE, RINGING, ACTIVE }

/** CALL-001, RTE-007 — observation only, never control of the system dialer (CALL-002). */
interface CallObserverPort {
    val cellularCall: Flow<CellularCallState>
}

enum class BtAdapterState { ON, OFF, TURNING_ON, TURNING_OFF, UNSUPPORTED, UNAUTHORIZED }

enum class SystemFlowResult { LAUNCHED, COMPLETED, CANCELLED, UNAVAILABLE }

/** DEV-003, DEV-006, DEV-007, DEV-008 — only OS-sanctioned flows. */
interface BluetoothPort {
    val adapterState: Flow<BtAdapterState>
    /** Paired devices; empty where the OS does not expose them (iOS). */
    val bonded: Flow<List<BondedDevice>>
    suspend fun launchPairing(): SystemFlowResult
    suspend fun launchReconnect(device: DeviceRef): SystemFlowResult
    suspend fun requestEnable(): SystemFlowResult
}

enum class AudioSharingState { UNAVAILABLE, AVAILABLE, ACTIVE }

enum class SettingsTarget { BLUETOOTH, SOUND, APP_DETAILS }

/** EXT-002, EXT-004, CALL-003 */
interface SystemAudioPort {
    fun showSystemOutputPicker()
    val audioSharing: Flow<AudioSharingState>
    fun openSystemSettings(target: SettingsTarget)
}

/** DIA-004, DIA-005, DEV-008 */
interface PermissionPort {
    val statuses: StateFlow<Map<AppPermission, PermissionStatus>>
    suspend fun request(permission: AppPermission): PermissionStatus
    fun openAppSettings()
}

sealed interface PresenceEvent {
    val stableKey: String
    data class Appeared(override val stableKey: String) : PresenceEvent
    data class Disappeared(override val stableKey: String) : PresenceEvent
}

/** CFG-006 — OS presence wake-ups (Android CDM). iOS relies on Shortcuts + App Intents instead. */
interface PresencePort {
    suspend fun observe(device: DeviceRef): Boolean
    suspend fun stopObserving(device: DeviceRef)
    val presence: Flow<PresenceEvent>
}

/**
 * One synchronised play-and-record pass for the sync analyzer (MED-007).
 * [recording] is microphone audio kept in memory only; [emitFrame] is the recording frame at which
 * the probe signal was handed to the output.
 */
class ProbeCapture(val recording: FloatArray, val sampleRate: Int, val emitFrame: Int)

interface SyncProbePort {
    suspend fun probe(endpoint: EndpointId, signal: FloatArray, sampleRate: Int): ProbeCapture?
}

data class PlatformInfo(
    val os: String,
    val osVersion: String,
    val model: String,
    /** Whether media and communication audio can be kept on different outputs (VOIP-006). */
    val supportsSplitMediaAndComm: Boolean,
    val supportsMultiOutput: Boolean,
)

class PlatformPorts(
    val info: PlatformInfo,
    val audioDevices: AudioDevicePort,
    val mediaRouting: MediaRoutingPort,
    val commRouting: CommRoutingPort,
    val multiOutput: MultiOutputPort,
    val playback: PlaybackPort,
    val voip: VoipPort,
    val callObserver: CallObserverPort,
    val bluetooth: BluetoothPort,
    val systemAudio: SystemAudioPort,
    val permissions: PermissionPort,
    val presence: PresencePort,
    val syncProbe: SyncProbePort,
)
