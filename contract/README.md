# Contract

Shared rules for the publisher and the apps. The Expo app remains the oracle until the Flutter app passes these same files.

Committed fixtures are canonical. Do not regenerate them in CI. `npm run fixtures` (from `tools/publisher`) creates a new signing key on every run, so a second run will not match the committed bytes.

The private key is never written. Each fixture directory holds `public-key.hex`, the raw 32-byte Ed25519 public key in lower-case hex, and `bundle.json.sig`, the raw 64-byte signature of the exact bytes of `bundle.json`.

## Files

- `bundle-format.md` — bundle layout version 2.
- `retrieval-rules.json` — relevance threshold, term coverage, boosts, pool size, stopwords, synonyms, hazard terms, and the twelve disaster types.
- `retrieval-cases.tsv` — question cases. The Expo evaluation test reads this file.
- `word-cases.tsv` — columns `left`, `right`, `match` (`true` or `false`).
- `strings.tsv` — columns `key`, `placeholders`. One row per key in `apps/mobile/src/i18n/locales/en.json`. Placeholders are the `%{pN}` tokens in that string, in order, separated by spaces. Spanish and French are not copied.
- `model-cases.tsv` — columns `question`, `passages`, `model`, `expect` (`show`, `hide`, or `nocall`). Passage text quotes a fixture sentence. `nocall` is a retrieval refusal: the model is not called.
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
| `unsupported-format` | `formatVersion` is not 2. The edited manifest was signed again, so the signature verifies and the format check is what fails. |
| `plain-http-url` | A normal signed bundle. Serve every other fixture from an `https` base URL. Serve this one from the URL in `fixtures/plain-http-url/base-url.txt` (`http://example.org/bundle`). The app refuses that URL before it reads the bundle. |
| `downgrade-older-sequence` | `sequence` is lower than `valid-signed`. Install `valid-signed` first. The sync fails and the reason contains `older`. |
| `same-sequence` | `sequence` equals `valid-signed`, and `bundleVersion` does not. Install `valid-signed` first. The sync fails and the reason contains `older`. |
| `expired-document` | A signed bundle whose `expiresOn` is in the past. Expiry is a warning, not a sync failure, so the app installs it. `prior` is `empty` and `result` is `updated`. |
| `rotated-key-valid` | Signed by the second key in `trusted-keys.json`. The list holds the previous key and that key. `prior` is `empty` and `result` is `updated`. |
| `revoked-key` | The signature verifies under `public-key.hex`, and `signedBy` is not in `trusted-keys.json`. That key was removed from the compiled list. `result` is `rejectedSignature`. |
| `cards-valid` | A signed bundle whose `cards` array quotes the boil-water chunk verbatim. `prior` is `empty` and `result` is `updated`. |
| `cards-uncited-step` | The same documents, re-signed after a step was given a `chunkId` that is not in the bundle. The publisher refuses that step. The documents still install. `prior` is `empty` and `result` is `updated`. |

`rotated-key-valid` and `revoked-key` also contain `trusted-keys.json`: an array of at most two `{ id, publicKeyHex, notAfter }` objects. When that file is present, sync uses it instead of verifying only with `public-key.hex`. A listed key past `notAfter` still installs. The About screen shows `about_key_date_passed`. Answers still show.

`rejectedSignature` carries no reason on the current sync result, so those fixtures use `null` for `reasonContains`.
