# Flutter App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Merged to local `main` on 2026-11-03. Not pushed.** On that date the Expo app (`apps/mobile`) was removed. The steps below are the record of the port. They still name `apps/mobile` as the source that was copied. The publisher stays TypeScript.

**Goal:** Replace the Expo app with one Flutter app, written in Dart, that passes one shared contract on Android and iOS, then add cited on-device summaries through Google LiteRT-LM.

**Architecture:** One Dart codebase. `sheltr_core` has no Flutter imports and holds the bundle format, signature check, SQLite store, retrieval, sync, settings, and the passage rewriter. The Flutter app is UI, localisation, background scheduling, and the LiteRT-LM adapter. A `contract/` folder is the source of truth for the bundle format, retrieval rules, signed fixtures, and question cases. The publisher stays TypeScript and generates the fixtures. The Expo app stays as the oracle until the Flutter app passes the same contract and the release checklist is done. LiteRT-LM only rewrites passages that already passed retrieval. It never answers a refusal, and a failed or missing model leaves the keyword answer in place.

**Tech Stack:** Flutter stable channel recorded at Task 3, Dart 3. `package:sqlite3` bundled build with FTS5 enabled (do not switch either phone to the system SQLite). `package:cryptography` `DartEd25519` for verification, without `cryptography_flutter`. `flutter_edge_ai` 2.5.5 and `flutter_edge_ai_litertlm` 1.19.1 for LiteRT-LM, CPU backend only. Publisher: Node 24, TypeScript. CI: GitHub Actions.

**Spec:** `docs/design/offline-mobile-app.md` (the on-device model section is the LiteRT-LM rule this plan implements), `docs/adr/0015-offline-mobile-publisher-no-server.md`, `docs/evaluation.md`. Behaviour to port is `apps/mobile/src/core`.

This file used to describe two native apps, Kotlin and Swift. That version is replaced by this plan.

## Global Constraints

- No server, no accounts, no analytics, no tracking SDK. The only network host is the bundle host. HTTP is refused. A model file, when the signed manifest names one, is fetched from that same host and checked against the signed checksum. No Hugging Face download and no token in the app.
- The app answers only from approved documents, shows agency, title, date, page and link for every passage, and refuses when no passage passes the relevance check. It never generates guidance. Model text is an optional lead above those passages. Hiding the lead still shows the passages.
- Relevance rules, shared through `contract/retrieval-rules.json`: minimum keyword score `1.0` (negated FTS5 bm25); a passage must contain `60%` of the question's topical words; country boost `+0.15`, hazard boost `+0.15`; boosts only reorder; pool multiplier `5`. Scores are not compared across devices. The contract compares passage ids and answer-or-refuse, not bm25 numbers.
- Word rule, shared through `contract/word-cases.tsv` and implemented once in `sheltr_core`: two words match when they share a prefix of at least 4 letters that covers all but at most one letter of the shorter word. When both words have exactly one letter left after that prefix, they do not match (`flood` / `floor`).
- Bundle format version `1` until Task 10; version `2` afterwards. The app reads exactly its own version and rejects others with a clear message.
- An update is all-or-nothing. On any failure, including a thrown write halfway through install, the previous content stays active. A staging file is never the active file. A leftover `content.db.staging` is deleted on the next open. Tests simulate the abort by throwing. They do not kill a process.
- Signatures: Ed25519, raw 32-byte public key compiled into the app, raw 64-byte signature of the exact bytes of `bundle.json`, strict RFC 8032 via `DartEd25519`. `verify` returns false for a bad input and does not throw. Do not enable `cryptography_flutter`.
- A wrong device clock does not hide guidance. A bad signature, an unknown key id, and a bundle sequence less than or equal to the installed sequence reject the update. A document past `expiresOn`, a last sync older than 90 days, and a trusted key past `notAfter` show a warning. Answers still work. Removing a key from the compiled list is what revokes it.
- Android minSdk 24. iOS 16 minimum. The LiteRT-LM engine runs on the CPU backend on both, including the iOS simulator. GPU and NPU are out of this plan. Gemma 4 on GPU has been measured copying digits wrongly, which this app cannot show.
- Languages: English, Spanish, French UI, keys in `contract/strings.tsv`, taken from `apps/mobile/src/i18n/locales/en.json`. Retrieval and the model support check are English. Text scale factors `1.0`, `1.2`, `1.4`. Touch targets at least 48 logical pixels. Every control has a screen-reader label.
- The model flag defaults to off. Nothing in this plan turns it on. A later human commit may turn it on only together with a passing device record in `docs/evaluation.md`.
- Model weights are never committed. `*.litertlm` is gitignored.
- Licence: Apache-2.0. Corpus text keeps its own licence (`THIRD_PARTY_CONTENT.md`). The model file keeps the licence its publisher states, recorded in the eval doc, not in the app binary.
- The publisher already drops images and drops a line that is only a logo, crest, emblem, coat of arms, or wordmark (`tools/publisher/src/html.ts`, `tools/publisher/src/text.ts`). A sentence that mentions one of those words stays. Do not add a second stripper.
- Every task ends with its tests passing and a commit. Committed fixtures are canonical. CI does not regenerate them. Generation uses a new key every run, so a regeneration will not match the committed bytes.

## Review Focus

1. **Replayed old signed bundle.** Rejected because `sequence` is not greater than the installed sequence. Content unchanged (Task 11).
2. **Install interrupted.** A document source throws on the second document, and a driver throws on a later statement. The old content still answers, no partial rows, no leftover staging file after the next open (Task 5).
3. **Odd questions.** Empty text, only stopwords, only punctuation, non-Latin script, a 5,000-character paste, FTS5 operators in the text. Refusal or an answer, never a crash or a syntax error (Task 6). `flood` does not match `floor` (Task 6, `contract/word-cases.tsv`).
4. **Unsupported or missing settings.** Device language not en/es/fr, country unset, damaged stored settings. English UI, no country boost, defaults for any damaged field (Task 8).
5. **Stale content, wrong clock, no network.** Content past `expiresOn`, or last sync older than 90 days, or the device date before the bundle's published time. Answers still work and a warning shows (Task 11).
6. **Model adds a fact.** Retrieval passed, the scripted model reply contains a content word or a digit sequence that is not in the cited passages. The lead is hidden, the passages and their citations still show, and a retrieval refusal never calls the model (Task 14).

## File Structure

```
contract/
  README.md
  bundle-format.md
  retrieval-rules.json
  retrieval-cases.tsv          moved from apps/mobile/src/core/testing/retrieval-eval.tsv
  word-cases.tsv               left, right, match (true|false)
  strings.tsv                  key, placeholders. Keys copied from the English locale.
  model-cases.tsv              question, passages, model output, show|hide
  fixtures/<name>/             bundle.json, bundle.json.sig, documents/, public-key.hex, expect.json
apps/flutter/
  pubspec.yaml                 path dependency on packages/sheltr_core; app UI
  lib/                         Flutter UI, l10n, sync scheduling, LiteRT adapter
  packages/sheltr_core/        pure Dart. No package:flutter import.
  test/
apps/mobile/                   Expo app: oracle until Task 17, then deleted
tools/publisher/               unchanged except Tasks 1, 10, 12 and 13
docs/
```

`expect.json` is:

```json
{
  "prior": "empty" | "valid-signed",
  "result": "updated" | "rejectedSignature" | "failed" | "upToDate",
  "reasonContains": null,
  "documentsDownloaded": null
}
```

`reasonContains` is asserted only when it is a string. `documentsDownloaded` is asserted only when it is a number. `rejectedSignature` has no reason on the current sync result, so those fixtures use `null`.

| Fixture | prior | result | reasonContains | documentsDownloaded |
|---|---|---|---|---|
| `valid-signed` | empty | updated | null | number of documents in that bundle |
| `valid-unchanged-doc` | valid-signed | updated | null | count of documents whose file checksum differs from the installed one |
| `tampered-manifest` | empty | rejectedSignature | null | null |
| `wrong-key` | empty | rejectedSignature | null | null |
| `bad-file-checksum` | empty | failed | `does not match the manifest` | null |
| `missing-document` | empty | failed | null | null |
| `unsupported-format` | empty | failed | `format` | null |
| `plain-http-url` | empty | failed | `https` | null |

## Phase A: contract

### Task 1: Contract folder and generated fixtures

**Files:**
- Create: `contract/README.md`, `contract/bundle-format.md`, `contract/retrieval-rules.json`, `contract/retrieval-cases.tsv`, `contract/word-cases.tsv`, `contract/strings.tsv`, `tools/publisher/src/fixtures.ts`, `tools/publisher/src/fixtures.test.ts`
- Modify: `tools/publisher/package.json` (script `fixtures`)

**Interfaces:**
- Produces: the eight fixture directories in the table above, each with `expect.json` matching that row. `npm run fixtures` writes them. The private key is never written. Only `public-key.hex` and the signature are.
- `retrieval-rules.json` keys: `relevanceThreshold` (1.0), `minTermCoverage` (0.6), `countryBoost` (0.15), `hazardBoost` (0.15), `poolMultiplier` (5), `stopwords[]`, `synonyms{}`, `hazardTerms[]`, `disasterTypes[]` (the 12 `DisasterType` values in `apps/mobile/src/ui/hazards.ts`).
- `word-cases.tsv` rows: `aftershocks aftershock true`, `evacuate evacuation true`, `disasters disaster true`, `heat heating true`, `programming programmes false`, `pet petrol false`, `flood floor false`, `heat heal false`.
- `strings.tsv`: one row per key in `apps/mobile/src/i18n/locales/en.json`, with the `%{pN}` placeholders used by that string. Spanish and French are not copied. Later tasks only check that those locales have the same keys and placeholders.

- [ ] **Step 1: Write the failing test** `fixtures.test.ts`: the eight directories exist; each `expect.json` equals the row in the table; `valid-signed` verifies under its `public-key.hex`; `tampered-manifest` does not; running generation twice into two temp directories produces fixtures that verify under their own public keys. A second test reads `word-cases.tsv` and `strings.tsv` and checks they are non-empty and have the header columns above.
- [ ] **Step 2: Run** `cd tools/publisher && npm test -- --test-name-pattern fixtures`. Expected: FAIL (module not found).
- [ ] **Step 3: Implement** `generateFixtures(outDir: string, now: () => Date): Promise<void>` using `buildBundle` and a key generated in memory. Derive the broken fixtures by editing bytes after signing. Write the rules JSON from the constants in `apps/mobile/src/core/retrieval`. Write `word-cases.tsv` with the eight rows. Generate `strings.tsv` from the English locale keys. Write `bundle-format.md` from `docs/design/offline-mobile-app.md` section 4 and `apps/mobile/src/core/bundle/format.ts`.
- [ ] **Step 4: Run** the publisher tests. Expected: PASS. Run `npm run fixtures` once and commit that output. Do not re-run it in CI.
- [ ] **Step 5: Commit** `feat: add the shared contract and generated fixtures`.

### Task 2: Prove the Expo app already satisfies the contract

**Files:**
- Create: `apps/mobile/src/core/contract.test.ts`
- Modify: `apps/mobile/src/core/retrieval/stopwords.ts`, `vocabulary.ts`, `boost.ts`, `retriever.ts` so the numbers and lists come from `contract/retrieval-rules.json`. The `sameWord` function stays in `retriever.ts`.

**Interfaces:**
- Consumes: Task 1 fixtures, rules, word cases, and retrieval cases.
- Produces: the oracle. Flutter's contract tests assert the same fixture table, the same word cases, and the same retrieval bars.

- [ ] **Step 1: Write the failing test** `contract.test.ts`. For each fixture, install `prior` when it is `valid-signed`, serve the fixture through a mock `SyncClient`, run `SyncEngine` with that fixture's `public-key.hex`, and assert `result.kind`, `reasonContains` when non-null, and `documentsDownloaded` when non-null. Assert every `word-cases.tsv` row against `sameWord`. Assert `retrieval-cases.tsv` has zero false accepts and at least 90% of the answerable rows answered, and that each answered row's expected term is present. These are the bars in `docs/evaluation.md`.
- [ ] **Step 2: Run** `cd apps/mobile && npx jest src/core/contract.test.ts`. Expected: FAIL until the fixtures path and the rules file are wired.
- [ ] **Step 3: Implement** the JSON load in the four retrieval files. Keep `sameWord` as it is. Point the existing evaluation test at `contract/retrieval-cases.tsv` so there is one case file.
- [ ] **Step 4: Run** `npm test` in `apps/mobile`. Expected: PASS.
- [ ] **Step 5: Commit** `test: the Expo app passes the shared contract`.

## Phase B: Flutter app

### Task 3: Flutter project and a Flutter-free core package

**Files:**
- Create: `apps/flutter/` with `flutter create`, then `apps/flutter/packages/sheltr_core/` as a pure Dart package. `apps/flutter/pubspec.yaml` depends on it by path. `.github/workflows/flutter.yml`. `.gitignore` gains `*.litertlm`.

**Interfaces:**
- Produces: `flutter test` in `apps/flutter` runs the app tests and `dart test` in `packages/sheltr_core`. A test `core_has_no_flutter_import_test.dart` fails if any file under `packages/sheltr_core/lib` contains `package:flutter`.
- Record `flutter --version` in the commit message. Android `minSdk` 24. iOS deployment target 16.0.

- [ ] **Step 1: Write the failing check** that greps `sheltr_core/lib` for `package:flutter` and fails on a hit. The package does not exist yet, so the test command fails first.
- [ ] **Step 2: Run** `cd apps/flutter/packages/sheltr_core && dart test`. Expected: FAIL.
- [ ] **Step 3: Implement** the create, the path dependency, the grep test, and the workflow running `dart test` and `flutter test` on `ubuntu-latest`.
- [ ] **Step 4: Run** both test commands. Expected: PASS.
- [ ] **Step 5: Commit** `feat(flutter): add the app and sheltr_core`.

### Task 4: Bundle parsing, checksums, and Ed25519

**Files:**
- Create: `packages/sheltr_core/lib/bundle/format.dart`, `parser.dart`, `sha256.dart`, `lib/signing/verifier.dart`
- Test: `packages/sheltr_core/test/contract_fixtures_test.dart`, `verifier_test.dart`, `parser_test.dart`

**Interfaces:**
- Produces:
  - `bool verifySignature(Uint8List publicKey, Uint8List message, Uint8List signature)` using `DartEd25519` from `package:cryptography/dart.dart`. False for a key that is not 32 bytes, a signature that is not 64 bytes, a DER key, or a high-S signature.
  - `BundleManifest parseBundleManifest(String text)`
  - `void verifyDocumentFile(BundleDocument doc, Uint8List bytes)` throws a format error whose message contains `does not match the manifest` when the SHA-256 does not match `fileChecksum`.
- Add `cryptography` at the version resolved on the day of the task. Do not add `cryptography_flutter`.

- [ ] **Step 1: Write the failing tests.** RFC 8032 test vector 1 verifies. A signature with `S` greater than or equal to the group order returns false. A 33-byte key returns false. A DER-encoded key returns false. `valid-signed` verifies. `tampered-manifest` and `wrong-key` do not. `unsupported-format` and `bad-file-checksum` throw with the substrings in the fixture table.
- [ ] **Step 2: Run** `dart test`. Expected: FAIL.
- [ ] **Step 3: Implement** the parser against `contract/bundle-format.md` and `apps/mobile/src/core/bundle`. Verification calls `DartEd25519().verify` and catches any error as false.
- [ ] **Step 4: Run** `dart test`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(flutter): verify Ed25519 bundles in sheltr_core`.

### Task 5: Store, FTS5, and interrupted installs

**Files:**
- Create: `packages/sheltr_core/lib/store/content_store.dart`
- Test: `packages/sheltr_core/test/content_store_test.dart`
- Modify: `apps/flutter/pubspec.yaml` and `packages/sheltr_core/pubspec.yaml` to depend on `sqlite3`. Do not set `hooks.user_defines.sqlite3.source` to `system` on Android or iOS. The package's bundled SQLite is compiled with `SQLITE_ENABLE_FTS5`.

**Interfaces:**
- Produces: `ContentStore` opened on a directory. `install` writes `content.db.staging`, checks row counts against the manifest, closes it, and renames it over `content.db`. On any throw before the rename, `content.db` is unchanged and the staging file is removed. `open` deletes a leftover staging file, including `content.db.staging-journal` and `content.db.staging-wal` if present. `search(query, limit)` returns FTS5 rows using `bm25(chunks_fts)`. The query string is built by the store from tokens, never passed through as the user's text.

- [ ] **Step 1: Write the failing tests** `installThrowsOnSecondDocumentLeavesOldContent`, `driverThrowMidwayLeavesOldContent`, `leftoverStagingFileIsDeletedOnOpen`, `fts5TableCanBeCreated`. The FTS5 test runs in `dart test` against the bundled library.
- [ ] **Step 2: Run** `dart test`. Expected: FAIL.
- [ ] **Step 3: Implement** the staging rename. Port the SQL from `apps/mobile/src/core/store/schema.ts` and `contentStore.ts`.
- [ ] **Step 4: Run** `dart test`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(flutter): interruption-safe FTS5 store`.

### Task 6: Retrieval

**Files:**
- Create: `packages/sheltr_core/lib/retrieval/rules.dart`, `retriever.dart`, `stopwords.dart`, `citations.dart`
- Test: `packages/sheltr_core/test/retrieval_test.dart`

**Interfaces:**
- Produces: `Rules` loaded from `contract/retrieval-rules.json`, copied into the package by a test helper that fails if the copy differs. `bool sameWord(String a, String b)`. `RetrievalResult retrieve(RetrievalQuery query)` with kinds `passages` and `refuse`. A `passages` result carries agency, title, published date, page, and source link for each passage.

- [ ] **Step 1: Write the failing tests.** Every `word-cases.tsv` row. Every review-focus odd question returns `refuse` or `passages` and does not throw: empty, stopwords only, punctuation only, a non-Latin string, 5,000 characters, and a query containing `AND`, `OR`, `NOT`, and `"`. `retrieval-cases.tsv`: zero false accepts, at least 90% of answerable rows answered, expected term present on answered rows. Country and hazard boosts reorder and do not turn a refusal into an answer.
- [ ] **Step 2: Run** `dart test`. Expected: FAIL.
- [ ] **Step 3: Implement** by porting `apps/mobile/src/core/retrieval/retriever.ts`, including `sameWord` and the 60% coverage check. Load lists from `Rules`. The FTS query is tokenised before it reaches SQLite.
- [ ] **Step 4: Run** `dart test`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(flutter): retrieval passes the contract`.

### Task 7: Sync

**Files:**
- Create: `packages/sheltr_core/lib/sync/client.dart`, `engine.dart`, `result.dart`, `retry_policy.dart`
- Test: `packages/sheltr_core/test/sync_engine_test.dart`

**Interfaces:**
- Produces: `SyncResult` with kinds `updated`, `upToDate`, `rejectedSignature`, and `failed`. Only `failed` has `reason`. `SyncEngine.run(baseUrl, publicKey, store)` fetches `bundle.json` and `bundle.json.sig`, verifies the signature before parsing, refuses a base URL that does not start with `https://`, downloads only documents whose `fileChecksum` changed, and installs through `ContentStore.install`. `RetryPolicy.delayBeforeRetry` returns null for `rejectedSignature`, `upToDate`, and `updated`.

- [ ] **Step 1: Write the failing test** over every `contract/fixtures/*` directory, honouring `expect.json` `prior`, `result`, `reasonContains`, and `documentsDownloaded`. Use an in-memory client. No sockets.
- [ ] **Step 2: Run** `dart test`. Expected: FAIL.
- [ ] **Step 3: Implement** by porting `apps/mobile/src/core/sync/engine.ts` and `client.ts`.
- [ ] **Step 4: Run** `dart test`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(flutter): sync engine passes the fixture contract`.

### Task 8: UI, localisation, accessibility, settings

**Files:**
- Create: `apps/flutter/lib/screens/home.dart`, `search.dart`, `answer.dart`, `settings.dart`, `about.dart`, `l10n/app_en.arb`, `app_es.arb`, `app_fr.arb`
- Test: `apps/flutter/test/screens_test.dart`, `apps/flutter/test/strings_test.dart`
- Modify: `packages/sheltr_core` settings types, ported from `apps/mobile/src/core/settings`

**Interfaces:**
- Produces: screens for the 12 hazards, search, answer, settings, and about. `strings_test.dart` fails if any key or placeholder in `contract/strings.tsv` is missing from a locale. Settings: country null or two uppercase letters, language en/es/fr, text scale one of `1.0`, `1.2`, `1.4`, sync frequency manual/daily/weekly/monthly, `allowMobileData` default false. Damaged stored JSON falls back field by field.

- [ ] **Step 1: Write the failing tests.** `unsupportedDeviceLanguageShowsEnglish`, `damagedStoredSettingsFallBackToDefaults`, `largestTextScaleKeepsEveryControlReachable` at text scale `1.4`, `everyButtonHasASemanticsLabel`, `answerShowsAgencyTitleDatePageAndLink`, `refusalShowsOwnText`, `homeShowsTwelveHazards`, `searchSubmitDisabledWhenBlank`.
- [ ] **Step 2: Run** `flutter test`. Expected: FAIL.
- [ ] **Step 3: Implement** the screens against `sheltr_core`. Copy the English strings from `apps/mobile/src/i18n/locales/en.json` and the existing Spanish and French translations. Wire `MediaQuery.textScaler` from the setting. Read-aloud uses the platform text-to-speech plugin already chosen in this task's commit message. Record the plugin name and version there.
- [ ] **Step 4: Run** `flutter test`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(flutter): screens, localisation, and accessibility`.

### Task 9: When a sync is due

**Files:**
- Create: `packages/sheltr_core/lib/settings/sync_due.dart`, `apps/flutter/lib/sync/scheduler.dart`
- Test: `packages/sheltr_core/test/sync_due_test.dart`

**Interfaces:**
- Produces: `DateTime? nextSyncDue(AppSettings settings, DateTime? lastSync, DateTime now)`. Manual frequency returns null. Otherwise the interval is 86400, 604800, or 2592000 seconds. The Flutter scheduler asks this function on launch and registers a best-effort background callback with the same interval. The background callback is not covered by a unit test. The release checklist runs it on a device.

- [ ] **Step 1: Write the failing tests** by porting the cases in `apps/mobile/src/core/settings/settings.test.ts` for `nextSyncDue`, including the unmetered-network boundary the existing tests cover.
- [ ] **Step 2: Run** `dart test`. Expected: FAIL.
- [ ] **Step 3: Implement** `nextSyncDue`. The app calls it from the launch path. Background registration may no-op in tests.
- [ ] **Step 4: Run** `dart test` and `flutter test`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(flutter): sync schedule on launch`.

## Phase C: bundle format 2

### Task 10: Publisher sequence, review dates, and expiry

**Files:**
- Modify: `tools/publisher/src/bundle.ts`, `tools/publisher/src/manifest.ts`, `corpus/manifest.yaml`, `contract/bundle-format.md`
- Create: fixtures `downgrade-older-sequence`, `same-sequence`, `expired-document` by extending `fixtures.ts`
- Test: publisher tests named below

**Interfaces:**
- Produces: `BUNDLE_FORMAT_VERSION = 2`. Bundle field `sequence` is an integer, strictly increasing per publication via `--previous <bundle.json>`. Each manifest entry requires `reviewed_on`, `expires_on`, and `reviewed_by`. The publisher refuses a missing date and refuses an `expires_on` already past `now`. Document fields on the wire are `reviewedOn` and `expiresOn`, ISO dates. An optional `model` object is specified in `bundle-format.md` and omitted by the publisher until Task 15: `{ file, sha256, byteSize, modelType }` where `modelType` is one of `gemma4`, `gemmaIt`, `qwen`, `qwen3`, `general`.

- [ ] **Step 1: Write the failing tests** `refusesEntryWithoutReviewDates`, `refusesExpiredEntry`, `sequenceMustExceedPreviousPublished`.
- [ ] **Step 2: Run** `cd tools/publisher && npm test`. Expected: FAIL.
- [ ] **Step 3: Implement** the checks and the new fields. Regenerate fixtures once, including the three new directories. `downgrade-older-sequence` and `same-sequence` use `prior: valid-signed` and `result: failed` with `reasonContains: "older"`. `expired-document` is a bundle the app still installs. Expiry is a warning, not a sync failure. Its `expect.json` is `prior: empty`, `result: updated`.
- [ ] **Step 4: Run** the publisher tests and the Expo contract test. Update the Expo parser enough that the oracle stays green on format 2. Expected: PASS.
- [ ] **Step 5: Commit** `feat!: bundle format 2 with sequence and review dates`.

### Task 11: Flutter understands format 2

**Files:**
- Modify: `packages/sheltr_core` bundle, store, and sync. `apps/flutter/lib/screens/answer.dart` and `about.dart`.
- Test: extend `sync_engine_test.dart` and `screens_test.dart`

**Interfaces:**
- Produces: the store keeps `sequence`. Sync rejects `sequence <= installed` with `failed` and a reason containing `older`. `Staleness` is `fresh`, `expiringSoon`, or `expired`. `expiringSoon` when a document expires within 30 days or the last successful sync is older than 90 days. `expired` when a document's `expiresOn` is before the device date. The answer screen still shows passages in all three states. The warning copy is a new key added to `contract/strings.tsv` and all three locales in this task. If the device date is earlier than the bundle's published timestamp, show the warning `answer_clock_unsure` and do not treat documents as expired.

- [ ] **Step 1: Write the failing tests** for the three new fixtures, `answerShowsExpiredBanner`, `answerShowsPassagesWhenExpired`, `aboutListsReviewDateAndExpiry`, `deviceDateBeforePublishShowsClockWarning`.
- [ ] **Step 2: Run** `dart test` and `flutter test`. Expected: FAIL.
- [ ] **Step 3: Implement** the sequence check and the staleness warning. Do not block `retrieve` on staleness.
- [ ] **Step 4: Run** the tests. Expected: PASS.
- [ ] **Step 5: Commit** `feat(flutter): reject downgrades and warn on stale content`.

### Task 12: Key rotation

**Files:**
- Modify: publisher bundle writer (`signedBy` key id), both apps' trusted-key list, `docs/publishing.md`, fixtures `rotated-key-valid` and `revoked-key`
- Test: publisher, Expo contract test, `sheltr_core` sync test

**Interfaces:**
- Produces: a compiled list of at most two keys, `{ id, publicKeyHex, notAfter }`. A bundle is accepted when `signedBy` names a key on that list and the signature verifies under it. An unknown id is `rejectedSignature`. A listed key past `notAfter` still verifies. The About screen shows `about_key_date_passed`. A key removed from the compiled list is rejected.

- [ ] **Step 1: Write the failing tests** `rotatedKeyBundleVerifies`, `unknownKeyIdRejected`, `listedKeyPastNotAfterStillInstallsAndWarns` in the publisher, the Expo contract test, and `sheltr_core`.
- [ ] **Step 2: Run** each suite. Expected: FAIL.
- [ ] **Step 3: Implement** publisher first, regenerate the two fixtures once, then Expo and Flutter.
- [ ] **Step 4: Run** all three suites. Expected: PASS.
- [ ] **Step 5: Commit** `feat: key ids with a date warning`.

### Task 13: Cited action cards

**Files:**
- Create: `corpus/cards/<hazard>-<phase>.yaml` only as examples the publisher tests use under `tools/publisher`. Do not add production cards in this task. `tools/publisher/src/cards.ts`. Fixtures `cards-valid` and `cards-uncited-step`.
- Modify: bundle format `cards[]`, the Flutter home screen, `docs/publishing.md`

**Interfaces:**
- Produces: card `{ id, hazard, phase: before|during|after, country, title, steps: [{ text, documentId, chunkId }] }`. The publisher refuses a step whose chunk is not in the bundle or whose text is not a verbatim substring of that chunk. The home screen lists cards for a hazard with the step text, a citation, and read-aloud. Zero cards shows the hazard grid as it does today.

- [ ] **Step 1: Write the failing tests** `refusesStepWithNoCitation`, `refusesStepNotQuotedFromItsChunk`, `acceptsVerbatimCitedStep`, plus a Flutter test that `cards-valid` renders the step and its source.
- [ ] **Step 2: Run** the publisher tests and `flutter test`. Expected: FAIL.
- [ ] **Step 3: Implement.** Content authoring stays out of this commit. The sample card lives in the fixture only.
- [ ] **Step 4: Run** the tests. Expected: PASS.
- [ ] **Step 5: Commit** `feat: cited action cards`.

## Phase D: LiteRT-LM

The engine package is the successor of `flutter_gemma`, which is the package Google documents for Flutter. On 2026-11-02, `flutter_gemma` 1.11.4 says it has moved, and `flutter_edge_ai` 2.5.5 plus `flutter_edge_ai_litertlm` 1.19.1 are the current releases. Google's page `https://developers.google.com/edge/litert-lm/flutter` still names `flutter_gemma` and was last updated 2026-06-01. Re-read both pages at the start of Task 15. If pub.dev has a newer patch of these two packages, pin that patch in the commit message and keep the support-check tests. Do not add `flutter_edge_ai_mediapipe`, `flutter_edge_ai_rag`, `flutter_edge_ai_speech`, `flutter_edge_ai_agent`, or `flutter_litert_lm`.

### Task 14: Passage rewriter, with the model flag off

**Files:**
- Create: `packages/sheltr_core/lib/model/rewriter.dart`, `contract/model-cases.tsv`
- Test: `packages/sheltr_core/test/rewriter_test.dart`
- Modify: `apps/flutter/lib/screens/answer.dart`

**Interfaces:**
- Produces:
  - `abstract class PassageModel { Future<String> rewrite(String question, List<CitedPassage> passages); }`
  - `bool modelTextIsSupported(String modelText, List<CitedPassage> passages)`
  - `AnswerPresentation present({required RetrievalResult retrieval, String? modelText, required bool modelEnabled})`
- `modelTextIsSupported` is false when the trimmed text is empty or `NONE`, when it is longer than 600 characters, when any maximal digit sequence in the model text is absent from the cited passage texts, or when any remaining word of 4 or more letters, after the retrieval stopword list is removed, fails `sameWord` against every word in those passages. Otherwise true.
- `present` never calls a model when retrieval is `refuse` or when `modelEnabled` is false. When the support check fails, `lead` is null and `passages` is the retrieval passages. When it passes, `lead` is the model text and `passages` is unchanged. The default of `modelEnabled` in the app settings is false.

- [ ] **Step 1: Write `contract/model-cases.tsv` and the failing test** that reads it. Required rows: a shortened sentence using only passage words, expect `show`; a reply that adds a content word absent from the passages, expect `hide`; a reply that changes `72` to `3`, expect `hide`; `NONE`, expect `hide`; a reply longer than 600 characters, expect `hide`; a retrieval refusal, expect no model call. A widget test asserts the lead is absent while the flag is false even if a fake model would return supported text.
- [ ] **Step 2: Run** `dart test` and `flutter test`. Expected: FAIL.
- [ ] **Step 3: Implement** the check and the presentation. The answer screen takes `AnswerPresentation`. The fake model is a test double. No LiteRT import in `sheltr_core`.
- [ ] **Step 4: Run** the tests. Expected: PASS.
- [ ] **Step 5: Commit** `feat(flutter): hide unsupported model text`.

### Task 15: LiteRT-LM adapter and a signed model file

**Files:**
- Create: `apps/flutter/lib/model/litert_passage_model.dart`
- Modify: `apps/flutter/pubspec.yaml`, `packages/sheltr_core` sync so an optional model file is downloaded after a successful content install, `contract/bundle-format.md` if Task 10's wording needs the download rule
- Test: `apps/flutter/test/litert_passage_model_test.dart` with a fake `FlutterEdgeAi` wrapper. The real engine is not started in `flutter test`.

**Interfaces:**
- Produces: `LitertPassageModel implements PassageModel`. Construction takes a local file path that has already passed the SHA-256 in the signed manifest. The adapter calls:

```dart
await FlutterEdgeAi.initialize(
  inferenceEngines: const [LiteRtLmEngine()],
);
await FlutterEdgeAi.installModel(
  modelType: modelTypeFromManifest,
  fileType: ModelFileType.litertlm,
).fromFile(verifiedPath).install();
final model = await FlutterEdgeAi.getActiveModel(
  maxTokens: 256,
  preferredBackend: PreferredBackend.cpu,
);
```

- `modelTypeFromManifest` maps the five strings from Task 10 onto `ModelType`. Any other string leaves the model off.
- The session system instruction is the constant: `Restate only the quoted passages in plain language. Use no fact that is not in the passages. If the passages do not answer the question, reply with the single word NONE.`
- The adapter passes no tools, no images, and no audio. It does not enable thinking mode. It closes the session in a `finally` block.
- Sync: after content is installed, if `model` is present, download `${baseUrl}/${model.file}` over HTTPS, check the length and SHA-256, and move it into place with a staging rename. A model download or checksum failure returns the content `SyncResult` unchanged and leaves any previous verified model file in place. It does not roll back the content install.
- `fromNetwork` and Hugging Face helpers are not called. A test greps `apps/flutter/lib` for `fromNetwork` and `huggingface` and fails on a hit.

- [ ] **Step 1: Write the failing tests** `checksumMismatchLeavesPreviousModel`, `modelDownloadFailureDoesNotChangeContentResult`, `unknownModelTypeStaysOff`, `adapterSendsPassagesAndNoTools` (the fake records the session instruction and that no tool list was set), and the grep test.
- [ ] **Step 2: Run** `flutter test`. Expected: FAIL.
- [ ] **Step 3: Implement** the adapter and the download. Pin `flutter_edge_ai` 2.5.5 and `flutter_edge_ai_litertlm` 1.19.1 unless pub.dev shows a newer patch of the same majors. Record the resolved versions in the commit message.
- [ ] **Step 4: Run** `flutter test` and `dart test`. Expected: PASS. Do not download a model in CI.
- [ ] **Step 5: Commit** `feat(flutter): load a signed LiteRT-LM file on CPU`.

### Task 16: Device eval, flag still off

**Files:**
- Modify: `docs/evaluation.md`
- Create: `docs/model-eval.md`

**Interfaces:**
- Produces: a written procedure, not a default-on flag. The maintainer places one `.litertlm` file outside the repo, publishes it through the bundle host with the signed checksum, and runs the answerable rows of `contract/retrieval-cases.tsv` on one Android phone and one iPhone. For each row, record whether a lead was shown and whether `modelTextIsSupported` was true. The run passes only when every shown lead passed the support check, retrieval refusals showed no lead, and the process was not killed. `docs/model-eval.md` has a table with device, OS, file sha256, byte size, licence name, and pass or fail. The settings default stays false. This task's commit does not change that default.

- [ ] **Step 1: Write the failing test** `modelFlagDefaultsOff` if Task 14's widget test does not already assert the settings default. Add a test that `docs/model-eval.md` exists and contains the column names `device`, `sha256`, `result`.
- [ ] **Step 2: Run** `flutter test`. Expected: FAIL until the doc exists.
- [ ] **Step 3: Write** `docs/model-eval.md` with the procedure and an empty result table. Extend `docs/evaluation.md` with a pointer to it. Do not claim a device run that has not happened.
- [ ] **Step 4: Run** `flutter test`. Expected: PASS.
- [ ] **Step 5: Commit** `docs: record how a LiteRT-LM file is accepted`.

## Phase E: release

### Task 17: Release checklist, then retire the Expo app

**Files:**
- Create: `docs/release-checklist.md`, `contract/check-coverage.mjs`
- Modify: `.github/workflows/*`, `docs/publishing.md`, `docs/store/listing.md`, `README.md`, `CONTRIBUTING.md`, `CLAUDE.md`, `SECURITY.md`
- Delete: `apps/mobile/` only in a commit that comes after the checklist file is ticked by a human. If the checklist is not done, this task stops after the docs and the coverage script. It does not delete the Expo app.

**Interfaces:**
- Produces: CI jobs `publisher`, `flutter` (`dart test`, `flutter test`, the coverage script), on `ubuntu-latest`. `contract/check-coverage.mjs` fails when a directory in `contract/fixtures` has no reference under `apps/flutter` or `packages/sheltr_core`. The checklist names: independent review of signature and sync code, the support-check tests, one Android phone and one iPhone run of search and a forced stale bundle, a model eval either passed or explicitly left off, sideloadable Android build, store data-safety answers. iOS background refresh is checked on the iPhone run and may be late. That does not fail the checklist.

- [ ] **Step 1: Write the failing check** `contract/check-coverage.mjs` and a test or a dry run that fails while a fixture name is missing from the Flutter tree.
- [ ] **Step 2: Run** it. Expected: FAIL until every fixture directory name appears in a Flutter test.
- [ ] **Step 3: Wire** it into the workflow. Write `docs/release-checklist.md`. Update the docs that describe the app as Expo so they describe the Flutter app and point at `apps/mobile` as the oracle that remains until the checklist is done.
- [ ] **Step 4: Run** publisher tests, `dart test`, and `flutter test`. Expected: PASS.
- [ ] **Step 5: Commit** `chore: release checklist for the Flutter app`. Do not delete `apps/mobile` in this commit.

## Not in this plan

Corpus re-approval and expansion. `THIRD_PARTY_CONTENT.md` lists sources cleared to add (New Zealand Get Ready, the Queensland severe-weather guide, the IFRC 2018 key messages, Ready.gov, and the Canadian preparedness guide). None of them is in `corpus/manifest.yaml`. Adding one is a separate change with its own checksum. Expert content review, writing the first production action cards, store accounts and submissions, GPU or NPU backends, embeddings and RAG, speech-to-text, tool calling, and turning the model flag on stay out. Turning the flag on is a separate commit made after `docs/model-eval.md` shows a pass on both phones. Live hazard alerts stay out.
