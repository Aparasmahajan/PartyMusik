# ADR-002: Local persistence with Room KMP + DataStore, encrypted at rest

**Status:** Proposed for v1.0 · 2026-10-27. The POC uses an interim JSON `LocalStore`, encrypted at rest (Android Keystore AES-256-GCM, iOS data protection); see [doc 15 D2/D9](../15-poc-implementation.md#5-deviations-from-the-design-docs-decided-while-building)

## Context

Profiles and preferences must survive restarts (CFG-007), work offline (NFR-005) and be protected (NFR-006). No backend or accounts are in scope.

## Decision

- **Room KMP** (bundled SQLite driver) for relational data: profiles, targets, triggers, known devices, compatibility results, sync reports, diagnostic events.
- **DataStore (Preferences, KMP)** for small key-value settings.
- **Encryption:** Android — SQLCipher via `SupportOpenHelperFactory` with a key wrapped by Android Keystore; iOS — database file under `NSFileProtectionComplete`, key in Keychain (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`) for SQLCipher.
- Profile export/import as versioned JSON for user-driven backup.

## Alternatives considered

- SQLDelight — equally viable; Room chosen to match SRS §4 ("Room or DataStore") and Android team familiarity.
- SwiftData on iOS only — would split the data layer; rejected.

## Consequences

- Single schema and migrations for both platforms.
- Encryption adds ~1–2 ms per query; negligible for this workload.
