# Contributing to Sheltr

Thank you for helping. Sheltr is a free, non-profit, offline-first guide to disaster preparedness. Because people may rely on it in an emergency, correctness and source fidelity matter more than speed.

Please follow the [Code of Conduct](CODE_OF_CONDUCT.md). For bugs and questions see [SUPPORT.md](SUPPORT.md). Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

## Ground rules

- Answers come only from approved official documents, and every answer shows its source. Do not add content that is not in the approved corpus.
- Never commit secrets, signing keys, `local.properties` or personal data. The publisher's signing key must stay outside the repository.
- The app has no accounts, no analytics and no server. Changes that add any of these need an ADR first.

## Setup

Requirements: Node 24 and npm, and the Flutter SDK. Flutter is not assumed to be on PATH. The app is Flutter (`apps/flutter`). The publisher (`tools/publisher`) is the only TypeScript, and it builds the signed bundle.

```bash
(cd tools/publisher && npm ci && npm run typecheck && npm test)                  # publisher
(cd apps/flutter/packages/sheltr_core && dart test)                              # core
(cd apps/flutter && flutter test)                                                # app
node contract/check-coverage.mjs                                                 # fixture names
```

Phone runs are on `docs/release-checklist.md` and are not done.

## Making a change

1. Open an issue first for anything larger than a small fix.
2. Branch from `main`, keep the change focused, and add or update tests.
3. Run the tests above. CI runs them on every pull request.
4. Explain what changed and why in the pull request. Update docs if behaviour changes.

Use short imperative commit messages, for example `fix(shared): reject unsigned bundle`.

## Proposing corpus content

Corpus entries live in `corpus/manifest.yaml`. A source must be an official document whose licence allows storing and quoting it with attribution. Record the licence (with the URL where it is stated), the agency, the date and the SHA-256 checksum. Entries from commercial-restricted licences need explicit maintainer sign-off. See `THIRD_PARTY_CONTENT.md`.

## Architecture

Read `docs/adr/0015-offline-mobile-publisher-no-server.md` and `docs/design/offline-mobile-app.md` before larger changes. Record significant decisions as a new ADR in `docs/adr`.

## Licence

By contributing you agree your code is licensed under Apache-2.0, as in `LICENSE`.
