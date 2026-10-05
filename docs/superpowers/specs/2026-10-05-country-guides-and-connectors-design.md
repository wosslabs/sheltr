# Sheltr — Country Guides and Source Connectors Design (Amendment)

Status: Draft. Amends docs/superpowers/specs/2026-10-04-corpus-eval-retrieval-design.md §2, §3.2 and §3.4, and the 2026-10-04 Phase 0 design where it touches ingestion.
Date: 2026-10-05

## 1. What changes and why

The corpus previously held only global guidance (see 2026-10-04 spec §3.2: "Not tied to one country"). The product owner has decided that official government guidance for each country must be included, so that answers and guidance cards can be suggested by the user's country of residence and current location. This reverses the country exclusion for ingested guidance. Global guidance remains.

Two gaps in the current pipeline block this:
- Ingestion accepts PDF only.
- The corpus has no country dimension, so retrieval cannot prefer a user's country.

## 2. Decisions

### 2.1 Country dimension
- Add `country_code CHAR(2) NULL` (ISO 3166-1 alpha-2) to `knowledge.documents`. `NULL` means global.
- Manifest entries gain `country: <ISO code or 'GLOBAL'>`.
- Retrieval takes an optional country. When set, a document whose country equals it, or is `NULL`, is eligible. Ranking boosts country-matched passages; it never removes global passages. This follows the existing rule that context boosts and never filters.

### 2.2 Official-source rule (replaces the spec's country exclusion for guides)
- Allowed sources: national government agencies (for example civil protection, meteorological or health ministries and agencies) and international bodies.
- A guide must be official, English or the country's official language only if an English version is also official (see 2.4), and published by the agency itself.
- Reuse terms are recorded per document as today. Government open-licence terms are accepted if the terms allow reuse with attribution.

### 2.3 Connectors
- Keep PDF over HTTP.
- Add an HTML web-page connector. It fetches the page, extracts the main content (not navigation or footer), normalises it to the existing text model, and records the page URL, fetch time and content checksum. A page that changes is treated like a changed document: it fails the manifest checksum check and needs re-approval.
- Other connectors (for example DOCX, RSS, JSON feeds) are not built now. Each needs its own approval and a real source that requires it.

### 2.4 Language
- The 2026-10-04 English-only rule still holds for answers and the eval. Official non-English guides are not ingested until the multilingual sub-project exists.

### 2.5 Location (later sub-projects, unchanged in intent)
- The user's country comes from a country picker, with optional device location for precision. Live alerts are sub-project 3.
- This amendment only supplies the country data that those features read.

### 2.6 Hazard taxonomy
- `DisasterType` is extended only where a document needs it: `TSUNAMI`, `TORNADO`, `WINTER_STORM`, `LANDSLIDE`, `GLACIAL_LAKE_FLOOD`, `VOLCANO`, `DROUGHT` already exist. Add new values only with a source that needs them.

## 3. Scope of the first build

- The set of countries is a product decision and is not fixed here. The first build covers the countries the product owner selects. Coverage of "all" countries is not a build target; each country needs its own approved sources.
- Each country's guides are approved through the same manifest and checksum process as global guidance.

## 4. Testing

- Unit: HTML extraction on a fixed page fixture (main content kept; navigation and footer dropped); country matching and boosting, including the global fallback; manifest validation of country codes.
- Integration: country-filtered retrieval returns global passages when no country-specific passage matches; a changed web page fails its checksum.

## 5. Risks

- Government sites change layout and move URLs. Each HTML source needs a manual check when re-approved.
- Some government sites block automated fetches. Such a source is not ingested by automation; its text is approved by the maintainer and stored as a PDF or a fixed snapshot.
- Official guidance may conflict across countries. Answers must cite the country-specific passage when one is used, so the citation shows which authority said it.
