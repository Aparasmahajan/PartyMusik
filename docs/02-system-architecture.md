# System Architecture

**Status:** Draft v0.1 — 2026-10-27
**Related:** [Android](03-android-architecture.md) · [iOS](04-ios-architecture.md) · [Shared core & UI](05-shared-core-and-ui.md) · [Routing engine](06-routing-engine.md) · [Bridge API](08-native-bridge-api.md) · [ADRs](adr/)

---

## 1. Architectural drivers

| Driver | Source | Architectural response |
|--------|--------|------------------------|
| One UI, two native audio stacks | SRS header, Deliverable 3 & 4 | Kotlin Multiplatform shared core + Compose Multiplatform UI; native audio adapters per OS behind ports ([ADR-001](adr/ADR-001-cross-platform-stack.md)) |
| Truthful route status | RTE-004, RTE-008, NFR-002, DIA-001 | Three-state route model: **Requested → Available → Active**, where *Active* is only ever written by OS observation ([ADR-003](adr/ADR-003-route-confirmation-model.md)) |
| Separate media and communication routing | RTE-003, VOIP-006 | Two independent routing *channels* (`MEDIA`, `COMMUNICATION`) each with its own state machine |
| Multi-output playback | MED-002, MED-007 | Fan-out audio pipeline with per-output delay compensation and sync measurement |
| Resilience under churn | MED-005, NFR-001 | Event-sourced device registry, single-threaded engine actor, idempotent native commands |
| Offline, private, local | NFR-005, NFR-006, DIA-003 | Local encrypted persistence, no backend, redacted diagnostics ([ADR-002](adr/ADR-002-local-persistence.md)) |
| Background automation | CFG-006, NFR-004 | Event-driven triggers via OS presence APIs only; no polling ([ADR-004](adr/ADR-004-automation-triggers.md)) |

## 2. Context view (C4 level 1)

```mermaid
flowchart LR
    user([User])
    subgraph Phone
        app[PartyMusik App]
        os[(OS audio + Bluetooth stack)]
        sysui[System output switcher /<br/>AVRoutePickerView]
        dialer[System dialer]
        third[3rd-party media apps<br/>Spotify, YouTube…]
        shortcuts[iOS Shortcuts /<br/>Android CDM]
    end
    acc1[[BT headphones]]
    acc2[[BT speakers]]
    acc3[[LE Audio / Auracast]]
    acc4[[Wired / USB]]

    user --> app
    app <--> os
    app -. launches .-> sysui
    app -. observes state .-> dialer
    third --> os
    shortcuts -. triggers .-> app
    os <--> acc1 & acc2 & acc3 & acc4
```

## 3. Container view (C4 level 2)

```mermaid
flowchart TB
    subgraph UI["UI layer — Compose Multiplatform (commonMain)"]
        screens[Screens: Devices · Player · Routing · Call · Profiles · Diagnostics · Permissions]
        vm[ViewModels / StateHolders]
    end

    subgraph Core["Shared core — Kotlin Multiplatform (commonMain)"]
        reg[Device Registry]
        eng[Routing Engine<br/>MEDIA + COMMUNICATION channels]
        fb[Fallback Policy]
        prof[Profile Manager]
        auto[Automation Engine]
        play[Playback Controller]
        call[VoIP Session Controller]
        sync[Sync Analyzer]
        diag[Diagnostics & Compatibility Test]
        repo[(Persistence<br/>Room KMP + DataStore)]
    end

    subgraph Ports["Ports (Kotlin interfaces, commonMain)"]
        p1[AudioDevicePort]
        p2[MediaRoutingPort]
        p3[CommRoutingPort]
        p4[PlaybackPort]
        p5[MultiOutputPort]
        p6[VoipPort]
        p7[BluetoothPort]
        p8[SystemAudioPort]
        p9[PermissionPort]
        p10[PresencePort]
    end

    subgraph Android["Android adapters (androidMain / :platform-android)"]
        a1[AudioManager + AudioDeviceCallback]
        a2[Media3 ExoPlayer + AudioTrack fan-out]
        a3[setCommunicationDevice]
        a4[Core-Telecom CallsManager]
        a5[BluetoothManager / CDM]
        a6[MediaRouter2]
    end

    subgraph iOS["iOS adapters (Swift package PlatformIOS)"]
        i1[AVAudioSession]
        i2[AVAudioEngine + channel map]
        i3[CallKit + VoiceProcessingIO]
        i4[AVRoutePickerView]
        i5[AccessorySetupKit / CoreBluetooth]
        i6[App Intents / CXCallObserver]
    end

    screens --> vm --> Core
    Core --> Ports
    Ports --> Android
    Ports --> iOS
    Core --> repo
```

## 4. Layering rules

1. **UI** depends only on **Core** (StateFlows + intents). UI never calls a port directly.
2. **Core** depends only on **Ports** (interfaces) and the persistence abstraction. No `android.*` or `platform.AVFAudio.*` imports in `commonMain`.
3. **Adapters** implement ports, translate OS callbacks into domain events, and never hold business rules (no fallback logic, no profile logic).
4. **Adapters are the only writers of `ActiveRoute`.** Core may write `RequestedRoute`; only an adapter observation can promote a route to `Active` (RTE-008).
5. All native callbacks are marshalled onto the **Routing Engine actor** (single coroutine, `Dispatchers.Default.limitedParallelism(1)`), giving a total order of events and eliminating races during rapid connect/disconnect (NFR-001).

## 5. Key runtime flows

### 5.1 Select a media output (RTE-001, RTE-004, RTE-008)

```mermaid
sequenceDiagram
    actor U as User
    participant UI as Routing screen
    participant E as Routing Engine
    participant P as MediaRoutingPort (native)
    participant OS as OS audio stack

    U->>UI: Choose "Sony WH-1000XM5"
    UI->>E: Intent.RequestMediaRoute(deviceId)
    E->>E: state = Requested(device, t0)
    E-->>UI: Show "Switching…" (never "Active")
    E->>P: requestOutput(deviceId)
    P->>OS: setPreferredAudioDevice / route picker
    OS-->>P: routing-changed callback
    P-->>E: Event.ActiveRouteObserved(MEDIA, [deviceId])
    alt observed == requested
        E->>E: state = Confirmed
        E-->>UI: "Active on Sony WH-1000XM5" ✓
    else timeout (3 s) or mismatch
        E->>E: state = Rejected(reason)
        E-->>UI: Actionable error + troubleshooting (RTE-005, DIA-007)
    end
```

### 5.2 Split media + VoIP routing (RTE-003, VOIP-002, VOIP-006)

```mermaid
sequenceDiagram
    participant E as Routing Engine
    participant M as MediaRoutingPort
    participant C as CommRoutingPort
    participant V as VoipPort

    Note over E: Media active on BT speaker (A2DP)
    E->>V: startCall(peer)
    V-->>E: CallState.Active
    E->>E: snapshot media prefs (for RTE-006)
    E->>C: setCommunicationDevice(earbuds HFP/LE)
    C-->>E: Event.CommDeviceObserved(earbuds)
    E->>M: re-assert media on speaker
    M-->>E: Event.ActiveRouteObserved(MEDIA, speaker)
    Note over E: Both channels Confirmed independently
    V-->>E: CallState.Ended
    E->>C: clearCommunicationDevice()
    E->>M: restore snapshot (RTE-006)
```

### 5.3 Output disconnect + fallback (MED-005, MED-006, CFG-004)

```mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Requested: user / profile / automation
    Requested --> Confirmed: OS reports route == requested
    Requested --> Rejected: timeout / mismatch / OS error
    Confirmed --> Degraded: device lost or OS moved route
    Degraded --> Requested: fallback policy picks next candidate
    Degraded --> Paused: policy = PAUSE
    Rejected --> Requested: retry / next candidate
    Paused --> Requested: device returns (auto-resume)
    Confirmed --> Idle: release
```

Full semantics in [06-routing-engine.md](06-routing-engine.md).

### 5.4 Multi-output playback (MED-002, MED-007, MED-008)

```mermaid
flowchart LR
    src[Media source<br/>file / stream] --> dec[Decoder<br/>Media3 / AVAudioFile]
    dec --> pcm[PCM ring buffer<br/>shared clock]
    pcm --> d1[Delay line +Δ1] --> v1[Gain 1] --> o1[Output 1<br/>BT speaker]
    pcm --> d2[Delay line +Δ2] --> v2[Gain 2] --> o2[Output 2<br/>BT headphones]
    pcm --> d3[Delay line +Δ3] --> v3[Gain 3] --> o3[Output 3<br/>built-in / USB]
    o1 & o2 & o3 -. timestamps / acoustic chirp .-> sa[Sync Analyzer]
    sa -. Δ adjustments .-> d1 & d2 & d3
```

- Android: one decoder, N `AudioTrack` sinks, each bound with `setPreferredDevice()`.
- iOS: `AVAudioEngine` in `.multiRoute` session; output node channel map assigns channel pairs to ports.
- The Sync Analyzer computes per-output latency and writes delay offsets so all outputs align to the slowest one.
- An OS-level path (LE Audio broadcast / Auracast sink, vendor party modes) is modelled as a single *broadcast output* with N receivers.

## 6. Module structure

```
partymusik/
├── shared/                         # KMP module
│   ├── src/commonMain/kotlin/
│   │   ├── domain/                 # AudioEndpoint, RouteState, Profile, …
│   │   ├── engine/                 # RoutingEngine, FallbackPolicy, channels
│   │   ├── playback/               # PlaybackController, MultiOutputMixer model
│   │   ├── voip/                   # VoipSessionController
│   │   ├── automation/             # AutomationEngine, triggers
│   │   ├── diagnostics/            # DiagnosticsLog, CompatibilityTest, redaction
│   │   ├── sync/                   # SyncAnalyzer (chirp correlation)
│   │   ├── ports/                  # Port interfaces (bridge API)
│   │   └── data/                   # Room KMP DAOs, DataStore prefs
│   ├── src/androidMain/kotlin/     # Android port implementations
│   └── src/iosMain/kotlin/         # iOS glue (Swift impls injected)
├── composeApp/                     # Compose Multiplatform UI
│   ├── src/commonMain/             # Screens, theme, navigation
│   ├── src/androidMain/            # MainActivity, services, manifest
│   └── src/iosMain/                # MainViewController()
├── iosApp/                         # Xcode project (SwiftUI host)
│   ├── iosApp/                     # App, SceneDelegate, Info.plist
│   └── PlatformIOS/                # Swift package: AVAudioSession, CallKit adapters
├── tools/sync-harness/             # Chirp generator + offline analysis
└── docs/                           # ← you are here
```

## 7. Cross-cutting concerns

| Concern | Approach |
|---------|----------|
| Concurrency | Engine actor; ports expose `Flow` (Android) / bridged `Flow` from Swift `AsyncStream` (iOS) |
| Error model | Sealed `RouteError` with stable codes (`RTE_TIMEOUT`, `RTE_DEVICE_GONE`, `RTE_OS_REJECTED`, `RTE_UNSUPPORTED_COMBO`, `PERM_DENIED`, `BT_OFF`, …) mapped to user text + troubleshooting article |
| Logging | Structured diagnostics events with codes only; device MAC addresses hashed; no audio buffers ([12-security-privacy.md](12-security-privacy.md)) |
| Accessibility | Every route chip has `contentDescription` with *state* ("Sony headphones, requested, not yet active") — NFR-007 |
| Battery | No polling; OS callbacks only; foreground service only while playing / in call; presence observation delegated to OS (NFR-004) |
| Configuration | Feature flags per capability, auto-set by Compatibility Test results |
| Localisation | All user strings in Compose resources (`composeResources/values*/strings.xml`) |

## 8. Capability model

Every endpoint carries a capability set computed by the adapter, so the UI and engine never hard-code platform rules:

```kotlin
enum class Capability {
    MEDIA_OUTPUT,          // can carry app media
    COMMUNICATION_OUTPUT,  // can carry call audio (HFP/SCO, LE Audio, built-in, wired)
    MICROPHONE,            // has input
    MULTI_OUTPUT_MEMBER,   // can be one of N in fan-out
    BROADCAST_SINK,        // LE Audio broadcast / Auracast receiver
    INDEPENDENT_VOLUME,    // per-device volume controllable (MED-008)
    ABSOLUTE_VOLUME,       // BT absolute volume
    LOW_LATENCY,           // e.g. LE Audio, wired, built-in
    RECONNECTABLE,         // OS-permitted reconnect available (DEV-006)
}
```

## 9. Deployment view

- **Android:** single APK/AAB, `minSdk 31` (Android 12 — `setCommunicationDevice`, `BLUETOOTH_CONNECT`, CDM presence), `targetSdk 36`.
- **iOS:** single app, deployment target iOS 17 (AccessorySetupKit features gated to iOS 18+), Swift 6, Kotlin framework built as static XCFramework.
- No server components. Optional crash reporting is opt-in only.

Details: [14-build-and-deployment.md](14-build-and-deployment.md).
