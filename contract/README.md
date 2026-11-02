# Contract

Shared rules for the publisher and the apps. The Expo app remains the oracle until the Flutter app passes these same files.

Committed fixtures are canonical. Do not regenerate them in CI. `npm run fixtures` (from `tools/publisher`) creates a new signing key on every run, so a second run will not match the committed bytes.

The private key is never written. Each fixture directory holds `public-key.hex`, the raw 32-byte Ed25519 public key in lower-case hex, and `bundle.json.sig`, the raw 64-byte signature of the exact bytes of `bundle.json`.

## Files

- `bundle-format.md` — bundle layout version 1.
- `retrieval-rules.json` — relevance threshold, term coverage, boosts, pool size, stopwords, synonyms, hazard terms, and the twelve disaster types.
- `retrieval-cases.tsv` — question cases, copied from `apps/mobile/src/core/testing/retrieval-eval.tsv`.
- `word-cases.tsv` — columns `left`, `right`, `match` (`true` or `false`).
- `strings.tsv` — columns `key`, `placeholders`. One row per key in `apps/mobile/src/i18n/locales/en.json`. Placeholders are the `%{pN}` tokens in that string, in order, separated by spaces. Spanish and French are not copied.
- `fixtures/<name>/` — `bundle.json`, `bundle.json.sig`, `documents/`, `public-key.hex`, `expect.json`.

## expect.json

`prior` is `empty` or `valid-signed`. `result` is `updated`, `rejectedSignature`, `failed`, or `upToDate`. `reasonContains` is a string or null. `documentsDownloaded` is a number or null. Assert `reasonContains` only when it is a string, and `documentsDownloaded` only when it is a number.

When `prior` is `valid-signed`, install that fixture first, then sync the fixture under test. Use that fixture's own `public-key.hex`.

## Fixtures

| Fixture | What it is |
|---|---|
| `valid-signed` | A bundle the publisher signed. `documentsDownloaded` is the number of documents in it. |
| `valid-unchanged-doc` | A later bundle. At least one document has the same id and `fileChecksum` as in `valid-signed`. `documentsDownloaded` is how many of its documents differ. |
| `tampered-manifest` | `bundle.json` changed after signing. The signature does not verify. |
| `wrong-key` | Signed by a different key from the one in `public-key.hex`. |
| `bad-file-checksum` | A document file changed after signing. The manifest signature still verifies. The file hash does not match `fileChecksum`. |
| `missing-document` | A document listed in the manifest has no file. The manifest signature still verifies. |
| `unsupported-format` | `formatVersion` is not 1. The edited manifest was signed again, so the signature verifies and the format check is what fails. |
| `plain-http-url` | A normal signed bundle. Serve every other fixture from an `https` base URL. Serve this one from the URL in `fixtures/plain-http-url/base-url.txt` (`http://example.org/bundle`). The app refuses that URL before it reads the bundle. |

`rejectedSignature` carries no reason on the current sync result, so those fixtures use `null` for `reasonContains`.
