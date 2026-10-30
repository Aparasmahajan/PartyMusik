# Device & Accessory Compatibility Matrix

**SRS deliverable:** #6 · NFR-009 · NFR-010
**Status:** v0 template — populated by POC spikes and the in-app Compatibility Test (DIA-002)
**Related:** [Feasibility report](09-feasibility-report.md) · [Test strategy](10-test-strategy.md)

---

## 1. Legend

| Symbol | Meaning |
|--------|---------|
| ✅ | Validated — declared compatible, covered by release tests |
| ⚠️ | Works with caveat (note required) |
| 🧪 | Experimental — allowed in app behind "experimental" label, not declared |
| ❌ | Tested, does not work — app shows actionable error |
| — | Not applicable |
| (blank) | Not yet tested |

Only ✅ entries are "supported configurations" for NFR-010 purposes.

## 2. Phones under test

| ID | Model | OS / skin | BT version | LE Audio | Notes |
|----|-------|-----------|------------|----------|-------|
| A1 | Google Pixel 10 | Android 16 | 5.4 | ✔ | Reference Android |
| A2 | Samsung Galaxy S25 | Android 16 / One UI 8 | 5.3 | ✔ | Dual Audio feature |
| A3 | OnePlus 13 | Android 15 / OxygenOS | 5.4 | ✔ | |
| A4 | Pixel 6 | Android 12 | 5.2 | ✘ | API 31 baseline |
| I1 | iPhone 17 | iOS 26 | 5.3 | ✘ (bluetoothLE port for hearing devices) | Reference iOS |
| I2 | iPhone 13 | iOS 18 | 5.0 | ✘ | Baseline-ish |

## 3. Accessories under test

| ID | Accessory | Type | Profiles | Notes |
|----|-----------|------|----------|-------|
| H1 | Sony WH-1000XM5 | Headphones | A2DP, HFP, (LE Audio fw) | Multipoint |
| H2 | Bose QC Ultra | Headphones | A2DP, HFP | Multipoint |
| E1 | AirPods Pro 2 | Earbuds | A2DP, HFP (+ Apple) | Share Audio |
| E2 | Galaxy Buds3 Pro | Earbuds | A2DP, HFP, LE Audio, Auracast | |
| E3 | Pixel Buds Pro 2 | Earbuds | A2DP, HFP | |
| S1 | JBL Charge 6 | Speaker | A2DP, Auracast | Vendor party mode |
| S2 | UE Boom 4 | Speaker | A2DP | PartyUp |
| C1 | Generic HFP car kit | Car | HFP only | Calls-only test |
| W1 | USB-C DAC | Wired | USB Audio | |
| W2 | USB-C headset | Wired | USB Audio + mic | |
| HA1 | ASHA hearing aid | Hearing aid | ASHA | Android only |

## 4. Single-output media routing (MED-001)

| Accessory | A1 | A2 | A3 | A4 | I1 | I2 |
|-----------|----|----|----|----|----|----|
| H1 | | | | | | |
| H2 | | | | | | |
| E1 | | | | | | |
| E2 | | | | | | |
| S1 | | | | | | |
| W1 | | | | | | |
| C1 (expect ❌ for media) | | | | | | |

## 5. Communication routing & split (RTE-003, VOIP-006)

| Media → | Comm → | A1 | A2 | A3 | A4 | I1 | I2 |
|---------|--------|----|----|----|----|----|----|
| S1 | Earpiece | | | | | | |
| S1 | E2 | | | | | | |
| H1 | E3 | | | | | | |
| W1 | E1 | | | | | | |
| E2 (LE) | E2 (LE) | | | | | — | — |

## 6. Multi-output media (MED-002) — includes measured skew after compensation (MED-007)

| Config | Outputs | A1 | A2 | A3 | I1 | I2 | Median skew (ms) |
|--------|---------|----|----|----|----|----|------------------|
| M1 | Built-in + S1 | | | | | | |
| M2 | W1 + H1 | | | | | | |
| M3 | Wired + E3 | | | | | | |
| M4 | H1 + S1 (dual BT) | | | | — | — | |
| M5 | Auracast → E2 + S1 | | | | — | — | |
| M5-iOS | Share Audio E1 + E1 | — | — | — | | | |
| M6 | Vendor party mode S1 + S1 | | | | | | |

## 7. Automation (CFG-006)

| Trigger | A1 | A2 | A3 | A4 | I1 | I2 |
|---------|----|----|----|----|----|----|
| H1 connect, app foreground | | | | | | |
| H1 connect, app background | | | | | | |
| H1 connect, app killed | | | | | | |

## 8. Known limitations register (disclosed in-app, AC9)

| ID | Limitation | Platforms | User-facing text key |
|----|-----------|-----------|----------------------|
| L1 | Routing applies to audio played by PartyMusik; other apps follow the OS route | Both | `limit_third_party` |
| L2 | Cellular call audio is controlled by the phone's dialer | Both | `limit_cellular` |
| L3 | Multi-output works only on configurations marked supported | Both | `limit_multi` |
| L4 | Sync is compensated but not guaranteed outside validated configs | Both | `limit_sync` |
| L5 | (filled after POC) | | |

## 9. Update process

1. In-app Compatibility Test writes `COMPAT_RESULT` rows locally.
2. Users may export results (opt-in) and share via support channel; QA validates and promotes entries to ✅.
3. Matrix version bumps with each release; app ships the matrix as a bundled JSON (`compat-matrix.json`) used to label combos as *declared*, *experimental* or *known-bad*.
