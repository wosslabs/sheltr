# Release checklist

The app to ship is `apps/flutter`. `apps/mobile` (Expo) stays as the oracle until a human ticks every box in this file. A later commit deletes it. This file does not tick the boxes, and it does not turn `AppSettings.modelEnabled` on. That default stays false. Turning the flag on is a separate commit, and only after `docs/model-eval.md` shows a pass on both phones.

The background callback (`sheltrSyncDispatcher` in `apps/flutter/lib/sync/scheduler.dart`) does not download. It does not open a socket. A firing callback is not a completed sync.

iOS background refresh is checked on the iPhone run below. It may be late. A late callback does not fail this checklist.

- [ ] Independent review of the signature code (`apps/flutter/packages/sheltr_core/lib/signing/verifier.dart` and `trusted_keys.dart`) and the sync code (`apps/flutter/packages/sheltr_core/lib/sync/engine.dart`, `client.dart`, and `apps/flutter/lib/sync/scheduler.dart`).
- [ ] Support-check tests pass. `modelTextIsSupported` is covered by `apps/flutter/packages/sheltr_core/test/rewriter_test.dart`.
- [ ] One Android phone and one iPhone: run search, and run a forced stale bundle on each phone.
- [ ] Model eval either passed on both phones (`docs/model-eval.md` has a pass row for each) or explicitly left off (`AppSettings.modelEnabled` stays false).
- [ ] Sideloadable Android build. From `apps/flutter`: `flutter build apk`. Release builds in `android/app/build.gradle.kts` are still signed with the debug key.
- [ ] Store data-safety answers, taken from the drafts in `docs/store/listing.md`, entered for the store accounts.
