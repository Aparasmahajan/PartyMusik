# ADR-001: Kotlin Multiplatform + Compose Multiplatform with native audio adapters

**Status:** Accepted for the POC · 2026-10-29 — implemented; the iOS bridge uses Swift driver protocols rather than SKIE (doc 15 D4)

## Context

The SRS asks for a *cross-platform UI* with *native audio-routing implementations*, and names Kotlin + Jetpack Compose for Android and Swift + SwiftUI for the iOS native layer. Routing truthfulness rules (RTE-008) and fallback logic must behave identically on both platforms.

## Options

| Option | UI sharing | Logic sharing | Fit with SRS §4 | Native audio access |
|--------|-----------|---------------|-----------------|---------------------|
| **A. KMP + Compose Multiplatform** | High | High (Kotlin) | Android: Kotlin/Compose ✔; iOS: Swift/SwiftUI host + Swift adapters ✔ | Direct Kotlin on Android; Swift via interfaces on iOS |
| B. Flutter + platform channels | High | Dart | Adds Dart; native layers still Kotlin/Swift | Method channels, more serialization |
| C. React Native + Turbo Modules | High | TS | Adds JS runtime | JSI modules |
| D. Fully native ×2 | None | None (or KMP logic only) | ✔ | ✔ |

## Decision

Option **A**. Business logic, persistence and UI live in Kotlin Multiplatform; all audio/Bluetooth/telephony work is implemented natively (Kotlin on Android, Swift on iOS) behind port interfaces defined in `commonMain`.

## Consequences

- ✔ Android app is pure Kotlin/Compose — no bridge on Android at all.
- ✔ One routing engine → identical truthfulness and fallback behaviour.
- ✔ iOS shell remains SwiftUI; system components (`AVRoutePickerView`, App Intents) stay native.
- ✘ iOS engineers must work with a Kotlin framework; mitigated by SKIE and a small, stable port surface.
- ✘ Compose on iOS has a larger binary (~+9 MB); acceptable.
- Fallback: if S-17 fails, keep KMP for logic and write SwiftUI screens on iOS (Option D-hybrid) — ports unchanged.
