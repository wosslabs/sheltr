# Changelog

## Unreleased — early development

Core publisher and Flutter app are built and tested. Distribution is not.

- Publisher (`tools/publisher`) builds Ed25519-signed bundles from `corpus/manifest.yaml`.
- Flutter app (`apps/flutter`) verifies bundles, stores chunks in SQLite FTS5, and answers
  offline with citations (or refuses).
- Offline sample content lives in `corpus/sample-bundle` for tests. Nothing is hosted, and
  the app's trusted key list is empty — see `docs/publishing.md` and the README Status section.

Community packaging: `LICENSE` (Apache-2.0), `CODE_OF_CONDUCT.md`, `SECURITY.md`,
`CONTRIBUTING.md`, `SUPPORT.md`, issue/PR templates, Dependabot (npm, pub, Actions).
