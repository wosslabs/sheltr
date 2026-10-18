# Sheltr

Sheltr is a free, non-profit, offline-first guide to disaster preparedness and response. The app is designed to work with no network connection once the approved content is on the phone, and to sync updates whenever a network is available.

Every answer is designed to show the official source it came from: the agency, the document title, the date and a link to the original. Sheltr answers only from approved official documents. It refuses when no source passes its relevance check, rather than inventing guidance.

Sheltr is not an official warning system. Follow official alerts from your local authorities.

The approved corpus covers earthquake, flood, tropical cyclone, tornado, tsunami, wildfire, extreme heat, winter storm, landslide, glacial lake flood, volcano and drought.

There is no account, no analytics and no server in production. Location, if used, stays on the device and is used only to choose a country.

## Status

Work in progress. Only what is listed under "Built" has code and tests in this repository. The implementation plan has the full task list.

Built:

- **Publisher** (`tools/publisher`): manifest parsing, PDF and HTML extraction, text cleaning, chunking, bundle building, Ed25519 signing, the `publish` command, and a check that refuses a signing key inside the repository. `gradle :tools:publisher:test` passed on 2026-10-05 (125 tests).
- **Shared module** (`apps/mobile/shared`): bundle parsing, signature verification, SQLite store with FTS5 and atomic swap, keyword retrieval with country and hazard boosts, citation selection and refusal, sync engine with retry policy, and the settings model. `gradle :apps:mobile:shared:jvmTest` passed on 2026-10-05 (96 tests).
- **Corpus**: two approved entries in `corpus/manifest.yaml`.
- **Clean-up**: the server, the chat UI and their infrastructure were removed in commit `116a0e7`.

Not done:

- **Android app** (Jetpack Compose): not created. The shared module declares an Android target (file and SQLite driver actuals, a DataStore-backed settings store, and the offline sample content bundle installer), but no app has been built on top of it yet.
- **iOS app** (SwiftUI): not created. The iOS targets are declared in the shared module, but they have not been built or tested.
- **Bundle hosting**: no hosting location is configured in this repository, and no bundle has been published from it.
- **On-device model** (LiteRT-LM): not started. Version 1 search is keyword only.
- **Search evaluation on the phone**: not started.
- **About screen, privacy statement and store listings**: not started.

## Repository layout

- `apps/mobile/shared`: Kotlin Multiplatform module shared by both apps (bundle, store, retrieval, sync, settings).
- `apps/mobile`: the app projects (Android and iOS are planned; see `apps/mobile/README.md`).
- `tools/publisher`: Kotlin/JVM command-line publisher.
- `corpus/manifest.yaml`: the approved corpus.
- `docs/adr`: architecture decision records. ADR-0015 is the current design; older ADRs are marked where the server parts are superseded.
- `docs/architecture/mobile-design.md`: diagram of the mobile and publisher design.
- `docs/superpowers/specs`: design specs. `docs/superpowers/plans`: the current implementation plan.
- `.github/workflows`: CI workflows (not yet built).

## The publisher

The publisher reads the approved manifest, fetches each source over HTTPS (only from hosts named in the manifest), checks it against the approved checksum, extracts and cleans the text, splits it into chunks, and writes a bundle signed with an Ed25519 key.

```bash
publish --manifest <file> --out <dir> --key <path> [--public-key <path>]
```

- `--manifest`: the approved manifest, `corpus/manifest.yaml`.
- `--out`: the directory for the bundle. It must not exist yet.
- `--key`: the Ed25519 private key (PKCS#8, DER or PEM).
- `--public-key`: optional. If given, the bundle is verified with this public key (X.509, DER or PEM).

The output directory contains `bundle.json` (the manifest, with each document's checksum and chunks), `bundle.json.sig` (its signature) and `documents/`. If an entry fails, it is listed as `FAILED`, left out of the bundle, and the command exits with status 1. If no bundle can be built, nothing is left at the output path.

There is no Gradle run task for the publisher yet. Run `com.sheltr.publisher.MainKt` from the IDE with the arguments above, or compile it and run it with `java` on a classpath that includes its dependencies.

### Signing key

Create the key outside the repository:

```bash
mkdir -p ~/sheltr-keys && chmod 700 ~/sheltr-keys
openssl genpkey -algorithm ed25519 -out ~/sheltr-keys/sheltr-publisher.key.pem
chmod 600 ~/sheltr-keys/sheltr-publisher.key.pem
openssl pkey -in ~/sheltr-keys/sheltr-publisher.key.pem -pubout -out ~/sheltr-keys/sheltr-publisher.pub.pem
```

- The private key must stay outside this repository. The publisher finds the repository root (the nearest parent of its working directory that contains `settings.gradle.kts`) and refuses to read a key from inside it, following symlinks. Pass the key's path with `--key`.
- Never commit the key, copy it to the phone or upload it to hosting. The `.gitignore` patterns for key files are a backstop, not the control.
- Keep an offline backup. A lost key means no new signed bundles can be trusted by the apps. A leaked key lets anyone sign content the apps accept.
- The apps verify against the public key they ship with, so rotating the key requires an app update.

## Building and testing

Requirements: JDK 25 and Gradle 9.2.0. No Gradle wrapper is committed yet, so install Gradle 9.2.0 and run it from the repository root.

```bash
gradle :tools:publisher:test          # publisher tests (JVM)
gradle :apps:mobile:shared:jvmTest    # shared module tests (JVM)
```

Both commands passed on 2026-10-05 with Gradle 9.2.0 on JDK 25.0.4. The iOS targets and the Android app are not covered by these commands.

## Licence and attribution

- **Code**: Apache License 2.0. See `LICENSE` and ADR-0009.
- **Content**: each source document keeps its own licence and attribution. The licence and the attribution are recorded per document in `corpus/manifest.yaml` and carried into the bundle. The apps will show them on the About screen.
  - "Prepare" (UK Government): Open Government Licence v3.0.
  - IFRC, "Public awareness and public education for disaster risk reduction: key messages (Short version)": copies may be made for non-commercial use with acknowledgement. Commercial reproduction needs IFRC permission. Sheltr is free and non-profit.
- Sheltr is an independent project. It is not an official government or agency service.

## Documentation

- Design: `docs/superpowers/specs/2026-10-05-offline-mobile-app-design.md`
- Plan: `docs/superpowers/plans/2026-10-05-mobile-kotlin-mpp.md`
- Decision: `docs/adr/0015-offline-mobile-publisher-no-server.md`
- Diagram: `docs/architecture/mobile-design.md`
