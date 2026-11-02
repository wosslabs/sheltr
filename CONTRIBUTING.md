# Contributing to Sheltr

Thank you for helping. Sheltr is a free, non-profit, offline-first guide to disaster preparedness. Because people may rely on it in an emergency, correctness and source fidelity matter more than speed.

## Ground rules

- Answers come only from approved official documents, and every answer shows its source. Do not add content that is not in the approved corpus.
- Never commit secrets, signing keys, `local.properties` or personal data. The publisher's signing key must stay outside the repository.
- The app has no accounts, no analytics and no server. Changes that add any of these need an ADR first.

## Setup

Requirements: Node 24 and npm. Everything in this repository is TypeScript.

```bash
(cd tools/publisher && npm ci && npm run typecheck && npm test)                  # publisher
(cd apps/mobile && npm ci --legacy-peer-deps && npm run typecheck && npm test)   # app
```

The iOS targets are declared but not built or tested yet.

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
