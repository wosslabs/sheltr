# Mobile and Publisher Design

Status: current. Decision: `docs/adr/0015-offline-mobile-publisher-no-server.md`. Design: `docs/design/offline-mobile-app.md`.

There is no server. The phones work offline from a signed content bundle. The maintainer builds that bundle on a computer with the publisher and uploads it to free static hosting. The apps make one kind of network call: they fetch the bundle from that host.

```mermaid
flowchart LR
  subgraph Maintainer["Maintainer machine"]
    M["corpus/manifest.yaml (approved)"]
    P["tools/publisher (Kotlin/JVM CLI)"]
    K["Ed25519 private key (outside the repo)"]
    M --> P
    K --> P
  end

  S["Official sources (allowlisted HTTPS hosts)"]
  H["Free static hosting (bundle.json, bundle.json.sig, documents/)"]

  P -- "fetch, check checksum, extract, chunk" --> S
  P -- "signed bundle" --> H

  subgraph Phone["Phone (works offline)"]
    SY["sync: verify signature, diff by checksum"]
    ST["SQLite store with FTS5 (atomic swap)"]
    R["keyword retrieval, country and hazard boost, citations, refusal"]
    SET["settings: sync frequency, country, text size"]
    UI["Expo / React Native UI (Android and iOS)"]
    SET --> SY
    SY --> ST --> R --> UI
  end

  H -- "HTTPS, the only network call" --> SY
```

## Notes

- Trust boundary: the signed bundle. The app rejects any bundle whose signature does not verify and keeps the last good content.
- Updates: the app downloads only documents whose checksum changed, and installs the new content in one swap. A failed or interrupted sync keeps the previous content.
- One codebase: `apps/mobile` (Expo, TypeScript). `src/core` holds the bundle format, signature check, store, retrieval, citations, refusal and sync, with no React Native imports, so it is tested in plain Node.
- The publisher and the app use the same bundle format version (`BUNDLE_FORMAT_VERSION`).
- Out of scope for v1 (spec section 1): live hazard alerts, user accounts, analytics, advertising and payments. No server is part of the design.
