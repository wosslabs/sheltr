# Sheltr mobile app

Expo (React Native) app for Android and iOS, in TypeScript. It works offline from a signed content bundle. See `docs/adr/0015-offline-mobile-publisher-no-server.md` and `docs/adr/0016-expo-react-native-app.md`.

## Layout

- `src/core`: the library, with no React Native imports, tested in plain Node.
  - `bundle/` bundle format and parser, `signing.ts` Ed25519 check, `store/` SQLite content store (FTS5), `retrieval/` keyword search, citations and refusal, `sync/` sync engine and retry policy, `settings/`.
- `src/platform`: Expo glue: `expo-sqlite` adapter, the first-run sample bundle, publisher config, the provider that boots the app.
- `src/app`: screens (Expo Router): Home, Search, Answer, Settings, About.
- `src/ui`: theme and shared components. `src/i18n`: English, Spanish and French catalogs.
- `assets/sample-bundle`: the offline sample content installed on first run (real publisher output).
- `scripts/`: `npm run gen:sample` regenerates the asset index after the sample bundle changes; `node scripts/gen-countries.mjs` regenerates the country list.

## Commands

```bash
npm ci --legacy-peer-deps
npm run typecheck
npm test                          # core tests (Node) and UI tests (jest-expo)
npx expo start                    # dev server; open in Expo Go or an emulator
npx expo-doctor                   # dependency and config checks
npx expo export --platform android --platform ios --output-dir /tmp/sheltr-export   # prove Metro can bundle both
```

Add packages with `npx expo install <package> -- --legacy-peer-deps`.

## Running a native build

`android/` and `ios/` are generated and git-ignored.

```bash
npx expo prebuild --clean
cd android && ./gradlew assembleRelease      # needs ANDROID_HOME and a JDK
```

## Sync configuration

Set `SHELTR_PUBLISHER_BASE_URL` and `SHELTR_PUBLISHER_PUBLIC_KEY_HEX` when building. See `docs/publishing.md`.
