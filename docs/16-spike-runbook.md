# Spike Runbook — Device-Lab Phase

**Status:** v0.1 — 2026-10-29
**Related:** [01 POC plan §4](01-poc-plan.md#4-spike-catalogue) · [09 Feasibility report](09-feasibility-report.md) · [10 Test strategy](10-test-strategy.md) · [11 Compatibility matrix](11-compatibility-matrix.md) · [15 POC implementation](15-poc-implementation.md)

This is the procedure for running spikes S-01 to S-18 on the device lab with the POC app, and for turning the results into docs 09 and 11. **Rule:** every result written into doc 09 or doc 11 cites the export folder it came from. No data, no result.

---

## 1. Before the session

| Step | Android | iOS |
|------|---------|-----|
| Get a build | `partymusik-debug-apk` artifact from the CI run on `main` ([.github/workflows/ci.yml](../.github/workflows/ci.yml)), or `./gradlew :composeApp:installDebug` | On a Mac: `cd iosApp && xcodegen generate`, open `PartyMusik.xcodeproj`, set the team, run on the device |
| Install | `adb install -r composeApp-debug.apk` | Xcode run (Bluetooth routing cannot be tested on the simulator) |
| Permissions | Grant Nearby devices and Microphone on first use | Grant Microphone and Bluetooth on first use |
| Identify the phone | Use the phone ID from [doc 11 §2](11-compatibility-matrix.md#2-phones-under-test) (A1…A4, I1, I2) | same |
| Accessory IDs | Use the accessory IDs from [doc 11 §3](11-compatibility-matrix.md#3-accessories-under-test) (H1, E2, S1, C1…) | same |

## 2. Standard run procedure

Every spike run uses the **Diagnostics → Spike measurements** card:

1. **Reset run.** This clears the event log and the stored sync and compatibility reports, so the figures cover this run only.
2. Do the spike steps (§4).
3. Check the summary on the card. It updates live.
4. **Save CSV files.** Leave "Hide device names in export" on unless the run is in-house only.
5. Collect the folder (§3) and store it as `spike-results/<spike>/<phone>/<yyyymmdd>-<accessories>/` in the team drive.
6. Write the result into doc 09 / doc 11 with a link to that folder.

The event log is saved on the phone (7-day retention), so counts survive the app being killed or woken by the OS. This matters for S-13/S-14.

### Files in each export

| File | Contents | Used by |
|------|----------|---------|
| `device.csv` | OS, version, model, declared capabilities | All |
| `summary.csv` | One row per spike metric: n, p50, p95, max, notes | All: the numbers that go into doc 09 |
| `route_latency.csv` | Every route request outcome: `CONFIRMED` with request → OS-confirmed latency, or the `RTE_*` error | S-04…S-07, S-11, S-12 |
| `sync.csv` | Per-output acoustic latency (median, IQR, raw samples), compensation | S-08…S-10 |
| `compat.csv` | Compatibility-test results per output (DIA-002) | Doc 11 §4+ |
| `events.csv` | Full diagnostics log (codes and timings only; no audio, addresses hashed) | Investigation |

## 3. Collecting the files

| Platform | Where | How |
|----------|-------|-----|
| Android | `/sdcard/Android/data/io.mahajan.partymusik/files/exports/spike-<timestamp>/` | `adb pull /sdcard/Android/data/io.mahajan.partymusik/files/exports ./exports` |
| iOS | Files app › On My iPhone › PartyMusik › exports | AirDrop the folder, or Finder › iPhone › Files |
| Desktop simulator | `~/.partymusik-sim/exports/` | — (for rehearsing this procedure) |

## 4. Spikes

"Summary metric" refers to the row in `summary.csv` / the Spike measurements card. "Manual" means the app cannot measure it; record it on the run sheet.

### Enumeration

| Spike | Steps | Record | Pass | Feeds |
|-------|-------|--------|------|-------|
| **S-01** Android output enumeration | Devices screen open. Connect each lab accessory in turn (H1, H2, E1–E3, S1, S2, C1, W1, W2, HA1); disconnect each. | Kinds and capabilities shown per accessory (screenshot); `events.csv` `DEVICE_CONNECTED` / `DEVICE_DISCONNECTED`. **Manual:** time from power-on to row appearing. | Every accessory appears with the correct kind within 1 s ([PT-01](10-test-strategy.md)) | Doc 09 §2 rows 1–2 |
| **S-02** iOS route enumeration | As S-01. Also open the system picker from the Routing screen. | What the Devices screen shows for each accessory (iOS shows the current route plus BT/wired inputs only) | Current route always correct; limits written down | Doc 09 §3 rows 1–3 |
| **S-03** Paired vs audio-capable | Pair all 10 accessories, power half of them off. Devices screen. | Classification of each: connected / "Paired · not connected" with reason / not an audio device | 10/10 correct against the accessory's real state | Doc 09 §2 rows 3–5, [PT-02](10-test-strategy.md) |

### Routing

| Spike | Steps | Record | Pass | Feeds |
|-------|-------|--------|------|-------|
| **S-04** Android media output | Player: play the 1 kHz tone. Routing: switch between each connected output 10× (single mode). Then run the compatibility test. | Summary metric *Media route: request → OS-confirmed*; *Route requests not confirmed* with error breakdown; `compat.csv`. **Manual:** listen that sound comes from the device shown as Active. | Each output confirmed in ≤ 3 s (confirmation timeout); no case where "Active" ≠ the device you hear (RTE-008) | Doc 09 §2 rows 6–7, doc 11 §4 |
| **S-05** iOS media output | As S-04. Outputs not selectable in-app go through the system picker (the UI says so). | As S-04, plus which outputs needed the picker (`ROUTE_NEEDS_SYSTEM_PICKER` in `events.csv`) | As S-04 | Doc 09 §3 rows 5–6, doc 11 §4 |
| **S-06** Android split media/comm | Media playing on output A. Devices: *Use for calls* on B. Call screen: *Start test call* (echo loopback). Diagnostics: both route tables show Active. Repeat for each A/B pair in doc 11. | *Comm route: request → OS-confirmed*; both rows in `route_latency.csv`. **Manual:** media audible on A and your voice echo on B. | Both routes confirmed at the same time on ≥ 1 combination ([PT-06](10-test-strategy.md)) | Doc 09 §2 row 9, decides `supportsSplitMediaAndComm` |
| **S-07** iOS split media/comm | As S-06. The POC ships with `supportsSplitMediaAndComm = false` on iOS (media pauses during calls); try the session strategies in [doc 04](04-ios-architecture.md) and record which hold both routes. | As S-06, per strategy | As S-06; failing is an accepted outcome (risk R2) | Doc 09 §3 row 8 |

### Multi-output and sync

| Spike | Steps | Record | Pass | Feeds |
|-------|-------|--------|------|-------|
| **S-08** Android multi-output | Routing: multi mode, select each configuration M1–M6 from [doc 11](11-compatibility-matrix.md). Play the party beat. Adjust the per-output volume sliders. | *Multi-output requests only partly confirmed*; `MULTI_PARTIAL` rows in `events.csv` name the missing output. **Manual:** sound on each output; whether per-output volume changes level. | ≥ 2 configurations confirmed on all outputs (go/no-go G4) | Doc 09 §2 rows 10–13, doc 11 multi-output table |
| **S-09** iOS multi-output | As S-08 with the `.multiRoute` configurations (wired/USB + BT). | As S-08 | As S-08 | Doc 09 §3 rows 9–11 |
| **S-10** Sync measurement | For each confirmed multi-output configuration: Player → *Measure delay* in a quiet room, phone mic 30 cm from each output. Then *Apply compensation* and measure again. | `sync.csv` (per-output median and IQR), summary *Multi-output skew before compensation*. **Manual:** reference measurement with a lab mic/oscilloscope for one configuration. | App measurement within ±5 ms of the reference; skew after compensation ≤ 20 ms | Doc 09 §2/§3 sync rows, doc 11 sync columns |

### Calls

| Spike | Steps | Record | Pass | Feeds |
|-------|-------|--------|------|-------|
| **S-11** Android VoIP endpoint switching | Call screen: *Start test call*. Switch the call endpoint between earpiece, speaker and each BT headset 10× mid-call. End the call. | *Comm route* latency rows; `ROUTE_RESTORED` after the call. **Manual:** length of the audio gap at each switch. | Every switch confirmed; media route restored after the call ([PT-07](10-test-strategy.md)) | Doc 09 §2 row 14 |
| **S-12** iOS CallKit endpoint switching | As S-11 with the CallKit echo call. | As S-11 | As S-11 | Doc 09 §3 row 12 |
| **S-16** Cellular call awareness | Media playing. Call the phone from the lab SIM; answer; hang up. 10 times. | *Cellular call state changes seen* and *Communication sessions started* (CELLULAR). **Manual:** media paused during the call and resumed after. | 10/10 calls detected; media restored each time ([PT-08](10-test-strategy.md)) | Doc 09 §2 rows 15–16, §3 row 13 |

### Automation, system hooks, bridge, stress

| Spike | Steps | Record | Pass | Feeds |
|-------|-------|--------|------|-------|
| **S-13** Android background automation | Profiles: create a profile with "When H1 connects". Connect H1 once in the foreground (registers CDM presence). Kill the app process (`adb shell am kill io.mahajan.partymusik`; not force-stop, which disables wake-ups). Power-cycle H1 50 times, ~20 s apart. Open the app. | *OS presence events received* and *Automation triggers fired* (source `os-presence`). Reliability = triggers ÷ 50. | ≥ 95 % (go/no-go G6) | Doc 09 §2 last row, [PT-12](10-test-strategy.md) |
| **S-14** iOS Shortcuts automation | Shortcuts: personal automation "When AirPods connect → Apply Audio Profile", "Run immediately". Close the app. Connect AirPods 50 times. Open the app. | *Automation triggers fired* (source `ios-shortcut`). Reliability = triggers ÷ 50. | Behaviour documented (G6 for iOS is "documented", not a threshold) | Doc 09 §3 last row |
| **S-15** System-level audio hooks | Routing: *Open system output picker*. On Android 16 / Galaxy S25 check LE Audio sharing; on iOS check Share Audio with AirPods. | Screenshots; which feature is present per phone | Presence recorded per phone ([PT-17](10-test-strategy.md)) | Doc 09 rows "Steer third-party audio", doc 11 notes |
| **S-17** KMP ↔ Swift bridge | Run the iOS app under Instruments (Leaks + Allocations) for 30 min while doing S-05 and S-12 steps. | Leaks found; memory trend | No leaks attributable to the bridge; memory flat | Doc 09 §4 row 2, ADR-001 |
| **S-18** Stress | Connect/disconnect a headset 200 times (relay or by hand) while media plays on it with the default fallback. | *Device connect / disconnect events* (expect 400), *Fallbacks applied*, *Engine errors (must be 0)*. **Manual:** crash/ANR count. | 0 crashes, 0 engine errors, final state correct (G5, [ST-01](10-test-strategy.md)) | Doc 09 §6 risk register, doc 10 |

## 5. Writing up

- **Doc 09:** fill the *Result* column with 🟢 / 🟡 / 🟠 / 🔴 per the legend in doc 09 §1, the key number (e.g. "p95 confirm 1.4 s, n=40") and the export folder link.
- **Doc 11:** one cell per phone × accessory/configuration; the `compat.csv` outcome plus manual observations.
- **Go/no-go ([doc 01 §9](01-poc-plan.md#9-success-criteria--gono-go)):** G3 from S-06/S-07, G4 from S-08/S-09/S-10, G5 from S-18, G6 from S-13/S-14, "truthful state" from the manual checks in S-04/S-05.
- Anything the app reported wrongly (e.g. "Active" on a device that was silent) is a **blocking bug**: export immediately, attach the folder to the bug.
