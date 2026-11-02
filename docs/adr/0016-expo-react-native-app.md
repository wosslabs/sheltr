# ADR-0016: Expo, React Native and TypeScript for the app and the publisher

## Status
Accepted. Refines the platform choices in ADR-0015 (one codebase for both phones, and one language for the app and the publisher). The rest of ADR-0015 stands: no server, a signed bundle, keyword search on the device.

Date: 2026-11-02

## Context
- An earlier design used a different native UI for each platform plus a shared-logic module with its own build toolchain, and a separate JVM tool for the publisher. A small non-profit project cannot afford two UIs, two toolchains for the app and a third for the publisher.
- React Native with Expo has the largest community of the cross-platform options, one UI codebase for both platforms, and a simple toolchain (`npm`, Expo Go, `expo export`).
- The product needs are modest and all available in Expo: SQLite with FTS5 (`expo-sqlite`), Ed25519 verification (`@noble/curves`), SHA-256 (`@noble/hashes`), text to speech (`expo-speech`), localisation.
- The publisher needs HTTP, YAML, HTML and PDF parsing, SHA-256 and Ed25519: all available in Node 24 and a few small packages.

## Decision
1. The app is an Expo project in `apps/mobile`, written in TypeScript, for Android and iOS.
2. The app's logic lives in `src/core` with no React Native imports: bundle parsing, signature check, store, retrieval, sync, settings. It talks to SQLite through a small `SqlDatabase` interface, implemented with `expo-sqlite` in the app and with Node's built-in SQLite in tests.
3. The publisher is a TypeScript package in `tools/publisher` that Node 24 runs directly (no build step). It uses Node's `crypto` for Ed25519 and SHA-256, `yaml`, `cheerio` for HTML and `pdfjs-dist` for PDF text. Its tests use Node's built-in test runner.
4. The bundle format is the contract between the two. A test in the publisher re-encodes the committed sample bundle to the same bytes, and the app's tests read bundles and verify signatures the publisher produced.
5. Native projects are generated (`expo prebuild`) and never committed. The repository's tooling is Node and TypeScript only.

## Consequences
- Positive: one language and one package manager for everything, one UI codebase, no native toolchain in CI, a fast test loop (167 app tests and 124 publisher tests run in seconds), and a large pool of contributors who know TypeScript.
- Negative: the rewrite replaced working code. PDF text now comes from `pdfjs-dist` instead of the library used before, so the extracted text of a given PDF can differ in small ways (line breaks, spacing). That changes chunk text and chunk checksums but not the approved source checksum, which is a SHA-256 of the downloaded file. Both approved sources have to be re-approved anyway (see `docs/publishing.md`), so no published bundle is affected.
- Hermes lacks some APIs (`TextDecoder` is not assumed): the app core has its own UTF-8 decoder.
- Retrieval keeps the fixes made earlier (stopwords, term coverage, word forms) and the evaluation gives the same results (0 of 20 off-topic questions answered, 29 of 31 answerable ones answered). One rule was tightened: `flood` and `floor` no longer count as the same word.
- The YAML library follows YAML 1.2, so an unquoted `NO` is read as text instead of a boolean. The manifest still quotes country codes, as its comments say.
- The app has been run on an Android emulator only. iOS is bundled in CI but not yet run.
