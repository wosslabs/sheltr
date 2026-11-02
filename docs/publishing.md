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

The base URL and the hex public key are build-time settings, read by `apps/mobile/app.config.js` from the environment. They are compiled into the app, never stored in source:

```bash
cd apps/mobile
SHELTR_PUBLISHER_BASE_URL=https://<org>.github.io/sheltr-bundles/ \
SHELTR_PUBLISHER_PUBLIC_KEY_HEX=<64 hex characters> \
  npx expo run:android --variant release
```

For store builds use EAS Build (`eas build`) and set the same two variables as EAS environment variables.

With either missing (the default), "Sync now" says sync is not available in this build and the app only has the bundled sample content. A key that is not 64 hex characters is treated as missing.

## Key rotation and loss

- The apps verify against the public key compiled in, so rotating the key needs an app update. Plan an overlap: ship an app that trusts the new key before publishing with it.
- If the private key is lost, no new bundle can be trusted. Apps keep their last good content.
- If the private key leaks, anyone can publish content the apps accept. Treat it as a security incident: see `SECURITY.md`, publish nothing, rotate the key and ship an app update.

## Before every publication

1. `corpus/manifest.yaml` changes reviewed: licence, attribution and checksum recorded for every entry.
2. `npm test` passes in both `tools/publisher` and `apps/mobile`, including the retrieval evaluation (`docs/evaluation.md`), extended with questions for any new document.
3. `THIRD_PARTY_CONTENT.md` updated for new sources.

## Known blockers (checked 2026-11-02)

The first real publish run failed for both approved entries. The publisher refused correctly; the manifest needs a maintainer decision before a bundle can be published:

- **"Prepare" (UK Government).** `https://prepare.campaign.gov.uk/` now redirects to `https://beready.campaign.gov.uk/`. The publisher only follows hosts named in the manifest, so it stops. Fixing it means pointing the entry at the new URL, reading the new page, and approving its new checksum. The content may have changed.
- **IFRC key messages (2013, short version).** The regional mirror in the manifest now answers HTTP 403 (bot protection). The canonical `ifrc.org` copy of this exact edition was not verified, and the file must match the approved checksum byte for byte. A different edition (for example the 2018 second edition) is a different document and needs its own approval and licence check.

Until both are resolved, no bundle can be published and the app ships with the bundled sample content only.
