# Mobile apps

The mobile apps are offline-first and have no server. The design is in `docs/design/offline-mobile-app.md` and `docs/adr/0015-offline-mobile-publisher-no-server.md`.

- `shared/`: Kotlin Multiplatform module shared by both apps. It holds the bundle format and signature check, the SQLite content store (SQLDelight, FTS5), keyword retrieval, citations and refusal, sync and settings. Targets: `jvm`, `android`, `iosArm64`, `iosSimulatorArm64`.
- `android/`: the Jetpack Compose app (Home, Search, Answer, Settings).
- `android-sample-bundle/`: sample content installed for offline use.
- iOS app (SwiftUI): not created yet.

Test the shared module on the JVM from the repository root with `./gradlew :apps:mobile:shared:jvmTest`.
