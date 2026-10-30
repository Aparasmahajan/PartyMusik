package io.mahajan.partymusik.simulation

import io.mahajan.partymusik.domain.AppPermission
import io.mahajan.partymusik.domain.AudioEndpoint
import io.mahajan.partymusik.domain.BondedDevice
import io.mahajan.partymusik.domain.BtProfile
import io.mahajan.partymusik.domain.Capability
import io.mahajan.partymusik.domain.Capability.ABSOLUTE_VOLUME
import io.mahajan.partymusik.domain.Capability.BROADCAST_SINK
import io.mahajan.partymusik.domain.Capability.COMMUNICATION_OUTPUT
import io.mahajan.partymusik.domain.Capability.INDEPENDENT_VOLUME
import io.mahajan.partymusik.domain.Capability.LOW_LATENCY
import io.mahajan.partymusik.domain.Capability.MEDIA_OUTPUT
import io.mahajan.partymusik.domain.Capability.MICROPHONE
import io.mahajan.partymusik.domain.Capability.MULTI_OUTPUT_MEMBER
import io.mahajan.partymusik.domain.Capability.RECONNECTABLE
import io.mahajan.partymusik.domain.ChannelType
import io.mahajan.partymusik.domain.DeviceRef
import io.mahajan.partymusik.domain.EndpointId
import io.mahajan.partymusik.domain.EndpointKind
import io.mahajan.partymusik.domain.PermissionStatus
import io.mahajan.partymusik.domain.RouteCommandResult
import io.mahajan.partymusik.domain.RouteError
import io.mahajan.partymusik.engine.ProfileResolver
import io.mahajan.partymusik.platform.Clock
import io.mahajan.partymusik.ports.AudioDevicePort
import io.mahajan.partymusik.ports.AudioSharingState
import io.mahajan.partymusik.ports.BluetoothPort
import io.mahajan.partymusik.ports.BtAdapterState
import io.mahajan.partymusik.ports.CallObserverPort
import io.mahajan.partymusik.ports.CallState
import io.mahajan.partymusik.ports.CellularCallState
import io.mahajan.partymusik.ports.CommRoutingPort
import io.mahajan.partymusik.ports.MediaItemRef
import io.mahajan.partymusik.ports.MediaRoutingPort
import io.mahajan.partymusik.ports.MultiOutputPort
import io.mahajan.partymusik.ports.Peer
import io.mahajan.partymusik.ports.PermissionPort
import io.mahajan.partymusik.ports.PlatformInfo
import io.mahajan.partymusik.ports.PlatformPorts
import io.mahajan.partymusik.ports.PlaybackPort
import io.mahajan.partymusik.ports.PlaybackStatus
import io.mahajan.partymusik.ports.PresenceEvent
import io.mahajan.partymusik.ports.PresencePort
import io.mahajan.partymusik.ports.ProbeCapture
import io.mahajan.partymusik.ports.SettingsTarget
import io.mahajan.partymusik.ports.SyncProbePort
import io.mahajan.partymusik.ports.SystemAudioPort
import io.mahajan.partymusik.ports.SystemFlowResult
import io.mahajan.partymusik.ports.VoipPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/** One simulated accessory. [endpoint] is null for paired devices that have no audio profile. */
data class SimDevice(
    val key: String,
    val name: String,
    val kind: EndpointKind?,
    val capabilities: Set<Capability>,
    val latencyMs: Int,
    val connected: Boolean,
    val bonded: Boolean,
    val btProfiles: Set<BtProfile> = emptySet(),
) {
    val endpoint: AudioEndpoint?
        get() = kind?.let { AudioEndpoint(EndpointId("sim:$key"), key, name, it, capabilities = capabilities) }
}

/** Knobs to reproduce OS behaviours that the routing engine must handle truthfully. */
data class SimBehaviour(
    val confirmDelayMs: Long = 400,
    /** Media requests for these keys are silently ignored by the "OS" → engine times out. */
    val ignoreMediaFor: Set<String> = emptySet(),
    /** The "OS" routes media here instead of the requested device → RTE_OS_REDIRECTED. */
    val redirectMediaTo: String? = null,
    /** iOS-like: these kinds can only be chosen through the system route picker. */
    val pickerOnlyKinds: Set<EndpointKind> = emptySet(),
    /** Devices that cannot join multi-output on this "phone" (RTE_UNSUPPORTED_COMBO from the OS). */
    val multiRejectFor: Set<String> = emptySet(),
)

/**
 * A fake phone implementing every port, used by unit tests and the desktop UI prototype.
 * All mutations run on [scope]; give it a single-threaded dispatcher.
 */
class SimulatedPlatform(
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    devices: List<SimDevice> = defaultDevices(),
    val info: PlatformInfo = PlatformInfo(
        os = "Simulator", osVersion = "1.0", model = "Desktop simulator",
        supportsSplitMediaAndComm = true, supportsMultiOutput = true,
    ),
) {
    private val _devices = MutableStateFlow(devices)
    val devices: StateFlow<List<SimDevice>> = _devices.asStateFlow()

    private val _bt = MutableStateFlow(BtAdapterState.ON)
    val bluetoothState: StateFlow<BtAdapterState> = _bt.asStateFlow()

    private val _behaviour = MutableStateFlow(SimBehaviour())
    val behaviour: StateFlow<SimBehaviour> = _behaviour.asStateFlow()

    private var preferredMedia: EndpointId? = null
    private var pendingPicker: EndpointId? = null
    private var multiTargets: List<EndpointId> = emptyList()
    private var preferredComm: EndpointId? = null

    private val mediaRoute = MutableStateFlow<List<EndpointId>>(emptyList())
    private val multiRoute = MutableStateFlow<List<EndpointId>>(emptyList())
    private val commDevice = MutableStateFlow<EndpointId?>(null)
    private val _gains = MutableStateFlow<Map<EndpointId, Float>>(emptyMap())
    val gains: StateFlow<Map<EndpointId, Float>> = _gains.asStateFlow()
    private val _delays = MutableStateFlow<Map<EndpointId, Int>>(emptyMap())
    val delays: StateFlow<Map<EndpointId, Int>> = _delays.asStateFlow()

    private val cellular = MutableStateFlow(CellularCallState.NONE)
    private val callState = MutableStateFlow<CallState>(CallState.Idle)
    private val muted = MutableStateFlow(false)
    private val presenceEvents = MutableSharedFlow<PresenceEvent>(extraBufferCapacity = 32)
    private val observedPresence = mutableSetOf<String>()
    private val permissions = MutableStateFlow(AppPermission.entries.associateWith { PermissionStatus.GRANTED })
    private val playback = MutableStateFlow(PlaybackStatus())
    private var ticker: Job? = null

    private val endpointsFlow = MutableStateFlow<List<AudioEndpoint>>(emptyList())

    init {
        recompute()
    }

    // region simulator controls (used by tests and the desktop simulator panel)

    fun setConnected(key: String, connected: Boolean) = scope.launch {
        val before = _devices.value.firstOrNull { it.key == key } ?: return@launch
        if (before.connected == connected) return@launch
        _devices.update { list -> list.map { if (it.key == key) it.copy(connected = connected) else it } }
        if (key in observedPresence && (before.kind?.isBluetooth == true || before.bonded)) {
            presenceEvents.tryEmit(if (connected) PresenceEvent.Appeared(key) else PresenceEvent.Disappeared(key))
        }
        recompute()
    }

    fun setBluetooth(on: Boolean) = scope.launch {
        _bt.value = if (on) BtAdapterState.ON else BtAdapterState.OFF
        recompute()
    }

    fun setBehaviour(transform: (SimBehaviour) -> SimBehaviour) = scope.launch { _behaviour.update(transform) }

    fun setPermission(permission: AppPermission, status: PermissionStatus) =
        permissions.update { it + (permission to status) }

    fun startCellularCall() = scope.launch {
        cellular.value = CellularCallState.RINGING
        delay(1_000)
        if (cellular.value == CellularCallState.RINGING) cellular.value = CellularCallState.ACTIVE
    }

    fun endCellularCall() = scope.launch { cellular.value = CellularCallState.NONE }

    val cellularState: StateFlow<CellularCallState> = cellular.asStateFlow()

    // endregion

    private fun visible(): List<SimDevice> = _devices.value.filter { d ->
        d.connected && d.kind != null && (_bt.value == BtAdapterState.ON || !d.kind.isBluetooth)
    }

    private fun visibleEndpoints(): List<AudioEndpoint> = visible().mapNotNull { it.endpoint }

    private fun endpointFor(id: EndpointId): AudioEndpoint? = visibleEndpoints().firstOrNull { it.id == id }

    /** Mirrors how a real OS settles routes after any change. */
    private fun recompute() {
        val eps = visibleEndpoints()
        val ids = eps.map { it.id }.toSet()
        val preferred = preferredMedia?.takeIf { it in ids }
        mediaRoute.value = listOfNotNull(preferred ?: ProfileResolver.bestMedia(eps)?.id)
        multiRoute.value = multiTargets.filter { it in ids }
        commDevice.value = when {
            preferredComm == null -> null
            preferredComm in ids -> preferredComm
            else -> eps.firstOrNull { it.kind == EndpointKind.BUILTIN_EARPIECE }?.id
        }
        endpointsFlow.value = eps
    }

    private val library = listOf(
        MediaItemRef("tone-1k", "Test tone · 1 kHz", 30_000),
        MediaItemRef("sweep", "Frequency sweep 20 Hz–20 kHz", 45_000),
        MediaItemRef("party-mix", "Party mix (demo track)", 214_000),
    )

    val ports: PlatformPorts = PlatformPorts(
        info = info,
        audioDevices = object : AudioDevicePort {
            override val endpoints: Flow<List<AudioEndpoint>> = endpointsFlow
        },
        mediaRouting = object : MediaRoutingPort {
            override suspend fun requestOutput(endpoint: EndpointId): RouteCommandResult {
                val ep = endpointFor(endpoint) ?: return RouteCommandResult.Rejected(RouteError.DeviceGone)
                val b = _behaviour.value
                if (ep.kind in b.pickerOnlyKinds) {
                    pendingPicker = endpoint
                    return RouteCommandResult.NeedsSystemPicker
                }
                scope.launch {
                    delay(b.confirmDelayMs)
                    when {
                        ep.stableKey in b.ignoreMediaFor -> Unit
                        b.redirectMediaTo != null -> preferredMedia = EndpointId("sim:${b.redirectMediaTo}")
                        else -> preferredMedia = endpoint
                    }
                    recompute()
                }
                return RouteCommandResult.Submitted
            }

            override suspend fun clearPreference() {
                scope.launch {
                    preferredMedia = null
                    recompute()
                }
            }

            override val observedRoute: Flow<List<EndpointId>> = mediaRoute
        },
        commRouting = object : CommRoutingPort {
            override suspend fun request(endpoint: EndpointId): RouteCommandResult {
                val ep = endpointFor(endpoint) ?: return RouteCommandResult.Rejected(RouteError.DeviceGone)
                if (!ep.canCarry(ChannelType.COMMUNICATION)) return RouteCommandResult.Rejected(RouteError.NotACommDevice)
                scope.launch {
                    delay(_behaviour.value.confirmDelayMs + 600) // SCO / HFP link setup is slower
                    preferredComm = endpoint
                    recompute()
                }
                return RouteCommandResult.Submitted
            }

            override suspend fun release() {
                scope.launch {
                    preferredComm = null
                    recompute()
                }
            }

            override val observed: Flow<EndpointId?> = commDevice
        },
        multiOutput = object : MultiOutputPort {
            override suspend fun start(targets: List<EndpointId>): RouteCommandResult {
                val eps = targets.map { endpointFor(it) ?: return RouteCommandResult.Rejected(RouteError.DeviceGone) }
                if (eps.any { it.stableKey in _behaviour.value.multiRejectFor }) {
                    return RouteCommandResult.Rejected(RouteError.UnsupportedCombo)
                }
                scope.launch {
                    multiTargets = emptyList()
                    recompute()
                    // Sinks come up one by one, like independent Bluetooth links.
                    for (id in targets) {
                        delay(_behaviour.value.confirmDelayMs / 2 + Random.nextLong(0, 200))
                        multiTargets = multiTargets + id
                        recompute()
                    }
                }
                return RouteCommandResult.Submitted
            }

            override suspend fun stop() {
                scope.launch {
                    multiTargets = emptyList()
                    recompute()
                }
            }

            override fun setDelay(endpoint: EndpointId, ms: Int) = _delays.update { it + (endpoint to ms) }
            override fun setGain(endpoint: EndpointId, gain: Float) = _gains.update { it + (endpoint to gain) }
            override val observedRoutes: Flow<List<EndpointId>> = multiRoute
        },
        playback = object : PlaybackPort {
            override val library: List<MediaItemRef> = this@SimulatedPlatform.library
            override val status: StateFlow<PlaybackStatus> = playback.asStateFlow()

            override suspend fun load(item: MediaItemRef) {
                playback.value = PlaybackStatus(item = item, durationMs = item.durationMs)
            }

            override fun play() {
                if (playback.value.item == null) playback.value = PlaybackStatus(item = library.first(), durationMs = library.first().durationMs)
                playback.update { it.copy(playing = true) }
                ticker?.cancel()
                ticker = scope.launch {
                    while (true) {
                        delay(250)
                        val s = playback.value
                        if (!s.playing) break
                        val pos = s.positionMs + 250
                        playback.value = if (pos >= s.durationMs) s.copy(playing = false, positionMs = 0) else s.copy(positionMs = pos)
                    }
                }
            }

            override fun pause() {
                playback.update { it.copy(playing = false) }
            }

            override fun seekTo(positionMs: Long) {
                playback.update { it.copy(positionMs = positionMs.coerceIn(0, it.durationMs)) }
            }
        },
        voip = object : VoipPort {
            override val callState: StateFlow<CallState> = this@SimulatedPlatform.callState.asStateFlow()
            override val muted: StateFlow<Boolean> = this@SimulatedPlatform.muted.asStateFlow()

            override suspend fun startCall(peer: Peer): RouteCommandResult {
                if (permissions.value[AppPermission.MICROPHONE] != PermissionStatus.GRANTED) {
                    return RouteCommandResult.Rejected(RouteError.PermissionMissing(AppPermission.MICROPHONE))
                }
                if (this@SimulatedPlatform.callState.value !is CallState.Idle && this@SimulatedPlatform.callState.value !is CallState.Ended) {
                    return RouteCommandResult.Rejected(RouteError.SessionConflict)
                }
                scope.launch {
                    this@SimulatedPlatform.callState.value = CallState.Dialing
                    delay(700)
                    this@SimulatedPlatform.callState.value = CallState.Active(clock.nowMillis())
                }
                return RouteCommandResult.Submitted
            }

            override suspend fun hangup() {
                scope.launch {
                    this@SimulatedPlatform.callState.value = CallState.Ended("local")
                    this@SimulatedPlatform.muted.value = false
                    delay(300)
                    this@SimulatedPlatform.callState.value = CallState.Idle
                }
            }

            override suspend fun setMuted(muted: Boolean) {
                this@SimulatedPlatform.muted.value = muted
            }
        },
        callObserver = object : CallObserverPort {
            override val cellularCall: Flow<CellularCallState> = cellular
        },
        bluetooth = object : BluetoothPort {
            override val adapterState: Flow<BtAdapterState> = _bt
            override val bonded: Flow<List<BondedDevice>> = combine(_devices, _bt) { list, bt ->
                list.filter { it.bonded }.map {
                    BondedDevice(it.key, it.name, it.btProfiles, connected = it.connected && bt == BtAdapterState.ON)
                }
            }

            override suspend fun launchPairing(): SystemFlowResult = SystemFlowResult.LAUNCHED

            override suspend fun launchReconnect(device: DeviceRef): SystemFlowResult {
                scope.launch {
                    delay(1_200)
                    setConnected(device.stableKey, true)
                }
                return SystemFlowResult.LAUNCHED
            }

            override suspend fun requestEnable(): SystemFlowResult {
                setBluetooth(true)
                return SystemFlowResult.COMPLETED
            }
        },
        systemAudio = object : SystemAudioPort {
            override fun showSystemOutputPicker() {
                // Simulates the user confirming the pending device in the OS picker.
                scope.launch {
                    val pick = pendingPicker ?: return@launch
                    pendingPicker = null
                    delay(_behaviour.value.confirmDelayMs)
                    preferredMedia = pick
                    recompute()
                }
            }

            override val audioSharing: Flow<AudioSharingState> = endpointsFlow.map { eps ->
                if (eps.any { BROADCAST_SINK in it.capabilities }) AudioSharingState.AVAILABLE else AudioSharingState.UNAVAILABLE
            }

            override fun openSystemSettings(target: SettingsTarget) = Unit
        },
        permissions = object : PermissionPort {
            override val statuses: StateFlow<Map<AppPermission, PermissionStatus>> = permissions.asStateFlow()

            override suspend fun request(permission: AppPermission): PermissionStatus {
                val current = permissions.value[permission] ?: PermissionStatus.DENIED
                if (current == PermissionStatus.DENIED) permissions.update { it + (permission to PermissionStatus.GRANTED) }
                return permissions.value.getValue(permission)
            }

            override fun openAppSettings() = Unit
        },
        presence = object : PresencePort {
            override suspend fun observe(device: DeviceRef): Boolean = observedPresence.add(device.stableKey).let { true }
            override suspend fun stopObserving(device: DeviceRef) {
                observedPresence.remove(device.stableKey)
            }
            override val presence: Flow<PresenceEvent> = presenceEvents
        },
        syncProbe = object : SyncProbePort {
            override suspend fun probe(endpoint: EndpointId, signal: FloatArray, sampleRate: Int): ProbeCapture? {
                val device = visible().firstOrNull { it.endpoint?.id == endpoint } ?: return null
                delay(150)
                val latencyMs = device.latencyMs + Random.nextDouble(-2.0, 2.0)
                val emitFrame = sampleRate / 20
                val arrival = emitFrame + (latencyMs * sampleRate / 1000).toInt()
                val recording = FloatArray(arrival + signal.size + sampleRate / 10) { Random.nextFloat() * 0.04f - 0.02f }
                signal.forEachIndexed { i, v -> recording[arrival + i] += v * 0.6f }
                return ProbeCapture(recording, sampleRate, emitFrame)
            }
        },
    )

    companion object {
        fun defaultDevices(): List<SimDevice> = listOf(
            SimDevice(
                "builtin-speaker", "Phone speaker", EndpointKind.BUILTIN_SPEAKER,
                setOf(MEDIA_OUTPUT, COMMUNICATION_OUTPUT, MULTI_OUTPUT_MEMBER, LOW_LATENCY, INDEPENDENT_VOLUME),
                latencyMs = 25, connected = true, bonded = false,
            ),
            SimDevice(
                "builtin-earpiece", "Phone earpiece", EndpointKind.BUILTIN_EARPIECE,
                setOf(COMMUNICATION_OUTPUT, MICROPHONE, LOW_LATENCY),
                latencyMs = 20, connected = true, bonded = false,
            ),
            SimDevice(
                "bt-sony-xm5", "Sony WH-1000XM5", EndpointKind.BT_CLASSIC_MEDIA,
                setOf(MEDIA_OUTPUT, COMMUNICATION_OUTPUT, MICROPHONE, MULTI_OUTPUT_MEMBER, ABSOLUTE_VOLUME, RECONNECTABLE),
                latencyMs = 220, connected = true, bonded = true, btProfiles = setOf(BtProfile.A2DP, BtProfile.HFP),
            ),
            SimDevice(
                "bt-jbl-charge6", "JBL Charge 6", EndpointKind.BT_CLASSIC_MEDIA,
                setOf(MEDIA_OUTPUT, MULTI_OUTPUT_MEMBER, BROADCAST_SINK, ABSOLUTE_VOLUME, RECONNECTABLE),
                latencyMs = 180, connected = true, bonded = true, btProfiles = setOf(BtProfile.A2DP, BtProfile.LE_AUDIO),
            ),
            SimDevice(
                "bt-buds3pro", "Galaxy Buds3 Pro", EndpointKind.BT_LE_AUDIO,
                setOf(MEDIA_OUTPUT, COMMUNICATION_OUTPUT, MICROPHONE, MULTI_OUTPUT_MEMBER, LOW_LATENCY, RECONNECTABLE),
                latencyMs = 90, connected = false, bonded = true, btProfiles = setOf(BtProfile.A2DP, BtProfile.HFP, BtProfile.LE_AUDIO),
            ),
            SimDevice(
                "bt-carkit", "Car kit (hands-free)", EndpointKind.BT_CLASSIC_HFP,
                setOf(COMMUNICATION_OUTPUT, MICROPHONE),
                latencyMs = 150, connected = false, bonded = true, btProfiles = setOf(BtProfile.HFP),
            ),
            SimDevice(
                "usb-dac", "USB-C DAC", EndpointKind.USB,
                setOf(MEDIA_OUTPUT, MULTI_OUTPUT_MEMBER, LOW_LATENCY, INDEPENDENT_VOLUME),
                latencyMs = 12, connected = false, bonded = false,
            ),
            SimDevice(
                "bt-watch", "Pixel Watch", kind = null, capabilities = emptySet(),
                latencyMs = 0, connected = true, bonded = true, btProfiles = setOf(BtProfile.OTHER),
            ),
        )
    }
}
