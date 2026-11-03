## What and why

## How it was tested
- [ ] Publisher changes: `cd tools/publisher && npm run typecheck && npm test`
- [ ] App changes: `dart test` in `apps/flutter/packages/sheltr_core`, `flutter test` in `apps/flutter`, and `node contract/check-coverage.mjs`
- [ ] Expo oracle, until `docs/release-checklist.md` is done: `cd apps/mobile && npm run typecheck && npm test`

## Checklist
- [ ] No secrets, signing keys or personal data added
- [ ] Docs updated if behaviour changed
- [ ] If this touches corpus content: the licence and attribution are recorded in `corpus/manifest.yaml`
