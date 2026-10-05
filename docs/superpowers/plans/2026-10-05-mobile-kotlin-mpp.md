# Sheltr Mobile — Kotlin MPP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a free, offline-first Sheltr app for Android and iPhone built on a Kotlin Multiplatform shared module with native UIs, with signed-bundle sync, and remove the server-rendered HTML chat UI.

**Architecture:** See `docs/superpowers/specs/2026-10-05-offline-mobile-app-design.md`. The phone runs search, citations and refusal offline and syncs a signed corpus bundle from static hosting. The Spring Boot server becomes the publisher of that bundle and the admin API.

**Tech Stack:** Kotlin Multiplatform (commonMain/androidMain/iosMain), SQLDelight (SQLite + FTS5), kotlinx.serialization, kotlinx.coroutines, Ktor client (HTTP), Ed25519 signature verification via a Kotlin library chosen in Task 3, Jetpack Compose (Android), SwiftUI (iOS), LiteRT-LM (Android Kotlin API; iOS Swift API after the Task 9 spike), WorkManager (Android), BGTaskScheduler (iOS). Server: Spring Boot 4.1 (Java today; see Decision D1).

## Decisions required before Phase 1

- **D1. Server language.** Option A (recommended): convert the Spring backend to Kotlin on the JVM, keeping Spring, jOOQ and Flyway. Kotlin/JVM is not Kotlin Multiplatform; the server cannot share the MPP module's `commonMain` because it is not an MPP target. Option B: keep Java for the server and share only the bundle format. Option C: convert the server to Kotlin MPP targeting JVM only. This is the same as A, with more build configuration.
- **D2. Remove the Lumina chat UI.** Deletes `LuminaChatApp`, `LuminaChatAppConfig`, the `lumina-*` dependencies, the Lumina end-to-end tests and the README and ADR references to Lumina. The admin ingestion API and the `/ask` REST endpoint remain. Recommended: yes.
- **D3. Clean docs.** Current docs (README, `docs/architecture`, `docs/openapi`, ADR-0003 and ADR-0008 status text) get updated to the new design. Historical plans and specs are kept as an archive under `docs/superpowers/` with a banner saying they are superseded. Deleting history is not recommended; it cannot be undone from the repo, though git keeps it. Confirm this option or ask for deletion.
- **D4. Java to Kotlin migration scope.** Option A from D1 migrates all main and test Java to Kotlin/JVM in phases (Phase 2). Shared domain rules move into the MPP module only where the mobile app needs them (citation selection, refusal rules, retrieval boost, bundle format).

## Phase 0 — Decisions and baseline (no code)

- [ ] **Task 0.1:** Confirm D1–D4. Record the answers in the design spec's section 11.
- [ ] **Task 0.2:** Record the current test baseline (`mvn -q test` from `apps/backend`, with the environment wrapper) in the plan's progress file.

## Phase 1 — Remove the HTML chat UI (D2)

- [ ] **Task 1.1:** Delete `LuminaChatApp.java`, `LuminaChatAppConfig.java`, and the Lumina test classes under `src/test/.../ai/interfaces/` that exercise only the chat UI. Keep `ConversationController`, `AskQuestionService` and the `/ask` REST contract.
- [ ] **Task 1.2:** Remove the `lumina-spring-boot-starter`, `lumina-spring-ai` and `lumina-web` dependencies from `apps/backend/pom.xml`, the `lumina.port` configuration, and the `ChatClient` workaround bean.
- [ ] **Task 1.3:** Run `mvn -q test`. Expected: all remaining tests pass; the number of tests drops by the number of deleted Lumina tests. Record the new count.
- [ ] **Task 1.4:** Update `README.md` (remove the chat UI URL on port 8090 and the Lumina paragraph), `docs/openapi` (no UI endpoints exist there), and the ADR references that describe the chat UI (keep the ADR text, add a status line "Superseded by ADR-0015 for the UI").

## Phase 2 — Server to Kotlin (D1 Option A, D4)

Each task converts one package, keeps the tests passing, and commits.

- [ ] **Task 2.1:** Add the Kotlin JVM plugin and `kotlin-spring` to `apps/backend/pom.xml`. Configure `src/main/kotlin` and `src/test/kotlin`. Build still passes with no code moved.
- [ ] **Task 2.2:** Convert `com.sheltr.search` (domain records and ranking) to Kotlin. Keep the public method names and signatures. Tests move with the code.
- [ ] **Task 2.3:** Convert `com.sheltr.ai.domain` and `com.sheltr.ai.application` (judge, pipeline, citation selector, refusal). Keep the domain free of Spring.
- [ ] **Task 2.4:** Convert `com.sheltr.knowledge`, `com.sheltr.ingestion` and `com.sheltr.eval`.
- [ ] **Task 2.5:** Convert `com.sheltr.config`, `com.sheltr.interfaces` and the controllers. Keep every endpoint's status codes and error bodies unchanged (regression tests stay green).
- [ ] **Task 2.6:** Delete the remaining Java sources and tests. Confirm with `find apps/backend/src -name '*.java'` returning nothing.
- [ ] **Task 2.7:** Run the full suite, the Phase 0 security tests, and the eval harness unit tests. Record results.

## Phase 3 — Shared module (KMP)

- [ ] **Task 3.1:** Create `apps/mobile/shared` as a KMP module with `androidTarget` and `iosArm64`/`iosSimulatorArm64`. Empty commonMain test passes on both.
- [ ] **Task 3.2:** Bundle format: `commonMain` data classes for the signed manifest, documents, chunks and checksums, with kotlinx.serialization. Round-trip test on a fixture bundle.
- [ ] **Task 3.3:** Signature verification (Ed25519). Choose the library in this task; test valid, tampered and wrong-key cases with fixed vectors.
- [ ] **Task 3.4:** Local store with SQLDelight and FTS5. Atomic swap: stage a new database, index, verify counts, then replace. Test an interrupted write leaves the previous content intact.
- [ ] **Task 3.5:** Retrieval, citation selection and refusal ported from the server rules, using the same test vectors as the server (`CitationSelectorTest`, `RelevanceGateTest`). Country and hazard boost; global passages never removed.
- [ ] **Task 3.6:** Sync: fetch manifest, diff by checksum, download changed documents only, remove deleted documents, retry policy. Test with a local fake host (Ktor MockEngine).
- [ ] **Task 3.7:** Settings model: sync frequency (manual, daily, weekly, monthly; default weekly), mobile-data permission (default off), country, text size.

## Phase 4 — Publisher on the server

- [ ] **Task 4.1:** Bundle publisher: build the signed bundle from the approved manifest and the active documents. Output to a directory, ready for static hosting. Test: the output verifies with the public key and has the expected checksums.
- [ ] **Task 4.2:** Key handling: the signing key is read from a file outside the repository (environment variable path). A test asserts the key is not read from the repository and that the public key is committed.
- [ ] **Task 4.3:** Publish target: a documented GitHub Releases or CDN upload step, run by the maintainer. No automated publish in this plan.

## Phase 5 — Android app (Compose)

- [ ] **Task 5.1:** Android app module depending on `shared`. Single search screen, country setting, cards grouped by hazard, source view.
- [ ] **Task 5.2:** WorkManager sync worker with the schedule from settings and the network constraint (unmetered by default).
- [ ] **Task 5.3:** Answer view: every answer shows source agency, title, date, and a link; refusal text when no source passes; standing disclaimer that the app is not an official warning system.
- [ ] **Task 5.4:** Accessibility: content descriptions, text scaling, contrast check, TalkBack pass on a device.
- [ ] **Task 5.5:** LiteRT-LM (Kotlin API) spike: load a model file offline, generate a rewrite of one cited passage, measure latency. Behind a feature flag, off by default. Adopt only after the eval passes on the device model (see the design spec, section 6).

## Phase 6 — iOS app (SwiftUI)

- [ ] **Task 6.1:** Xcode project depending on the `shared` framework. Same screens and settings as Android.
- [ ] **Task 6.2:** BGTaskScheduler sync. Document that iOS decides when the task runs.
- [ ] **Task 6.3:** LiteRT-LM Swift API spike, the iOS counterpart of Task 5.5. Record whether the Swift API works on device before the feature is enabled on iOS.
- [ ] **Task 6.4:** Accessibility pass with VoiceOver and Dynamic Type.

## Phase 7 — Release

- [ ] **Task 7.1:** About screen: free, non-profit, sources and licences listed per document, attribution text, not an official service.
- [ ] **Task 7.2:** Privacy statement: no account, no analytics, location used only on the device.
- [ ] **Task 7.3:** Store listings and review checklists (Google Play, App Store). Maintainer submits.

## Verification

- Server: `mvn -q test` from `apps/backend` (Kotlin, no Java sources) passes with the environment wrapper.
- Shared module: `./gradlew :shared:allTests` passes on JVM and both iOS simulator targets.
- Android: unit tests and an instrumented run on one device.
- iOS: unit tests and a simulator run; LiteRT-LM spike results recorded.
- Any step that cannot run is reported with the exact error. Nothing is marked passed without a run.
