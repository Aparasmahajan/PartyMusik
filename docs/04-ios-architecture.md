# iOS Architecture & Native Routing POC

**Status:** Draft v0.1 — 2026-10-27
**Baseline:** Deployment target iOS 17, built with Xcode 26 / iOS 26 SDK, Swift 6, Kotlin/Native XCFramework from `:shared`
**Related:** [System architecture](02-system-architecture.md) · [Bridge API](08-native-bridge-api.md) · [Routing engine](06-routing-engine.md)

---

## 1. Component map

```mermaid
flowchart TB
    subgraph Host["iosApp (SwiftUI)"]
        app[PartyMusikApp<br/>@main SwiftUI App]
        cvc[ComposeView<br/>UIViewControllerRepresentable → MainViewController()]
        rpv[RoutePickerView<br/>AVRoutePickerView wrapper]
        intents[App Intents<br/>ApplyProfileIntent]
    end

    subgraph Pkg["PlatformIOS (Swift package) — implements Kotlin ports"]
        ses[AudioSessionCoordinator<br/>single owner of AVAudioSession]
        dev[IOSAudioDeviceAdapter]
        med[IOSMediaRoutingAdapter]
        com[IOSCommRoutingAdapter]
        mo[IOSMultiOutputAdapter]
        vo[IOSVoipAdapter]
        bt[IOSBluetoothAdapter]
        sys[IOSSystemAudioAdapter]
        per[IOSPermissionAdapter]
        cel[IOSCallObserverAdapter]
    end

    subgraph OS["Apple frameworks"]
        avs[AVAudioSession<br/>routeChange · interruption · mediaServicesReset]
        ave[AVAudioEngine<br/>AVAudioPlayerNode · outputNode channelMap]
        avp[AVPlayer / AVQueuePlayer]
        ck[CallKit CXProvider · CXCallController · CXCallObserver]
        pk[PushKit (prod only)]
        ask[AccessorySetupKit (iOS 18+)]
        cb[CoreBluetooth]
        mpr[MPRemoteCommandCenter · MPNowPlayingInfoCenter]
    end

    app --> cvc
    cvc -->|KMP core| Pkg
    rpv --> avs
    intents --> Pkg
    dev & med & com & mo --> ses --> avs
    med --> avp & ave
    mo --> ave
    vo --> ck & ave
    cel --> ck
    bt --> ask & cb
    med --> mpr
```

## 2. Audio session ownership

`AVAudioSession` is a process-wide singleton, so all adapters go through one **`AudioSessionCoordinator`** actor that arbitrates category changes between Media, Multi-Output and VoIP use cases:

| App state | Category | Mode | Options |
|-----------|----------|------|---------|
| Idle / browsing | `.ambient` | `.default` | — |
| Media playback (single output) | `.playback` | `.default` | `.allowAirPlay` |
| Media playback while VoIP pre-armed | `.playAndRecord` | `.default` | `.allowBluetoothA2DP`, `.allowAirPlay`, `.defaultToSpeaker` |
| VoIP call | `.playAndRecord` | `.voiceChat` | `.allowBluetoothHFP` (`.allowBluetooth` pre-iOS 26), `.allowBluetoothA2DP` |
| Multi-output | `.multiRoute` | `.default` | — |
| Sync measurement | `.playAndRecord` | `.measurement` | `.allowBluetoothA2DP` |

```swift
actor AudioSessionCoordinator {
    static let shared = AudioSessionCoordinator()
    private let session = AVAudioSession.sharedInstance()
    private(set) var current: SessionProfile = .idle

    func activate(_ profile: SessionProfile) throws {
        guard profile != current else { return }
        try session.setCategory(profile.category, mode: profile.mode, options: profile.options)
        try session.setActive(true, options: [])
        current = profile
    }

    func deactivate() throws {
        try session.setActive(false, options: .notifyOthersOnDeactivation)
        current = .idle
    }
}
```

## 3. Route enumeration & monitoring (DEV-001, DEV-002, DEV-004, S-02)

```swift
final class IOSAudioDeviceAdapter: AudioDevicePort {   // AudioDevicePort is the Kotlin interface
    private let session = AVAudioSession.sharedInstance()
    private let subject = MutableStateFlowBridge<[AudioEndpoint]>([])

    init() {
        let nc = NotificationCenter.default
        nc.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: nil) { [weak self] n in
            self?.publish(reason: n.routeChangeReason)
        }
        nc.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: nil) { [weak self] _ in
            self?.publish(reason: .unknown)   // rebuild everything — NFR-001
        }
        publish(reason: .unknown)
    }

    private func publish(reason: AVAudioSession.RouteChangeReason) {
        let outputs = session.currentRoute.outputs.map { $0.toEndpoint(active: true) }
        let inputs  = (session.availableInputs ?? []).map { $0.toEndpoint(active: false) }
        subject.value = merge(outputs, inputs)       // HFP inputs imply a BT comm-capable output
        DiagnosticsBridge.log(code: "ROUTE_CHANGE", detail: reason.code)
    }
}

extension AVAudioSessionPortDescription {
    func toEndpoint(active: Bool) -> AudioEndpoint {
        let kind: EndpointKind = switch portType {
            case .bluetoothA2DP:  .btClassicMedia
            case .bluetoothHFP:   .btClassicHfp
            case .bluetoothLE:    .btLeAudio
            case .airPlay:        .airplay
            case .headphones, .headsetMic: .wired
            case .usbAudio:       .usb
            case .HDMI:           .hdmi
            case .builtInSpeaker: .builtinSpeaker
            case .builtInReceiver:.builtinEarpiece
            case .carAudio:       .carAudio
            default:              .other
        }
        return AudioEndpoint(id: EndpointId.ios(uid: uid), name: portName, kind: kind,
                             channels: channels?.count ?? 0, isActive: active)
    }
}
```

**Observation scope:** iOS exposes the *current* output route plus the list of available *inputs*. The complete list of connectable outputs is surfaced through `AVRoutePickerView`. The adapter therefore publishes:
- `active` outputs (ground truth for `ActiveRoute`),
- `available` inputs (Bluetooth HFP / LE, wired, USB) mapped to their paired output,
- a **"More outputs…"** affordance that opens the system route picker (DEV-005, EXT-002).

## 4. Media routing (MED-001, MED-003, RTE-001, RTE-004, S-05)

```swift
final class IOSMediaRoutingAdapter: MediaRoutingPort {
    func requestOutput(endpoint: EndpointId) async -> RouteCommandResult {
        let s = AVAudioSession.sharedInstance()
        switch endpoint.kind {
        case .builtinSpeaker:
            try? await AudioSessionCoordinator.shared.activate(.playAndRecordSpeaker)
            try? s.overrideOutputAudioPort(.speaker)
            return .submitted
        case .btClassicHfp, .btLeAudio, .usb, .wired:
            // Selecting the matching input pulls the output with it
            guard let port = s.availableInputs?.first(where: { $0.uid == endpoint.uid }) else {
                return .needsSystemPicker          // UI presents AVRoutePickerView
            }
            try? s.setPreferredInput(port)
            return .submitted
        default:
            return .needsSystemPicker              // A2DP / AirPlay: user confirms in picker
        }
    }
    // observedRoute = currentRoute.outputs on every routeChangeNotification (ground truth, RTE-008)
}
```

- Playback: `AVQueuePlayer` for simple single-output; `AVAudioEngine` + `AVAudioPlayerNode` when multi-output or sync compensation is needed.
- Transport controls & lock screen: `MPRemoteCommandCenter` + `MPNowPlayingInfoCenter` (MED-003).
- Interruptions: `AVAudioSession.interruptionNotification` (`.began` / `.ended` + `.shouldResume`) → `Event.FocusChanged` (RTE-007).
- `routeChangeNotification` with `.oldDeviceUnavailable` → engine `Event.DeviceLost` → fallback (MED-005, MED-006).

## 5. Communication routing & VoIP (RTE-002, RTE-003, VOIP-001..006, S-07, S-12)

```swift
final class IOSVoipAdapter: NSObject, VoipPort, CXProviderDelegate {
    private let provider = CXProvider(configuration: {
        let c = CXProviderConfiguration()
        c.supportsVideo = false
        c.supportedHandleTypes = [.generic]
        c.maximumCallsPerCallGroup = 1
        return c
    }())
    private let controller = CXCallController()
    private let engine = AVAudioEngine()

    func startCall(peer: Peer) async throws {
        let action = CXStartCallAction(call: UUID(), handle: CXHandle(type: .generic, value: peer.uri))
        try await controller.request(CXTransaction(action: action))
    }

    // CallKit activates the session for us — start audio only here
    func provider(_ p: CXProvider, didActivate session: AVAudioSession) {
        try? engine.inputNode.setVoiceProcessingEnabled(true)   // AEC / NS
        try? engine.start()
        applyPreferredCommEndpoint()                            // VOIP-002
    }

    func provider(_ p: CXProvider, didDeactivate session: AVAudioSession) {
        engine.stop()                                           // VOIP-005
        RoutingEngineBridge.emit(.commReleased)                 // triggers RTE-006 restore
    }

    func provider(_ p: CXProvider, perform action: CXSetMutedCallAction) {
        engine.inputNode.isVoiceProcessingInputMuted = action.isMuted   // VOIP-003
        action.fulfill()
    }

    private func applyPreferredCommEndpoint() {
        guard let target = PreferencesBridge.preferredCommEndpoint(),
              let port = AVAudioSession.sharedInstance().availableInputs?.first(where: { $0.uid == target.uid })
        else { return }
        try? AVAudioSession.sharedInstance().setPreferredInput(port)
    }
}
```

**Split media + call experiments (S-07):**

| # | Strategy | What is measured |
|---|----------|------------------|
| C1 | One `.playAndRecord` session, HFP input preferred, media via `AVAudioPlayerNode` on same engine | Whether media follows HFP (mono, narrow-band) or stays A2DP |
| C2 | `.multiRoute` with voice on BT channels and media on built-in / USB channels | Whether VoiceProcessingIO cooperates with multiRoute |
| C3 | Voice on built-in receiver, media on A2DP | Route confirmation for both |
| C4 | LE Audio earbuds (bluetoothLE port) for both contexts | Simultaneous media + mic quality |

## 6. Multi-output (MED-002, MED-007, MED-008, S-09)

```swift
final class IOSMultiOutputAdapter: MultiOutputPort {
    private let engine = AVAudioEngine()
    private var players: [EndpointId: AVAudioPlayerNode] = [:]
    private var delays:  [EndpointId: AVAudioUnitDelay] = [:]

    func start(targets: [EndpointId], file: AVAudioFile) async throws {
        try await AudioSessionCoordinator.shared.activate(.multiRoute)
        let route = AVAudioSession.sharedInstance().currentRoute
        // Build a flat channel list across every output port in the route
        let allChannels = route.outputs.flatMap { $0.channels ?? [] }
        let out = engine.outputNode
        let hwFormat = out.outputFormat(forBus: 0)            // N channels = sum of all ports

        var channelMap: [NSNumber] = Array(repeating: -1, count: allChannels.count)
        for (i, target) in targets.enumerated() {
            let player = AVAudioPlayerNode(); let delay = AVAudioUnitDelay()
            delay.wetDryMix = 100; delay.feedback = 0
            engine.attach(player); engine.attach(delay)
            engine.connect(player, to: delay, format: file.processingFormat)
            engine.connect(delay, to: engine.mainMixerNode, format: file.processingFormat)
            // map stereo pair of this target port onto source channels
            for (j, ch) in allChannels.enumerated() where ch.owningPortUID == target.uid {
                channelMap[j] = NSNumber(value: (j % 2))
            }
            players[target] = player; delays[target] = delay
        }
        out.auAudioUnit.channelMap = channelMap
        engine.prepare(); try engine.start()
        let start = AVAudioTime(hostTime: mach_absolute_time() + AVAudioTime.hostTime(forSeconds: 0.2))
        players.values.forEach { $0.scheduleFile(file, at: nil); $0.play(at: start) }  // common start time
    }

    func setDelay(_ id: EndpointId, ms: Int32) { delays[id]?.delayTime = Double(ms) / 1000 }
    func setGain(_ id: EndpointId, gain: Float) { players[id]?.volume = gain }           // MED-008
}
```

Configurations under test:

| # | Output set | Path |
|---|-----------|------|
| M1 | USB-C DAC + built-in speaker | `.multiRoute` |
| M2 | Wired (adapter) + BT A2DP | `.multiRoute` |
| M3 | HDMI / USB + BT | `.multiRoute` |
| M4 | AirPods + AirPods (Share Audio) | OS audio sharing — observed as two outputs in `currentRoute` |
| M5 | AirPlay 2 multi-room (HomePod + AirPlay speakers) | `AVRoutePickerView` multi-select, `AVPlayer` |
| M6 | Vendor party mode speakers | Single A2DP to host speaker |

## 7. Cellular calls (CALL-001..005, S-16)

- `CXCallObserver` → `callChanged` → `Event.CellularCallStarted/Ended` (outgoing, connected, ended; no audio access) — CALL-001, RTE-006.
- During a cellular call, `currentRoute` is shown read-only; guide text explains the in-call "Audio" button (CALL-003).
- No recording, no call-audio capture (CALL-004).

## 8. Bluetooth & accessories (DEV-003, DEV-006, DEV-008)

| Need | API |
|------|-----|
| Pair BLE / LE Audio accessory | **AccessorySetupKit** (iOS 18+) picker; on iOS 17 Settings deep-link |
| Classic BT pairing | Settings › Bluetooth (system only) — guided by in-app instructions |
| BT state / permission | `CBCentralManager.state` + `CBManager.authorization` (only if BLE features enabled) |
| Reconnect | System-managed; app offers route picker + Settings deep-link (DEV-006) |

## 9. Automation (CFG-006, S-14)

- **App Intents**: `ApplyProfileIntent(profile: ProfileEntity)` exposed to Shortcuts.
- Onboarding walks the user through creating a **Personal Automation → "Bluetooth: When <device> connects" → Apply Profile** (runs without confirmation on iOS 17+ when configured).
- In-foreground and while background audio is active, `routeChangeNotification` with `.newDeviceAvailable` triggers profile matching directly.

```swift
struct ApplyProfileIntent: AppIntent {
    static let title: LocalizedStringResource = "Apply Audio Profile"
    @Parameter(title: "Profile") var profile: ProfileEntity
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let result = try await SharedCore.shared.profiles.apply(id: profile.id, source: .automation)
        return .result(dialog: "\(profile.name): \(result.summary)")
    }
}
```

## 10. Third-party & system audio (EXT-001..005)

- `AVRoutePickerView` in the toolbar lets users move system audio (including other apps) via the OS picker (EXT-002).
- Share Audio (AirPods/Beats) is detected when two Apple headphones appear in `currentRoute.outputs` (EXT-004).
- No `ReplayKit`/audio capture of other apps (EXT-005).

## 11. Info.plist & capabilities

| Key / capability | Purpose |
|------------------|---------|
| `NSMicrophoneUsageDescription` | VoIP calls and sync measurement only (DIA-005) |
| `NSBluetoothAlwaysUsageDescription` | Accessory setup and LE device presence |
| `UIBackgroundModes`: `audio`, `voip` | Background playback, VoIP calls |
| `NSAccessorySetupKitSupports` + `NSAccessorySetupBluetoothServices` | AccessorySetupKit (iOS 18+) |
| Push Notifications + PushKit | Incoming VoIP calls (production phase) |
| App Groups | Share profile DB with App Intents extension |

## 12. iOS-specific test hooks

- `AVAudioSession` wrapped in protocol `AudioSessionProtocol` for unit tests with `FakeAudioSession`.
- XCUITest uses launch arguments `-uitest-fake-routes` to inject scripted route sequences.
- Physical tests capture `log stream --predicate 'subsystem == "com.apple.coreaudio"'` on failure.
