# Sheltr

Sheltr is a free, non-profit, offline-first guide to disaster preparedness and response. The app is designed to work with no network connection once the approved content is on the phone, and to sync updates whenever a network is available.

Every answer is designed to show the official source it came from: the agency, the document title, the date and a link to the original. Sheltr answers only from approved official documents. It refuses when no source passes its relevance check, rather than inventing guidance.

Sheltr is not an official warning system. Follow official alerts from your local authorities.

The approved corpus covers earthquake, flood, tropical cyclone, tornado, tsunami, wildfire, extreme heat, winter storm, landslide, glacial lake flood, volcano and drought.

There is no account, no analytics and no server in production. Location, if used, stays on the device and is used only to choose a country.

## Status

Early development. The core is built and tested; distribution is not.

Built:

- **Publisher** (`tools/publisher`, Node and TypeScript): manifest parsing, PDF and HTML extraction, text cleaning, chunking, bundle building, Ed25519 signing, the `publish` command, and a check that refuses a signing key inside the repository.
- **Mobile app** (`apps/mobile`, Expo / React Native / TypeScript, Android and iOS from one codebase):
  - A pure-TypeScript core (`src/core`): bundle parsing, signature verification, SQLite store with FTS5 and atomic replace, keyword retrieval with country and hazard boosts, citation selection and refusal, sync engine with retry policy, settings.
  - Home, Search, Answer, Settings and About screens (with the privacy statement and the source licences), in English, Spanish and French, with text-size and high-contrast options and read-aloud.
  - It installs an offline sample bundle (`apps/mobile/assets/sample-bundle`) on first run.
- **Corpus**: two approved entries in `corpus/manifest.yaml`.

Not done:

- **Bundle hosting and the first published bundle**: the guide is in `docs/publishing.md`, but nothing is hosted, and both approved corpus entries currently fail to fetch (the UK site moved, the IFRC mirror answers 403). They need a maintainer to re-approve the sources.
- **Running on an iPhone**: the app bundles for iOS in CI, but it has only been run on an Android emulator. An iOS simulator runtime (`xcodebuild -downloadPlatform iOS`) on a Mac is needed to try it.
- **On-device model**: not started. Search is keyword only. Only worth adding if `docs/evaluation.md`, extended with real questions, shows too many refusals.
- **Corpus beyond two documents**: needs sources with a licence that allows storing and quoting them.
- **Store listings**: drafts are in `docs/store`; accounts and screenshots are the maintainer's.

Search is evaluated on the sample bundle: 0 of 20 off-topic questions answered, 29 of 31 answerable questions answered (`docs/evaluation.md`).

## Repository layout

- `apps/mobile`: the Expo app (Android and iOS). `src/core` is the framework-free library, `src/app` the screens, `assets/sample-bundle` the offline sample content.
- `tools/publisher`: the command-line publisher (Node and TypeScript).
- `corpus/manifest.yaml`: the approved corpus.
- `docs/adr`: architecture decision records (ADR-0015 is the current design).
- `docs/design`: design documents. `docs/architecture`: diagrams.

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

Run it with Node 24: `cd tools/publisher && npm ci && npm run bundle -- --manifest ../../corpus/manifest.yaml --out <dir> --key <path>`. See `docs/publishing.md` for hosting, key handling and the current blockers.

### Signing key

Create the key outside the repository:

```bash
mkdir -p ~/sheltr-keys && chmod 700 ~/sheltr-keys
openssl genpkey -algorithm ed25519 -out ~/sheltr-keys/sheltr-publisher.key.pem
chmod 600 ~/sheltr-keys/sheltr-publisher.key.pem
openssl pkey -in ~/sheltr-keys/sheltr-publisher.key.pem -pubout -out ~/sheltr-keys/sheltr-publisher.pub.pem
```

- The private key must stay outside this repository. The publisher finds the repository root (the nearest parent of its working directory that contains `.git`) and refuses to read a key from inside it, following symlinks. Pass the key's path with `--key`.
- Never commit the key, copy it to the phone or upload it to hosting. The `.gitignore` patterns for key files are a backstop, not the control.
- Keep an offline backup. A lost key means no new signed bundles can be trusted by the apps. A leaked key lets anyone sign content the apps accept.
- The apps verify against the public key they ship with, so rotating the key requires an app update.

## Building and testing

Everything is TypeScript. You need Node 24 and npm.

```bash
cd tools/publisher && npm ci && npm run typecheck && npm test           # publisher
cd apps/mobile && npm ci --legacy-peer-deps && npm run typecheck && npm test   # app
cd apps/mobile && npx expo start                                          # run the app (Expo Go or an emulator)
```

CI runs both on every pull request and also bundles the app for Android and iOS. See `apps/mobile/README.md`.

## Contributing and security

See `CONTRIBUTING.md` and `CODE_OF_CONDUCT.md`. Report vulnerabilities privately as described in `SECURITY.md`.

## Licence and attribution

- **Code**: Apache License 2.0. See `LICENSE` and ADR-0009. The licence covers source code only.
- **Content**: each source document keeps its own licence and attribution. The licence and the attribution are recorded per document in `corpus/manifest.yaml` and carried into the bundle. The apps will show them on the About screen. See `THIRD_PARTY_CONTENT.md`.
  - "Prepare" (UK Government): Open Government Licence v3.0.
  - IFRC, "Public awareness and public education for disaster risk reduction: key messages (Short version)": copies may be made for non-commercial use with acknowledgement. Commercial reproduction needs IFRC permission. Sheltr is free and non-profit.
- Sheltr is an independent project. It is not an official government or agency service.

## Documentation

- Design: `docs/design/offline-mobile-app.md`
- Decision: `docs/adr/0015-offline-mobile-publisher-no-server.md`
- Diagram: `docs/architecture/mobile-design.md`
