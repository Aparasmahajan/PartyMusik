# Security & Privacy

**Status:** Draft v0.1 — 2026-10-27
**Covers:** DIA-003..006, NFR-006, CALL-004, EXT-005

---

## 1. Principles

1. **Local-first:** no backend, no accounts. All data on device (NFR-005, SRS §6).
2. **Minimum permission, just-in-time:** request each permission at the moment of the feature that needs it, with an explainer (DIA-004, DIA-005).
3. **No audio retention:** audio buffers are never written to disk or logs; mic capture only for in-app VoIP and the sync test, in memory (DIA-006, CALL-004).
4. **No third-party audio capture** (EXT-005): `AudioPlaybackCapture`, ReplayKit and screen-recording APIs are banned by lint rule.
5. **Codes, not content:** diagnostics record error codes and state transitions only (DIA-003).

## 2. Permission inventory

| Permission | Platform | Requested when | Purpose shown to user | If denied |
|-----------|----------|----------------|-----------------------|-----------|
| Nearby devices (`BLUETOOTH_CONNECT`) | Android | First open of Devices | Show names and connection state of your Bluetooth audio devices | Generic outputs only; banner |
| `BLUETOOTH_SCAN` (`neverForLocation`) | Android | Pair new device | Find accessories to pair | Pairing via Settings |
| Bluetooth | iOS | Accessory setup (BLE) only | Set up and recognise accessories | Route list from audio session still works |
| Microphone | Both | Start VoIP call / run sync test | Your voice in app calls; measuring speaker delay | VoIP & sync disabled |
| Notifications | Android 13+ | First playback | Playback / call controls | Silent FGS |
| Companion presence | Android | Enabling automation for a device | Apply profiles when this device connects | Manual profiles only |

## 3. Threat model (STRIDE, condensed)

| Threat | Asset | Mitigation |
|--------|-------|------------|
| Spoofing — malicious app sends intents to apply profiles | Profiles | Exported components protected by permission; App Intents require device unlock for mutation |
| Tampering — local DB modification | Profiles / settings | Encrypted DB (SQLCipher / file protection), integrity check on load |
| Repudiation | — | N/A (single user, local) |
| Information disclosure — diagnostics export leaks device identifiers | Diagnostics | MAC addresses salted-hashed at write; export preview + anonymise toggle |
| Information disclosure — backups | DB | Android `dataExtractionRules` exclude diagnostics; iOS exclude from iCloud backup for diag files |
| DoS — event storms from flapping BT | Engine | Bounded queue, debounce 150 ms on identical events, reducer never throws |
| Elevation — use of hidden/privileged APIs | App | Lint bans reflection on `android.bluetooth`, `AudioSystem`, private frameworks |

## 4. Data inventory

| Data | Personal? | Stored | Retention | Leaves device? |
|------|-----------|--------|-----------|----------------|
| Device display names | Possibly (e.g. "Alex's AirPods") | Encrypted DB | Until deleted | Only in user-initiated export (anonymise option) |
| BT addresses | Pseudonymous | Salted hash only | Until deleted | Hash only, on export |
| Profiles | No | Encrypted DB | Until deleted | Profile export (user action) |
| Diagnostic events | No (codes) | Encrypted DB | 7 days | User export |
| Sync measurements | No (numbers) | Encrypted DB | 90 days | User export |
| Mic audio | Yes | **Never stored** | — | VoIP peer only (encrypted SRTP/DTLS in production) |
| Crash reports | Minimal | — | — | Only if opted in |

## 5. Platform compliance

- **Google Play Data safety:** "No data collected" (crash reporting opt-in disclosed if enabled). Foreground service types declared with justification video.
- **Apple Privacy manifest (`PrivacyInfo.xcprivacy`):** no tracking, required-reason API entries for `UserDefaults` / file timestamps.
- **Microphone indicator:** always visible during VoIP / sync by OS; app shows its own in-UI indicator too.

## 6. Secure development

- Dependency scanning (Dependabot / OWASP dependency-check) in CI.
- Release builds: R8 full mode, no debug logs, `android:debuggable=false`, iOS strip symbols + upload dSYMs privately.
- Signing keys in CI secret store; Play App Signing; App Store Connect API key with least privilege.
