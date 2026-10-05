# Sheltr — Phase 2 (Sub-project 2): MVP Ingestion Pipeline Design

Superseded in part by ADR-0015.

Status: Approved
Date: 2026-07-13

## 1. Scope

This spec covers the **second buildable slice of Phase 2** for the Sheltr Disaster Response
Knowledge Assistant: a real, end-to-end ingestion pipeline that downloads, extracts, cleans,
chunks, embeds, and stores one real FEMA PDF, proving the `SourceConnector` extensibility
pattern the original product brief's ingestion architecture calls for.

The original product brief's Ingestion pipeline is itself multiple independent pieces:
multi-format extraction (PDF, Word, HTML, Markdown, RSS, government websites), four agency
connectors (FEMA, American Red Cross, WHO, UNOCHA), and deduplication/versioning/incremental
re-indexing. This spec deliberately narrows to one connector, one format, one real document,
and first-ingest only — proving the pipeline shape works before generalizing it.

**In scope:**
- Real HTTP download of one live FEMA PDF: [FEMA Disaster Preparedness Guide for
  Caregivers](https://www.ready.gov/sites/default/files/2024-02/ready-gov_caregivers-preparedness-guide.pdf)
  (verified live, 200 OK, ~2.5MB, `application/pdf`, as of this spec's writing).
- Full pipeline: Downloader → PDF Content Extractor (Apache PDFBox) → Cleaner → Metadata
  Extractor (connector-supplied tags, not auto-classified) → Chunker (page-aware,
  ~1000-char chunks with overlap) → Embedding Generator (reused from Phase 2) → Knowledge
  Repository (reused from Phase 2, extended with insert/create methods).
- Real `SourceDocument` aggregate and status tracking (`DISCOVERED` → `DOWNLOADED` →
  `EXTRACTED` → `CLEANED` → `CHUNKED` → `INDEXED`, or `FAILED`), persisted via a new
  `SourceDocumentRepository` port/adapter, giving checksum-based dedup-readiness for later
  slices at near-zero extra cost this slice.
- Implements the two `/api/v1/admin/ingestion/runs` REST endpoints the Phase 1 OpenAPI spec
  already documents but Task 13 of the RAG-loop plan left unimplemented.
- End-to-end test proving the full Ingestion→RAG loop: ingest the real document, then query
  it via Phase 2's `Retriever` and get real, relevant results back.

**Explicitly out of scope** (each becomes its own spec when its phase starts): Word/HTML/
Markdown/RSS extraction, the Red Cross/WHO/UNOCHA connectors, deduplication logic (skipping
re-ingestion of an unchanged checksum), versioning (superseding an existing `KnowledgeDocument`
when its source changes), incremental re-indexing, automated disaster-type/topic
classification, async/queued ingestion execution (this slice runs synchronously within the
HTTP request), authentication on the admin endpoints (still an open Phase 1 gap).

## 2. Architecture

```
POST /admin/ingestion/runs → IngestDocumentService (ingestion/application)
  → SourceConnector port (ingestion/domain) → FemaHttpConnector (ingestion/infrastructure)
      — downloads PDF bytes, computes SHA-256 checksum
  → PdfContentExtractor (ingestion/infrastructure) — Apache PDFBox, per-page text extraction
  → TextCleaner (ingestion/domain or application, pure function) — whitespace/boilerplate
      normalization
  → Chunker (ingestion/domain or application) — splits into ~1000-char chunks with ~100-char
      overlap, tracks originating PDF page number per chunk
  → EmbeddingGenerator port (REUSED from knowledge/domain, built in Phase 2 — no new code)
  → KnowledgeDocumentRepository / ChunkRepository (REUSED from knowledge/domain, Phase 2
      ports — extended with new create/insert methods, since Phase 2 only ever needed
      read + embedding-backfill, not creating brand-new documents/chunks)
```

`SourceDocument` status is persisted via `SourceDocumentRepository` (ingestion/domain port,
new jOOQ adapter in ingestion/infrastructure) after each pipeline stage completes. Any stage
failure sets status to `FAILED` with a recorded reason and stops the pipeline — no partial
writes to `knowledge.documents`/`knowledge.chunks`.

## 3. SourceDocument Domain Model

Implements, for real, the `SourceDocument` aggregate and `SourceConnector` port that Phase 1's
domain model spec (`docs/superpowers/specs/2026-07-04-phase1-foundation-design.md`, Section
5.2) and database schema (`ingestion.source_documents`, `ingestion.ingestion_runs`) already
designed but never had a working implementation:

- **`SourceDocument`** (ingestion/domain, aggregate root): `id: DocumentId`,
  `sourceUri: String`, `sourceType: SourceType` (`PDF` only this slice — enum has room for
  future values), `checksum: Checksum` (reused from knowledge/domain), `status:
  IngestionStatus` (`DISCOVERED, DOWNLOADED, EXTRACTED, CLEANED, CHUNKED, INDEXED, FAILED`),
  `metadata: SourceMetadata` (title, sourceAgency, disasterTypes — connector-supplied).
- **`SourceConnector`** port (ingestion/domain): `RawContent fetch(String sourceUri)` —
  downloads raw bytes and reports a computed checksum. `FemaHttpConnector`
  (ingestion/infrastructure) is the one implementation this slice, using Java's built-in
  `java.net.http.HttpClient` (no new HTTP library dependency needed).
- **`SourceDocumentRepository`** port (ingestion/domain): `save(SourceDocument)`,
  `findById(DocumentId)` — new jOOQ dynamic-DSL adapter in ingestion/infrastructure, same
  pattern as Phase 2's Knowledge/AI repository adapters.

## 4. Metadata & Chunking

- **Metadata is connector-supplied, not auto-classified.** `FemaHttpConnector` carries a
  small static config for this document: `sourceAgency = "FEMA"`, a hand-picked
  `Set<DisasterType>` tag (the caregivers guide is general/multi-hazard content, so all 8
  `DisasterType` values), and a fallback title. Real PDF-embedded metadata (via PDFBox's
  `PDDocumentInformation`) is preferred when present in the document; the connector's static
  title is the fallback.
- **Chunker**: splits cleaned text into ~1000-character chunks with ~100-character overlap
  (word/sentence-boundary-aware — never splits mid-word), tracking which PDF page each chunk
  started on. PDFBox extracts text per-page, so page boundaries are known before chunking
  begins; a chunk that spans a page boundary is tagged with the page it started on.

## 5. API Surface

Implements the two admin endpoints already documented in `docs/openapi/openapi.yaml`:

- `POST /api/v1/admin/ingestion/runs` — body `{sourceUri, sourceType}`. Runs the pipeline
  **synchronously** within the HTTP request (single document, no queue/async worker this
  slice — reasonable at this scale; async execution is a natural follow-up once multiple
  sources or scheduled re-ingestion matter). Returns `202 Accepted` with an `IngestionRun`
  (`id`, `sourceDocumentId`, `status`, `startedAt`) matching the OpenAPI schema.
- `GET /api/v1/admin/ingestion/runs/{id}` — reads back run status from
  `ingestion.ingestion_runs`.
- Both remain unsecured (no authentication exists yet — unchanged, tracked Phase 1 gap).

## 6. Testing Strategy

- **Unit tests** (pure logic, no I/O): `Chunker`'s splitting logic (text shorter than chunk
  size, exact-multiple boundaries, overlap correctness, no mid-word splits), `TextCleaner`'s
  normalization rules.
- **`PdfContentExtractor` test**: a small, checked-in test PDF fixture (not the live 2.5MB
  FEMA document) proving PDFBox extraction and per-page tracking work, with no network
  dependency in this unit test.
- **`FemaHttpConnector` test**: a real HTTP call to the live FEMA URL, verifying checksum
  computation and byte-for-byte download correctness — network-dependent, accepted as a
  slower integration test (same category as the project's existing Testcontainers-gated
  tests).
- **End-to-end test** (capstone, real Gemini + real FEMA download): calls
  `POST /admin/ingestion/runs` with the real FEMA URL, confirms the run reaches `INDEXED`,
  then verifies `GET /documents/{id}` returns the stored document and a semantic-search-style
  query (reusing Phase 2's `Retriever`) against the newly-ingested content returns real,
  relevant chunks — proving the full Ingestion→RAG loop is connected end to end, not just
  that ingestion "completes" in isolation.

## 7. Error Handling & Dependencies

- Any stage failure (network error, PDF parse failure, embedding failure) sets
  `SourceDocument.status = FAILED` with a recorded reason and returns a clean error from the
  REST endpoint. No partial writes to `knowledge.documents`/`knowledge.chunks` — the
  per-document pipeline's persistence boundary follows the same transactional discipline
  established in Phase 2's `JooqConversationRepository` fix (Task 11 amendment).
- **New dependency**: Apache PDFBox (`org.apache.pdfbox:pdfbox`, Apache 2.0 license,
  consistent with the project's own license) for PDF text extraction — the first new
  third-party dependency added since the Phase 1/Phase 2 core stack.

## 8. Global Constraints Carried Forward

- Domain packages (`ingestion/domain`) have zero framework imports (ADR-0002).
- No Gemini/Spring-AI-specific type outside `infrastructure`/`config` packages (ADR-0003) —
  not newly exercised this slice (reuses Phase 2's existing adapters), but the constraint
  still applies to any new code touching Gemini.
- `GEMINI_API_KEY` read only from environment (carried from Phase 2).
- Container runtime: Podman only (ADR-0010).
- Real-network/real-API tests are accepted as slower integration tests, consistent with the
  project's existing pattern of gating Gemini-dependent tests behind
  `@EnabledIfEnvironmentVariable` and Testcontainers-dependent tests on a live container
  runtime.
