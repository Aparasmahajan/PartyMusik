# ADR-004: Connection-triggered automation through OS presence mechanisms

**Status:** Proposed · 2026-10-27

## Context

CFG-006 wants profiles applied when a device connects, including in background, while NFR-004 limits battery use. Polling or long-running background services are unacceptable.

## Decision

| Platform | Foreground | Background / killed |
|----------|-----------|---------------------|
| Android | `AudioDeviceCallback` → AutomationEngine | `CompanionDeviceManager.startObservingDevicePresence()` → `CompanionDeviceService` wakes app → apply profile (short-lived, no FGS unless playback starts) |
| iOS | `routeChangeNotification(.newDeviceAvailable)` → AutomationEngine | Shortcuts personal automation ("Bluetooth → device connects") invoking `ApplyProfileIntent` (App Intents); onboarding guides setup |

Rules:
- Automation is lowest priority; a manual override blocks it until expiry (CFG-008).
- Every automated apply is logged and shown as a dismissible notification/snackbar with "Undo".

## Consequences

- ✔ Zero polling, OS-sanctioned wakeups.
- ✔ Works with app killed on Android.
- ✘ iOS background automation requires one-time user setup in Shortcuts.
