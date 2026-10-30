package io.mahajan.partymusik.ios

import io.mahajan.partymusik.PartyMusikCore
import io.mahajan.partymusik.diagnostics.Redaction
import io.mahajan.partymusik.domain.AppPermission
import io.mahajan.partymusik.domain.AudioEndpoint
import io.mahajan.partymusik.domain.BondedDevice
import io.mahajan.partymusik.domain.Capability
import io.mahajan.partymusik.domain.DeviceRef
import io.mahajan.partymusik.domain.EndpointId
import io.mahajan.partymusik.domain.EndpointKind
import io.mahajan.partymusik.domain.PermissionStatus
import io.mahajan.partymusik.domain.Profile
import io.mahajan.partymusik.domain.RequestSource
import io.mahajan.partymusik.domain.RouteCommandResult
import io.mahajan.partymusik.domain.RouteError
import io.mahajan.partymusik.platform.DocumentsExportSink
import io.mahajan.partymusik.platform.FileTextStore
import io.mahajan.partymusik.platform.currentTimeMillis
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Adapts the Swift drivers to the shared ports (spikes S-02, S-05, S-07, S-09, S-12, S-16). */
class IosPlatform(private val drivers: IosDrivers) : IosEventSink {
    private val session get() = drivers.session

    private val library = listOf(
        MediaItemRef("tone-1k", "Test tone · 1 kHz", 30_000),
        MediaItemRef("sweep", "Frequency sweep 20 Hz–20 kHz", 45_000),
        MediaItemRef("party-mix", "Party beat (124 BPM)", 214_000),
    )

    private val routeTick = MutableStateFlow(0L)
    private val playback = MutableStateFlow(PlaybackStatus())
    private val callState = MutableStateFlow<CallState>(CallState.Idle)
    private val muted = MutableStateFlow(false)
    private val cellular = MutableStateFlow(CellularCallState.NONE)
    private val multiUids = MutableStateFlow<List<String>>(emptyList())
    private val permissions = MutableStateFlow(readPermissions())

    /** stableKey → ports seen in the last snapshot (an accessory can expose several, e.g. HFP in + out). */
    private var portsByKey: Map<String, List<IosPort>> = emptyMap()

    init {
        drivers.attach(this)
    }

    // region IosEventSink

    override fun routeChanged(reason: String) {
        routeTick.value = routeTick.value + 1
    }

    override fun mediaServicesReset() {
        routeTick.value = routeTick.value + 1
        multiUids.value = emptyList()
    }

    override fun playbackChanged(itemId: String?, playing: Boolean, positionMs: Long, durationMs: Long) {
        playback.value = PlaybackStatus(library.firstOrNull { it.id == itemId }, playing, positionMs, durationMs)
    }

    override fun callStateChanged(state: String) {
        callState.value = when {
            state == "dialing" -> CallState.Dialing
            state == "active" -> CallState.Active(currentTimeMillis())
            state == "held" -> CallState.Held
            state.startsWith("ended") -> CallState.Ended(state.substringAfter(':', "ended"))
            else -> CallState.Idle
        }
        routeTick.value = routeTick.value + 1
    }

    override fun mutedChanged(muted: Boolean) {
        this.muted.value = muted
    }

    override fun cellularChanged(state: String) {
        cellular.value = when (state) {
            "ringing" -> CellularCallState.RINGING
            "active" -> CellularCallState.ACTIVE
            else -> CellularCallState.NONE
        }
    }

    override fun multiRouteChanged(activeUids: List<String>) {
        multiUids.value = activeUids
    }

    // endregion

    // region identity & endpoints

    private fun keyOf(p: IosPort): String = when (p.portType) {
        "speaker" -> SPEAKER
        "receiver", "mic_builtin" -> RECEIVER
        // Bluetooth UIDs look like "<MAC>-tacl" / "<MAC>-tsco": group by MAC, never expose it.
        "bt_a2dp", "bt_hfp", "bt_le", "car" -> "ios-bt-" + Redaction.fnv1a64("pm:" + p.uid.substringBefore('-').uppercase()).take(12)
        else -> "ios-${p.portType}-" + Redaction.fnv1a64(p.uid).take(8)
    }

    private fun kindOf(types: Set<String>): EndpointKind = when {
        "bt_le" in types -> EndpointKind.BT_LE_AUDIO
        "bt_a2dp" in types -> EndpointKind.BT_CLASSIC_MEDIA
        "bt_hfp" in types -> EndpointKind.BT_CLASSIC_HFP
        "car" in types -> EndpointKind.CAR_AUDIO
        "wired" in types -> EndpointKind.WIRED
        "usb" in types -> EndpointKind.USB
        "hdmi" in types -> EndpointKind.HDMI
        "airplay" in types -> EndpointKind.AIRPLAY
        "speaker" in types -> EndpointKind.BUILTIN_SPEAKER
        "receiver" in types || "mic_builtin" in types -> EndpointKind.BUILTIN_EARPIECE
        else -> EndpointKind.OTHER
    }

    private fun snapshot(): List<AudioEndpoint> {
        val ports = session.currentOutputs() + session.availableInputs()
        val grouped = ports.groupBy(::keyOf).toMutableMap()
        // The built-ins are always selectable even when not in the current route.
        grouped.getOrPut(SPEAKER) { listOf(IosPort("builtin-speaker", "iPhone speaker", "speaker", 2, false)) }
        grouped.getOrPut(RECEIVER) { listOf(IosPort("builtin-receiver", "iPhone earpiece", "receiver", 1, false)) }
        portsByKey = grouped
        return grouped.map { (key, list) ->
            val types = list.map { it.portType }.toSet()
            val kind = kindOf(types)
            val hasInput = list.any { it.isInput } || kind == EndpointKind.BUILTIN_EARPIECE
            val caps = buildSet {
                if (kind != EndpointKind.BUILTIN_EARPIECE && kind != EndpointKind.BT_CLASSIC_HFP) add(Capability.MEDIA_OUTPUT)
                if (kind in COMM_KINDS || hasInput) add(Capability.COMMUNICATION_OUTPUT)
                if (hasInput) add(Capability.MICROPHONE)
                if (kind in MULTI_KINDS) add(Capability.MULTI_OUTPUT_MEMBER)
                if (kind in MULTI_KINDS) add(Capability.INDEPENDENT_VOLUME)
                if (kind == EndpointKind.BUILTIN_SPEAKER || kind == EndpointKind.WIRED || kind == EndpointKind.USB) add(Capability.LOW_LATENCY)
            }
            val display = list.firstOrNull { !it.isInput }?.name ?: list.first().name
            AudioEndpoint(EndpointId(key), key, display, kind, capabilities = caps, channelCounts = list.map { it.channels }.distinct())
        }.sortedBy { it.kind.ordinal }
    }

    private fun result(raw: String): RouteCommandResult = when {
        raw == "submitted" -> RouteCommandResult.Submitted
        raw == "picker" -> RouteCommandResult.NeedsSystemPicker
        raw == "gone" -> RouteCommandResult.Rejected(RouteError.DeviceGone)
        raw == "unsupported" -> RouteCommandResult.Rejected(RouteError.UnsupportedCombo)
        raw == "conflict" -> RouteCommandResult.Rejected(RouteError.SessionConflict)
        else -> RouteCommandResult.Rejected(RouteError.OsRejected(raw.removePrefix("error:")))
    }

    private fun portsFor(id: EndpointId): List<IosPort>? = portsByKey[id.value]

    private fun currentOutputKeys(): List<String> = session.currentOutputs().map(::keyOf).distinct()

    // endregion

    private fun readPermissions(): Map<AppPermission, PermissionStatus> = mapOf(
        AppPermission.MICROPHONE to when (drivers.system.microphonePermission()) {
            "granted" -> PermissionStatus.GRANTED
            "denied" -> PermissionStatus.PERMANENTLY_DENIED // iOS asks only once; afterwards Settings is the only path
            else -> PermissionStatus.DENIED
        },
        AppPermission.NEARBY_DEVICES to PermissionStatus.NOT_REQUIRED,
        AppPermission.NOTIFICATIONS to PermissionStatus.NOT_REQUIRED,
        AppPermission.COMPANION_PRESENCE to PermissionStatus.NOT_REQUIRED,
    )

    val ports: PlatformPorts by lazy {
        PlatformPorts(
            info = PlatformInfo(
                os = "iOS", osVersion = drivers.system.osVersion, model = drivers.system.model,
                // One AVAudioSession per app: media and call audio share a route unless spike S-07 proves otherwise.
                supportsSplitMediaAndComm = false,
                supportsMultiOutput = true,
            ),
            audioDevices = object : AudioDevicePort {
                override val endpoints: Flow<List<AudioEndpoint>> = routeTick.map { snapshot() }.distinctUntilChanged()
            },
            mediaRouting = object : MediaRoutingPort {
                override suspend fun requestOutput(endpoint: EndpointId): RouteCommandResult {
                    val ports = portsFor(endpoint) ?: return RouteCommandResult.Rejected(RouteError.DeviceGone)
                    val type = ports.firstOrNull { !it.isInput }?.portType ?: ports.first().portType
                    return result(session.requestMediaOutput(type, ports.map { it.uid }))
                }

                override suspend fun clearPreference() = session.clearMediaPreference()

                override val observedRoute: Flow<List<EndpointId>> = routeTick.map { currentOutputKeys().map(::EndpointId) }
            },
            commRouting = object : CommRoutingPort {
                override suspend fun request(endpoint: EndpointId): RouteCommandResult {
                    val ports = portsFor(endpoint) ?: return RouteCommandResult.Rejected(RouteError.DeviceGone)
                    val type = ports.firstOrNull { it.isInput }?.portType ?: ports.first().portType
                    return result(session.requestCommunicationOutput(type, ports.map { it.uid }))
                }

                override suspend fun release() = session.releaseCommunication()

                // While a call runs, the session's current output *is* the call route.
                override val observed: Flow<EndpointId?> = combine(routeTick, callState) { _, call ->
                    if (call is CallState.Active || call is CallState.Held) currentOutputKeys().firstOrNull()?.let(::EndpointId) else null
                }.distinctUntilChanged()
            },
            multiOutput = object : MultiOutputPort {
                override suspend fun start(targets: List<EndpointId>): RouteCommandResult {
                    val uids = targets.map { id -> portsFor(id)?.firstOrNull { !it.isInput }?.uid ?: return RouteCommandResult.Rejected(RouteError.DeviceGone) }
                    return result(session.startMultiRoute(uids))
                }

                override suspend fun stop() = session.stopMultiRoute()

                override fun setDelay(endpoint: EndpointId, ms: Int) {
                    portsFor(endpoint)?.firstOrNull { !it.isInput }?.let { session.setDelay(it.uid, ms) }
                }

                override fun setGain(endpoint: EndpointId, gain: Float) {
                    portsFor(endpoint)?.firstOrNull { !it.isInput }?.let { session.setGain(it.uid, gain) }
                }

                override val observedRoutes: Flow<List<EndpointId>> = multiUids.map { uids ->
                    uids.mapNotNull { uid -> portsByKey.entries.firstOrNull { (_, ps) -> ps.any { it.uid == uid } }?.key?.let(::EndpointId) }.distinct()
                }
            },
            playback = object : PlaybackPort {
                override val library: List<MediaItemRef> = this@IosPlatform.library
                override val status: StateFlow<PlaybackStatus> = playback.asStateFlow()
                override suspend fun load(item: MediaItemRef) = drivers.player.load(item.id, item.durationMs)
                override fun play() = drivers.player.play()
                override fun pause() = drivers.player.pause()
                override fun seekTo(positionMs: Long) = drivers.player.seek(positionMs)
            },
            voip = object : VoipPort {
                override val callState: StateFlow<CallState> = this@IosPlatform.callState.asStateFlow()
                override val muted: StateFlow<Boolean> = this@IosPlatform.muted.asStateFlow()

                override suspend fun startCall(peer: Peer): RouteCommandResult {
                    if (permissions.value[AppPermission.MICROPHONE] != PermissionStatus.GRANTED) {
                        return RouteCommandResult.Rejected(RouteError.PermissionMissing(AppPermission.MICROPHONE))
                    }
                    return result(drivers.calls.startCall(peer.name, peer.uri))
                }

                override suspend fun hangup() = drivers.calls.hangup()
                override suspend fun setMuted(muted: Boolean) = drivers.calls.setMuted(muted)
            },
            callObserver = object : CallObserverPort {
                override val cellularCall: Flow<CellularCallState> = cellular
            },
            bluetooth = object : BluetoothPort {
                // iOS exposes neither the adapter state nor paired devices to ordinary apps.
                override val adapterState: Flow<BtAdapterState> = flowOf(BtAdapterState.ON)
                override val bonded: Flow<List<BondedDevice>> = flowOf(emptyList())

                override suspend fun launchPairing(): SystemFlowResult {
                    drivers.system.showPairingHelp()
                    return SystemFlowResult.LAUNCHED
                }

                override suspend fun launchReconnect(device: DeviceRef): SystemFlowResult {
                    session.showRoutePicker()
                    return SystemFlowResult.LAUNCHED
                }

                override suspend fun requestEnable(): SystemFlowResult {
                    drivers.system.showPairingHelp()
                    return SystemFlowResult.LAUNCHED
                }
            },
            systemAudio = object : SystemAudioPort {
                override fun showSystemOutputPicker() = session.showRoutePicker()

                // Share Audio shows up as two Apple headphones in the current route.
                override val audioSharing: Flow<AudioSharingState> = routeTick.map {
                    val bt = session.currentOutputs().count { it.portType.startsWith("bt_") }
                    if (bt >= 2) AudioSharingState.ACTIVE else AudioSharingState.UNAVAILABLE
                }.distinctUntilChanged()

                override fun openSystemSettings(target: SettingsTarget) = drivers.system.openSettings(target.name.lowercase())
            },
            permissions = object : PermissionPort {
                override val statuses: StateFlow<Map<AppPermission, PermissionStatus>> = permissions.asStateFlow()

                override suspend fun request(permission: AppPermission): PermissionStatus {
                    if (permission == AppPermission.MICROPHONE && permissions.value[permission] == PermissionStatus.DENIED) {
                        suspendCancellableCoroutine { cont ->
                            drivers.system.requestMicrophone(object : IosStringCallback {
                                override fun onResult(value: String) {
                                    if (cont.isActive) cont.resume(value)
                                }
                            })
                        }
                    }
                    permissions.value = readPermissions()
                    return permissions.value.getValue(permission)
                }

                override fun openAppSettings() = drivers.system.openSettings("app_details")
            },
            presence = object : PresencePort {
                // ADR-004: background triggers on iOS come from Shortcuts personal automations → ApplyProfileIntent.
                override suspend fun observe(device: DeviceRef): Boolean = false
                override suspend fun stopObserving(device: DeviceRef) = Unit
                override val presence: Flow<PresenceEvent> = emptyFlow()
            },
            syncProbe = object : SyncProbePort {
                override suspend fun probe(endpoint: EndpointId, signal: FloatArray, sampleRate: Int): ProbeCapture? {
                    val uid = portsFor(endpoint)?.firstOrNull { !it.isInput }?.uid ?: return null
                    return suspendCancellableCoroutine { cont ->
                        drivers.system.probe(uid, signal, sampleRate, object : IosProbeCallback {
                            override fun onResult(recording: FloatArray?, emitFrame: Int) {
                                if (cont.isActive) cont.resume(recording?.let { ProbeCapture(it, sampleRate, emitFrame) })
                            }
                        })
                    }
                }
            },
        )
    }

    private companion object {
        const val SPEAKER = "ios-speaker"
        const val RECEIVER = "ios-receiver"
        val COMM_KINDS = setOf(
            EndpointKind.BUILTIN_SPEAKER, EndpointKind.BUILTIN_EARPIECE, EndpointKind.BT_CLASSIC_HFP, EndpointKind.BT_LE_AUDIO,
            EndpointKind.WIRED, EndpointKind.USB, EndpointKind.CAR_AUDIO,
        )
        val MULTI_KINDS = setOf(EndpointKind.WIRED, EndpointKind.USB, EndpointKind.HDMI, EndpointKind.BT_CLASSIC_MEDIA, EndpointKind.BUILTIN_SPEAKER)
    }
}

/** Entry points used from Swift. */
object IosBridge {
    private var core: PartyMusikCore? = null

    fun createCore(drivers: IosDrivers): PartyMusikCore {
        core?.let { return it }
        val platform = IosPlatform(drivers)
        val created = PartyMusikCore(
            platform.ports,
            FileTextStore("partymusik.json"),
            CoroutineScope(SupervisorJob() + Dispatchers.Main),
            exportSink = DocumentsExportSink(),
            diagnosticsStore = FileTextStore("diagnostics.json"),
        )
        created.start()
        core = created
        return created
    }

    /** For App Intents (Shortcuts automation, CFG-006): profile ids and names. */
    fun profiles(): List<Profile> = core?.profiles?.profiles?.value ?: emptyList()

    fun applyProfile(profileId: String, fromAutomation: Boolean) {
        val c = core ?: return
        c.scope.launch {
            if (fromAutomation) {
                // S-14 counts these against the number of connect cycles performed.
                val name = c.profiles.profiles.value.firstOrNull { it.id == profileId }?.name ?: profileId
                c.diagnostics.log("AUTOMATION_TRIGGERED", null, mapOf("profile" to name, "trigger" to "shortcut", "source" to "ios-shortcut"))
            }
            c.profiles.apply(profileId, if (fromAutomation) RequestSource.AUTOMATION else RequestSource.USER)
        }
    }
}
