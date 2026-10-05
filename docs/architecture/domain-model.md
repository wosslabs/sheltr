# Domain Model — Phase 1

Full source of truth: `docs/superpowers/specs/2026-07-04-phase1-foundation-design.md`, Section 5.

## 5.1 Knowledge context (core domain — full design)

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
TROPICAL_CYCLONE, TORNADO, TSUNAMI, WILDFIRE, EXTREME_HEAT, WINTER_STORM, LANDSLIDE,
GLACIAL_LAKE_FLOOD, VOLCANO, DROUGHT)

**Repositories (ports)**: `KnowledgeDocumentRepository`, `ChunkRepository`

## 5.2 Ingestion context (core domain — full design)

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

## 5.3 Search context (supporting domain — intentionally service-heavy, not aggregate-heavy)

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

## 5.4 AI context (core domain — full design)

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

## 5.5 Remaining 7 contexts (stubs — full design deferred to their implementing phase)

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
