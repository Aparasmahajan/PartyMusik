# Platform Feasibility Report

**SRS deliverable:** #1
**Status:** Draft v0.1 — initial assessment before POC spikes; the *Result* columns are filled in as spikes complete.
**Related:** [POC plan](01-poc-plan.md) · [Compatibility matrix](11-compatibility-matrix.md)

---

## 1. How to read this report

Each capability gets:
- **Initial rating** (before spikes): 🟢 documented public API, 🟡 public API with device/accessory variance, 🔵 achievable through OS-provided user flow, 🟠 needs spike to decide.
- **Spike** that validates it.
- **Result** (after spike): ✅ Supported · ⚠️ Supported on declared configs · ↪️ Via system UI · ❌ Not supported.

## 2. Capability assessment — Android (API 31+)

| Capability | SRS | Primary API | Initial | Spike | Result |
|------------|-----|-------------|---------|-------|--------|
| List outputs with type, name, capabilities | DEV-001/002 | `AudioManager.getDevices`, `AudioDeviceInfo` | 🟢 | S-01 | |
| Live connect/disconnect events | DEV-004 | `AudioDeviceCallback`, BT `ACTION_CONNECTION_STATE_CHANGED` | 🟢 | S-01 | |
| Paired-but-not-audio explanation | DEV-007 | Profile proxies + bonded list | 🟢 | S-03 | |
| System pairing flow | DEV-003 | `CompanionDeviceManager.associate` | 🟢 | S-03 | |
| Reconnect via permitted mechanisms | DEV-006 | CDM + system panels | 🔵 | S-03 | |
| App media → chosen output | MED-001 | `ExoPlayer.setPreferredAudioDevice` | 🟢 | S-04 | |
| Confirm active media route | MED-004, RTE-004 | `getAudioDevicesForAttributes`, `AudioRouting` | 🟢 | S-04 | |
| Comm audio → chosen device | RTE-002, VOIP-002 | `setCommunicationDevice` | 🟢 | S-06 | |
| Separate media and comm routes | RTE-003, VOIP-006 | Above two combined | 🟡 | S-06 | |
| Multi-output: speaker/wired/USB + BT | MED-002 | N × `AudioTrack` | 🟡 | S-08 | |
| Multi-output: BT + BT | MED-002 | N × `AudioTrack`, OEM dual audio | 🟠 | S-08 | |
| Multi-output: LE Audio broadcast | MED-002, EXT-004 | `TYPE_BLE_BROADCAST`, system audio sharing | 🟡 | S-08/S-15 | |
| Per-device volume | MED-008 | `AudioTrack.setVolume`, BT absolute volume | 🟡 | S-08 | |
| Sync measurement | MED-007 | `AudioTrack.getTimestamp` + mic chirp | 🟢 | S-10 | |
| App VoIP with endpoint switching | VOIP-001..005 | Core-Telecom `CallsManager` | 🟢 | S-11 | |
| Detect cellular calls | CALL-001, RTE-007 | `OnModeChangedListener` | 🟢 | S-16 | |
| Route cellular call audio | CALL-005 | — (system dialer) | 🟠 | S-16 | |
| Steer third-party audio | EXT-002 | `MediaRouter2.showSystemOutputSwitcher` | 🔵 | S-15 | |
| Connection-triggered automation (background) | CFG-006 | CDM presence + `CompanionDeviceService` | 🟢 | S-13 | |

## 3. Capability assessment — iOS (17+)

| Capability | SRS | Primary API | Initial | Spike | Result |
|------------|-----|-------------|---------|-------|--------|
| List current outputs + BT/wired inputs | DEV-001/002 | `currentRoute`, `availableInputs` | 🟡 | S-02 | |
| Full list of connectable outputs | DEV-001 | `AVRoutePickerView` | 🔵 | S-02 | |
| Live route changes | DEV-004 | `routeChangeNotification` | 🟢 | S-02 | |
| Pairing flow | DEV-003 | AccessorySetupKit (18+), Settings | 🔵 | S-02 | |
| App media → chosen output | MED-001 | Picker / `setPreferredInput` / `overrideOutputAudioPort` | 🟡 | S-05 | |
| Confirm active route | MED-004, RTE-004 | `currentRoute.outputs` | 🟢 | S-05 | |
| Comm audio → chosen device | RTE-002, VOIP-002 | `setPreferredInput` (HFP/LE) in `.playAndRecord` | 🟢 | S-07 | |
| Separate media and comm routes | RTE-003, VOIP-006 | Single session; `.multiRoute` experiments | 🟠 | S-07 | |
| Multi-output wired/USB/HDMI + BT | MED-002 | `.multiRoute` + channel map | 🟡 | S-09 | |
| Multi-output BT + BT | MED-002 | Share Audio (Apple headphones), AirPlay 2 | 🔵 | S-09 | |
| Per-device volume | MED-008 | `AVAudioPlayerNode.volume` per mapped channel | 🟡 | S-09 | |
| Sync measurement | MED-007 | `AVAudioTime`, `outputLatency`, mic chirp | 🟢 | S-10 | |
| VoIP with CallKit | VOIP-001..005 | CallKit + VoiceProcessingIO | 🟢 | S-12 | |
| Detect cellular calls | CALL-001 | `CXCallObserver` | 🟢 | S-16 | |
| Steer third-party audio | EXT-002 | `AVRoutePickerView` | 🔵 | S-15 | |
| Connection-triggered automation | CFG-006 | Shortcuts personal automation → App Intent | 🔵 | S-14 | |

## 4. Cross-platform stack feasibility

| Item | Assessment | Spike |
|------|-----------|-------|
| Compose Multiplatform on iOS (stable since 1.8) | 🟢 Production-ready; embed in SwiftUI via `UIViewControllerRepresentable` | — |
| Swift implementing Kotlin interfaces | 🟢 Standard Obj-C export; SKIE for Flow/suspend ergonomics | S-17 |
| Room KMP on iOS | 🟢 Stable (Room 2.7+) with bundled SQLite driver | — |
| Real-time audio in Kotlin/Native | Not used — all real-time audio stays in Swift/Kotlin-JVM native code | — |

## 5. Proposed supported OS versions (NFR-008, to be frozen after POC)

| Platform | Minimum | Reason | Full feature set |
|----------|---------|--------|------------------|
| Android | 12 (API 31) | `setCommunicationDevice`, `BLUETOOTH_CONNECT`, CDM presence | 14+ (output switcher API), 16 (LE Audio sharing) |
| iOS | 17 | Compose MP + SwiftUI host, App Intents automation | 18+ (AccessorySetupKit) |

## 6. Risk register

| ID | Risk | Likelihood | Impact | Mitigation | Owner |
|----|------|-----------|--------|-----------|-------|
| R1 | Dual classic-BT output varies by chipset/OEM | High | High | Declare only validated combos; offer LE Audio broadcast and vendor party modes as alternatives | Android lead |
| R2 | iOS session arbitration couples media & comm routes | Medium | High | Evaluate C1–C4 strategies; fall back to "media pauses during call" with clear messaging | iOS lead |
| R3 | BT latency variance (100–300 ms) makes multi-output echo audible | High | Medium | Per-output delay compensation from SyncAnalyzer; re-measure on reconnect | Audio lead |
| R4 | iOS background automation depends on user-built Shortcut | High | Low | Guided onboarding, deep-link to Shortcuts gallery | iOS lead |
| R5 | OEM audio policy quirks (Samsung/Xiaomi) override preferred device | Medium | Medium | Truthful confirmation model surfaces it; OEM entries in matrix | QA |
| R6 | KMP ↔ Swift interop bugs / memory leaks across Flow bridges | Low | Medium | SKIE, leak tests in CI, keep bridge surface small | Tech lead |
| R7 | Store review questions about Bluetooth / mic usage | Low | Medium | Precise purpose strings, mic only on VoIP/sync screens | PM |
| R8 | Battery drain from FGS / engine | Low | Medium | No polling; FGS only during playback/call; battery tests in CI device lab | QA |

## 7. Go / no-go summary (fill after POC)

| Goal | Android | iOS | Notes |
|------|---------|-----|-------|
| G1 Enumeration | | | |
| G2 Media routing | | | |
| G3 Split routing | | | |
| G4 Multi-output | | | |
| G5 Resilience | | | |
| G6 Automation | | | |
| G7 Diagnostics | | | |
| G8 Shared UI | | | |
| G9 System hooks | | | |
| **Decision** | | | |
