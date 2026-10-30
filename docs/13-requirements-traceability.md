# Requirements Traceability Matrix

**Status:** Draft v0.1 — 2026-10-27
Maps every SRS requirement → design component → POC spike → test.

Component abbreviations: **REG** DeviceRegistry · **ENG** RoutingEngine · **FB** FallbackPolicy · **PM** ProfileManager · **AUT** AutomationEngine · **PB** PlaybackController · **VOIP** VoipSessionController · **SYNC** SyncAnalyzer · **DIAG** Diagnostics · **UI** Compose screens · port names per [08-native-bridge-api.md](08-native-bridge-api.md).

## Device management

| Req | Component(s) | Spike | Tests |
|-----|--------------|-------|-------|
| DEV-001 | AudioDevicePort, REG, UI Devices | S-01, S-02 | PT-01, unit REG |
| DEV-002 | AudioDevicePort capability mapping, UI Device detail | S-01, S-02 | PT-01 |
| DEV-003 | BluetoothPort.launchPairing (CDM / AccessorySetupKit) | S-03 | PT-01 |
| DEV-004 | AudioDevicePort.events, ENG | S-01, S-02 | PT-11, ST-01 |
| DEV-005 | UI Routing (multi-select, reorder), Profile.mediaTargets | S-04, S-05 | PT-04, UI tests |
| DEV-006 | BluetoothPort.launchReconnect (system flows only) | S-03 | PT-02 |
| DEV-007 | REG unavailableReason, profile proxies | S-03 | PT-02 |
| DEV-008 | PermissionPort, BluetoothPort.adapterState | S-01 | PT-03, PT-16 |

## Multi-device media playback

| Req | Component(s) | Spike | Tests |
|-----|--------------|-------|-------|
| MED-001 | MediaRoutingPort, PB | S-04, S-05 | PT-04 |
| MED-002 | MultiOutputPort, ENG multi orchestration | S-08, S-09 | PT-09 |
| MED-003 | PlaybackPort, UI Player | S-04, S-05 | UI tests |
| MED-004 | MediaRoutingPort.observedRoute, UI badges | S-04, S-05 | PT-04 |
| MED-005 | ENG Degraded state, reducer totality | S-18 | PT-11, ST-01 |
| MED-006 | FB | S-18 | Unit FB, PT-11 |
| MED-007 | SYNC, MicCapturePort | S-10 | PT-09, ST-05 |
| MED-008 | MultiOutputPort.setGain, Capability.INDEPENDENT_VOLUME | S-08, S-09 | PT-10 |

## Music & communication routing

| Req | Component(s) | Spike | Tests |
|-----|--------------|-------|-------|
| RTE-001 | Profile.mediaTargets, ENG media channel | S-04, S-05 | PT-04 |
| RTE-002 | Profile.commTarget, ENG comm channel | S-06, S-07 | PT-06 |
| RTE-003 | ENG two independent channels | S-06, S-07 | PT-06 |
| RTE-004 | Observed flows → Confirmed | S-04..S-07 | PT-04, property test |
| RTE-005 | RouteError codes, UI error + help | S-04..S-07 | PT-05 |
| RTE-006 | ENG snapshot/restore | S-11, S-12, S-16 | PT-07 |
| RTE-007 | PlaybackPort.focus, CallObserverPort | S-16 | PT-08 |
| RTE-008 | ADR-003, ENG invariant 1 | all | Property test, PT-04 |

## VoIP

| Req | Component(s) | Spike | Tests |
|-----|--------------|-------|-------|
| VOIP-001 | VoipPort (Core-Telecom / CallKit) | S-11, S-12 | PT-06 |
| VOIP-002 | CommRoutingPort | S-06, S-07 | PT-06 |
| VOIP-003 | VoipPort.setMuted, PermissionPort | S-11, S-12 | PT-16, UI tests |
| VOIP-004 | VoipPort.switchEndpoint | S-11, S-12 | PT-06 |
| VOIP-005 | CommRoutingPort.release, ENG invariant 4 | S-11, S-12 | PT-07 |
| VOIP-006 | ENG split, compat matrix §5 | S-06, S-07 | PT-06 |

## Cellular calls

| Req | Component(s) | Spike | Tests |
|-----|--------------|-------|-------|
| CALL-001 | CallObserverPort | S-16 | PT-08 |
| CALL-002 | Design rule: no dialer control assumed | — | Review |
| CALL-003 | Help articles, SystemAudioPort.openSystemSettings | S-16 | PT-08 |
| CALL-004 | No capture — lint rule | — | Privacy static check |
| CALL-005 | Feature-flagged TelecomRouteProbe | S-16 | Matrix entry |

## Third-party media

| Req | Component(s) | Spike | Tests |
|-----|--------------|-------|-------|
| EXT-001 | Copy / onboarding | — | Content review |
| EXT-002 | SystemAudioPort.showSystemOutputPicker | S-15 | PT-17 |
| EXT-003 | UI disclosure on Routing screen | — | PT-17 |
| EXT-004 | SystemAudioPort.audioSharing | S-15 | PT-09 (M5) |
| EXT-005 | Lint ban on capture APIs | — | Privacy static check |

## Profiles & automation

| Req | Component(s) | Spike | Tests |
|-----|--------------|-------|-------|
| CFG-001 | PM, UI Profiles | — | Unit PM, UI tests |
| CFG-002 | Profile.mediaTargets / commTarget | — | Unit PM |
| CFG-003 | Built-in templates | — | Unit PM |
| CFG-004 | Profile.fallback, FB | S-18 | Unit FB, PT-11 |
| CFG-005 | PM.apply(source=USER) | — | UI tests |
| CFG-006 | AUT, PresencePort, App Intents | S-13, S-14 | PT-12 |
| CFG-007 | Room KMP + DataStore | — | PT-14 |
| CFG-008 | ManualOverride, ENG priority | — | Unit AUT, PT-13 |

## Diagnostics & privacy

| Req | Component(s) | Spike | Tests |
|-----|--------------|-------|-------|
| DIA-001 | ChannelSnapshot requested/available/active, UI Diagnostics | — | UI tests, PT-04 |
| DIA-002 | DIAG.runCompatibilityTest | — | PT-15 |
| DIA-003 | DIAG redaction | — | Unit redaction |
| DIA-004 | UI Permissions | — | PT-16 |
| DIA-005 | Just-in-time mic request | — | PT-16 |
| DIA-006 | No audio retention | — | Privacy static check |
| DIA-007 | Help articles keyed by error code | — | Content review |

## Non-functional

| Req | Approach | Tests |
|-----|----------|-------|
| NFR-001 | Engine actor, total reducer, idempotent adapters | ST-01..04 |
| NFR-002 | ADR-003 | Property test |
| NFR-003 | Targets in test strategy §6 | Perf suite |
| NFR-004 | Event-driven, FGS only when needed | Battery tests |
| NFR-005 | Local DB | PT-14 (airplane mode) |
| NFR-006 | Encryption at rest | Privacy tests |
| NFR-007 | A11y rules | A11y audits |
| NFR-008 | Feasibility §5 | Release gate |
| NFR-009 | Compatibility matrix | Physical suite |
| NFR-010 | Experimental labelling | Content review |

## Acceptance criteria

| AC | Evidence |
|----|----------|
| AC1 | PT-01 |
| AC2 | PT-14, unit PM |
| AC3 | PT-04 |
| AC4 | PT-06 |
| AC5 | PT-09 on every ✅ config |
| AC6 | PT-05 |
| AC7 | PT-11, ST-01 |
| AC8 | PT-04, UI Diagnostics |
| AC9 | PT-17, limitations register |
| AC10 | PT-16, privacy & battery tests |
