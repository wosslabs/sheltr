# ADR-0016: Expo (React Native, TypeScript) for the mobile app

## Status
Accepted. Replaces the platform choice in ADR-0015 (Kotlin Multiplatform with native Jetpack Compose and SwiftUI UIs). The rest of ADR-0015 stands: no server, a signed bundle, keyword search on the device.

Date: 2026-11-02

## Context
- The Kotlin Multiplatform setup worked for the shared logic and the Android app, but it needed a second UI (SwiftUI) for iOS, Gradle and Kotlin/Native toolchains that ran out of memory in CI, and an iOS simulator for any iOS test. The maintainer found the combination too much overhead for a small non-profit project.
- React Native with Expo has the largest community of the cross-platform options, one UI codebase for both platforms, and a simple toolchain (`npm`, Expo Go, `expo export`).
- The product needs are modest and all available in Expo: SQLite with FTS5 (`expo-sqlite`), Ed25519 verification (`@noble/curves`), SHA-256 (`@noble/hashes`), text to speech (`expo-speech`), localisation.

## Decision
1. The app is an Expo project in `apps/mobile`, written in TypeScript, for Android and iOS.
2. The logic lives in `src/core` with no React Native imports: bundle parsing, signature check, store, retrieval, sync, settings. It talks to SQLite through a small `SqlDatabase` interface, implemented with `expo-sqlite` in the app and with Node's built-in SQLite in tests.
3. The publisher stays in Kotlin (`tools/publisher`). The bundle format is the contract between it and the app. A TypeScript test reads files written by the Kotlin publisher and verifies a signature it produced.
4. The old Kotlin shared module and Android app are removed; git history keeps them.
5. Native projects are generated (`expo prebuild`) and never committed.

## Consequences
- Positive: one UI codebase, no Gradle or Kotlin/Native in the app build, a fast test loop (167 tests in seconds), and a large pool of contributors who know TypeScript.
- Negative: the rewrite replaced working code; the publisher is still Kotlin, so a contributor who touches both needs both toolchains. Hermes lacks some APIs (for example `TextDecoder` was not assumed; the core has its own UTF-8 decoder).
- The retrieval fixes made on the Kotlin side (stopwords, term coverage, word forms) were ported, and the evaluation gives the same results (0 of 20 off-topic questions answered, 29 of 31 answerable ones answered). One rule was tightened during the port: `flood` and `floor` no longer count as the same word.
- The app has been run on an Android emulator only. iOS is bundled in CI but not yet run.
