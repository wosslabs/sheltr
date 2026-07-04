# Sheltr — Phase 1: Foundation Design (Repo, ADRs, C4, Domain Model, DB Schema, API Contracts)

Status: Approved
Date: 2026-07-04

## 1. Scope

This spec covers **Phase 1 only** of the Sheltr Disaster Response Knowledge Assistant: the
architectural foundation that later phases (backend implementation, ingestion pipeline,
mobile app, security, observability, deployment) build on. No production code is written
in this phase beyond a Maven module skeleton with empty hexagonal packages.

Sheltr is a RAG-based platform that answers citizen questions during disasters (earthquake,
flood, hurricane, tornado, tsunami, wildfire, extreme heat, winter storm) by retrieving from
government/NGO sources (FEMA, American Red Cross, WHO, UNOCHA) and generating grounded,
cited answers via Google Gemini through Spring AI. The platform must never invent emergency
guidance — every answer is backed by a citation to a source document.

The full product vision (11 bounded contexts, Flutter mobile app, maps, offline mode, push
notifications, multi-cloud deployment) is much larger than one buildable increment. This
spec deliberately narrows Phase 1 to: repo scaffolding, architecture decision records, C4
diagrams (context + container level), full DDD domain modeling for the 4 bounded contexts
Phase 2 will implement (Knowledge, Search, Ingestion, AI), one-paragraph stubs for the
remaining 7 contexts, database schema for the 4 core contexts, and OpenAPI contracts for
the core RAG endpoints. Everything else (mobile, maps, offline, push, remaining bounded
contexts, CI/CD, k8s manifests) is out of scope for this spec and gets its own spec when its
phase starts.

## 2. Repo Layout

```
sheltr/
├── apps/
│   ├── backend/            # Spring Boot, Maven multi-module, hexagonal skeleton (built this phase)
│   ├── mobile/              # Flutter — placeholder README only, built in Phase 3
│   └── admin-web/           # placeholder README only, built in a later phase
├── packages/
│   ├── shared-domain/       # placeholder — cross-cutting VOs/types, populated when admin-web/mobile need shared contracts
│   ├── shared-api/          # placeholder — generated OpenAPI clients
│   ├── shared-ui/           # placeholder
│   └── shared-test/         # placeholder
├── docs/
│   ├── architecture/        # C4 diagrams doc, domain model doc (this phase)
│   ├── adr/                 # ADR-0001..0009 (this phase)
│   ├── diagrams/             # Mermaid diagram sources (this phase)
│   ├── openapi/              # openapi.yaml (this phase)
│   └── superpowers/specs/    # this spec and future specs
├── infra/
│   ├── docker/               # docker-compose.yml (Postgres+pgvector), built this phase
│   └── k8s/                   # placeholder, later phase
├── .github/
│   └── workflows/            # placeholder, CI built in a later phase
├── scripts/                   # placeholder dev helper scripts
├── Makefile                   # root task runner: build, test, up, down
├── LICENSE                    # Apache 2.0
└── README.md                  # project overview, links to docs/architecture
```

No monorepo orchestrator (no Turborepo/Nx). Rationale: the stack is polyglot (Maven for
backend, Flutter/Dart for mobile, likely Vite/npm for admin-web) and none of the JS-first
orchestrators natively understand Maven or Flutter build graphs well enough to justify the
added tooling surface. A root `Makefile` (or `justfile`) provides shared entrypoints
(`make build`, `make test`, `make up`) that shell out to each app's native tool. This is
documented as ADR-0001.

Placeholder directories (`apps/mobile`, `apps/admin-web`, `packages/*`, `infra/k8s`,
`.github/workflows`) each contain a `README.md` stating what will live there and which future
phase populates it — so the monorepo shape is visible from Phase 1 even though most of it is
empty.

## 3. Architecture Decision Records

Nine ADRs, Michael Nygard format (Context, Decision, Consequences, Alternatives Considered),
stored as `docs/adr/NNNN-title.md`:

- **ADR-0001** — Monorepo, no build orchestrator
- **ADR-0002** — Hexagonal architecture for backend (domain has zero framework dependencies;
  dependency direction: REST API → Application Services → Domain ← Ports ← Adapters;
  infrastructure depends on domain, never the reverse)
- **ADR-0003** — Gemini-only via Spring AI abstractions (business logic never imports a
  Gemini-specific SDK type; all AI capability is behind Spring AI's portable interfaces so a
  future model swap doesn't touch domain/application code)
- **ADR-0004** — PostgreSQL + pgvector for the vector store, instead of a dedicated vector
  database (Pinecone/Weaviate/Milvus). Rationale: one less operational system, transactional
  consistency between relational document metadata and embeddings, sufficient performance at
  the expected corpus scale (government guidance documents, not web-scale). Revisit if corpus
  size or query volume outgrows pgvector.
- **ADR-0005** — JOOQ over JPA/Hibernate. Rationale: hybrid search and pgvector similarity
  queries need precise, typed SQL control that an ORM's generated queries fight against; JOOQ
  keeps persistence adapters thin and explicit, which fits the hexagonal port/adapter
  boundary better than an ORM's implicit entity lifecycle.
- **ADR-0006** — Flyway for schema migration and versioning, one migration path per
  Postgres schema (`knowledge`, `ingestion`, `ai`, and future ones as needed).
- **ADR-0007** — Bounded context boundaries: the 11 contexts (Knowledge, Search, Disaster,
  User, Location, Emergency, Notification, Administration, Analytics, AI, Ingestion) and the
  decision to fully design only Knowledge, Search, Ingestion, AI in Phase 1 (these are what
  Phase 2's backend implementation actually builds), deferring full design of the remaining 7
  to the phase that implements them.
- **ADR-0008** — Citation-grounded generation is mandatory and enforced structurally, not
  just via prompt instruction: if retrieval returns no chunks above a relevance threshold,
  the AI context's answer-generation domain service returns a "no answer available" result
  instead of invoking the LLM for a free-form answer. This is the platform's core trust
  guarantee and shapes the AI context design in Section 4.
- **ADR-0009** — Apache 2.0 license (patent grant clause, consistent with the
  Spring/Google-adjacent ecosystem this stack builds on).

## 4. C4 Diagrams (Mermaid, Context + Container level only)

Stored as Mermaid `C4Context`/`C4Container` diagrams in `docs/diagrams/`, embedded in
`docs/architecture/c4.md`. Component-level diagrams are deliberately deferred until Phase 2
code exists, to avoid diagrams drifting from an implementation that hasn't been written yet.

**System Context diagram** — actors and external systems:
- Actor: Citizen — mobile app end user, disaster-affected
- Actor: Admin — content curator reviewing ingested documents
- System: Sheltr Disaster Response Assistant (single box)
- External system: Google Gemini — embeddings, generation, query rewriting, HyDE
- External system: FEMA / American Red Cross / WHO / UNOCHA — source data pulled by ingestion
- External system: Firebase Cloud Messaging — push notifications (shown dotted/future, not
  built until the Push Notifications phase)
- External system: Google Maps — shelter/hospital mapping (shown dotted/future)

**Container diagram** — inside the Sheltr system:
- Mobile App (Flutter) — dotted/future, shown for completeness, not built this phase
- Admin Web — dotted/future
- Backend API (Spring Boot, hexagonal) — the one real container built this phase
- PostgreSQL + pgvector — document/chunk/embedding/conversation storage
- Ingestion Worker — a logical container within the backend Maven build for Phase 1 (own
  module); whether it splits into a separately deployable service is an open question to
  revisit once real ingestion volume/latency is known
- Google Gemini — external, invoked via Spring AI

Dotted/future containers are visually distinguished in the diagram so it doesn't overclaim
what Phase 1 actually builds.

## 5. Domain Model

### 5.1 Knowledge context (core domain — full design)

Responsibility: canonical store of processed documents, chunks, and embeddings; versioning
and deduplication.

**Aggregate: `KnowledgeDocument`** (root)
- `id: DocumentId`, `title`, `sourceAgency`, `sourceUri`, `disasterTypes: Set<DisasterType>`,
  `publishedDate`, `version: DocumentVersion`, `checksum: Checksum`,
  `supersedes: Optional<DocumentId>`, `status: DocumentStatus` (ACTIVE, SUPERSEDED, RETRACTED)

**Aggregate: `Chunk`** (root — deliberately a separate aggregate from `KnowledgeDocument`,
not an entity nested inside it)
- `id: ChunkId`, `documentId: DocumentId` (cross-aggregate reference, not an object graph),
  `content: ChunkContent`, `embedding: Embedding`, `pageNumber: Optional<PageNumber>`,
  `chunkIndex: int`, `metadata: ChunkMetadata` (section heading, inherited disaster types,
  checksum)

Rationale for splitting `Chunk` out as its own aggregate: retrieval (Search context) queries
chunks directly via vector similarity at scale — loading a full `KnowledgeDocument` aggregate
(potentially hundreds of chunks) on every vector search hit would be wasteful. This also
implements the "Parent Document Retrieval" requirement cleanly: a chunk is fetched via vector
search, then the parent `KnowledgeDocument` is optionally fetched by `documentId` only when
full-document context is needed.

**Domain events**: `DocumentIndexed`, `DocumentSuperseded`, `DocumentRetracted`,
`ChunkEmbedded`

**Value objects**: `DocumentId`, `ChunkId`, `ChunkContent`, `Embedding`, `PageNumber`,
`ChunkMetadata`, `DocumentVersion`, `Checksum`, `DisasterType` (enum: EARTHQUAKE, FLOOD,
HURRICANE, TORNADO, TSUNAMI, WILDFIRE, EXTREME_HEAT, WINTER_STORM)

**Repositories (ports)**: `KnowledgeDocumentRepository`, `ChunkRepository`

### 5.2 Ingestion context (core domain — full design)

Responsibility: pull source content from external agencies, extract, clean, checksum,
version, chunk, hand off to Knowledge.

**Aggregate: `SourceDocument`** (root)
- `id: DocumentId`, `sourceUri: SourceUri`, `sourceType: SourceType` (PDF, HTML, MARKDOWN,
  TEXT, RSS_ITEM), `checksum: Checksum`, `version: DocumentVersion`,
  `status: IngestionStatus` (DISCOVERED, DOWNLOADED, EXTRACTED, CLEANED, CHUNKED, INDEXED,
  FAILED), `metadata: DocumentMetadata` (title, publishedDate, agency, disasterTypes,
  language)

**Domain events**: `DocumentDiscovered`, `DocumentDownloaded`, `DocumentContentChanged`
(fired when checksum differs from the prior version — triggers re-indexing),
`DocumentIngestionFailed`, `DocumentChunked`

**Value objects**: `DocumentId`, `SourceUri`, `Checksum`, `DocumentVersion`,
`DocumentMetadata`, `IngestionStatus`, `SourceType`

**Repository (port)**: `SourceDocumentRepository`

**Connector abstraction (port)**: `SourceConnector` — `fetch(SourceDescriptor): RawContent`.
Per-agency adapters (`FemaConnector`, `RedCrossConnector`, `WhoConnector`,
`UnochaConnector`) implement this port. This is what makes adding a new data source a matter
of writing one new adapter, not touching the pipeline.

### 5.3 Search context (supporting domain — intentionally service-heavy, not aggregate-heavy)

Responsibility: retrieval orchestration — semantic search, hybrid search, metadata
filtering, multi-query retrieval, HyDE, cross-encoder re-ranking, context compression.

This context holds no persistent aggregate root in Phase 1. This is a deliberate DDD
strategic classification choice (Search is a supporting subdomain, not the core domain) and
not an oversight or anemic-model gap: retrieval results are transient by nature, so modeling
them as persisted aggregates would be modeling data that doesn't need to outlive a request.

**Domain concepts** (value objects + domain service ports):
- `SearchQuery` (VO: raw text, filters — disaster types, agency, date range)
- `Retriever` (port, strategy interface) — implementations: `SemanticSearchRetriever`,
  `HybridSearchRetriever`, `MultiQueryRetriever`, `HyDERetriever`
- `RankedResult` (VO: chunk reference, score, rank)
- `ReRanker` (port — cross-encoder re-ranking adapter)
- `ContextCompressor` (port — reduces retrieved chunks to relevant snippets before prompt
  assembly)

### 5.4 AI context (core domain — full design)

Responsibility: conversation orchestration, citation-grounded answer generation, confidence
scoring, streaming.

**Aggregate: `Conversation`** (root)
- `id: ConversationId`, `userRef: Optional<String>` (anonymous session id in Phase 1 — no
  User context yet), `messages: List<Message>` (bounded, held as entities within the
  aggregate — a conversation's history is small enough this doesn't become a "huge
  aggregate" problem the way `Chunk`-in-`KnowledgeDocument` would have), `createdAt`,
  `lastActiveAt`

**Entity: `Message`** (within `Conversation`)
- `id: MessageId`, `role: MessageRole` (USER, ASSISTANT), `content: MessageContent`,
  `citations: List<Citation>` (only populated on ASSISTANT messages),
  `confidence: AnswerConfidence` (score + level: LOW/MEDIUM/HIGH),
  `retrievedChunkIds: List<ChunkId>` (audit trail of what was retrieved for this answer)

**Value objects**: `ConversationId`, `MessageId`, `MessageContent`, `MessageRole`,
`Citation` (documentId, chunkId, pageNumber, sourceTitle, sourceUrl), `AnswerConfidence`

**Domain events**: `ConversationStarted`, `AnswerGenerated`, `LowConfidenceAnswerFlagged`
(fired when confidence falls below a threshold — the hook future phases use for
human-review escalation)

**Repository (port)**: `ConversationRepository`

**Domain service (port)**: `AnswerGenerator` — takes a `SearchQuery` and
`List<RankedResult>`, returns a `Message`. Implements ADR-0008 structurally: if the input
`RankedResult` list is empty or entirely below the relevance threshold, this service returns
a `NoAnswerAvailable` result without calling the LLM — the "never invent guidance" rule is
enforced in the domain service's control flow, not left to a prompt instruction alone.

### 5.5 Remaining 7 contexts (stubs — full design deferred to their implementing phase)

- **Disaster** — disaster type taxonomy and active-disaster tracking. Future entity:
  `DisasterEvent`.
- **User** — identity, profile, authentication. Deferred to the Security phase.
- **Location** — geo data for shelters, hospitals, distribution points. Future entity:
  `PointOfInterest`. Deferred to the Mobile/Maps phase.
- **Emergency** — emergency contacts and protocols. Deferred.
- **Notification** — push alert delivery via FCM. Future entity: `AlertNotification`.
  Deferred to the Push Notifications phase.
- **Administration** — admin curation/moderation workflows over ingested documents.
  Deferred.
- **Analytics** — usage and business metrics beyond raw observability. Deferred.

## 6. Database Schema (Phase 1 — Knowledge, Ingestion, AI schemas only)

Separate Postgres schemas per bounded context (`knowledge`, `ingestion`, `ai`) — logical
isolation within one database instance. No cross-schema foreign keys from `ingestion` into
`knowledge` (the handoff between those contexts happens through domain events / an
integration step in the application layer, not a DB-level FK), keeping the contexts
decoupled at the persistence layer too. `User`/`session`/`audit` tables are explicitly out of
scope this phase — added when the Security phase needs them.

```sql
-- schema: knowledge
knowledge.documents (
  id UUID PK, title TEXT, source_agency TEXT, source_uri TEXT,
  source_type TEXT, disaster_types TEXT[], published_date DATE,
  version INT, supersedes_id UUID NULL REFERENCES documents(id),
  status TEXT, checksum TEXT, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ
)

knowledge.chunks (
  id UUID PK, document_id UUID REFERENCES documents(id),
  content TEXT, content_tsv TSVECTOR, embedding VECTOR(768),
  page_number INT NULL, chunk_index INT, section_heading TEXT,
  checksum TEXT, created_at TIMESTAMPTZ
)
-- indexes: HNSW (pgvector) on chunks.embedding; GIN on chunks.content_tsv (hybrid search)

-- schema: ingestion
ingestion.source_documents (
  id UUID PK, source_uri TEXT, source_type TEXT, checksum TEXT,
  version INT, status TEXT, discovered_at TIMESTAMPTZ, downloaded_at TIMESTAMPTZ,
  metadata JSONB
)

ingestion.ingestion_runs (
  id UUID PK, source_document_id UUID, started_at TIMESTAMPTZ, finished_at TIMESTAMPTZ,
  status TEXT, error_message TEXT NULL
)

-- schema: ai
ai.conversations (
  id UUID PK, user_ref TEXT NULL, created_at TIMESTAMPTZ, last_active_at TIMESTAMPTZ
)

ai.messages (
  id UUID PK, conversation_id UUID REFERENCES conversations(id),
  role TEXT, content TEXT, confidence_score NUMERIC NULL,
  confidence_level TEXT NULL, created_at TIMESTAMPTZ
)

ai.citations (
  id UUID PK, message_id UUID REFERENCES messages(id),
  chunk_id UUID REFERENCES knowledge.chunks(id),
  source_title TEXT, source_url TEXT, page_number INT NULL
)
```

Flyway migrations live at
`apps/backend/src/main/resources/db/migration/{knowledge,ingestion,ai}/V1__*.sql`. JOOQ code
generation runs against the migrated schema during the Maven `generate-sources` phase.

## 7. API Contracts (Phase 1 — core RAG endpoints only)

OpenAPI spec at `docs/openapi/openapi.yaml`, version `v1`.

```
POST   /api/v1/conversations                 -- start a new conversation
POST   /api/v1/conversations/{id}/ask         -- ask a question; SSE streaming response
GET    /api/v1/conversations/{id}             -- get conversation history
GET    /api/v1/documents/{id}                 -- document metadata (citation drill-down)
GET    /api/v1/documents/{id}/citations       -- chunks/pages cited from this document
POST   /api/v1/admin/ingestion/runs           -- trigger ingestion for a source
GET    /api/v1/admin/ingestion/runs/{id}      -- ingestion run status
GET    /actuator/health
GET    /actuator/info
```

`/ask` is documented as a separate streaming operation (`text/event-stream`) per Spring AI's
streaming convention, alongside a non-streaming variant for simple clients. Admin endpoints
are unsecured in this phase (no auth exists yet — a known, explicitly tracked gap closed in
the Security phase) but are already path-namespaced under `/admin` so authorization can be
attached later without changing paths or client contracts.

## 8. Out of Scope for Phase 1

Explicitly not built or designed in depth this phase: Flutter mobile app, admin-web,
Google Maps integration, offline mode, push notifications (FCM), full DDD design for
Disaster/User/Location/Emergency/Notification/Administration/Analytics contexts, JWT/OAuth2
security, rate limiting, observability (Micrometer/OTel/Grafana), CI/CD pipelines, Kubernetes
manifests, multi-cloud deployment targets, RAG evaluation/hallucination-metric tooling. Each
becomes its own spec when its phase starts, per the phased delivery plan in the original
product brief.
