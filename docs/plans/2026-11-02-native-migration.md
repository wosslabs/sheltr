# Native Apps Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Expo app with two native apps, Android (Kotlin, Jetpack Compose) and iOS (Swift, SwiftUI), that behave identically because both pass one shared contract, and add the life-safety features the contract needs.

**Architecture:** No shared runtime code. A `contract/` folder is the single source of truth: the bundle format, the retrieval rules and word lists, signed test bundles, and question cases with expected answers. The publisher (TypeScript, unchanged) generates the fixtures; the Expo app is kept as a reference implementation until both native apps pass the same contract, then deleted. Safety features (downgrade protection, content expiry, action cards, key rotation) come after parity so parity failures and feature failures never mix.

**Tech Stack:** Android: Kotlin 2.x, Jetpack Compose, SQLite with FTS5 (a bundled build if the platform lacks it), Tink for Ed25519, WorkManager, OkHttp. iOS: Swift 6, SwiftUI, GRDB (SQLite with FTS5), CryptoKit, BGTaskScheduler, URLSession. Publisher: Node 24, TypeScript. CI: GitHub Actions.

**Spec:** `docs/design/offline-mobile-app.md`, `docs/adr/0015-offline-mobile-publisher-no-server.md`, `docs/adr/0016-expo-react-native-app.md` (superseded in part by the ADR written in Task 1), `docs/evaluation.md`.

## Global Constraints

- No server, no accounts, no analytics, no tracking SDK. The only network request is fetching the signed bundle over HTTPS. HTTP is refused.
- The app answers only from approved documents, shows agency, title, date, page and link for every passage, and refuses when no passage passes the relevance check. It never generates guidance.
- Relevance rules (shared through `contract/retrieval-rules.json`): minimum keyword score `1.0` (negated FTS5 bm25); a passage must contain `60%` of the question's topical words (word forms match when they share a prefix of at least 4 letters covering all but one letter of the shorter word, and two words that each have exactly one letter left after the shared prefix differ); country boost `+0.15`, hazard boost `+0.15`, boosts only reorder.
- Bundle format version `1` until Task 14; `2` afterwards. An app reads exactly its own version and rejects others with a clear message.
- An update is all-or-nothing: on any failure, including a process kill mid-install, the previous content stays active and unchanged.
- Signatures: Ed25519, raw 32-byte public key compiled into the app, raw 64-byte signature of the exact bytes of `bundle.json`, strict RFC 8032 verification.
- Android: minSdk 24, the current stable compileSdk and AGP at execution time, Kotlin core module with no Android imports so it runs in plain JVM tests. iOS: iOS 16 minimum, core in a Swift package with no UIKit or SwiftUI imports.
- Languages: English, Spanish, French (same keys on both platforms). Text scale factors `1.0`, `1.2`, `1.4`. Touch targets at least 48 dp / 44 pt. Every control has a screen-reader label.
- Licence: Apache-2.0. Corpus text keeps its own licence (`THIRD_PARTY_CONTENT.md`).
- Every task ends with its tests passing in CI and a commit. No task leaves another platform's tests red.

## Review Focus

1. **Replayed old signed bundle.** An attacker serves a genuine but older signed bundle. Expected: rejected as a downgrade, content unchanged (Task 14 pins it for both apps and the publisher).
2. **Install interrupted or disk full.** Process killed or storage exhausted mid-install. Expected: the old content still answers, no partial rows, no leftover staging file after restart (Tasks 5 and 11).
3. **Odd questions.** Empty text, only stopwords, only punctuation, non-Latin script, a 5,000-character paste, FTS5 operators in the text. Expected: refusal or an answer, never a crash or a syntax error (Tasks 4, 5, 11).
4. **Unsupported or missing settings.** Device language not en/es/fr, country unset, damaged stored settings. Expected: English UI, no country boost, defaults for any damaged field (Tasks 8 and 13).
5. **Stale content with no network.** Content past its expiry, or never synced for a long time, offline. Expected: answers still work and a visible staleness warning shows, in both apps (Task 14).

## File Structure

```
contract/
  README.md                     how to run the contract in each platform
  bundle-format.md              layout, field rules, version rules (human readable)
  retrieval-rules.json          thresholds, boosts, stopwords, synonyms, hazard terms, hazard order
  retrieval-cases.tsv           question, country, hazards -> answerable | refuse (+ expected term)
  fixtures/<name>/              bundle.json, bundle.json.sig, documents/, public-key.hex, expect.json
apps/android/
  core/                         pure Kotlin library (bundle, signing, store interface, retrieval, sync, settings)
  app/                          Compose UI, Room-free SQLite store, WorkManager, resources en/es/fr
apps/ios/
  SheltrCore/                   Swift package (same layers)
  Sheltr/                       SwiftUI app, Localizable strings en/es/fr
tools/publisher/                unchanged except Tasks 1 and 14-16
apps/mobile/                    Expo app: kept until Task 17, then deleted
docs/                           ADR-0017, publishing, evaluation, store listings updated
```

`expect.json` is `{"result": "updated" | "rejectedSignature" | "failed" | "upToDate", "reasonContains": string | null}` and tells a test what the sync engine must return for that fixture.

## Phase A: contract

### Task 1: Contract folder and generated fixtures

**Files:**
- Create: `contract/README.md`, `contract/bundle-format.md`, `contract/retrieval-rules.json`, `contract/retrieval-cases.tsv` (moved from `apps/mobile/src/core/testing/retrieval-eval.tsv`), `tools/publisher/src/fixtures.ts`, `tools/publisher/src/fixtures.test.ts`, `docs/adr/0017-native-apps.md`
- Modify: `tools/publisher/package.json` (script `fixtures`), `apps/mobile/src/core/retrieval/*` (read the rules file in tests only, see Task 2)

**Interfaces:**
- Produces: `contract/fixtures/<name>/` for these names: `valid-signed`, `valid-unchanged-doc` (second bundle that reuses one document), `tampered-manifest`, `wrong-key`, `bad-file-checksum`, `missing-document`, `unsupported-format`, `plain-http-url` (expect `failed`, `reasonContains` "https"). Each has `expect.json`. Produces `npm run fixtures` in `tools/publisher`.
- `retrieval-rules.json` keys: `relevanceThreshold`, `minTermCoverage`, `countryBoost`, `hazardBoost`, `poolMultiplier`, `stopwords[]`, `synonyms{}`, `hazardTerms[]`, `disasterTypes[]`.

- [ ] **Step 1: Write the failing test** `fixtures.test.ts`: `it('generates every named fixture with an expect.json')` asserting the eight directories exist and each `expect.json` parses to the shape above; `it('valid-signed verifies under its public-key.hex')` using the publisher's `verify`; `it('tampered-manifest does not verify')`; `it('regenerating produces a coherent set')` (run generation twice into temp dirs, every fixture's signature verifies under its own `public-key.hex`).
- [ ] **Step 2: Run** `cd tools/publisher && npm test -- --test-name-pattern fixtures` — Expected: FAIL (module not found).
- [ ] **Step 3: Implement** `generateFixtures(outDir: string, now: () => Date): Promise<void>` in `fixtures.ts`, building bundles with `buildBundle` and a freshly generated key per run (the private key is never written to disk; only `public-key.hex` and the signature are), then deriving the broken variants by editing bytes after signing.
- [ ] **Step 4: Run** the publisher tests — Expected: PASS. Run `npm run fixtures` once and commit the output.
- [ ] **Step 5: Write `retrieval-rules.json`** from the constants currently in `apps/mobile/src/core/retrieval` (values in Global Constraints), and `bundle-format.md` from `docs/design/offline-mobile-app.md` section 4 and `src/bundle.ts` comments. Write ADR-0017: native apps, contract-based parity, Expo app kept as oracle until Task 17.
- [ ] **Step 6: Commit** `feat: add the cross-platform contract and generated fixtures`.

### Task 2: Prove the Expo app already satisfies the contract

**Files:**
- Create: `apps/mobile/src/core/contract.test.ts`
- Modify: `apps/mobile/src/core/retrieval/stopwords.ts`, `vocabulary.ts`, `boost.ts`, `retriever.ts` (load values from `contract/retrieval-rules.json` instead of literals)

**Interfaces:**
- Consumes: Task 1 fixtures and rules.
- Produces: the proof that the contract equals current, shipped-quality behaviour; every native task's parity tests mirror this file.

- [ ] **Step 1: Write the failing test** `contract.test.ts`: for each fixture directory, serve its files through a mock `SyncClient`, run `SyncEngine` with `public-key.hex`, assert `result.kind` equals `expect.json.result` and the reason contains `reasonContains`; and `it('answers every case in retrieval-cases.tsv as expected')` over the sample bundle, asserting zero false accepts and at least 90% answered (same bars as `docs/evaluation.md`).
- [ ] **Step 2: Run** `cd apps/mobile && npx jest src/core/contract.test.ts` — Expected: FAIL until fixtures path and rules loading exist.
- [ ] **Step 3: Implement** loading of the rules JSON in the four retrieval files (import the JSON; Metro and Jest both support it). Remove the duplicated literals.
- [ ] **Step 4: Run** `npm test` in `apps/mobile` — Expected: all prior tests plus the contract tests PASS.
- [ ] **Step 5: Commit** `test: the Expo app passes the shared contract`.

## Phase B: Android

### Task 3: Android project from history, split into core and app

**Files:**
- Create: `apps/android/` by restoring `apps/mobile/shared` and `apps/mobile/android` from commit `ae9fbaaf` (33 main files, 38 tests) into `apps/android/core` and `apps/android/app`, plus `settings.gradle.kts`, `gradle/` wrapper, `.github/workflows/android.yml`
- Modify: package names `com.sheltr.shared` -> `com.sheltr.core`; remove the Kotlin Multiplatform plugin and the iOS and JVM targets: `core` becomes an Android-independent Kotlin/JVM library module

**Interfaces:**
- Produces: Gradle modules `:core` (Kotlin JVM library, no `android.*` imports) and `:app`. `./gradlew :core:test :app:testDebugUnitTest` is the single check.

- [ ] **Step 1: Restore** with `git restore --source=ae9fbaaf -- apps/mobile/shared apps/mobile/android`, move the folders to `apps/android/{core,app}`, adjust the Gradle files. Do not touch `apps/mobile` (Expo).
- [ ] **Step 2: Write the failing check** — an ArchUnit-style test `CoreHasNoAndroidImportsTest` in `:core` that scans compiled classes and fails on any `android.` reference.
- [ ] **Step 3: Run** `./gradlew :core:test` — Expected: FAIL until the Android-specific files (settings DataStore, store driver factory, file helpers) are moved to `:app` behind interfaces `SettingsStore`, `StoreDriverFactory`, `FileSystem`.
- [ ] **Step 4: Implement the move**; keep every restored test passing.
- [ ] **Step 5: Add the workflow** running both Gradle tasks; **Run** `./gradlew :core:test :app:testDebugUnitTest` — Expected: PASS.
- [ ] **Step 6: Commit** `feat(android): restore the Android app as core and app modules`.

### Task 4: Android core passes the contract; rules come from the contract

**Files:**
- Create: `apps/android/core/src/test/kotlin/com/sheltr/core/ContractFixturesTest.kt`, `ContractRetrievalCasesTest.kt`, `core/src/main/kotlin/com/sheltr/core/retrieval/Rules.kt`
- Modify: `Retriever.kt`, `Stopwords.kt`, `HazardVocabulary.kt`, `Boost.kt`

**Interfaces:**
- Produces: `object Rules { val relevanceThreshold: Double; val minTermCoverage: Double; val countryBoost: Double; val hazardBoost: Double; val stopwords: Set<String>; val synonyms: Map<String,String>; val hazardTerms: Set<String>; val disasterTypes: List<String> }` loaded from `contract/retrieval-rules.json` (copied to test and app resources by a Gradle sync task, with a test that the copy is identical to `contract/`).

- [ ] **Step 1: Write failing tests**: `ContractFixturesTest` (a parameterised test over every `contract/fixtures/*`, asserting the `SyncResult` kind and reason like Task 2) and `ContractRetrievalCasesTest` (zero false accepts, at least 90% answered, same cases file), plus `sameWordDoesNotMatchFloodAndFloor`, `emptyAndPunctuationQueriesRefuse`, `ftsOperatorsInTextAreNotInterpreted`, `fiveThousandCharacterQuestionDoesNotFail`.
- [ ] **Step 2: Run** `./gradlew :core:test` — Expected: FAIL.
- [ ] **Step 3: Implement** `Rules` loading and replace literals; port the one-letter-substitution rule for `sameWord` from `apps/mobile/src/core/retrieval/retriever.ts`.
- [ ] **Step 4: Run** `./gradlew :core:test` — Expected: PASS.
- [ ] **Step 5: Commit** `feat(android): core satisfies the contract`.

### Task 5: Android store: FTS5 everywhere and interruption safety

**Files:**
- Modify: `app/.../store/AndroidStoreDriverFactory.kt`, `core/.../store/ContentStore.kt`
- Test: `core/.../ContentStoreInterruptionTest.kt`, `app/src/androidTest/.../FtsAvailabilityTest.kt`

**Interfaces:**
- Consumes: `StoreDriverFactory.open(path: String, create: Boolean): SqlDriver` (existing).
- Produces: unchanged `ContentStore.install(manifest, documents)` semantics, now with a documented guarantee: a staging file is never the active file, and a leftover staging file is deleted on the next open.

- [ ] **Step 1: Write failing tests**: `killDuringInstallLeavesOldContentActive` (a `DocumentSource` that throws on the second document and a second variant that closes the driver midway), `leftoverStagingFileIsDeletedOnOpen`, `installFailsCleanlyWhenWritesFail` (a `StoreDriverFactory` whose driver throws on the Nth statement), and an instrumented `ftsIsAvailableOnMinSdk` creating an FTS5 table on an API 24 emulator image.
- [ ] **Step 2: Run** the JVM tests — Expected: FAIL for any gap; the instrumented test runs in CI on an emulator matrix (API 24, 30, 35).
- [ ] **Step 3: Implement** fixes; keep the bundled `sqlite-android` build only if the API 24 emulator lacks FTS5.
- [ ] **Step 4: Run** all; PASS. **Step 5: Commit** `feat(android): interruption-safe store, FTS5 on every supported API level`.

### Task 6: Android signature verification on minSdk 24

**Files:**
- Create: `core/.../signing/SignatureVerifier.kt` (replace the multiplatform crypto dependency with Tink or BouncyCastle, chosen by a one-hour spike recorded in the commit message)
- Test: `core/.../SignatureVerifierTest.kt`

**Interfaces:**
- Produces: `fun verifySignature(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean` (32-byte key, 64-byte signature, false for any malformed input, never throws).

- [ ] **Step 1: Write failing tests**: `rfc8032Vector1Verifies`, `highSScalarIsRejected`, `thirtyThreeByteKeyIsRejected`, `derKeyIsRejected`, and the contract fixture `valid-signed` verifies while `tampered-manifest` and `wrong-key` do not.
- [ ] **Step 2: Run** — Expected: FAIL. **Step 3: Implement** with the chosen library using strict (non-ZIP215) verification.
- [ ] **Step 4: Run** — PASS. **Step 5: Commit** `feat(android): Ed25519 verification on API 24+`.

### Task 7: Android sync, scheduling and network policy

**Files:**
- Modify: `core/.../sync/SyncEngine.kt` (from history), `app/.../sync/SyncWorker.kt` (new), `AndroidManifest.xml`
- Test: `core/.../SyncEngineContractTest.kt`, `app/src/test/.../SyncWorkerTest.kt`

**Interfaces:**
- Produces: `class SyncWorker : CoroutineWorker` scheduled by `fun scheduleSync(context: Context, settings: AppSettings)` with `NetworkType.UNMETERED` unless `allowMobileData`, `RetryPolicy` back-off, and unique work name `sheltr-sync`.

- [ ] **Step 1: Write failing tests**: sync-engine cases from `fixtures/` served by OkHttp `MockWebServer`; `workerRunsOnlyOnUnmeteredWhenMobileDataOff`, `manualFrequencyCancelsWork`, `rejectedSignatureIsNotRetried`, `failedSyncRetriesWithBackoff` using `WorkManagerTestInitHelper`.
- [ ] **Step 2-5:** run (FAIL), implement, run (PASS), commit `feat(android): background sync with WorkManager`.

### Task 8: Android UI parity, accessibility and settings resilience

**Files:**
- Modify: the Compose screens restored from history (Home, Search, Answer, Settings, About); `res/values{,-es,-fr}/strings.xml`
- Test: `app/src/test/.../*ScreenTest.kt` (Robolectric), `StringResourceParityTest.kt`

**Interfaces:**
- Consumes: Task 4 `Retriever`, Task 7 `scheduleSync`. Produces: the screens and string keys listed in `contract/README.md` (the key list is the cross-platform string contract; Task 12 checks iOS against it).

- [ ] **Step 1: Write failing tests**: `unsupportedDeviceLanguageShowsEnglish`, `damagedStoredSettingsFallBackToDefaults`, `largestTextScaleKeepsEveryControlReachable`, `everyButtonHasAContentDescription`, `answerShowsAgencyTitleDatePageAndLink`, `refusalShowsOwnTextAndTheEmergencyNumberHint`.
- [ ] **Step 2-5:** run, implement, run, commit `feat(android): UI parity and accessibility checks`.

## Phase C: iOS

### Task 9: iOS project: Swift package core plus SwiftUI app

**Files:**
- Create: `apps/ios/SheltrCore/Package.swift`, `apps/ios/Sheltr.xcodeproj` (or an XcodeGen `project.yml`, committed), `.github/workflows/ios.yml` (macOS runner, `xcodebuild test`)

**Interfaces:**
- Produces: package `SheltrCore` (no UIKit/SwiftUI import) and app target `Sheltr`. Prerequisite on the dev machine: an iOS simulator runtime (`xcodebuild -downloadPlatform iOS`).

- [ ] **Step 1: Write the failing check** `CoreImportsTests` that greps the package sources for `import UIKit` and `import SwiftUI`.
- [ ] **Step 2-5:** run (FAIL), scaffold, run, commit `feat(ios): project skeleton`.

### Task 10: iOS bundle parsing, checksums, CryptoKit verification, contract fixtures

**Files:**
- Create: `SheltrCore/Sources/SheltrCore/Bundle/{BundleFormat,BundleParser,Sha256}.swift`, `Signing/SignatureVerifier.swift`; Test: `ContractFixturesTests.swift`, `SignatureVerifierTests.swift`, `BundleParserTests.swift`

**Interfaces:**
- Produces: `func verifySignature(publicKey: Data, message: Data, signature: Data) -> Bool`, `func parseBundleManifest(_ text: String) throws -> BundleManifest`, `func verifyDocumentFile(_ doc: BundleDocument, bytes: Data) throws`; same error messages as Android and the contract.

- [ ] **Step 1: Write failing tests** mirroring Task 6 (RFC vector, high-S, wrong length, DER key) and Task 2's fixture expectations for parse errors (`unsupported-format`, `bad-file-checksum`).
- [ ] **Step 2-5:** run, implement with `Curve25519.Signing.PublicKey.isValidSignature`, run, commit `feat(ios): bundle format and signature verification`.

### Task 11: iOS store (GRDB FTS5) and retrieval parity

**Files:**
- Create: `SheltrCore/Sources/SheltrCore/Store/ContentStore.swift`, `Retrieval/{Rules,Retriever,Stopwords,Citations}.swift`; Test: `ContentStoreTests.swift`, `RetrievalCasesTests.swift`

**Interfaces:**
- Produces: `final class ContentStore { init(path: String) throws; func install(_ manifest: BundleManifest, documents: (BundleDocument) throws -> DocumentFile) throws; func search(_ query: String, limit: Int) throws -> [SearchHit]; func bundleVersion() throws -> String?; func documents() throws -> [StoredDocument] }` and `struct Retriever { func retrieve(_ query: RetrievalQuery) throws -> RetrievalResult }`; `Rules` loaded from the same `retrieval-rules.json` (copied into the package's test and app resources by a build phase, with a test that the copy equals `contract/`).

- [ ] **Step 1: Write failing tests**: `killDuringInstallKeepsOldContent` (throwing document source and a transaction abort), `leftoverStagingFileRemovedOnOpen`, the same four odd-question tests as Task 4, and `RetrievalCasesTests` (zero false accepts, at least 90% answered).
- [ ] **Step 2-5:** run, implement, run, commit `feat(ios): store and retrieval pass the contract`.

### Task 12: iOS sync and background scheduling

**Files:**
- Create: `SheltrCore/.../Sync/{SyncClient,SyncEngine,RetryPolicy}.swift`, `Sheltr/Background/SyncScheduler.swift`; Test: `SyncEngineContractTests.swift` (URLProtocol stub), `RetryPolicyTests.swift`

**Interfaces:**
- Produces: `func registerSyncTask()` for `BGAppRefreshTask` identifier `org.sheltr.sync` (declared in Info.plist), `func scheduleNextSync(settings: AppSettings, lastSync: Date?)` using `nextSyncDue` semantics from the contract (manual never; unmetered unless `allowMobileData`; interval 86400 / 604800 / 2592000 s).

- [ ] **Step 1: Write failing tests**: fixtures through a `URLProtocol` stub, plain-http refusal, `nextSyncDue` boundary cases copied from `apps/mobile/src/core/settings/settings.test.ts`.
- [ ] **Step 2-5:** run, implement, run, commit `feat(ios): sync engine and background refresh`.

### Task 13: iOS SwiftUI parity, localisation, VoiceOver, Dynamic Type

**Files:**
- Create: `Sheltr/Views/{HomeView,SearchView,AnswerView,SettingsView,AboutView}.swift`, `Sheltr/Resources/{en,es,fr}.lproj/Localizable.strings`; Test: `SheltrUITests`, `LocalizableKeysTests.swift`

**Interfaces:**
- Consumes: Task 8's string key list in `contract/README.md`.

- [ ] **Step 1: Write failing tests**: `LocalizableKeysTests` (every key of the contract list exists in all three languages and has the same placeholders), UI tests `homeShowsTwelveHazards`, `searchSubmitDisabledWhenBlank`, `answerShowsSourceBlock`, `largestDynamicTypeKeepsControlsReachable`, `everyControlHasAnAccessibilityLabel`, `unsupportedDeviceLanguageShowsEnglish`.
- [ ] **Step 2-5:** run, implement, run, commit `feat(ios): SwiftUI screens at parity with Android`.

## Phase D: safety features (contract version 2)

### Task 14: Downgrade protection, content expiry and staleness warnings

**Files:**
- Modify: `tools/publisher/src/{bundle,manifest}.ts`, `corpus/manifest.yaml` (add `reviewed_on`, `expires_on`, `reviewed_by` per entry), `contract/bundle-format.md`, `contract/fixtures/*` (new: `downgrade-older-sequence`, `same-sequence`, `expired-document`), both apps' sync engines and stores, About and Answer screens on both platforms

**Interfaces:**
- Produces: `BUNDLE_FORMAT_VERSION = 2`; bundle field `sequence: Int` (strictly increasing per publication); document fields `reviewedOn: String`, `expiresOn: String` (ISO dates); store keeps `sequence`; sync rejects `sequence <= installed` with result `failed`, reason contains "older"; `StalenessState { fresh | expiringSoon | expired }` computed from `expiresOn` and last sync (threshold: warn when a document expires within 30 days or the last successful sync is older than 90 days); publisher refuses entries without both dates or already expired.

- [ ] **Step 1: Write failing tests** per component: publisher (`refusesEntryWithoutReviewDates`, `refusesExpiredEntry`, `sequenceMustExceedPreviousPublished` via a `--previous <bundle.json>` flag), then in each app the three new fixtures plus `answerShowsExpiredBanner`, `answerShowsUpdatedOnDate`, `aboutListsReviewDateAndExpiry`; the Expo app is updated too so the oracle stays green.
- [ ] **Step 2: Run** each suite — Expected: FAIL.
- [ ] **Step 3: Implement** publisher first, regenerate fixtures, then Android, iOS and Expo.
- [ ] **Step 4: Run** all suites — PASS. **Step 5: Commit** `feat!: bundle format 2 with downgrade protection and content expiry`.

### Task 15: Action cards for each hazard

**Files:**
- Create: `corpus/cards/<hazard>-<phase>.yaml`, `tools/publisher/src/cards.ts`, `contract/fixtures/cards-valid/`, `contract/fixtures/cards-uncited-step/`
- Modify: bundle format (`cards[]` in `bundle.json`), Home screens on both platforms, `docs/publishing.md`

**Interfaces:**
- Produces: card `{ id, hazard, phase: "before"|"during"|"after", country: String?, title, steps: [{ text, documentId, chunkId }] }`; publisher refuses a card whose step cites a chunk that does not exist in the built bundle or whose text is not a verbatim substring of that chunk; apps show a card list per hazard with large step text, citation per step and read-aloud.

- [ ] **Step 1: Write failing tests**: publisher `refusesStepWithNoCitation`, `refusesStepNotQuotedFromItsChunk`, `acceptsVerbatimCitedStep`; each app parses `cards-valid`, rejects `cards-uncited-step`, and shows steps with their source.
- [ ] **Step 2-5:** run, implement, run, commit `feat: cited action cards`. Content authoring and expert review of the first cards happens outside this plan.

### Task 16: Key rotation and revocation

**Files:**
- Modify: `bundle.json` (`signedBy: String` key id), both apps (trusted key list: `[{id, publicKeyHex, notAfter}]`), `docs/publishing.md`, fixtures `rotated-key-valid`, `revoked-key`

**Interfaces:**
- Produces: apps compile in up to two trusted keys; a bundle is accepted if `signedBy` names a trusted, unexpired key and the signature verifies under it; a key past `notAfter` is rejected with reason containing "key".

- [ ] **Step 1: Write failing tests**: `rotatedKeyBundleVerifies`, `revokedKeyBundleRejected`, `unknownKeyIdRejected` in all three codebases against the new fixtures.
- [ ] **Step 2-5:** run, implement, run, commit `feat: key rotation`.

## Phase E: release and clean-up

### Task 17: Release readiness, CI, and retiring the Expo app

**Files:**
- Modify: `.github/workflows/*`, `docs/publishing.md`, `docs/store/listing.md`, `docs/evaluation.md`, `README.md`, `CONTRIBUTING.md`, `CLAUDE.md`, `SECURITY.md`; Create: `docs/release-checklist.md`
- Delete: `apps/mobile/` (after both native apps pass every contract fixture and case in CI), `.github/dependabot.yml` Expo entries

**Interfaces:**
- Produces: CI jobs `publisher`, `android` (JVM tests, emulator FTS test, lint), `ios` (xcodebuild test), and `contract` (a job that fails if any platform's contract test list differs from `contract/fixtures/*`); `docs/release-checklist.md` listing: independent security review of signature and sync code, expert content review sign-off, iPhone and low-end Android device runs, sideloadable signed APK steps, F-Droid notes, store data-safety answers.

- [ ] **Step 1: Write the failing check**: a script `contract/check-coverage.mjs` that fails when any fixture directory has no test reference in `apps/android` or `apps/ios`; wire it into CI.
- [ ] **Step 2: Run** it — Expected: FAIL until all fixtures are referenced.
- [ ] **Step 3: Delete** `apps/mobile` in its own commit, update every doc that mentions Expo (README, CLAUDE.md, CONTRIBUTING, ADR-0017 status, architecture notes).
- [ ] **Step 4: Run** all three platforms' CI — Expected: PASS.
- [ ] **Step 5: Commit** `chore: retire the Expo app; native apps are the product`.

## Not in this plan

Corpus re-approval and expansion, expert content review, store accounts and submissions, an on-device model, live hazard alerts. They need people or decisions, not code, and each gets its own plan.
