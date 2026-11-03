# Bundle format

Version 2. The app reads exactly this version. Any other `formatVersion` is rejected before the rest of the file is used, with a message that names the format.

This is the corpus bundle from section 4 of `docs/design/offline-mobile-app.md`, with the field names and nullability in `apps/flutter/packages/sheltr_core/lib/bundle/format.dart`.

## Files

```
bundle.json
bundle.json.sig
documents/<id>.json
```

`bundle.json.sig` is the raw 64-byte Ed25519 signature of the exact bytes of `bundle.json`. The app verifies that signature before it trusts the manifest. An invalid signature rejects the update and leaves the last good content in place.

JSON is UTF-8 with four-space indentation, explicit nulls, and no trailing newline. The signature covers those exact bytes. Every checksum is lower-case hex SHA-256.

## bundle.json

- `formatVersion` (number): `2`.
- `sequence` (integer): strictly increasing per publication. The first bundle is `1`. Each later bundle is one greater than the `sequence` in the previous `bundle.json`.
- `bundleVersion` (string): UTC build time, ISO-8601 to the second, for example `2026-10-05T12:00:00Z`.
- `signedBy` (string): lower-case hex SHA-256 of the raw 32-byte Ed25519 public key that signed this file. The app accepts the bundle when this id names a key on its compiled list of at most two keys and the signature verifies under that key. An unknown id is rejected. Old bundles without this field still verify under the single compiled public key.
- `documents` (array): one entry per document.
- `cards` (array): quoted action cards. Each card is `{ id, hazard, phase, country, title, steps }`. `phase` is `before`, `during`, or `after`. Each step is `{ text, documentId, chunkId }`. A step is included only when `chunkId` is a chunk in the bundle and `text` is a verbatim substring of that chunk. Empty when the bundle has no cards. Old bundles without this field have no cards.
- `model` (object, optional): omitted when the bundle has no model file. The publisher does not write it until a model is part of the publication. When present:
  - `file` (string): bundle-relative path of the model file.
  - `sha256` (string): lower-case hex SHA-256 of that file.
  - `byteSize` (number): size in bytes.
  - `modelType` (string): one of `gemma4`, `gemmaIt`, `qwen`, `qwen3`, `general`.

Each document:

- `id` (string): first 32 hex characters of the SHA-256 of the source URI. Stable across builds. Not blank.
- `title` (string).
- `agency` (string).
- `publishedDate` (string): `YYYY-MM-DD`.
- `reviewedOn` (string): `YYYY-MM-DD`. When the source was last reviewed.
- `expiresOn` (string): `YYYY-MM-DD`. A document past this date still installs. Expiry is a warning, not a sync failure.
- `licence` (string).
- `country` (string or null): ISO 3166-1 alpha-2, or null for global guidance.
- `hazards` (array of strings): disaster type names.
- `sourceUri` (string).
- `sourceType` (string): `pdf` or `html`.
- `checksum` (string): SHA-256 of the approved PDF bytes, or of the extracted HTML text.
- `fileChecksum` (string): SHA-256 of the exact bytes of `documents/<id>.json`.
- `chunks` (array of `{ id, checksum }`): `id` is `<documentId>-<index>` with `index` starting at 0. `checksum` is the SHA-256 of the chunk content in UTF-8.

## documents/<id>.json

- `id` (string): the document id. Not blank.
- `chunks` (array of `{ id, content, pageNumber }`): `pageNumber` is the 1-based PDF page, or null for HTML.

The phone does not parse PDFs. The publisher ships the extracted text, and for an HTML source it ships the extracted text. Chunking of that text is already done.

A document file whose SHA-256 is not the manifest `fileChecksum` is a format error: the message says the file does not match the manifest. Compute the hash over the bytes read, not over a re-encoded string.

## Sync

The app compares each document's `fileChecksum` with the installed copy. It downloads only documents whose file checksum changed, or that are new, and drops documents the manifest no longer lists. The new content replaces the old in one database transaction, which is rolled back unless every document and chunk is stored. A failed or interrupted sync leaves the previous content in place.

A bundle whose `sequence` is lower than the installed sequence is rejected. A different bundle with the same `sequence` is rejected too. The failure reason contains `older`. The same publication already installed (the same `sequence` and the same `bundleVersion`) is up to date. A document past `expiresOn` still installs.

When `model` is present, the app downloads it after a successful content sync. The URL is the bundle base URL, then `/`, then `model.file`, and it must be HTTPS. The manifest, the signature, and each document file are read in full. The model response is a stream of chunks. Each chunk is written to a staging file with a running SHA-256. The model body is not collected into one buffer. Writing stops if the next chunk would pass `byteSize`, and that stream is cancelled so the rest of the body is not read. The app then checks the length and `sha256`, and only then renames the staging file over the previous model file. A mismatch deletes the staging file. A download or checksum failure leaves the content install in place and leaves any previous verified model file in place. The content sync result does not change. The content install is not rolled back. A later sync retries that download when the signed `sha256` or `byteSize` differs from the installed model, including when the content result is up to date. A failure while comparing the installed model, or a retry failure, still leaves the content result and the previous verified file unchanged.

A compiled list holds at most two keys, each `{ id, publicKeyHex, notAfter }`. `notAfter` is `YYYY-MM-DD` and is not part of the bundle. A bundle is accepted when `signedBy` names a key on that list and the signature verifies under it. An unknown id is `rejectedSignature`. A listed key past `notAfter` still verifies. Removing a key from the list is what revokes it.
