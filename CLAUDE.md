# CLAUDE.md

Guidance for AI coding assistants working in this repository. Humans: see `CONTRIBUTING.md`.

## What this is

Sheltr is an offline-first disaster-preparedness guide. No server runs in production. A Kotlin/JVM publisher (`tools/publisher`) builds an Ed25519-signed bundle of approved official documents. Apps (`apps/mobile`) verify it, store it in SQLite FTS5, and answer by keyword retrieval with citations. Design: `docs/adr/0015-offline-mobile-publisher-no-server.md`, `docs/design/offline-mobile-app.md`.

## Layout

- `tools/publisher`: Kotlin/JVM CLI (manifest, fetch, extract, chunk, sign).
- `apps/mobile/shared`: Kotlin Multiplatform (bundle, signature check, store, retrieval, sync, settings).
- `apps/mobile/android`: Jetpack Compose app. iOS is planned, not built.
- `corpus/manifest.yaml`: the approved corpus.

## Commands

```bash
./gradlew :tools:publisher:test
./gradlew :apps:mobile:shared:jvmTest
./gradlew :apps:mobile:android:testDebugUnitTest
```

Needs JDK 25. Android needs `sdk.dir` in `local.properties` (git-ignored).

## Rules

- Safety first: answer only from approved documents, always cite, refuse when no source passes the relevance check. Never add invented guidance.
- Never read, create or commit a signing key inside the repository. `RepoGuard` enforces this for the publisher.
- No network calls from the apps except fetching the signed bundle over HTTPS. No analytics, no accounts.
- Verify signatures before trusting any bundle content. Treat fetched documents as untrusted input.
- Keep changes focused, add tests, and do not claim tests passed unless they were run.
- Corpus changes need licence, attribution and checksum in the manifest; update `THIRD_PARTY_CONTENT.md`.
- Significant decisions get an ADR in `docs/adr`.
