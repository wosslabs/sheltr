# Sheltr Mobile — Offline-First App Design

Status: Revised 2026-11-03: the phone app is Flutter / Dart (`apps/flutter`). The TypeScript publisher still builds the signed bundle. ADR-0016 (Expo) is superseded for the app. On-device model: LiteRT-LM, built and off by default. No device eval is recorded.
Date: 2026-10-05

## 1. Goal

A free, non-profit disaster-guidance app for Android and iPhone that works fully on the device:
- Answers, guidance cards and search run on the phone with no network.
- When a network is available, the app syncs the approved corpus and updates only what changed.
- Sync frequency is configurable by the user.
- The AI interface is clear, professional and accessible, and every answer shows its source.

Out of scope for v1: live hazard alerts, user accounts, analytics, advertising, payments.

## 2. Platform decision

Flutter with Dart: one codebase for Android and iPhone. `packages/sheltr_core` holds the corpus, search, citations, sync and settings, with no Flutter imports. The screens are in `apps/flutter/lib`. The publisher stays TypeScript (Node). ADR-0016 recorded the earlier Expo choice and is superseded for the app.

Chrome (`flutter run -d chrome`, or `flutter build web`) is a UI test target only. It installs the sample bundle in memory, using `apps/flutter/web/sqlite3.wasm` from the sqlite3.dart 3.8.0 release. `SHELTR_WEB_STALE=true` installs that sample with an expiry in the past. It is not a shipping platform. Android and iOS remain the app.

### Decisions in force

These are the decisions from the ADRs, stated for the current tree. The ADR files stay the record.

- The project is Apache License 2.0 (ADR-0009). That licence covers Sheltr's code. Document text keeps the licence in the manifest.
- No server runs in production. The phone's only network call is fetching the signed bundle over HTTPS (ADR-0015).
- The publisher is the TypeScript command in `tools/publisher`. It fetches approved sources, checks the checksum, extracts text, drops images and mark-only lines, chunks, and signs with Ed25519. The private key stays outside the repository.
- Search is SQLite FTS5 keyword ranking with citations. The app refuses when no passage passes the relevance check. It does not invent guidance.
- The on-device model may only restate cited passages. It stays off until `docs/model-eval.md` records a pass on both phones.
- The phone app is Flutter in `apps/flutter`, not the Expo app ADR-0016 chose. That Expo tree has been removed.

Cost: Google Play one-time $25; Apple Developer Program $99 per year (required for iPhone distribution). Both apps are free to download and use.

## 3. Architecture

```
App (apps/flutter, Flutter / Dart)
  packages/sheltr_core/lib/bundle/     bundle format, parser, checksums
  packages/sheltr_core/lib/signing/    Ed25519 signature check
  packages/sheltr_core/lib/store/      SQLite (bundled sqlite3), FTS5 keyword index, atomic replace
  packages/sheltr_core/lib/retrieval/  keyword ranking, country and hazard boost, citations, refusal
  packages/sheltr_core/lib/sync/       manifest fetch, diff, download, retry policy
  packages/sheltr_core/lib/settings/   user settings (sync frequency, country, text size, language)
  lib/                                 screens: Home, Search, Answer, Settings, About
  on-device model (LiteRT-LM, off by default)

Publisher (tools/publisher, Node and TypeScript, run by the maintainer)
  Approved manifest -> fetch and extract -> chunk -> signed bundle + manifest -> static hosting
```

No server runs in production. The phone never talks to a backend. A command-line publisher (module tools/publisher) builds a signed corpus bundle from the approved manifest and the maintainer uploads it to free static hosting. The publisher is run by the maintainer, not by the phone.

## 4. Corpus bundle and sync

- Bundle: versioned JSON manifest listing each document's ID, source URI, licence, checksum and chunks, plus the index data for each document.
- Signature: the manifest is signed with an Ed25519 key held by the maintainer. The app verifies the signature before using any content. An invalid signature rejects the update and keeps the last good content.
- Diff: the app compares each document's checksum with its local copy. It downloads and re-indexes only documents whose checksum changed, and removes documents no longer in the manifest.
- Re-ingest on device: for an HTML source the app stores the extracted text and the checksum of that text. For PDF the publisher ships pre-extracted text, so the phone does not parse PDFs. This keeps the on-device pipeline small. Chunking and indexing run on the device.
- Atomic update: the new content replaces the old inside one database transaction, which is rolled back unless every document and chunk is stored and the counts match the manifest.
- Failure: a failed or interrupted sync leaves the previous content in place and retries on the next schedule.

## 5. Sync frequency (configurable)

Options: manual only, daily, weekly, monthly. Default weekly. Sync runs only on an unmetered connection unless the user allows mobile data. The app checks once per launch whether a sync is due (the schedule and the network type allow it) and retries a failed sync with a capped back-off. Background scheduling while the app is closed is not built; the operating system controls it on both platforms.

## 6. Retrieval and answers (v1)

- Search: SQLite FTS5 keyword ranking over approved chunks. Works offline with no model.
- Boost: the user's country (set in settings) and the currently active hazards raise matching passages. Global passages are never removed. Same rule as the server.
- Answers: v1 returns the matching passages with their source, not a generated answer. This is the citation-first design already approved, and it needs no model.
- Refusal: when no passage meets the relevance threshold, the app says it has no reliable source. It never invents guidance.
- On-device model: Google LiteRT-LM (https://developers.google.com/edge/litert-lm). Facts checked on 2026-10-05:
  - Android: native API (Engine, Conversation, EngineConfig, ConversationConfig), offline with local .litertlm model files. The Flutter app uses `flutter_edge_ai` on the CPU backend only.
  - iOS: the overview page states native iOS Swift APIs (macOS "coming soon"). The Android page does not cover iOS, so the iOS integration must be proven in a spike before it is relied on.
  - Models: the two pages name different models (Android page: Gemma 3 1B-IT and Gemma 3n; overview: Gemma 4). Model choice is made by the eval, not by the page.
  - Status: the thinking feature is marked experimental. Core functionality is described as stable on Android. Pin versions.
  - Rule: the model only rewrites or summarises the cited passages. It never adds facts. Answers without a source are refused, as on the server.
  - Phase: after v1 search. Enabled only if the eval passes on the device-sized model.

## 7. Interface

- Clear hierarchy: a single search field, a country setting, and cards grouped by hazard.
- Every answer shows the source agency, document title, date and a link to the original.
- Plain language, large text support, high contrast, screen-reader labels on all controls.
- A visible note that the app is not an official warning system and that users should follow official alerts.
- Design tokens shared between platforms; platform-native components.

## 8. Licensing and attribution

- Each document keeps its licence and attribution in the bundle. The app shows the attribution for every source it uses.
- Non-commercial sources (for example the IFRC guide) are allowed because the app is free and non-profit. This must be stated in the app's About screen.
- No source is included without the licence check already in the corpus process.

## 9. Security and privacy

- No account, no analytics, no tracking. Location is used only on the device to pick the country.
- The signed bundle is the only trust boundary for content. The signing key stays off the phone and off the public repository.
- Network calls go only to the configured bundle host.

## 10. Testing

- Shared module: unit tests for signature verification, diff logic, atomic swap, retrieval boost and refusal.
- Sync: tests with a local bundle host, including an invalid signature, an interrupted download and a changed checksum.
- Platform UI: accessibility checks (screen reader labels, text scaling) on both platforms before release.

## 11. Open decisions

1. Hosting for the bundle: free static hosting (for example GitHub Releases or a CDN) — recommended (approved).
2. v1 search without an AI model, with the model deferred — recommended (approved).
3. Sync default weekly, mobile-data sync off by default — recommended (approved).
4. Apple Developer fee of $99 per year accepted for a free app (approved).
