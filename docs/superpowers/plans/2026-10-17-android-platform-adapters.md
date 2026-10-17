# Android Platform Adapters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Declare the Android target on `apps/mobile/shared` and provide its platform adapters (SQLite driver, settings persistence, file ops) plus a `highContrast` setting field and a checked-in sample content bundle, so an Android app module (separate plan) has everything it needs to show real content with zero network and zero server.

**Architecture:** Follows the existing per-platform adapter pattern already used for `jvmMain`/`iosMain` (e.g. `JdbcStoreDriverFactory`, `NativeStoreDriverFactory`, `StoreFiles.jvm.kt`/`StoreFiles.ios.kt`): add an `androidMain` source set with its own `actual` implementations and its own `StoreDriverFactory`/`SettingsStore`. No new architectural layer — these are adapters behind ports the domain already defines.

**Tech Stack:** Kotlin Multiplatform `androidTarget()`, SQLDelight `android-driver` 2.4.0, AndroidX DataStore (Preferences) for `SettingsStore`, Ktor `ktor-client-okhttp` 3.6.0 (wired here for the later sync plan, not used by this plan's tasks). Android SDK command-line tools (platform 34, build-tools 34.0.0) now installed locally via Homebrew (`android-commandlinetools` cask); `local.properties` points `sdk.dir` at `/opt/homebrew/share/android-commandlinetools` (gitignored).

**Spec:** No separate design doc — decisions below were made in conversation (confirmed default: Android first; shared module already had a comment in `build.gradle.kts` marking exactly this as deferred "Phase A" work, now unblocked).

## Global Constraints

- `minSdk = 24` (Android 7.0): covers the overwhelming majority of active devices worldwide, including older/low-cost hardware, without `java.nio.file` desugaring. Use `java.io.File` APIs in Android actuals, not `java.nio.file`.
- No new dependency outside AndroidX DataStore, SQLDelight android-driver, Ktor okhttp engine — all already named in the existing `build.gradle.kts` comment as the intended Android additions.
- `AppSettings` stays a plain immutable data class (existing pattern) — add `highContrast: Boolean = false` the same way `textScale` is already modeled, not as a separate type.
- The sample bundle asset is installed only when `store.bundleVersion() == null` (first run) — never overwrites a synced or previously-installed bundle.
- Corpus content in the sample bundle must be the exact two approved `corpus/manifest.yaml` entries — no placeholder or lorem-ipsum content, since citations must point at real, approved sources per README.

## Review Focus

- `ContentStore.install` on Android must not corrupt an already-installed bundle if the app is killed mid-install (same atomic-swap guarantee the JVM/iOS actuals already give — exercise the existing `ContentStoreTest`-style crash-before-swap case against the Android actuals, not just happy path).
- `AndroidSettingsStore` must return `AppSettings()` defaults (not throw, not null-crash) the very first time the app runs, before any `put` has ever happened.
- `AppSettings.highContrast` must round-trip through `copy()` and through `AndroidSettingsStore.put`/`get` exactly like `textScale` does today — add it to the existing settings round-trip test, don't just unit-test it in isolation.
- The sample-bundle installer must leave the store untouched (not half-installed) if an asset file is missing or malformed — same failure contract as `SyncEngine`'s `Failed` path, even though this path has no `SyncResult` to return (log/throw, do not partially install).
- Two hazards or chunks sharing an id between the two sample documents (collision) must not silently overwrite one another in the installed store — the two real manifest entries don't collide today, but the installer's dedup/keying logic should be the same one `ContentStore.install` already enforces, not a second, divergent one.

---

### Task 1: Declare `androidTarget()` and Android file/driver actuals

**Files:**
- Modify: `apps/mobile/shared/build.gradle.kts`
- Create: `apps/mobile/shared/src/androidMain/kotlin/com/sheltr/shared/store/StoreFiles.android.kt`
- Create: `apps/mobile/shared/src/androidMain/kotlin/com/sheltr/shared/store/AndroidStoreDriverFactory.kt`
- Create: `apps/mobile/shared/src/androidMain/AndroidManifest.xml` (minimal, library manifest — no components, just `<manifest package="com.sheltr.shared" />`, required by the Android Gradle library plugin even for a pure-Kotlin library module)
- Test: `apps/mobile/shared/src/androidUnitTest/kotlin/com/sheltr/shared/store/AndroidStoreDriverFactoryTest.kt`

**Interfaces:**
- Produces: `actual fun atomicReplaceFile(source: String, target: String)`, `actual fun deleteFileIfExists(path: String)`, `actual fun fileExists(path: String): Boolean` (same `expect` signatures as `StoreFiles.kt`).
- Produces: `object AndroidStoreDriverFactory : StoreDriverFactory` with `fun open(path: String, create: Boolean): SqlDriver` delegating to `app.cash.sqldelight.driver.android.AndroidSqliteDriver`, opened as a plain file-path driver (no `Context`/`android.database.sqlite.SQLiteOpenHelper` asset-copy flow needed — SQLDelight's Android driver can open a driver against a file path via `AndroidSqliteDriver(schema, context, name)`; since this module has no `Context` at the `commonMain` boundary, give `AndroidStoreDriverFactory` a `fun withContext(context: Context): StoreDriverFactory` or a small class taking `Context` in its constructor — the app module (next plan) owns the one `Context` it has and passes it in here; this factory is not a singleton `object` if it needs `Context` — make it `class AndroidStoreDriverFactory(private val context: Context) : StoreDriverFactory`).

- [ ] **Step 1: Add the Android Gradle plugin and `androidTarget()` to `apps/mobile/shared/build.gradle.kts`**

Add `id("com.android.library") version "8.7.3"` to the root `build.gradle.kts` plugin block (`apply false`, alongside the existing Kotlin plugin declarations) and apply it (without a version) in `apps/mobile/shared/build.gradle.kts`. Add `android { namespace = "com.sheltr.shared"; compileSdk = 34; defaultConfig { minSdk = 24 } }` and `androidTarget()` inside the `kotlin { }` block, next to the existing `jvm()`/`iosArm64()`/`iosSimulatorArm64()`. Add to `androidMain.dependencies`: `implementation("app.cash.sqldelight:android-driver:2.4.0")` and `implementation("io.ktor:ktor-client-okhttp:3.6.0")` (per the existing comment in this file). Remove the now-stale comment block above `kotlin { }` that says the Android target is intentionally not declared.

- [ ] **Step 2: Run a build sanity check**

Run: `gradle :apps:mobile:shared:assembleDebug` (or the equivalent Android-target compile task Gradle reports, e.g. `compileDebugKotlinAndroid`)
Expected: the module configures and compiles the `androidMain`/`androidTest` source sets with no actuals yet — it will FAIL with "expected function 'atomicReplaceFile' has no actual declaration" (and similarly for the other two) and "AndroidStoreDriverFactory not found" is not yet expected at this step since that type doesn't exist yet either; this step only confirms the target wires up and names the missing actuals.

- [ ] **Step 3: Implement `StoreFiles.android.kt`**

Same contract as `StoreFiles.jvm.kt` but using `java.io.File` (not `java.nio.file`, per the `minSdk 24` constraint above): `atomicReplaceFile` deletes `target` if present then `File(source).renameTo(File(target))` (same-filesystem rename is atomic on Android's filesystem, matching the JVM actual's guarantee), throwing if `renameTo` returns false; `deleteFileIfExists` calls `File(path).delete()` guarded by `File(path).exists()`; `fileExists` returns `File(path).isFile`.

- [ ] **Step 4: Implement `AndroidStoreDriverFactory`**

```kotlin
class AndroidStoreDriverFactory(private val context: Context) : StoreDriverFactory {
    override fun open(path: String, create: Boolean): SqlDriver =
        AndroidSqliteDriver(SheltrDatabase.Schema, context, name = null, factory = ...)
            // open directly against the file at `path`, not the app's default database directory —
            // ContentStore already computes activePath/stagingPath; this factory must honor the exact
            // path it's given, the same contract JdbcStoreDriverFactory and the native driver follow.
}
```

Use `AndroidSqliteDriver`'s path-based constructor (it accepts a `File` or absolute path, not only a bare database name resolved via `context.getDatabasePath`) so `ContentStore`'s staging/active file scheme keeps working unmodified. If the exact constructor overload needs `create` to control schema creation, mirror how `JdbcStoreDriverFactory` uses the `create` flag (read that file for the pattern before writing this one).

- [ ] **Step 5: Run the sanity build again**

Run: `gradle :apps:mobile:shared:assembleDebug`
Expected: PASS.

- [ ] **Step 6: Write and run an Android-target store test**

```kotlin
class AndroidStoreDriverFactoryTest {
    @Test fun opensAndReopensTheSameFile() {
        // Use androidx.test.core.app.ApplicationProvider.getApplicationContext() (Robolectric) for Context.
        // Create a ContentStore(tmpDir, AndroidStoreDriverFactory(context)), install a tiny manifest+document
        // (same fixture shape as the existing jvmTest ContentStoreTest), close it, reopen a new ContentStore
        // on the same directory, and assert document(id) and chunks(documentId) return the installed data.
    }
}
```

Add Robolectric (`org.robolectric:robolectric`) to `androidUnitTest.dependencies` — it is the standard way to run Android-API-dependent JVM tests without an emulator, needed here and by the UI plan; justified because there is no emulator/device in this environment and CLAUDE.md requires tests to actually run, not just compile.

Run: `gradle :apps:mobile:shared:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add build.gradle.kts apps/mobile/shared/build.gradle.kts \
        apps/mobile/shared/src/androidMain apps/mobile/shared/src/androidUnitTest
git commit -m "feat(mobile): declare Android target with file and SQLite driver actuals"
```

---

### Task 2: `AndroidSettingsStore` and the `highContrast` setting

**Files:**
- Modify: `apps/mobile/shared/src/commonMain/kotlin/com/sheltr/shared/settings/AppSettings.kt`
- Modify (add case): `apps/mobile/shared/src/commonTest/kotlin/com/sheltr/shared/settings/AppSettingsTest.kt`
- Create: `apps/mobile/shared/src/androidMain/kotlin/com/sheltr/shared/settings/AndroidSettingsStore.kt`
- Test: `apps/mobile/shared/src/androidUnitTest/kotlin/com/sheltr/shared/settings/AndroidSettingsStoreTest.kt`

**Interfaces:**
- Consumes: `SettingsStore` port, `AppSettings` (both `commonMain`, already defined).
- Produces: `AppSettings.highContrast: Boolean = false` (new field, positioned after `textScale`). `class AndroidSettingsStore(private val context: Context) : SettingsStore` with `fun get(): AppSettings` / `fun put(settings: AppSettings)` backed by AndroidX `Preferences` DataStore, synchronous (block on the DataStore `Flow`/`suspend` calls with `runBlocking`, matching the port's synchronous contract — `SettingsStore` is not a suspend interface today, and this plan does not change that).

- [ ] **Step 1: Write a failing test for `highContrast`**

Add to `AppSettingsTest.kt`:
```kotlin
@Test fun highContrastDefaultsToFalseAndRoundTripsThroughCopy() {
    val settings = AppSettings()
    assertFalse(settings.highContrast)
    assertTrue(settings.copy(highContrast = true).highContrast)
}
```

- [ ] **Step 2: Run — expect FAIL (`highContrast` unresolved)**

- [ ] **Step 3: Add the field to `AppSettings`**

Add `val highContrast: Boolean = false` to the data class, after `textScale`, with no new `init` validation needed (it's an unconstrained boolean, unlike `country`).

- [ ] **Step 4: Run — expect PASS**

- [ ] **Step 5: Write a failing Android test for `AndroidSettingsStore`**

```kotlin
class AndroidSettingsStoreTest {
    @Test fun getReturnsDefaultsBeforeAnyPut() {
        val store = AndroidSettingsStore(ApplicationProvider.getApplicationContext())
        assertEquals(AppSettings(), store.get())
    }
    @Test fun putThenGetRoundTripsEveryField() {
        val store = AndroidSettingsStore(ApplicationProvider.getApplicationContext())
        val settings = AppSettings(SyncFrequency.DAILY, allowMobileData = true, country = "KE",
            textScale = TextScale.LARGE, highContrast = true)
        store.put(settings)
        assertEquals(settings, store.get())
    }
}
```

- [ ] **Step 6: Run — expect FAIL (class does not exist)**

- [ ] **Step 7: Implement `AndroidSettingsStore`**

One DataStore<Preferences> instance per `Context` (use `context.applicationContext` to avoid leaking an `Activity`), with one `Preferences.Key` per `AppSettings` field (string for `country`, enum `.name` as string for `syncFrequency`/`textScale`, boolean for `allowMobileData`/`highContrast`). `get()` reads via `runBlocking { dataStore.data.first() }` and maps missing keys to `AppSettings()`'s defaults field-by-field (do not require all-or-nothing presence — a partially-written preferences file, e.g. after this plan adds `highContrast` to an app that already had other fields stored, must still produce valid defaults for the missing key). `put()` writes via `runBlocking { dataStore.edit { ... } }`.

- [ ] **Step 8: Run**

Run: `gradle :apps:mobile:shared:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add apps/mobile/shared/src/commonMain/kotlin/com/sheltr/shared/settings/AppSettings.kt \
        apps/mobile/shared/src/commonTest/kotlin/com/sheltr/shared/settings/AppSettingsTest.kt \
        apps/mobile/shared/src/androidMain/kotlin/com/sheltr/shared/settings/AndroidSettingsStore.kt \
        apps/mobile/shared/src/androidUnitTest/kotlin/com/sheltr/shared/settings/AndroidSettingsStoreTest.kt
git commit -m "feat(mobile): add Android DataStore settings adapter and high-contrast setting"
```

---

### Task 3: Sample content bundle (fixes "no hosting" so the app has real content offline)

**Files:**
- Create (generated, not hand-written — see Step 1): `apps/mobile/android-sample-bundle/bundle.json`, `apps/mobile/android-sample-bundle/documents/<id>.json` (one file per manifest entry) — staged here, not yet inside the Android app module, since that module doesn't exist until the next plan; this plan's Task 3 only produces and verifies the asset content and the shared-module loader that will consume it.
- Create: `apps/mobile/shared/src/commonMain/kotlin/com/sheltr/shared/store/AssetBundleInstaller.kt`
- Test: `apps/mobile/shared/src/commonTest/kotlin/com/sheltr/shared/store/AssetBundleInstallerTest.kt`

**Interfaces:**
- Consumes: `ContentStore.install(manifest: BundleManifest, documents: DocumentSource)` (existing), `BundleJson`, `BundleManifest` (existing, from `com.sheltr.shared.bundle`).
- Produces: `fun interface BundleAssetReader { fun readManifestJson(): String; fun readDocumentJson(documentId: String): String }` and `fun installSampleBundleIfEmpty(store: ContentStore, assets: BundleAssetReader)` — installs only when `store.bundleVersion() == null`; no-ops otherwise.

- [ ] **Step 1: Generate the sample bundle files**

Run the publisher against the real approved manifest with a throwaway Ed25519 key (never committed — `RepoGuardTest` already enforces this):
```bash
openssl genpkey -algorithm Ed25519 -out /tmp/sample-bundle-key.pem
gradle :tools:publisher:run --args="--manifest corpus/manifest.yaml --out /tmp/sample-bundle --key /tmp/sample-bundle-key.pem"
rm /tmp/sample-bundle-key.pem
```
Copy `/tmp/sample-bundle/bundle.json` and `/tmp/sample-bundle/documents/` into `apps/mobile/android-sample-bundle/` (do not copy `bundle.json.sig` — this local-asset install path does not verify a signature; verification applies only to content fetched over the network by `SyncEngine`, per the Global Constraints of this plan and the existing `ContentStore`/`SyncEngine` division of trust). Commit the two fetched source documents' content as ordinary repository files — both manifest entries' licences (UK Open Government Licence v3.0, IFRC noncommercial-use licence) permit this redistribution, confirmed earlier in this project's review.

- [ ] **Step 2: Write failing tests for `AssetBundleInstaller`**

```kotlin
class AssetBundleInstallerTest {
    @Test fun installsWhenStoreIsEmpty() {
        val store = ContentStore(tmpDir, JdbcStoreDriverFactory)
        installSampleBundleIfEmpty(store, FakeAssets(sampleManifestJson, sampleDocumentJsonById))
        assertNotNull(store.bundleVersion())
        assertEquals(2, /* count via store.document for each known sample id */)
    }
    @Test fun doesNothingWhenStoreAlreadyHasContent() {
        val store = ContentStore(tmpDir, JdbcStoreDriverFactory)
        store.install(someOtherManifest, someOtherDocumentSource) // arbitrary existing content
        val before = store.bundleVersion()
        installSampleBundleIfEmpty(store, FakeAssets(sampleManifestJson, sampleDocumentJsonById))
        assertEquals(before, store.bundleVersion()) // untouched
    }
}
```

`FakeAssets` is a small `BundleAssetReader` test double returning fixed JSON strings — it can read the real generated `apps/mobile/android-sample-bundle/bundle.json`/`documents/*.json` files from disk in the test (JVM test, real file I/O is fine here, same as other `jvmTest` fixtures) rather than embedding the JSON as string literals.

- [ ] **Step 3: Run — expect FAIL**

- [ ] **Step 4: Implement `AssetBundleInstaller`**

`installSampleBundleIfEmpty`: if `store.bundleVersion() != null`, return. Otherwise parse `assets.readManifestJson()` with `BundleJson.decodeFromString<BundleManifest>`, build a `DocumentSource` whose `load(document)` parses `assets.readDocumentJson(document.id)` with `BundleJson.decodeFromString<DocumentFile>`, and call `store.install(manifest, thatSource)`.

- [ ] **Step 5: Run — expect PASS**

- [ ] **Step 6: Commit**

```bash
git add apps/mobile/android-sample-bundle apps/mobile/shared/src/commonMain/kotlin/com/sheltr/shared/store/AssetBundleInstaller.kt \
        apps/mobile/shared/src/commonTest/kotlin/com/sheltr/shared/store/AssetBundleInstallerTest.kt
git commit -m "feat(mobile): add offline sample content bundle from the approved corpus"
```
