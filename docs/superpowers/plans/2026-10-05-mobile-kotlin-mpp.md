# Sheltr Mobile — Kotlin MPP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a free, offline-first Sheltr app for Android and iPhone. No server runs in production. The app is a Kotlin Multiplatform (KMP) shared module with native UIs. A command-line publisher, written in Kotlin on the JVM, builds and signs the corpus bundle that the apps sync from free static hosting.

**Architecture:** See `docs/superpowers/specs/2026-10-05-offline-mobile-app-design.md`.

**Tech Stack:** Kotlin Multiplatform (commonMain, androidMain, iosMain, jvmMain), SQLDelight (SQLite + FTS5), kotlinx.serialization, kotlinx.coroutines, Ktor client, Ed25519 via a Kotlin library chosen in Task S3, Jetpack Compose (Android), SwiftUI (iOS), LiteRT-LM (Android Kotlin API; iOS Swift API after spike M3), WorkManager (Android), BGTaskScheduler (iOS). Publisher: Kotlin/JVM, Apache PDFBox 3 (PDF text), Jsoup (HTML), SQLDelight JVM driver.

## What is removed

- The Spring Boot server (`apps/backend`), its REST API, admin API, security, rate limits and daily cap.
- The Postgres, pgvector, Flyway and jOOQ stack, and the Ollama container (`infra/podman`, `infra/k8s`).
- The Lumina chat UI (`lumina-*` dependencies, `LuminaChatApp`, its tests, its ports).
- The server-side eval harness and its Gemini judge. The mobile app's own search is evaluated instead (task S7).
- The OpenAPI contract and the server architecture diagrams.
- All plans under `docs/superpowers/plans/` (per decision D3). Specs stay; server-only specs carry a "superseded" banner.

Git history keeps everything removed. Nothing is deleted without a commit first.

## Order of work

The publisher ports the ingestion logic (PDF, HTML, manifest, checksum, chunking) from the Java server before the server is deleted. Deleting Java first would lose a working reference.

## Phase S — Publisher (Kotlin/JVM), ported from the server

- [ ] **S1:** Create `tools/publisher` as a Gradle Kotlin JVM module. Empty test passes.
- [ ] **S2:** Port the manifest parser (`CorpusManifest`) with the same validation rules and tests (`CorpusManifestTest`). Fixtures in `tools/publisher/src/test/resources`.
- [ ] **S3:** Choose the Ed25519 library and add it. Port nothing else yet; test a fixed-vector signature round trip.
- [ ] **S4:** Port PDF extraction (`PdfContentExtractor`, PDFBox 3) and the text cleaner (`TextCleaner`), with the same tests.
- [ ] **S5:** Port HTML main-content extraction (`MainContentExtractor`, Jsoup) and the HTML fetch rules: https only, default port, allowlisted hosts, no redirects except to allowed hosts, timeouts (`HtmlPageConnectorTest`, `MainContentExtractorTest`).
- [ ] **S6:** Port the chunker (`Chunker`) and its tests. Chunks carry no page number for HTML.
- [ ] **S7:** Build the bundle: for each approved manifest entry, fetch or read the source, check the checksum, extract, chunk, and write rows. Output one signed bundle file plus a manifest JSON. Test with local fixtures; the checksum mismatch case fails the entry.
- [ ] **S8:** Publisher CLI: `publish --manifest <file> --out <dir> --key <path>`. Reads the signing key from the path given, never from the repository. Test that the key path is not inside the repo.

## Phase M — Shared module (KMP)

- [ ] **M1:** `apps/mobile/shared` as a KMP module with `androidTarget`, `iosArm64`, `iosSimulatorArm64`, and `jvm` (the jvm target lets the publisher and the shared tests share code). Empty commonTest passes on all targets.
- [ ] **M2:** Bundle format in commonMain: manifest, documents, chunks, checksums, kotlinx.serialization. Round-trip test on a fixture bundle, shared with the publisher.
- [ ] **M3:** Ed25519 verification in commonMain (or expect/actual if the library needs it). Valid, tampered and wrong-key vectors.
- [ ] **M4:** Local store with SQLDelight and FTS5. Atomic swap: stage a database, verify counts, replace. Interrupted write keeps the previous content.
- [ ] **M5:** Retrieval, citation selection and refusal. Port the rules and test vectors from the server (`CitationSelectorTest`, `RelevanceGateTest`, `HazardBoostTest`, `RankFusionTest` where they apply to keyword search). Country boost; global passages never removed.
- [ ] **M6:** Sync: fetch the manifest, verify the signature, diff by checksum, download changed documents only, remove deleted ones, retry policy. Tests with Ktor MockEngine: invalid signature keeps old content; interrupted download keeps old content; one changed checksum re-downloads only that document.
- [ ] **M7:** Settings model: sync frequency (manual, daily, weekly, monthly; default weekly), mobile-data permission (default off), country, text size.
- [ ] **M8:** Spike: LiteRT-LM Kotlin API on Android JVM target is not used; keep the model behind an interface with a no-op implementation for now.

## Phase A — Android app (Compose)

- [ ] **A1:** Android module depending on `shared`. Search screen, country setting, cards grouped by hazard, source view.
- [ ] **A2:** WorkManager sync with the schedule from settings and the network constraint (unmetered by default).
- [ ] **A3:** Answer view: source agency, title, date, link; refusal text when no source passes; disclaimer that the app is not an official warning system.
- [ ] **A4:** Accessibility: content descriptions, text scaling, contrast, TalkBack pass on a device.
- [ ] **A5:** LiteRT-LM Kotlin API spike on device: offline load of a model file, one rewrite of one cited passage, latency measured. Behind a feature flag, off by default.

## Phase I — iOS app (SwiftUI)

- [ ] **I1:** Xcode project depending on the `shared` framework. Same screens and settings as Android.
- [ ] **I2:** BGTaskScheduler sync. Document that iOS decides when the task runs.
- [ ] **I3:** LiteRT-LM Swift API spike: confirm it works on device before enabling on iOS.
- [ ] **I4:** Accessibility pass with VoiceOver and Dynamic Type.

## Phase E — Evaluation on the phone's search

- [ ] **E1:** Eval set (question file) reused from the earlier plan's question format, with expected passages and refusal cases.
- [ ] **E2:** Runner on the JVM target of `shared`: runs the same keyword retrieval the phone uses and reports recall, refusal accuracy and false accepts.
- [ ] **E3:** Targets from the earlier design still apply (false accepts 0 on held-out questions). Report results; do not claim targets met without a run.

## Phase R — Release and docs

- [ ] **R1:** Remove `apps/backend`, `infra/podman`, `infra/k8s` and the server Makefile targets. Only after S1–S8 and M1–M8 pass. Run the publisher test suite and the shared test suite before deletion.
- [ ] **R2:** Remove the Lumina dependencies and any remaining references.
- [ ] **R3:** Delete every file under `docs/superpowers/plans/` (D3). Keep this plan.
- [ ] **R4:** Update `README.md` to describe the apps and the publisher. Remove server, Podman and Ollama setup text. Keep ADR-0009 (license) and ADR-0010 (Podman) as history with a superseded line.
- [ ] **R5:** Remove `docs/openapi` and `docs/architecture` server diagrams; replace architecture docs with one diagram of the mobile and publisher design.
- [ ] **R6:** Add ADR-0015 "Offline mobile apps with a publisher, no server" and mark ADR-0001 through ADR-0008 and ADR-0011 through ADR-0014 "superseded by ADR-0015" where they describe the server.
- [ ] **R7:** About screen (free, non-profit, sources and licences per document, not an official service), privacy statement (no account, no analytics, location only on device).
- [ ] **R8:** Store listings and review checklists. The maintainer submits.

## Verification

- Publisher: `./gradlew :tools:publisher:test` passes.
- Shared: `./gradlew :apps:mobile:shared:allTests` passes on JVM and both iOS simulator targets.
- Android: unit tests and an instrumented run on one device.
- iOS: unit tests and a simulator run.
- Any step that cannot run is reported with the exact error. Nothing is marked passed without a run.
