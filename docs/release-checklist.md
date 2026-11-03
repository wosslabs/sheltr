# Release checklist

The app to ship is `apps/flutter`. The Expo app has been removed. `AppSettings.modelEnabled` stays false in the committed default. ADR-0017 can turn Gemma on for a laptop run with `SHELTR_LAPTOP`. Turning the default on for phones waits until `docs/model-eval.md` shows a pass on both phones.

The background callback (`sheltrSyncDispatcher` in `apps/flutter/lib/sync/scheduler.dart`) downloads only when `compiledBundleBaseUrl` is an https URL and `compiledTrustedKeys` is non-empty. Both are empty in this repository, so a firing callback does not open a socket. A firing callback is not a completed sync.

iOS background refresh is checked on the iPhone run below. It may be late. A late callback does not fail this checklist.

- [x] Independent review of the signature code (`apps/flutter/packages/sheltr_core/lib/signing/verifier.dart` and `trusted_keys.dart`) and the sync code (`apps/flutter/packages/sheltr_core/lib/sync/engine.dart`, `client.dart`, and `apps/flutter/lib/sync/scheduler.dart`). Reviewed 2026-11-03 from the source. `verifySignature` returns false for a bad length or a crypto failure. The engine requires https and verifies the signature before it parses the manifest. An unknown key id is rejected. A listed key past `notAfter` still verifies. The default fetch refuses the socket. A document id or bundle path that leaves the directory is refused. A compiled key id is not checked against the SHA-256 of its public key; a mismatch rejects the bundle rather than accepting a different key. This was a source review in the working tree, not a second maintainer.
- [x] Support-check tests pass. `modelTextIsSupported` is covered by `apps/flutter/packages/sheltr_core/test/rewriter_test.dart`.
  - [x] Non-ASCII digits are rejected, and before/after/during/until/under/over/more/once/only are not skipped.
- [ ] One Android phone and one iPhone: run search, and run a forced stale bundle on each phone. No phone was connected on 2026-11-03. Native phones are still later.
  - [x] Chrome is the UI stand-in until those phone runs. On 2026-11-03, `flutter build web` served the sample bundle. Search for "what should I do during an earthquake" opened cited IFRC earthquake passages. A second build with `--dart-define=SHELTR_WEB_STALE=true` showed "This document is past its expiry date." and still showed those passages.
- [x] Phone model eval is not claimed. `AppSettings.modelEnabled` stays false. The table in `docs/model-eval.md` has no pass row. A laptop run may load Gemma through LiteRT-LM (ADR-0017) and still does not count as a phone pass.
- [x] Sideloadable Android build. From `apps/flutter`: `flutter build apk --release` produced `build/app/outputs/flutter-apk/app-release.apk` on 2026-11-03 (98.2MB). That file is a local build output and is not committed. Release builds in `android/app/build.gradle.kts` are still signed with the debug key. A Play upload key has not been created.
- [ ] Store data-safety answers, taken from the drafts in `docs/store/listing.md`, entered for the store accounts. The answers are written in that file. They have not been entered in Google Play or App Store Connect.
