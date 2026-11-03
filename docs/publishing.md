# Publishing content

Sheltr has no server. The maintainer builds a signed bundle on their own computer and uploads it to free static hosting. The apps download it, check the signature, and swap it in. See `docs/adr/0015-offline-mobile-publisher-no-server.md`.

## One-time setup

### 1. Create the signing key (outside this repository)

```bash
mkdir -p ~/sheltr-keys && chmod 700 ~/sheltr-keys
openssl genpkey -algorithm ed25519 -out ~/sheltr-keys/sheltr-publisher.key.pem
chmod 600 ~/sheltr-keys/sheltr-publisher.key.pem
openssl pkey -in ~/sheltr-keys/sheltr-publisher.key.pem -pubout -out ~/sheltr-keys/sheltr-publisher.pub.pem
```

Keep an offline backup of the private key. Never commit it, never put it in CI secrets, never upload it. The publisher refuses to read a key from inside the repository.

### 2. Get the public key as hex for the app

The app wants the raw 32-byte public key as 64 hex characters (the last 32 bytes of the X.509 DER form):

```bash
openssl pkey -pubin -in ~/sheltr-keys/sheltr-publisher.pub.pem -outform DER | tail -c 32 | xxd -p -c 64
```

### 3. Choose static hosting

Any HTTPS static host works. GitHub Pages is free and simple: create a repository (for example `sheltr-bundles`), enable Pages for its default branch, and the bundle is served at `https://<org>.github.io/sheltr-bundles/`. The base URL must be HTTPS (the app rejects anything else) and must serve these paths exactly:

```
<base>/bundle.json
<base>/bundle.json.sig
<base>/documents/<id>.json
```

## Publishing a bundle

You need Node 24. The output directory must not exist yet.

```bash
cd tools/publisher
npm ci
npm run bundle -- \
  --manifest ../../corpus/manifest.yaml \
  --out $HOME/sheltr-bundle \
  --key $HOME/sheltr-keys/sheltr-publisher.key.pem \
  --public-key $HOME/sheltr-keys/sheltr-publisher.pub.pem
```

The publisher fetches every source from the hosts named in the manifest, checks it against the approved checksum, and writes `bundle.json`, `bundle.json.sig` and `documents/`. If any entry fails it is listed as `FAILED`, left out, and the command exits with status 1: look at the list before uploading. Copy the output directory to the hosting repository, commit and push.

Check it before announcing it: download `bundle.json` and `bundle.json.sig` from the live URL and verify with `--public-key` (the publisher does this when you pass it).

## Pointing the app at the host

The base URL and the trusted keys are build-time settings. They are compiled into the app, never stored as source. Each trusted key is `{ id, publicKeyHex, notAfter }`. `id` is the lower-case SHA-256 of the raw 32-byte public key. `publicKeyHex` is those 32 bytes as 64 hex characters. `notAfter` is a `YYYY-MM-DD` date chosen by the maintainer. The list holds at most two keys.

The Flutter app (`apps/flutter`) is the app to ship. It compiles that list as `compiledTrustedKeys` in `apps/flutter/lib/trusted_keys.dart`. The list in this repository is empty. Settings "Sync now" shows that sync is not available and does not fetch. A sideloadable Android build is `flutter build apk` from `apps/flutter`. Release builds are still signed with the debug key (`android/app/build.gradle.kts`). Shipping waits on `docs/release-checklist.md`.

```bash
openssl pkey -pubin -in ~/sheltr-keys/sheltr-publisher.pub.pem -outform DER | tail -c 32 | xxd -p -c 64
openssl pkey -pubin -in ~/sheltr-keys/sheltr-publisher.pub.pem -outform DER | tail -c 32 | openssl dgst -sha256 -r | awk '{print $1}'
```

The first command is `publicKeyHex`. The second is `id`, which the publisher writes as `signedBy` in `bundle.json`.

`apps/mobile` is the Expo oracle until `docs/release-checklist.md` is done. It reads `SHELTR_PUBLISHER_BASE_URL`, `SHELTR_PUBLISHER_PUBLIC_KEY_HEX`, and an optional `SHELTR_PUBLISHER_TRUSTED_KEYS` JSON array. With the array unset, that app keeps verifying with the single hex key. With the array set, a bundle is accepted only when `signedBy` names one of those keys and the signature verifies under it.

```bash
cd apps/mobile
SHELTR_PUBLISHER_BASE_URL=https://<org>.github.io/sheltr-bundles/ \
SHELTR_PUBLISHER_PUBLIC_KEY_HEX=<64 hex characters> \
SHELTR_PUBLISHER_TRUSTED_KEYS='[{"id":"<64 hex>","publicKeyHex":"<64 hex>","notAfter":"2027-11-02"}]' \
  npx expo run:android --variant release
```

For an Expo oracle store build, use EAS Build (`eas build`) and set the same variables as EAS environment variables.

With the base URL or the single public key missing (the Expo default), "Sync now" says sync is not available in that build and the Expo app only has the bundled sample content. A key that is not 64 hex characters is treated as missing. A trusted-key list of more than two keys is treated as missing too.

## Key rotation and loss

- Ship an app that trusts the new key and the current key before publishing a bundle signed by the new key. `signedBy` must name a key on the compiled list, and the signature must verify under that key. An unknown id is rejected.
- A listed key past `notAfter` still verifies. The About screen shows that the date has passed. Answers still show. The warning does not add safety steps.
- Removing a key from the compiled list is what revokes it. The next app update rejects bundles that name the removed id.
- If the private key is lost, no new bundle can be trusted. Apps keep their last good content.
- If the private key leaks, anyone can publish content the apps accept until that key is removed from the list. Treat it as a security incident: see `SECURITY.md`, publish nothing, and ship an app update that drops the leaked key.

## Action cards

A card is quoted steps from chunks already in the bundle. The publisher does not write new guidance. Each card is `{ id, hazard, phase, country, title, steps }`. `phase` is `before`, `during`, or `after`. `country` is an ISO 3166-1 alpha-2 code, or null. Each step is `{ text, documentId, chunkId }`.

A step is accepted only when `chunkId` is a chunk in that bundle and `text` is a verbatim substring of the chunk. Anything else is refused and the bundle is not written. There are no production cards in `corpus/cards`. The sample card is the `cards-valid` fixture, and its step quotes the boil-water fixture chunk. `cards-uncited-step` names a chunk that is not in the bundle, which the publisher refuses.

## Before every publication

1. `corpus/manifest.yaml` changes reviewed: licence, attribution and checksum recorded for every entry.
2. Publisher tests pass, `dart test` passes in `apps/flutter/packages/sheltr_core`, `flutter test` passes in `apps/flutter`, and `node contract/check-coverage.mjs` passes. Until `docs/release-checklist.md` is done, `npm test` in `apps/mobile` passes too. The retrieval evaluation (`docs/evaluation.md`) is extended with questions for any new document.
3. `THIRD_PARTY_CONTENT.md` updated for new sources.

## Known blockers (checked 2026-11-02)

The first real publish run failed for both approved entries. The publisher refused correctly; the manifest needs a maintainer decision before a bundle can be published:

- **"Prepare" (UK Government).** `https://prepare.campaign.gov.uk/` now redirects to `https://beready.campaign.gov.uk/`. The publisher only follows hosts named in the manifest, so it stops. Fixing it means pointing the entry at the new URL, reading the new page, and approving its new checksum. The content may have changed.
- **IFRC key messages (2013, short version).** The regional mirror in the manifest now answers HTTP 403 (bot protection). The canonical `ifrc.org` copy of this exact edition was not verified, and the file must match the approved checksum byte for byte. A different edition (for example the 2018 second edition) is a different document and needs its own approval and licence check.

Until both are resolved, no bundle can be published. The Expo oracle ships with the bundled sample content only. The Flutter app has an empty `compiledTrustedKeys` list and does not fetch.
