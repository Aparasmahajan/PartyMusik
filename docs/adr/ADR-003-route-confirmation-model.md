# ADR-003: Requested / Available / Active route model with OS-only confirmation

**Status:** Proposed · 2026-10-27

## Context

RTE-004, RTE-008, NFR-002 and DIA-001 require that the app never shows a preference as a successful route until the OS confirms it, and that requested, available and active routes are shown separately. OS routing calls (`setPreferredAudioDevice`, `setCommunicationDevice`, `setPreferredInput`) are *requests*: the OS may honour, delay, redirect or ignore them.

## Decision

1. Each routing channel (`MEDIA`, `COMMUNICATION`) holds three lists: `requested`, `available`, `active`.
2. `requested` is written by intents; `available` by `AudioDevicePort`; **`active` is written only by adapter observation flows** (`observedRoute`, `observed`, per-sink routing callbacks).
3. A channel enters `Confirmed` only when `active ⊇ requested` (per output for multi).
4. A confirmation timeout (default 3 s) moves the channel to `Rejected(RTE_TIMEOUT)`; a mismatched observation moves it to `Rejected(RTE_OS_REDIRECTED(actual))`.
5. Native command return values never imply success (`Submitted`, not `Success`).

## Consequences

- ✔ UI truthfulness enforced structurally and by property tests.
- ✔ OEM quirks become visible diagnostics instead of silent lies.
- ✘ Users see a brief "Switching…" state on every change; acceptable and accessible.
- ✘ Some outputs can't be observed precisely (e.g. iOS lists only current route) — UI shows "Active (system-reported)" based on what is observable.
