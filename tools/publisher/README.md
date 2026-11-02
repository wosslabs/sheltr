# Sheltr publisher

Builds the signed content bundle the app downloads. Node 24 and TypeScript; Node runs the `.ts` files directly, so there is no build step.

```bash
npm ci
npm run bundle -- --manifest ../../corpus/manifest.yaml --out <dir> --key <private-key.pem> [--public-key <public-key.pem>]
npm run typecheck
npm test
```

- `--manifest` the approved corpus (`corpus/manifest.yaml`).
- `--out` the bundle directory; it must not exist yet.
- `--key` the Ed25519 private key (PKCS#8, DER or PEM). It must be outside the repository: the publisher refuses a key inside it.
- `--public-key` optional; the built bundle is verified with it.

Exit status: 0 on success, 1 if any entry failed or verification failed, 2 for a malformed command line.

## How it works

`src/run.ts` is the command; `src/bundle.ts` builds the bundle.

1. Read the manifest (`manifest.ts`). Every host named in it, and nothing else, may be fetched (`policy.ts`: https only, no credentials, no other port).
2. Fetch each source (`html.ts` follows at most three redirects and checks every target against the policy; `pdf.ts` follows none) and compare its SHA-256 with the approved checksum.
3. Extract text (`cheerio` for the main content of a page, `pdfjs-dist` for PDF pages), clean it (`text.ts`), split it into overlapping chunks cut on word boundaries (`chunker.ts`).
4. Write `bundle.json`, `bundle.json.sig` (raw Ed25519 signature) and `documents/<id>.json` to a temporary directory next to the output, and move it into place only when complete. A failed build leaves nothing behind.

The bundle format is read by `apps/mobile/src/core/bundle`. Change both together, and raise `BUNDLE_FORMAT_VERSION` for any incompatible change.

## Conventions

Node strips types without compiling, so use erasable TypeScript only: no `enum`, no namespaces, no parameter properties, `import type` for types, and `.ts` extensions in relative imports (`npm run typecheck` enforces this).
