# Build, CI/CD & Deployment

**SRS deliverable:** #7
**Status:** Draft v0.1 — 2026-10-27

---

## 1. Toolchain

| Tool | Version (pin in repo) |
|------|-----------------------|
| JDK | 21 |
| Gradle | 9.x with version catalog `gradle/libs.versions.toml` |
| Kotlin | 2.2.x |
| Compose Multiplatform | 1.9.x |
| Android Gradle Plugin | 8.12+ |
| Xcode | 26.x |
| Swift | 6 |
| SKIE | latest compatible with Kotlin version |
| Ruby / fastlane | For signing & store upload |

## 2. Repository layout & build targets

```
partymusik/
├── shared/            → Android AAR (internal) + iOS XCFramework (Shared.xcframework)
├── composeApp/        → Android app (applicationId io.mahajan.partymusik)
├── iosApp/            → Xcode project consuming Shared.xcframework + PlatformIOS package
├── tools/sync-harness → JVM CLI for offline sync analysis
└── docs/
```

Key commands:

```bash
./gradlew :shared:allTests                     # common + android + iOS simulator unit tests
./gradlew :composeApp:assembleDebug            # Android debug APK
./gradlew :composeApp:bundleRelease            # Android release AAB
./gradlew :shared:assembleSharedXCFramework    # iOS framework
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release archive
```

## 3. Branching & versioning

- Trunk-based: `main` always releasable; short-lived feature branches; PR + 1 review.
- SemVer `MAJOR.MINOR.PATCH`; Android `versionCode = MAJOR*10000 + MINOR*100 + PATCH`; iOS build number = CI run number.
- Release branches `release/x.y` cut for store submissions; hotfixes cherry-picked.

## 4. CI pipeline

```mermaid
flowchart LR
    pr[PR opened] --> lint[ktlint · detekt · SwiftLint · banned-API lint]
    lint --> unit[Unit + property tests<br/>JVM & iOS sim]
    unit --> ui[Compose UI + XCUITest<br/>emulator / simulator]
    ui --> build[Build debug APK + iOS sim app]
    build --> pr_ok[PR check ✔]

    main[Merge to main] --> nightly[Nightly: instrumented tests<br/>Firebase Test Lab + device lab]
    nightly --> phys[Physical E2E + stress subset]
    phys --> internal[Internal distribution<br/>Play Internal · TestFlight]

    tag[Tag vX.Y.Z] --> rel[Release build · sign · R8 · dSYMs]
    rel --> full[Full physical suite + matrix check]
    full --> store[Play production (staged) · App Store review]
```

> **POC implementation note:** the PR stage is implemented in [`.github/workflows/ci.yml`](../.github/workflows/ci.yml): JVM tests + desktop UI, Android `assembleDebug` + lint (report-only for now), iOS simulator tests + Kotlin framework + unsigned simulator build. ktlint/detekt/SwiftLint, UI tests, the nightly device stage and release signing come after the POC.

- Runners: Linux for Android/shared; macOS for iOS.
- Caches: Gradle config cache, Konan cache, SPM cache.
- Artifacts: APK/AAB, IPA, XCFramework, test reports, sync CSVs, compatibility-matrix JSON.

## 5. Signing & secrets

| Item | Storage |
|------|---------|
| Android upload keystore | CI secret (base64) + Play App Signing |
| iOS distribution cert + profiles | fastlane `match` in private encrypted repo |
| App Store Connect API key | CI secret |
| Crash-reporting DSN (opt-in) | Build config, not in repo |

## 6. Distribution

| Channel | Audience | Cadence |
|---------|----------|---------|
| Play Internal testing / TestFlight internal | Team | Every main merge (nightly) |
| Play Closed testing / TestFlight external | Beta users with diverse accessories | Weekly |
| Play Production (staged 5 % → 20 % → 100 %) / App Store phased release | Public | Per release |

## 7. Store submission checklist

- [ ] Play: foreground service type declarations (`mediaPlayback`, `phoneCall`, `connectedDevice`) with demo video.
- [ ] Play: Data safety form; Nearby devices permission justification.
- [ ] Apple: background modes `audio`, `voip` justification; CallKit usage review notes.
- [ ] Apple: `PrivacyInfo.xcprivacy`; microphone & Bluetooth purpose strings.
- [ ] Both: limitations & compatibility statement in listing (AC9).
- [ ] Both: accessibility review sign-off.

## 8. Operations (no backend)

- In-app "Export diagnostics" produces a redacted zip the user can share with support.
- Compatibility matrix updates ship with app releases (bundled JSON); optional remote config is out of scope for v1.0.
- Crash reporting (opt-in): Firebase Crashlytics or Sentry, PII scrubbing enabled.

## 9. Developer onboarding

1. Install JDK 21, Android Studio (latest stable) with KMP plugin, Xcode 26.
2. `git clone` → `./gradlew :composeApp:installDebug` on an Android 12+ device.
3. iOS: `./gradlew :shared:assembleSharedXCFramework`, open `iosApp/iosApp.xcodeproj`, select team, run on device (Bluetooth routing cannot be tested on simulator).
4. Read [02-system-architecture.md](02-system-architecture.md) and [06-routing-engine.md](06-routing-engine.md) first.
