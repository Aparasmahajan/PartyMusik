# POC Implementation — What Was Built

**Status:** v0.3 — 2026-10-29
**Org / IDs:** package `io.mahajan.partymusik` · Android `applicationId io.mahajan.partymusik` · iOS bundle `io.mahajan.partymusik`
**Related:** [POC plan](01-poc-plan.md) · [System architecture](02-system-architecture.md) · [Bridge API](08-native-bridge-api.md)

This document is the source of truth for the code in this repository. Where the code differs from the earlier design documents, §5 says so and why.

---

## 1. Verification status

| Part | Location | Built here | Tested here | How to verify |
|------|----------|------------|-------------|---------------|
| Shared core (engine, profiles, automation, calls, sync, diagnostics, storage) | `shared/src/commonMain` | ✅ JVM | ✅ 41 tests | `./gradlew :shared:jvmTest` |
| Simulated phone (all ports) | `shared/.../simulation` | ✅ | ✅ (integration tests) | same |
| Compose Multiplatform UI | `composeApp/src/commonMain` | ✅ desktop | ✅ launched manually | `./gradlew :composeApp:run` |
| Desktop host + simulator panel | `composeApp/src/desktopMain` | ✅ | ✅ launched | same |
| Android adapters | `shared/src/androidMain` | ❌ no Android SDK on the build machine | ❌ | Install the SDK, then `./gradlew :composeApp:assembleDebug` on a real phone |
| Android app shell | `composeApp/src/androidMain` | ❌ | ❌ | same |
| iOS Kotlin glue | `shared/src/iosMain`, `composeApp/src/iosMain` | ❌ needs macOS | ❌ | `./gradlew :composeApp:linkDebugFrameworkIosArm64` on a Mac |
| iOS Swift drivers, CallKit, App Intents | `iosApp/` | ❌ needs Xcode | ❌ | `cd iosApp && xcodegen generate`, then run on a device |

The Android and iOS code is written against the platform APIs named in docs 03/04. Expect compile fixes in the first build on each platform — that build is the first step of spikes S-01/S-02.

**CI** ([.github/workflows/ci.yml](../.github/workflows/ci.yml)) builds both on hosted runners once the repository is pushed to GitHub: JVM tests and the desktop UI on Linux; Android `assembleDebug` and lint on Linux (the runner image has the SDK with licences accepted); and on macOS, the shared tests on the iOS simulator, the Kotlin framework, and an unsigned simulator build of the Xcode project. The first green run of the `android` and `ios` jobs replaces the two ❌ "Built here" rows above.

## 2. Repository layout

```
PartyMusik/
├── settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradlew(.bat), .gitattributes
├── .github/workflows/ci.yml                PR pipeline: JVM tests, Android APK + lint, iOS simulator tests + build
├── shared/                                 KMP module: everything except UI
│   └── src/
│       ├── commonMain/kotlin/io/mahajan/partymusik/
│       │   ├── domain/        Endpoints, Routing (RouteState, RouteError…), Profiles
│       │   ├── ports/         Ports.kt — the native bridge API
│       │   ├── engine/        RoutingReducer (pure), RoutingEngine (actor), ProfileResolver, EngineModel
│       │   ├── registry/      DeviceRegistry (paired-but-unavailable classification)
│       │   ├── profiles/      ProfileManager
│       │   ├── automation/    AutomationEngine
│       │   ├── calls/         CallController (VoIP + cellular → engine)
│       │   ├── sync/          SyncAnalyzer (chirp, cross-correlation, compensation)
│       │   ├── diagnostics/   Diagnostics (redacted, persisted 7 days), SpikeData (CSV export), CompatibilityTest, Troubleshooting
│       │   ├── data/          LocalStore (profiles + settings)
│       │   ├── simulation/    SimulatedPlatform (fake phone + accessories)
│       │   └── PartyMusikCore.kt  composition root
│       ├── commonTest/        reducer, integration, sync, diagnostics, spike-data tests
│       ├── jvmMain/           FileTextStore, clock
│       ├── (androidMain)      EncryptedFileTextStore (AES-256-GCM, Android Keystore key)
│       ├── androidMain/       android/ adapters (AudioManager, AudioTrack engine, Core-Telecom, CDM, MediaRouter2…)
│       └── iosMain/           ios/ IosDrivers (Swift-facing protocols) + IosPlatform (ports) + IosBridge
├── composeApp/                             Compose Multiplatform UI + hosts
│   └── src/
│       ├── commonMain/ui/     App, Home, Devices, Routing, Player, Call, Profiles, Diagnostics, SimulatorPanel
│       ├── desktopMain/       Main.kt — UI prototype on the simulated phone
│       ├── androidMain/       Application, MainActivity, PresenceService, PlaybackService (MediaSession), AndroidManifest.xml
│       └── iosMain/           MainViewController()
├── iosApp/                                 Swift app (XcodeGen project.yml)
│   └── iosApp/
│       ├── PartyMusikApp.swift            SwiftUI host + AppServices
│       ├── Platform/                      AppDrivers, AudioController, CallController, SystemServices
│       └── Intents/                       ApplyProfileIntent (Shortcuts automation)
└── docs/
```

## 3. Running it

### Desktop prototype (any OS with JDK 17+)

```bash
./gradlew :composeApp:run        # UI + simulator panel
./gradlew :shared:jvmTest        # core test suite
```

The right-hand **Simulator** panel acts as the phone OS: connect or disconnect accessories, turn Bluetooth off, place a phone call, and make the "OS" ignore, redirect or require the system picker. Data is stored in `~/.partymusik-sim/` (`data.json`, `diagnostics.json`); **Diagnostics → Spike measurements → Save CSV files** writes to `~/.partymusik-sim/exports/`, which is a way to rehearse the [spike runbook](16-spike-runbook.md).

> Behind a TLS-inspecting proxy the JDK may not trust the corporate root CA. On Windows, add
> `-Djavax.net.ssl.trustStoreType=Windows-ROOT` to `GRADLE_OPTS` and `org.gradle.jvmargs`.

### Android

1. Install Android Studio (or the SDK) and create `local.properties` with `sdk.dir=…`.
2. The Android targets switch on automatically when an SDK is found (`-Ppartymusik.android=true|false` overrides).
3. `./gradlew :composeApp:installDebug` on an Android 12+ phone. Bluetooth routing cannot be tested on an emulator.

### iOS

1. On a Mac with Xcode 26: `brew install xcodegen`.
2. `cd iosApp && xcodegen generate && open PartyMusik.xcodeproj`, then set your signing team.
3. The pre-build script runs `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`.
4. Run on a physical iPhone. Routing, CallKit and the microphone do not work in the simulator.

## 4. Requirement coverage in code

| Area | Implemented by | Notes |
|------|----------------|-------|
| DEV-001/002/004 | `AudioDevicePort` adapters → `RoutingReducer.onEndpoints` → Devices screen | Android merges A2DP/SCO/LE entries per address |
| DEV-003/006 | `BluetoothPort.launchPairing/launchReconnect` | CDM sheet (Android 13+) / Settings; iOS shows pairing help |
| DEV-005 | Routing screen (single / multi), Profile editor priority ↑↓ | |
| DEV-007/008 | `DeviceRegistry.classify`, BT-off and permission banners | iOS does not expose paired devices |
| MED-001/003/004 | `MediaRoutingPort`, `PlaybackPort`, Player screen; Android `PlaybackService` (Media3 MediaSession over `PlaybackPortPlayer`) for notification, lock-screen and headset/AVRCP controls | Procedural test library (tone, sweep, beat) |
| MED-002/008 | `MultiOutputPort` (AudioTrack sinks / `.multiRoute` channel map), per-output gain sliders | |
| MED-005/006, CFG-004 | Reducer fallback rules, `heldTargets` race handling | 200-cycle stress test |
| MED-007 | `SyncAnalyzer` + `SyncProbePort` | Sim test checks 40 ms skew within ±5 ms |
| RTE-001…008 | Two channels in `RoutingReducer`, confirmation only from observation | Property test over 18 000 random events |
| VOIP-001…006 | Core-Telecom / CallKit echo-loopback calls, `CallController` | iOS: media suspended during calls (`supportsSplitMediaAndComm = false`) until S-07 shows otherwise |
| CALL-001…005 | `CallObserverPort` (audio mode / CXCallObserver), read-only display, disclosure | No control of, or capture from, the dialer |
| EXT-001…005 | System output picker, disclosures, no capture APIs | |
| CFG-001…008 | `ProfileManager`, `AutomationEngine`, `ManualOverride`, `LocalStore` | |
| DIA-001…007 | Diagnostics screen (requested · available · active), `CompatibilityTest`, redacted export, help articles; event log persisted for 7 days (`DiagnosticsPersistence`) | |
| POC deliverable #4 | `SpikeData`: per-spike summary + CSV export (`summary`, `route_latency`, `sync`, `compat`, `events`, `device`) through a platform `ExportSink` | Procedure in [doc 16](16-spike-runbook.md) |
| NFR-006 | Android `EncryptedFileTextStore` (AES-256-GCM, non-exportable Keystore key); iOS `FileTextStore` written with data protection `CompleteUntilFirstUserAuthentication` | `allowBackup=false` on Android. See D2, D9 |

## 5. Deviations from the design docs (decided while building)

| # | Design doc said | POC does | Why |
|---|-----------------|----------|-----|
| D1 | Android media via Media3 `ExoPlayer.setPreferredAudioDevice` (doc 03 §3) | Media via `AudioTrack` sinks (`AndroidAudioOutputEngine`) | ExoPlayer exposes no public read-back of the device the OS actually routed to; `AudioRouting.OnRoutingChangedListener` on our own tracks does, and RTE-008 needs that ground truth. The engine therefore owns audio focus (RTE-007). The MediaSession wraps it (D10); Media3 decoders can later feed PCM into these sinks. |
| D2 | Room KMP + DataStore (ADR-002) | `LocalStore`: one JSON document behind `TextStore`, encrypted at rest (Android Keystore AES-GCM / iOS data protection) | Persistence is not a feasibility risk, and the data is a few KB of profiles and settings. Encrypting the file meets NFR-006 without KSP/Room/SQLCipher. Room stays the v1.0 plan, for when diagnostic history and sync reports need queries; `LocalStore` is the only class that changes. |
| D3 | Koin DI (doc 05 §3) | Manual composition root `PartyMusikCore` | Fewer moving parts; one constructor per platform. |
| D4 | Swift implements Kotlin ports directly, SKIE for Flows (doc 08 §1, ADR-001) | Swift implements simple callback **driver protocols** (`IosDrivers.kt`); Kotlin `IosPlatform` builds the Flow-based ports | Swift never has to construct Kotlin Flows or suspend functions; no SKIE dependency. |
| D5 | Separate `Degraded` state (doc 06 §3) | `Confirmed`/`Requested` carry a `FallbackInfo(lost, original)` | Same information, fewer states; the UI shows "Active on X · Y lost". |
| D6 | `MicCapturePort` (doc 08 §2.12) | `SyncProbePort.probe()` plays and records in one call | The adapter must align play and record clocks itself; a combined call gives the analyzer the emit frame directly. |
| D7 | "Next in priority" falls through when no priority device remains | Falls back to the best available output | Found by tests: a single manual choice otherwise always ended in Pause. |
| D8 | — | Observations of devices that disappear are dropped | Found by tests: a stale observation could confirm a returning device before the OS reported it. |
| D9 | iOS `NSFileProtectionComplete` (doc 07 §5) | `NSFileProtectionCompleteUntilFirstUserAuthentication` | A Shortcuts automation (ADR-004) can apply a profile while the phone is locked; with `Complete` the profile file is unreadable then. |
| D10 | — | MediaSession wraps `PlaybackPort` in a Media3 `SimpleBasePlayer` (`PlaybackPortPlayer`) | Keeps D1: the AudioTrack engine stays the only audio path, and the port is the single source of truth for the in-app and system controls. |

## 6. Next steps (in POC-plan order)

1. Push to GitHub and get the `android` and `ios` CI jobs green (first compile of that code; fix what fails).
2. Device lab, following [doc 16](16-spike-runbook.md): Android S-01, S-03, S-04, S-06, S-08, S-11, S-13; iOS S-02, S-05, S-07 (decides `supportsSplitMediaAndComm`), S-09, S-12, S-14, S-17; both S-10, S-15, S-16, S-18.
3. Fill in [11-compatibility-matrix.md](11-compatibility-matrix.md) and [09-feasibility-report.md](09-feasibility-report.md) from the exports, then hold the go/no-go review ([doc 01 §9](01-poc-plan.md#9-success-criteria--gono-go)).

Done: encryption at rest and the Android MediaSession (v0.2: D2, D9, D10); spike data export, persisted diagnostics, CI and the spike runbook (v0.3). Room KMP is deferred to v1.0.
