# Flutter UX redesign (2026-11-03)

## Goal

Clear the `tools/ux` audit failures and replace the sparse Material defaults with a calm civic preparedness UI. Fix read-aloud clipping and voice quality.

## Visual direction

- Forest green primary (`#0D3B2E`) on cool sage off-white (`#EEF2F0`).
- Source Serif 4 for titles; IBM Plex Sans for body and UI.
- No purple gradients, cream/terracotta, or newspaper layout.

## Behaviour

- Home title is a semantic heading inside a `main` landmark.
- Search field: short accessible name ("Search"), Search button, Enter submits to `/search`.
- Hazard tiles: name + "Open guidance" hint; tap opens `/search` with that hazard.
- Answer: lead panel, readable passages, compact source cards (no raw URL as body text).
- Shared TTS helper: await engine, prefer enhanced en-US voice, slower rate, short pre-speak delay.

## Success

- `tools/ux` audit has no fails (score ≥ 70).
- `flutter test` in `apps/flutter` stays green.
