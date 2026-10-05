# Mobile apps

The mobile apps are offline-first and have no server. The design is in `docs/superpowers/specs/2026-10-05-offline-mobile-app-design.md` and `docs/adr/0015-offline-mobile-publisher-no-server.md`.

- `shared/`: Kotlin Multiplatform module shared by both apps. It holds the bundle format and signature check, the SQLite content store (SQLDelight, FTS5), keyword retrieval, citations and refusal, sync and settings. Targets: `jvm`, `iosArm64`, `iosSimulatorArm64`. The Android target is not declared yet.
- Android app (Jetpack Compose): not created yet.
- iOS app (SwiftUI): not created yet.

Test the shared module on the JVM from the repository root with `gradle :apps:mobile:shared:jvmTest`.
