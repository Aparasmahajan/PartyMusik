# Native Bridge API (Ports)

**Status:** Draft v0.1 — 2026-10-27
**Location:** `shared/src/commonMain/kotlin/ports/`
**Implemented by:** `shared/src/androidMain` (Kotlin) and `iosApp/PlatformIOS` (Swift)
**Related:** [Android](03-android-architecture.md) · [iOS](04-ios-architecture.md)

---

## 1. Conventions

> **POC implementation note (D4, D6 — the implemented ports are in `shared/src/commonMain/kotlin/io/mahajan/partymusik/ports/Ports.kt`; iOS uses Swift driver protocols and `SyncProbePort` replaces `MicCapturePort`):** see [15-poc-implementation.md §5](15-poc-implementation.md#5-deviations-from-the-design-docs-decided-while-building).

- Ports are plain Kotlin `interface`s in `commonMain`. On iOS they are exported through the KMP framework as Objective-C protocols and implemented in Swift.
- Streams are `Flow<T>`. On iOS, Swift implementations return a `Flow` built from a `SkieSwiftFlow` / `MutableStateFlow` wrapper; SKIE converts KMP `Flow` → Swift `AsyncSequence` for consumers.
- Commands are `suspend` functions returning `RouteCommandResult`. **A command result never means "active"** — only the observed flows confirm (RTE-008).
- All IDs are `EndpointId`; adapters own the mapping to `AudioDeviceInfo` / `AVAudioSessionPortDescription`.
- Adapters must be **idempotent** and **non-throwing** across the boundary: errors are values.

```kotlin
sealed interface RouteCommandResult {
    data object Submitted : RouteCommandResult
    data object NeedsSystemPicker : RouteCommandResult          // iOS A2DP/AirPlay, Android third-party
    data class Rejected(val error: RouteError) : RouteCommandResult
}

sealed interface RouteError {
    val code: String
    data object DeviceGone : RouteError { override val code = "RTE_DEVICE_GONE" }
    data object NotACommDevice : RouteError { override val code = "RTE_NOT_COMM_CAPABLE" }
    data object UnsupportedCombo : RouteError { override val code = "RTE_UNSUPPORTED_COMBO" }
    data object SessionConflict : RouteError { override val code = "RTE_SESSION_CONFLICT" }
    data object BluetoothOff : RouteError { override val code = "BT_OFF" }
    data class PermissionMissing(val perm: AppPermission) : RouteError { override val code = "PERM_${perm.name}_DENIED" }
    data class OsRejected(val detail: String) : RouteError { override val code = "RTE_OS_REJECTED" }
}
```

## 2. Port catalogue

### 2.1 `AudioDevicePort` — DEV-001, DEV-002, DEV-004

```kotlin
interface AudioDevicePort {
    val endpoints: Flow<List<AudioEndpoint>>                 // full snapshot on every change
    val events: Flow<DeviceEvent>                            // Added / Removed / Changed with reason
    suspend fun refresh()
}

sealed interface DeviceEvent {
    data class Added(val endpoint: AudioEndpoint) : DeviceEvent
    data class Removed(val id: EndpointId, val reason: RemovalReason) : DeviceEvent
    data class RouteChanged(val reason: String) : DeviceEvent      // iOS RouteChangeReason / Android cause
    data object MediaServicesReset : DeviceEvent
}
```

### 2.2 `MediaRoutingPort` — MED-001, MED-004, RTE-001, RTE-004

```kotlin
interface MediaRoutingPort {
    suspend fun requestOutput(endpoint: EndpointId): RouteCommandResult
    suspend fun clearPreference()
    val observedRoute: Flow<List<EndpointId>>                // ground truth
}
```

### 2.3 `CommRoutingPort` — RTE-002, RTE-003, VOIP-002, VOIP-004

```kotlin
interface CommRoutingPort {
    val available: Flow<List<EndpointId>>
    suspend fun request(endpoint: EndpointId): RouteCommandResult
    suspend fun release()                                     // VOIP-005
    val observed: Flow<EndpointId?>
}
```

### 2.4 `PlaybackPort` — MED-003

```kotlin
interface PlaybackPort {
    suspend fun load(items: List<MediaItemRef>)
    fun play(); fun pause(); fun seekTo(positionMs: Long)
    val status: Flow<PlaybackStatus>                          // state, position, duration, buffering, error
    val focus: Flow<FocusEvent>                               // RTE-007
}
```

### 2.5 `MultiOutputPort` — MED-002, MED-007, MED-008

```kotlin
interface MultiOutputPort {
    val supported: Flow<MultiOutputSupport>                   // which sets the platform can attempt now
    suspend fun start(targets: List<EndpointId>, source: MediaItemRef): RouteCommandResult
    suspend fun stop()
    fun setDelay(endpoint: EndpointId, ms: Int)
    fun setGain(endpoint: EndpointId, gain: Float)
    val perOutputRoute: Flow<Map<EndpointId, Boolean>>        // routed/confirmed per sink
    fun timestamps(): Map<EndpointId, PresentationTimestamp>
}
```

### 2.6 `VoipPort` — VOIP-001..006

```kotlin
interface VoipPort {
    suspend fun startCall(peer: Peer): RouteCommandResult
    suspend fun answer(callId: String)
    suspend fun hangup()
    suspend fun setMuted(muted: Boolean)
    suspend fun switchEndpoint(endpoint: EndpointId): RouteCommandResult
    val callState: Flow<CallState>                            // Idle, Dialing, Ringing, Active, Held, Ended(reason)
    val endpoints: Flow<List<EndpointId>>
    val currentEndpoint: Flow<EndpointId?>
}
```

### 2.7 `CallObserverPort` — CALL-001, RTE-006, RTE-007

```kotlin
interface CallObserverPort {
    val cellularCall: Flow<CellularCallState>                 // None, Ringing, Active, Ended
    val cellularRoute: Flow<List<EndpointId>>                 // read-only, when exposed
}
```

### 2.8 `BluetoothPort` — DEV-003, DEV-006, DEV-007, DEV-008

```kotlin
interface BluetoothPort {
    val adapterState: Flow<BtAdapterState>                    // ON, OFF, TURNING_ON, UNSUPPORTED, UNAUTHORIZED
    val bonded: Flow<List<BondedDevice>>                      // name, hashedAddress, profiles connected
    suspend fun launchPairing(): SystemFlowResult             // CDM / AccessorySetupKit / Settings
    suspend fun launchReconnect(device: DeviceRef): SystemFlowResult
    suspend fun requestEnable(): SystemFlowResult
}
```

### 2.9 `SystemAudioPort` — EXT-002, EXT-004, CALL-003

```kotlin
interface SystemAudioPort {
    fun showSystemOutputPicker()                              // MediaRouter2 / AVRoutePickerView
    val audioSharing: Flow<AudioSharingState>                 // Auracast / Share Audio detected
    fun openSystemSettings(target: SettingsTarget)            // BLUETOOTH, SOUND, APP_PERMISSIONS
}
```

### 2.10 `PermissionPort` — DIA-004, DIA-005, DEV-008

```kotlin
enum class AppPermission { BLUETOOTH, MICROPHONE, NOTIFICATIONS, NEARBY_DEVICES, COMPANION_PRESENCE }

interface PermissionPort {
    fun status(p: AppPermission): PermissionStatus            // GRANTED, DENIED, PERMANENTLY_DENIED, NOT_REQUIRED
    suspend fun request(p: AppPermission): PermissionStatus
    fun openAppSettings()
}
```

### 2.11 `PresencePort` — CFG-006

```kotlin
interface PresencePort {
    suspend fun observe(device: DeviceRef): Boolean           // Android CDM presence; iOS no-op (Shortcuts)
    suspend fun stopObserving(device: DeviceRef)
    val presence: Flow<PresenceEvent>                         // Appeared / Disappeared
}
```

### 2.12 `MicCapturePort` — MED-007 sync measurement only

```kotlin
interface MicCapturePort {
    suspend fun captureWindow(durationMs: Int, unprocessed: Boolean = true): PcmBuffer  // in-memory only
}
```

## 3. Platform implementation matrix

| Port | Android implementation | iOS implementation |
|------|------------------------|--------------------|
| AudioDevicePort | `AudioManager.getDevices` + `AudioDeviceCallback` | `currentRoute`, `availableInputs`, `routeChangeNotification` |
| MediaRoutingPort | `ExoPlayer.setPreferredAudioDevice`, `getAudioDevicesForAttributes` | `setPreferredInput`, `overrideOutputAudioPort`, picker |
| CommRoutingPort | `setCommunicationDevice` / `clearCommunicationDevice` + listener | `.playAndRecord` + `setPreferredInput` (HFP/LE) |
| PlaybackPort | Media3 `MediaController` | `AVQueuePlayer` / `AVAudioEngine` + `MPRemoteCommandCenter` |
| MultiOutputPort | N × `AudioTrack.setPreferredDevice` | `.multiRoute` + `AVAudioEngine` `channelMap` |
| VoipPort | Core-Telecom `CallsManager` + `AudioRecord/AudioTrack` | CallKit + `AVAudioEngine` VoiceProcessingIO |
| CallObserverPort | `OnModeChangedListener` | `CXCallObserver` |
| BluetoothPort | `BluetoothManager`, profile proxies, CDM | AccessorySetupKit, `CBCentralManager`, Settings |
| SystemAudioPort | `MediaRouter2.showSystemOutputSwitcher` | `AVRoutePickerView` |
| PermissionPort | Activity Result API | `AVAudioApplication.requestRecordPermission`, CB auth |
| PresencePort | `CompanionDeviceManager.startObservingDevicePresence` | App Intents + Shortcuts automation |
| MicCapturePort | `AudioRecord(UNPROCESSED)` | `AVAudioEngine.inputNode` in `.measurement` |

## 4. Fakes

Every port has a `Fake*Port` in `shared/src/commonTest` driven by a scripted scenario DSL:

```kotlin
scenario {
    at(0.ms)   { connect(sonyXm5, kinds = setOf(BT_CLASSIC_MEDIA, BT_CLASSIC_HFP)) }
    at(10.ms)  { user selects media(sonyXm5) }
    at(400.ms) { os reportsMedia(sonyXm5) }
    expect     { media.state is Confirmed }
    at(5.s)    { disconnect(sonyXm5) }
    expect     { media.state is Degraded && media.active == listOf(builtinSpeaker) }
}
```
