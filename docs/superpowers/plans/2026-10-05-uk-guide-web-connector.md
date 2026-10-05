# UK Guide and Web-Page Connector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ingest the approved UK "Prepare" guidance (OGL v3.0) through a new HTML web-page connector, and add a country dimension to documents and retrieval, as set out in `docs/superpowers/specs/2026-10-05-country-guides-and-connectors-design.md`.

**Architecture:** Add `HtmlPageConnector` in the ingestion infrastructure, behind the existing `SourceConnector` port. The application service already depends only on the port, so the change is a connector plus a source-type switch. Country becomes an optional column on `knowledge.documents`, set from the manifest, and retrieval gains an optional country boost that never filters out global passages.

**Tech Stack:** Java 25, Spring Boot 4.1.0, jOOQ (plain SQL), Flyway, Jsoup for HTML parsing (new dependency: a robust HTML parser is needed to strip navigation and footers; hand-written parsing is the rejected alternative), JUnit 5, AssertJ, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-10-05-country-guides-and-connectors-design.md`

## Global Constraints

- Licence must be recorded per document; ingestion refuses an entry without one.
- Fetch allowlist from Phase 0 (`sheltr.ingestion.allowed-hosts`) applies to web pages too. Add the UK host `prepare.campaign.gov.uk` to the allowlist.
- Redirects: the Phase 0 rule forbids following redirects. The UK source responds with a 301, so the web connector follows at most 3 redirects, each to the same host or to a host already in the allowlist. The PDF connector keeps `Redirect.NEVER`.
- Connect timeout and request timeout from `sheltr.ingestion.connect-timeout` and `sheltr.ingestion.request-timeout`.
- Checksum of the extracted main text (SHA-256) is the approval checksum for an HTML source.
- Domain packages stay free of Spring and Jsoup.
- Commit trailer: `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`.

## Review Focus

1. A web page that redirects off the allowlisted host is refused, not followed.
2. Navigation, footer and cookie banners are removed; main content is kept.
3. A page whose extracted text changes fails its manifest checksum (needs re-approval).
4. A country code that is not two letters, or is not `GLOBAL`, is rejected by the manifest parser.
5. A document with no country stays eligible for every country query (global fallback).

## File structure

- Modify `apps/backend/pom.xml` — add `org.jsoup:jsoup`.
- Create `apps/backend/src/main/java/com/sheltr/ingestion/infrastructure/HtmlPageConnector.java`.
- Create `apps/backend/src/main/java/com/sheltr/ingestion/domain/MainContentExtractor.java` (pure logic, Jsoup-based, in infrastructure; the domain only sees text).
- Create `apps/backend/src/main/resources/db/migration/V9__document_country_and_html_source.sql`.
- Modify `apps/backend/src/main/java/com/sheltr/ingestion/application/CorpusManifest.java` — `source_type` (`pdf` | `html`) and `country` per entry.
- Modify `apps/backend/src/main/java/com/sheltr/ingestion/application/IngestDocumentService.java` — choose connector by source type; pass country to the document.
- Modify `apps/backend/src/main/java/com/sheltr/knowledge/domain/KnowledgeDocument.java` and `JooqKnowledgeDocumentRepository.java` — country code.
- Modify `apps/backend/src/main/java/com/sheltr/search/infrastructure/HybridSearchAdapter.java` — country boost (only when a country is given).
- Modify `corpus/manifest.yaml` — add the UK entry after tests pass.
- Tests under the matching packages.

---

### Task 1: Schema and manifest fields

**Files:** V9 migration, `CorpusManifest.java`, `CorpusManifestTest.java`, `KnowledgeDocument.java`, `JooqKnowledgeDocumentRepository.java`.

- [ ] **Step 1: Failing tests.** `CorpusManifestTest`: an entry with `country: GB` parses to country `GB`; `country: GLOBAL` parses to null; `country: gbr` or `country: 1A` is rejected naming the entry; `source_type: html` parses; `source_type: docx` is rejected.
- [ ] **Step 2: Migration.** `ALTER TABLE knowledge.documents ADD COLUMN country_code CHAR(2) NULL;` plus `CREATE INDEX IF NOT EXISTS idx_documents_country ON knowledge.documents (country_code);`. No change to `source_type` storage (it is already text).
- [ ] **Step 3: Implement** the manifest fields and the document field. Repository writes and reads `country_code`.
- [ ] **Step 4:** run focused tests, then `mvn -q test` from `apps/backend`. Commit.

### Task 2: HTML connector and main-content extraction

**Files:** `pom.xml` (Jsoup), `HtmlPageConnector.java`, `MainContentExtractor.java`, tests `HtmlPageConnectorTest.java` (local `HttpServer`), `MainContentExtractorTest.java` (fixture HTML under `src/test/resources/html/`).

- [ ] **Step 1: Failing tests.** Extractor: keeps `<main>` or `<article>` text, drops `<nav>`, `<footer>`, `<script>`, cookie-banner elements; output has no runs of whitespace and is stable (same input, same output). Connector: follows a 301 to the same host; refuses a 301 to a host not on the allowlist (fail, no second request); refuses after 3 redirects; a 404 fails; returns extracted text bytes and a SHA-256 of the text.
- [ ] **Step 2: Implement** the extractor with Jsoup (select `main`, else `article`, else `body`; remove `nav, footer, script, style, noscript, header`, and elements with class or id containing `cookie`). Implement the connector with `HttpClient` (`Redirect.NEVER`), follow redirects manually with the host check.
- [ ] **Step 3:** focused tests, full suite, commit.

### Task 3: Source-type switch and country in ingestion

**Files:** `IngestDocumentService.java`, `IngestDocumentServiceConfig.java`, `IngestCorpusService.java` (pass source type and country), tests.

- [ ] **Step 1: Failing tests.** An `html` manifest entry uses the web connector and the fetch counter is 1; a `pdf` entry uses the PDF connector; the saved document carries the country code (`GB`) or null for `GLOBAL`; an html entry whose extracted-text checksum differs from the manifest checksum fails with the checksum-mismatch message.
- [ ] **Step 2: Implement.** `IngestDocumentService` takes a map from source type to `SourceConnector`; the manifest entry selects one. Keep the existing allowlist check first.
- [ ] **Step 3:** focused tests, full suite, commit.

### Task 4: Country-aware retrieval

**Files:** `HybridSearchAdapter.java`, `PgvectorSemanticSearchAdapter.java`, `SearchQuery.java` (optional country), `AnswerPipeline`/`AskRequestDto` as needed, tests.

- [ ] **Step 1: Failing tests.** With a country `GB`, a `GB` passage with equal relevance ranks above a `US` passage; a `GLOBAL` passage is still returned when no `GB` passage matches; no country given changes nothing (existing tests unchanged).
- [ ] **Step 2: Implement** as a boost, not a filter: documents with `country_code = ?` get a fixed boost; `country_code IS NULL` is eligible with no boost; other countries are eligible with no boost in v1 (the spec says never filter).
- [ ] **Step 3:** focused tests, full suite, commit.

### Task 5: Ingest the UK guide

**Files:** `corpus/manifest.yaml`, `application-local.yml` (allowlist host), test properties if needed.

- [ ] **Step 1:** add the UK entry (source `https://prepare.campaign.gov.uk/`, title "Prepare", agency "UK Government (GOV.UK campaign site)", hazards `FLOOD`, `WILDFIRE`, `WINTER_STORM` (the three `DisasterType` values the page covers; "power cuts" and "transport disruption" have no matching value and are not mapped), `country: GB`, licence `Open Government Licence v3.0 (https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/)`, and the checksum computed from the extracted text on first fetch.
- [ ] **Step 2:** run `make ingest-corpus` against the local database. Expected: one document ingested, status ACTIVE, country `GB`. Record the checksum in the manifest and re-run to confirm the entry is skipped.
- [ ] **Step 3:** commit the manifest and the allowlist change.

## Verification

- `mvn -q test` from `apps/backend` passes (gated tests skipped as before).
- `make ingest-corpus` ingests the UK guide and the second run skips it.
- Report any step that could not run, with the exact error.
