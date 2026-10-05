# ADR-0015: Offline mobile apps with a publisher, no server

## Status
Accepted

Date: 2026-10-05
Design: `docs/superpowers/specs/2026-10-05-offline-mobile-app-design.md`
Plan: `docs/superpowers/plans/2026-10-05-mobile-kotlin-mpp.md`
Diagram: `docs/architecture/mobile-design.md`

This ADR supersedes ADR-0001 to ADR-0008 and ADR-0010 to ADR-0014 where they describe the server. ADR-0009 (licence) stays in force.

## Context
- The Spring Boot backend (ADR-0001 to ADR-0014) answered questions over a REST API. It needed Postgres with pgvector, a local model or Gemini, and Podman for development. The only planned client was a mobile app that did not exist yet.
- Sheltr is for people in disasters, when the network and power are often down. The app has to answer from content stored on the phone, with no network.
- The app did not need a server. The content is a small set of approved official documents that changes slowly. The phone only needs a signed copy of that content, refreshed whenever a network is available.
- A server in production would be a single point of failure and an operating cost for a free, non-profit project.

## Decision
1. No server runs in production. The phone never calls a backend. Its only network call is to fetch the signed bundle from static hosting.
2. Shared logic is a Kotlin Multiplatform module (`apps/mobile/shared`): bundle format and signature check, SQLite store with FTS5, keyword retrieval with country and hazard boosts, citation selection and refusal, sync and settings. The user interfaces are native: Jetpack Compose on Android and SwiftUI on iOS.
3. A Kotlin/JVM command-line publisher (`tools/publisher`) runs on the maintainer's machine. It reads the approved manifest, fetches and extracts each source, chunks the text, and writes a bundle signed with an Ed25519 key. The maintainer uploads the bundle to free static hosting. The publisher ports the ingestion logic from the removed server.
4. The signing key stays outside the repository. The publisher refuses to read a key from inside the repository.
5. Search on the device is SQLite FTS5 keyword ranking. No model is needed in v1. An on-device model (LiteRT-LM) may be added later behind an interface. It is enabled only if the evaluation passes on a device-sized model. The model may rewrite or summarise cited passages; it may not add facts.

## Consequences
- Positive: the app works offline. There is no server to run, patch or attack, and no hosting cost beyond static files. There are no accounts and no analytics. The trust boundary is the signed bundle, not a server.
- Negative: v1 has no semantic retrieval on the phone. Keyword search misses passages that are worded differently from the question. The refusal rule prevents invented answers, but more questions will get no answer.
- Evaluation: the server eval harness and its Gemini judge are removed. The phone's own keyword retrieval is evaluated instead (plan tasks E1 to E3). The cosine thresholds in ADR-0012 (0.65 local, 0.75 Gemini) no longer apply. The targets in the earlier design still apply: zero false accepts on held-out questions.
- Freshness: content changes only when the maintainer runs the publisher and uploads the bundle. The server's automatic ingestion is gone.
- Signing key: the maintainer holds the Ed25519 private key, off the phone and outside the repository. Losing it stops new signed bundles; the apps keep the last good content. Leaking it lets anyone sign content that the apps accept. The apps verify against the public key they ship with, so rotating the key requires an app update.
- Network: the only network endpoint is the bundle host. The publisher fetches only the hosts named in the approved manifest.
- iOS: BGTaskScheduler decides when sync runs, so it may run later than the schedule.
- On-device model: the iOS integration is unproven until the spike (plan task I3). The Android integration is unproven until plan task A5.
- Costs: Google Play one-time $25; Apple Developer Program $99 per year.
- Removed: the Spring Boot backend, Postgres and pgvector, jOOQ, Flyway, Ollama, Podman, the Gemini provider, the Lumina chat UI and the OpenAPI contract. Git history keeps all of them.
