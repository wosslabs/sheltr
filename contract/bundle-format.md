# Bundle format

Version 2. The app reads exactly this version. Any other `formatVersion` is rejected before the rest of the file is used, with a message that names the format.

This is the corpus bundle from section 4 of `docs/design/offline-mobile-app.md`, with the field names and nullability in `apps/mobile/src/core/bundle/format.ts`.

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
- `documents` (array): one entry per document.
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
