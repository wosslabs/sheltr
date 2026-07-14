# MVP Ingestion Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a real, end-to-end ingestion pipeline that downloads, extracts, cleans, chunks, embeds, and stores one real FEMA PDF, proving the `SourceConnector` extensibility pattern and closing the `/admin/ingestion/runs` OpenAPI endpoints left unimplemented in Phase 2's RAG-loop plan.

**Architecture:** New `ingestion` bounded context (domain/application/infrastructure/interfaces/config) built on the already-existing Phase 1 hexagonal skeleton and `ingestion.source_documents`/`ingestion.ingestion_runs` schema. Reuses Phase 2's `EmbeddingGenerator` port unchanged; extends `KnowledgeDocumentRepository`/`ChunkRepository` (Phase 2 ports, previously read/embedding-update-only) with new insert methods for creating brand-new documents/chunks.

**Tech Stack:** Apache PDFBox (new dependency, PDF text extraction), Java's built-in `java.net.http.HttpClient` (no new HTTP library), jOOQ dynamic DSL (same pattern as Phase 2), Spring AI Gemini (reused, no new integration code), Podman.

## Global Constraints

- Domain packages (`ingestion/domain`) have zero framework imports — no `org.springframework.*`, no `org.jooq.*`, no PDFBox/Gemini SDK types (ADR-0002).
- No PDFBox/Gemini/Spring-AI-specific type outside `infrastructure`/`config` packages (ADR-0003) — `PdfContentExtractor` (uses PDFBox) and `FemaHttpConnector` (uses `HttpClient`) both live in `ingestion/infrastructure`.
- `SourceType` values match the OpenAPI `TriggerIngestionRequest` schema exactly: `PDF, HTML, MARKDOWN, TEXT, RSS_ITEM` (this slice only implements `PDF`; the others are enum values with no working extractor yet — an unsupported `sourceType` must fail cleanly, not silently misbehave).
- `IngestionStatus` values match the Phase 1 schema default and domain model exactly: `DISCOVERED, DOWNLOADED, EXTRACTED, CLEANED, CHUNKED, INDEXED, FAILED`.
- Checksum algorithm: SHA-256, lowercase hex string (matches `Checksum` value object's existing `hex()` field, no format change).
- Chunk size: 1000 characters, overlap: 100 characters, word-boundary-aware (never splits mid-word). Chunking is done **per PDF page** (not spanning page boundaries) — a deliberate simplification over the design spec's more general "may span pages" language, since PDFBox naturally segments text by page and per-page chunking trivially guarantees correct page attribution with no cross-page bookkeeping. Documented here, not a silent deviation.
- `GEMINI_API_KEY` read only from environment (carried from Phase 2) — `source ~/.zshrc` is NOT reliably ambient in fresh shells; every Gemini-dependent dispatch/test run must source it explicitly and `unset` the stray `SPRING_CONFIG_IMPORT`/`SPRING_DATASOURCE_*`/`SPRING_PROFILES_ACTIVE`/`LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_INTEGRATION` vars that also live in that shell profile from an unrelated project.
- Container runtime: Podman only (ADR-0010) — `make up` / `make migrate` for local verification.
- Real-network and real-Gemini tests are accepted as slower integration tests, consistent with the project's existing `@EnabledIfEnvironmentVariable`/Testcontainers-gated test patterns.
- Target document this slice: `https://www.ready.gov/sites/default/files/2024-02/ready-gov_caregivers-preparedness-guide.pdf` (verified live, 200 OK, ~2.5MB, `application/pdf` as of spec writing — re-verify with a `curl -sI` if a task's test unexpectedly fails on this URL, the government may have moved/renamed the file).

---

### Task 1: Ingestion domain value objects and SourceDocument aggregate

**Files:**
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/domain/SourceType.java`
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/domain/IngestionStatus.java`
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/domain/SourceMetadata.java`
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/domain/RawContent.java`
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/domain/PageText.java`
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/domain/ChunkDraft.java`
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/domain/SourceDocument.java`
- Test: `apps/backend/src/test/java/com/sheltr/ingestion/domain/SourceDocumentTest.java`

**Interfaces:**
- Consumes: `Checksum`, `DisasterType`, `DocumentId`, `PageNumber` from `com.sheltr.knowledge.domain` (Phase 1/2) — cross-context reference, matches the project's established pattern (e.g. `search.domain.RankedResult` referencing `knowledge.domain.ChunkId`).
- Produces: `SourceType` enum (`PDF, HTML, MARKDOWN, TEXT, RSS_ITEM`), `IngestionStatus` enum (`DISCOVERED, DOWNLOADED, EXTRACTED, CLEANED, CHUNKED, INDEXED, FAILED`), `SourceMetadata(String title, String sourceAgency, Set<DisasterType> disasterTypes)`, `RawContent(byte[] bytes, Checksum checksum)`, `PageText(int pageNumber, String text)`, `ChunkDraft(String content, PageNumber pageNumber)`, `SourceDocument` (fields/methods below) — Tasks 2, 5, 6, 8 all depend on these exact types.

- [ ] **Step 1: Write the failing test**

```java
package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.Checksum;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import com.sheltr.knowledge.domain.DocumentId;

import static org.assertj.core.api.Assertions.assertThat;

class SourceDocumentTest {

    @Test
    void startsInDiscoveredStatus() {
        SourceDocument document = new SourceDocument(
                new DocumentId(UUID.randomUUID()), "https://example.org/doc.pdf", SourceType.PDF);

        assertThat(document.status()).isEqualTo(IngestionStatus.DISCOVERED);
        assertThat(document.checksum()).isEmpty();
    }

    @Test
    void transitionsThroughStatusesInOrder() {
        SourceDocument document = new SourceDocument(
                new DocumentId(UUID.randomUUID()), "https://example.org/doc.pdf", SourceType.PDF);

        document.markDownloaded(new Checksum("abc123"));
        assertThat(document.status()).isEqualTo(IngestionStatus.DOWNLOADED);
        assertThat(document.checksum()).contains(new Checksum("abc123"));

        document.markExtracted();
        assertThat(document.status()).isEqualTo(IngestionStatus.EXTRACTED);

        document.markCleaned();
        assertThat(document.status()).isEqualTo(IngestionStatus.CLEANED);

        document.markChunked();
        assertThat(document.status()).isEqualTo(IngestionStatus.CHUNKED);

        document.markIndexed();
        assertThat(document.status()).isEqualTo(IngestionStatus.INDEXED);
    }

    @Test
    void markFailedSetsFailedStatusFromAnyState() {
        SourceDocument document = new SourceDocument(
                new DocumentId(UUID.randomUUID()), "https://example.org/doc.pdf", SourceType.PDF);

        document.markFailed();

        assertThat(document.status()).isEqualTo(IngestionStatus.FAILED);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd apps/backend && mvn -q test -Dtest=SourceDocumentTest`
Expected: FAIL — classes don't exist yet.

- [ ] **Step 3: Implement the value objects**

```java
package com.sheltr.ingestion.domain;

public enum SourceType {
    PDF, HTML, MARKDOWN, TEXT, RSS_ITEM
}
```

```java
package com.sheltr.ingestion.domain;

public enum IngestionStatus {
    DISCOVERED, DOWNLOADED, EXTRACTED, CLEANED, CHUNKED, INDEXED, FAILED
}
```

```java
package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.DisasterType;

import java.util.Set;

public record SourceMetadata(String title, String sourceAgency, Set<DisasterType> disasterTypes) {
    public SourceMetadata {
        disasterTypes = Set.copyOf(disasterTypes == null ? Set.of() : disasterTypes);
    }
}
```

```java
package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.Checksum;

import java.util.Arrays;

public record RawContent(byte[] bytes, Checksum checksum) {
    public RawContent {
        bytes = bytes == null ? new byte[0] : Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
```

```java
package com.sheltr.ingestion.domain;

public record PageText(int pageNumber, String text) {
    public PageText {
        if (pageNumber < 1) throw new IllegalArgumentException("pageNumber must be >= 1");
        if (text == null) throw new IllegalArgumentException("text must not be null");
    }
}
```

```java
package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.PageNumber;

public record ChunkDraft(String content, PageNumber pageNumber) {
    public ChunkDraft {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("ChunkDraft content must not be blank");
        }
    }
}
```

```java
package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.Checksum;
import com.sheltr.knowledge.domain.DocumentId;

import java.util.Optional;

public final class SourceDocument {
    private final DocumentId id;
    private final String sourceUri;
    private final SourceType sourceType;
    private Checksum checksum;
    private IngestionStatus status;

    public SourceDocument(DocumentId id, String sourceUri, SourceType sourceType) {
        this.id = id;
        this.sourceUri = sourceUri;
        this.sourceType = sourceType;
        this.status = IngestionStatus.DISCOVERED;
    }

    public void markDownloaded(Checksum checksum) {
        this.checksum = checksum;
        this.status = IngestionStatus.DOWNLOADED;
    }

    public void markExtracted() { this.status = IngestionStatus.EXTRACTED; }
    public void markCleaned() { this.status = IngestionStatus.CLEANED; }
    public void markChunked() { this.status = IngestionStatus.CHUNKED; }
    public void markIndexed() { this.status = IngestionStatus.INDEXED; }
    public void markFailed() { this.status = IngestionStatus.FAILED; }

    public DocumentId id() { return id; }
    public String sourceUri() { return sourceUri; }
    public SourceType sourceType() { return sourceType; }
    public Optional<Checksum> checksum() { return Optional.ofNullable(checksum); }
    public IngestionStatus status() { return status; }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd apps/backend && mvn -q test -Dtest=SourceDocumentTest`
Expected: `BUILD SUCCESS`, 3/3 passing.

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/main/java/com/sheltr/ingestion/domain/ apps/backend/src/test/java/com/sheltr/ingestion/domain/
git commit -m "feat(ingestion): add domain value objects and SourceDocument aggregate"
```

---

### Task 2: SourceConnector, SourceDocumentRepository ports, TextCleaner, Chunker

**Files:**
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/domain/SourceConnector.java`
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/domain/SourceDocumentRepository.java`
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/domain/TextCleaner.java`
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/domain/Chunker.java`
- Test: `apps/backend/src/test/java/com/sheltr/ingestion/domain/TextCleanerTest.java`
- Test: `apps/backend/src/test/java/com/sheltr/ingestion/domain/ChunkerTest.java`

**Interfaces:**
- Consumes: `SourceDocument`, `RawContent`, `SourceMetadata`, `PageText`, `ChunkDraft` (Task 1); `DocumentId` (Phase 1/2).
- Produces: `SourceConnector` port (`RawContent fetch(String sourceUri)`, `SourceMetadata metadata()`), `SourceDocumentRepository` port (`void save(SourceDocument document)`, `Optional<SourceDocument> findById(DocumentId id)`), `TextCleaner.clean(String rawText): String` static method, `Chunker.chunk(List<PageText> pages): List<ChunkDraft>` static method — Task 6 implements `SourceConnector`; Task 7 implements `SourceDocumentRepository`; Task 8 consumes `TextCleaner`/`Chunker` directly.

- [ ] **Step 1: Write the failing tests**

```java
package com.sheltr.ingestion.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextCleanerTest {

    @Test
    void collapsesRepeatedWhitespace() {
        String cleaned = TextCleaner.clean("Boil   water    for one   minute.");

        assertThat(cleaned).isEqualTo("Boil water for one minute.");
    }

    @Test
    void collapsesExcessiveBlankLines() {
        String cleaned = TextCleaner.clean("Line one.\n\n\n\n\nLine two.");

        assertThat(cleaned).isEqualTo("Line one.\n\nLine two.");
    }

    @Test
    void trimsLeadingAndTrailingWhitespace() {
        String cleaned = TextCleaner.clean("   Boil water.   ");

        assertThat(cleaned).isEqualTo("Boil water.");
    }
}
```

```java
package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.PageNumber;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkerTest {

    @Test
    void shortTextProducesOneChunkTaggedWithItsPage() {
        List<ChunkDraft> drafts = Chunker.chunk(List.of(new PageText(3, "Boil water for one minute.")));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).content()).isEqualTo("Boil water for one minute.");
        assertThat(drafts.get(0).pageNumber()).isEqualTo(new PageNumber(3));
    }

    @Test
    void longTextSplitsIntoMultipleChunksWithoutSplittingWords() {
        String longText = "word ".repeat(400); // 2000 chars, well over the 1000-char chunk size
        List<ChunkDraft> drafts = Chunker.chunk(List.of(new PageText(1, longText)));

        assertThat(drafts.size()).isGreaterThan(1);
        for (ChunkDraft draft : drafts) {
            assertThat(draft.content()).doesNotStartWith(" ").doesNotEndWith(" ");
            assertThat(draft.content()).doesNotContain("wor d", "wo rd"); // no mid-word split artifacts
        }
    }

    @Test
    void blankPageProducesNoChunks() {
        List<ChunkDraft> drafts = Chunker.chunk(List.of(new PageText(1, "   \n\n  ")));

        assertThat(drafts).isEmpty();
    }

    @Test
    void chunksFromDifferentPagesStayTaggedCorrectly() {
        List<ChunkDraft> drafts = Chunker.chunk(List.of(
                new PageText(1, "Page one content."),
                new PageText(2, "Page two content.")));

        assertThat(drafts).hasSize(2);
        assertThat(drafts.get(0).pageNumber()).isEqualTo(new PageNumber(1));
        assertThat(drafts.get(1).pageNumber()).isEqualTo(new PageNumber(2));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd apps/backend && mvn -q test -Dtest=TextCleanerTest,ChunkerTest`
Expected: FAIL — classes don't exist yet.

- [ ] **Step 3: Implement the ports and pure functions**

```java
package com.sheltr.ingestion.domain;

public interface SourceConnector {
    RawContent fetch(String sourceUri);
    SourceMetadata metadata();
}
```

```java
package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.DocumentId;

import java.util.Optional;

public interface SourceDocumentRepository {
    void save(SourceDocument document);
    Optional<SourceDocument> findById(DocumentId id);
}
```

```java
package com.sheltr.ingestion.domain;

public final class TextCleaner {

    private TextCleaner() {
    }

    public static String clean(String rawText) {
        String collapsed = rawText.replaceAll("[ \\t]+", " ");
        collapsed = collapsed.replaceAll("\\n{3,}", "\n\n");
        return collapsed.trim();
    }
}
```

```java
package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.PageNumber;

import java.util.ArrayList;
import java.util.List;

public final class Chunker {

    private static final int CHUNK_SIZE = 1000;
    private static final int OVERLAP = 100;

    private Chunker() {
    }

    public static List<ChunkDraft> chunk(List<PageText> pages) {
        List<ChunkDraft> drafts = new ArrayList<>();
        for (PageText page : pages) {
            String text = page.text();
            int start = 0;
            while (start < text.length()) {
                int end = Math.min(start + CHUNK_SIZE, text.length());
                if (end < text.length()) {
                    int lastSpace = text.lastIndexOf(' ', end);
                    if (lastSpace > start) {
                        end = lastSpace;
                    }
                }
                String chunkText = text.substring(start, end).trim();
                if (!chunkText.isBlank()) {
                    drafts.add(new ChunkDraft(chunkText, new PageNumber(page.pageNumber())));
                }
                if (end >= text.length()) {
                    break;
                }
                start = Math.max(end - OVERLAP, start + 1);
            }
        }
        return drafts;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd apps/backend && mvn -q test -Dtest=TextCleanerTest,ChunkerTest`
Expected: `BUILD SUCCESS`, 7/7 passing.

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/main/java/com/sheltr/ingestion/domain/SourceConnector.java apps/backend/src/main/java/com/sheltr/ingestion/domain/SourceDocumentRepository.java apps/backend/src/main/java/com/sheltr/ingestion/domain/TextCleaner.java apps/backend/src/main/java/com/sheltr/ingestion/domain/Chunker.java apps/backend/src/test/java/com/sheltr/ingestion/domain/TextCleanerTest.java apps/backend/src/test/java/com/sheltr/ingestion/domain/ChunkerTest.java
git commit -m "feat(ingestion): add SourceConnector/SourceDocumentRepository ports, TextCleaner, Chunker"
```

---

### Task 3: IngestionRun domain and repository port

**Files:**
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/domain/IngestionRunId.java`
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/domain/RunStatus.java`
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/domain/IngestionRun.java`
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/domain/IngestionRunRepository.java`
- Test: `apps/backend/src/test/java/com/sheltr/ingestion/domain/IngestionRunTest.java`

**Interfaces:**
- Consumes: `DocumentId` (Phase 1/2).
- Produces: `IngestionRunId(UUID value)`, `RunStatus` enum (`RUNNING, SUCCEEDED, FAILED`), `IngestionRun` (fields/methods below), `IngestionRunRepository` port (`save`, `findById`). Task 7 implements the repository; Task 8 and Task 9 (REST layer) consume `IngestionRun`/`RunStatus` directly.

- [ ] **Step 1: Write the failing test**

```java
package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.DocumentId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionRunTest {

    @Test
    void startsInRunningStatusWithNoFinishTime() {
        IngestionRun run = new IngestionRun(new IngestionRunId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()));

        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.finishedAt()).isEmpty();
        assertThat(run.errorMessage()).isEmpty();
    }

    @Test
    void markSucceededSetsStatusAndFinishTime() {
        IngestionRun run = new IngestionRun(new IngestionRunId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()));

        run.markSucceeded();

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.finishedAt()).isPresent();
    }

    @Test
    void markFailedRecordsReasonAndFinishTime() {
        IngestionRun run = new IngestionRun(new IngestionRunId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()));

        run.markFailed("Download failed: HTTP 404");

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.errorMessage()).contains("Download failed: HTTP 404");
        assertThat(run.finishedAt()).isPresent();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd apps/backend && mvn -q test -Dtest=IngestionRunTest`
Expected: FAIL — classes don't exist yet.

- [ ] **Step 3: Implement**

```java
package com.sheltr.ingestion.domain;

import java.util.UUID;

public record IngestionRunId(UUID value) {
    public IngestionRunId {
        if (value == null) throw new IllegalArgumentException("IngestionRunId value must not be null");
    }
}
```

```java
package com.sheltr.ingestion.domain;

public enum RunStatus {
    RUNNING, SUCCEEDED, FAILED
}
```

```java
package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.DocumentId;

import java.time.Instant;
import java.util.Optional;

public final class IngestionRun {
    private final IngestionRunId id;
    private final DocumentId sourceDocumentId;
    private final Instant startedAt;
    private RunStatus status;
    private Instant finishedAt;
    private String errorMessage;

    public IngestionRun(IngestionRunId id, DocumentId sourceDocumentId) {
        this.id = id;
        this.sourceDocumentId = sourceDocumentId;
        this.startedAt = Instant.now();
        this.status = RunStatus.RUNNING;
    }

    public void markSucceeded() {
        this.status = RunStatus.SUCCEEDED;
        this.finishedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = RunStatus.FAILED;
        this.errorMessage = reason;
        this.finishedAt = Instant.now();
    }

    public IngestionRunId id() { return id; }
    public DocumentId sourceDocumentId() { return sourceDocumentId; }
    public Instant startedAt() { return startedAt; }
    public RunStatus status() { return status; }
    public Optional<Instant> finishedAt() { return Optional.ofNullable(finishedAt); }
    public Optional<String> errorMessage() { return Optional.ofNullable(errorMessage); }
}
```

```java
package com.sheltr.ingestion.domain;

import java.util.Optional;

public interface IngestionRunRepository {
    void save(IngestionRun run);
    Optional<IngestionRun> findById(IngestionRunId id);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd apps/backend && mvn -q test -Dtest=IngestionRunTest`
Expected: `BUILD SUCCESS`, 3/3 passing.

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/main/java/com/sheltr/ingestion/domain/IngestionRunId.java apps/backend/src/main/java/com/sheltr/ingestion/domain/RunStatus.java apps/backend/src/main/java/com/sheltr/ingestion/domain/IngestionRun.java apps/backend/src/main/java/com/sheltr/ingestion/domain/IngestionRunRepository.java apps/backend/src/test/java/com/sheltr/ingestion/domain/IngestionRunTest.java
git commit -m "feat(ingestion): add IngestionRun domain and repository port"
```

---

### Task 4: Extend KnowledgeDocumentRepository/ChunkRepository with insert methods

**Files:**
- Modify: `apps/backend/src/main/java/com/sheltr/knowledge/domain/KnowledgeDocumentRepository.java`
- Modify: `apps/backend/src/main/java/com/sheltr/knowledge/domain/ChunkRepository.java`
- Modify: `apps/backend/src/main/java/com/sheltr/knowledge/infrastructure/JooqKnowledgeDocumentRepository.java`
- Modify: `apps/backend/src/main/java/com/sheltr/knowledge/infrastructure/JooqChunkRepository.java`
- Test: `apps/backend/src/test/java/com/sheltr/knowledge/infrastructure/JooqKnowledgeDocumentRepositoryInsertTest.java`
- Test: `apps/backend/src/test/java/com/sheltr/knowledge/infrastructure/JooqChunkRepositoryInsertTest.java`

**Interfaces:**
- Consumes: `KnowledgeDocument`, `Chunk`, `DocumentId`, `ChunkId`, `DisasterType`, `Embedding` (Phase 1/2, unchanged).
- Produces: `KnowledgeDocumentRepository.save(KnowledgeDocument document): void` (new — full INSERT), `ChunkRepository.insert(Chunk chunk): void` (new — full INSERT, distinct from the existing `save(Chunk)` which remains embedding-only-UPDATE, unchanged, to avoid breaking Phase 2's `ChunkEmbeddingBackfillService`). Task 8's `IngestDocumentService` consumes both new methods.

The existing `knowledge.documents` table requires `source_type TEXT NOT NULL` (a column not modeled on the `KnowledgeDocument` domain object — a known, already-logged Phase 1/2 gap). Since this insert path is now real production code (not a test-only fixture like Phase 2's workarounds), hardcode `source_type = 'PDF'` for this slice — `KnowledgeDocument` has no way to carry a different value yet, and this slice only ever ingests PDFs.

- [ ] **Step 1: Write the failing tests**

```java
package com.sheltr.knowledge.infrastructure;

import com.sheltr.knowledge.domain.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JooqKnowledgeDocumentRepositoryInsertTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void savesAndReadsBackANewDocument() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        JooqKnowledgeDocumentRepository repository = new JooqKnowledgeDocumentRepository(dataSource);
        DocumentId id = new DocumentId(UUID.randomUUID());
        KnowledgeDocument document = new KnowledgeDocument(
                id, "Disaster Preparedness Guide for Caregivers", "FEMA",
                "https://www.ready.gov/sites/default/files/2024-02/ready-gov_caregivers-preparedness-guide.pdf",
                Set.of(DisasterType.FLOOD, DisasterType.WILDFIRE), LocalDate.now(), new DocumentVersion(1),
                new Checksum("abc123def456"), null, DocumentStatus.ACTIVE);

        repository.save(document);

        Optional<KnowledgeDocument> reloaded = repository.findById(id);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().title()).isEqualTo("Disaster Preparedness Guide for Caregivers");
        assertThat(reloaded.get().disasterTypes()).containsExactlyInAnyOrder(DisasterType.FLOOD, DisasterType.WILDFIRE);
        assertThat(reloaded.get().checksum()).isEqualTo(new Checksum("abc123def456"));
    }
}
```

```java
package com.sheltr.knowledge.infrastructure;

import com.sheltr.knowledge.domain.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JooqChunkRepositoryInsertTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void insertsANewChunkWithEmbeddingAlreadySet() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        JooqKnowledgeDocumentRepository documentRepository = new JooqKnowledgeDocumentRepository(dataSource);
        JooqChunkRepository chunkRepository = new JooqChunkRepository(dataSource);

        DocumentId documentId = new DocumentId(UUID.randomUUID());
        documentRepository.save(new KnowledgeDocument(
                documentId, "Test Document", "FEMA", "https://example.org/doc.pdf",
                Set.of(DisasterType.FLOOD), LocalDate.now(), new DocumentVersion(1),
                new Checksum("docchecksum"), null, DocumentStatus.ACTIVE));

        float[] vector = new float[768];
        vector[0] = 0.5f;
        ChunkId chunkId = new ChunkId(UUID.randomUUID());
        Chunk chunk = new Chunk(chunkId, documentId, new ChunkContent("Boil water for one minute."),
                new Embedding(vector), new PageNumber(2), 0,
                new ChunkMetadata("Water Safety", Set.of(DisasterType.FLOOD)), new Checksum("chunkchecksum"));

        chunkRepository.insert(chunk);

        Optional<Chunk> reloaded = chunkRepository.findById(chunkId);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().content().value()).isEqualTo("Boil water for one minute.");
        assertThat(reloaded.get().pageNumber()).contains(new PageNumber(2));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd apps/backend && mvn -q test -Dtest=JooqKnowledgeDocumentRepositoryInsertTest,JooqChunkRepositoryInsertTest`
Expected: FAIL — `save(KnowledgeDocument)` and `insert(Chunk)` don't exist on the interfaces yet.

- [ ] **Step 3: Add `save(KnowledgeDocument)` to `KnowledgeDocumentRepository`**

Modify `apps/backend/src/main/java/com/sheltr/knowledge/domain/KnowledgeDocumentRepository.java`:

```java
package com.sheltr.knowledge.domain;

import java.util.Optional;

public interface KnowledgeDocumentRepository {
    Optional<KnowledgeDocument> findById(DocumentId id);
    void save(KnowledgeDocument document);
}
```

- [ ] **Step 4: Add `insert(Chunk)` to `ChunkRepository`**

Modify `apps/backend/src/main/java/com/sheltr/knowledge/domain/ChunkRepository.java`:

```java
package com.sheltr.knowledge.domain;

import java.util.List;
import java.util.Optional;

public interface ChunkRepository {
    Optional<Chunk> findById(ChunkId id);
    List<Chunk> findPendingEmbeddings();
    void save(Chunk chunk);
    void insert(Chunk chunk);
}
```

- [ ] **Step 5: Implement `save(KnowledgeDocument)` in `JooqKnowledgeDocumentRepository`**

Add this method to `apps/backend/src/main/java/com/sheltr/knowledge/infrastructure/JooqKnowledgeDocumentRepository.java` (alongside the existing `findById`):

```java
    @Override
    public void save(KnowledgeDocument document) {
        String disasterTypesLiteral = "{" + document.disasterTypes().stream()
                .map(Enum::name)
                .collect(java.util.stream.Collectors.joining(",")) + "}";
        dsl.execute(
                "INSERT INTO knowledge.documents (id, title, source_agency, source_uri, source_type, "
                        + "disaster_types, published_date, version, supersedes_id, status, checksum) "
                        + "VALUES (?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?, ?)",
                document.id().value(), document.title(), document.sourceAgency(), document.sourceUri(),
                "PDF", disasterTypesLiteral, document.publishedDate(), document.version().value(),
                document.supersedes().map(DocumentId::value).orElse(null),
                document.status().name(), document.checksum().hex());
    }
```

- [ ] **Step 6: Implement `insert(Chunk)` in `JooqChunkRepository`**

Add this method to `apps/backend/src/main/java/com/sheltr/knowledge/infrastructure/JooqChunkRepository.java` (alongside the existing `save`, reusing the already-private `toVectorLiteral` helper):

```java
    @Override
    public void insert(Chunk chunk) {
        Embedding embedding = chunk.embedding().orElseThrow(
                () -> new IllegalStateException("insert() requires the chunk to already have an embedding"));
        dsl.execute(
                "INSERT INTO knowledge.chunks (id, document_id, content, embedding, page_number, chunk_index, "
                        + "section_heading, checksum) VALUES (?, ?, ?, ?::vector, ?, ?, ?, ?)",
                chunk.id().value(), chunk.documentId().value(), chunk.content().value(),
                toVectorLiteral(embedding), chunk.pageNumber().map(PageNumber::value).orElse(null),
                chunk.chunkIndex(), chunk.metadata().sectionHeading(), chunk.checksum().hex());
    }
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `cd apps/backend && mvn -q test -Dtest=JooqKnowledgeDocumentRepositoryInsertTest,JooqChunkRepositoryInsertTest`
Expected: `BUILD SUCCESS`, 2/2 passing.

- [ ] **Step 8: Run the full suite to confirm no regressions (existing `ChunkEmbeddingBackfillService`/`save(Chunk)` behavior unchanged)**

Run: `cd apps/backend && mvn -q test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 9: Commit**

```bash
git add apps/backend/src/main/java/com/sheltr/knowledge/domain/KnowledgeDocumentRepository.java apps/backend/src/main/java/com/sheltr/knowledge/domain/ChunkRepository.java apps/backend/src/main/java/com/sheltr/knowledge/infrastructure/JooqKnowledgeDocumentRepository.java apps/backend/src/main/java/com/sheltr/knowledge/infrastructure/JooqChunkRepository.java apps/backend/src/test/java/com/sheltr/knowledge/infrastructure/JooqKnowledgeDocumentRepositoryInsertTest.java apps/backend/src/test/java/com/sheltr/knowledge/infrastructure/JooqChunkRepositoryInsertTest.java
git commit -m "feat(knowledge): add insert methods to KnowledgeDocumentRepository and ChunkRepository"
```

---

### Task 5: PDFBox dependency and PdfContentExtractor

**Files:**
- Modify: `apps/backend/pom.xml` (add Apache PDFBox dependency)
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/infrastructure/PdfContentExtractor.java`
- Test: `apps/backend/src/test/java/com/sheltr/ingestion/infrastructure/PdfContentExtractorTest.java`

**Interfaces:**
- Consumes: `PageText` (Task 1).
- Produces: `PdfContentExtractor` with method `List<PageText> extract(byte[] pdfBytes)`. Task 8's `IngestDocumentService` consumes this directly (no port needed yet — single format, YAGNI per the plan's architecture note; a `ContentExtractor` port only becomes worthwhile once a second format arrives).

This task's test builds its own tiny PDF fixture in-memory using PDFBox's own writer APIs — no binary file needs to be checked in, and the test has no network dependency.

- [ ] **Step 1: Add Apache PDFBox to `apps/backend/pom.xml`**

Add inside `<dependencies>`, alongside the existing entries:

```xml
    <dependency>
      <groupId>org.apache.pdfbox</groupId>
      <artifactId>pdfbox</artifactId>
      <version>3.0.3</version>
    </dependency>
```

If `3.0.3` isn't resolvable (check `mvn -q dependency:tree -Dincludes=org.apache.pdfbox` after adding it), use the latest available `3.x` version from Maven Central instead — the API used in this task (`Loader.loadPDF`, `PDFTextStripper`, `PDPageContentStream`, `Standard14Fonts`) is stable across the PDFBox 3.x line.

- [ ] **Step 2: Write the failing test**

```java
package com.sheltr.ingestion.infrastructure;

import com.sheltr.ingestion.domain.PageText;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PdfContentExtractorTest {

    @Test
    void extractsTextPerPageFromARealPdf() throws IOException {
        byte[] pdfBytes = buildTestPdf();

        PdfContentExtractor extractor = new PdfContentExtractor();
        List<PageText> pages = extractor.extract(pdfBytes);

        assertThat(pages).hasSize(2);
        assertThat(pages.get(0).pageNumber()).isEqualTo(1);
        assertThat(pages.get(0).text()).contains("Boil water for one minute.");
        assertThat(pages.get(1).pageNumber()).isEqualTo(2);
        assertThat(pages.get(1).text()).contains("Evacuate immediately if instructed.");
    }

    private static byte[] buildTestPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            addPageWithText(document, "Boil water for one minute.");
            addPageWithText(document, "Evacuate immediately if instructed.");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static void addPageWithText(PDDocument document, String text) throws IOException {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            stream.beginText();
            stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            stream.newLineAtOffset(50, 700);
            stream.showText(text);
            stream.endText();
        }
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd apps/backend && mvn -q test -Dtest=PdfContentExtractorTest`
Expected: FAIL — `PdfContentExtractor` doesn't exist yet.

- [ ] **Step 4: Implement `PdfContentExtractor`**

```java
package com.sheltr.ingestion.infrastructure;

import com.sheltr.ingestion.domain.PageText;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PdfContentExtractor {

    public List<PageText> extract(byte[] pdfBytes) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            List<PageText> pages = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            int totalPages = document.getNumberOfPages();
            for (int i = 1; i <= totalPages; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(document);
                pages.add(new PageText(i, text));
            }
            return pages;
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract PDF content", e);
        }
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd apps/backend && mvn -q test -Dtest=PdfContentExtractorTest`
Expected: `BUILD SUCCESS`, 1/1 passing.

- [ ] **Step 6: Commit**

```bash
git add apps/backend/pom.xml apps/backend/src/main/java/com/sheltr/ingestion/infrastructure/PdfContentExtractor.java apps/backend/src/test/java/com/sheltr/ingestion/infrastructure/PdfContentExtractorTest.java
git commit -m "feat(ingestion): add PDFBox dependency and PdfContentExtractor"
```

---

### Task 6: FemaHttpConnector

**Files:**
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/infrastructure/FemaHttpConnector.java`
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/config/SourceConnectorConfig.java`
- Test: `apps/backend/src/test/java/com/sheltr/ingestion/infrastructure/FemaHttpConnectorTest.java`

**Interfaces:**
- Consumes: `SourceConnector`, `RawContent`, `SourceMetadata` (Task 1/2); `DisasterType` (Phase 1/2).
- Produces: `FemaHttpConnector` implementing `SourceConnector`, and a `@Bean SourceConnector sourceConnector()` in `SourceConnectorConfig`. Task 8 consumes `SourceConnector`, not this class directly.

This test makes a real HTTP call to the live FEMA URL (network-dependent, no gate needed beyond normal network availability — same category as other real-I/O tests in this project).

- [ ] **Step 1: Write the failing test**

```java
package com.sheltr.ingestion.infrastructure;

import com.sheltr.ingestion.domain.RawContent;
import com.sheltr.ingestion.domain.SourceMetadata;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;

class FemaHttpConnectorTest {

    private static final String CAREGIVERS_GUIDE_URL =
            "https://www.ready.gov/sites/default/files/2024-02/ready-gov_caregivers-preparedness-guide.pdf";

    @Test
    void downloadsRealPdfAndComputesCorrectChecksum() throws Exception {
        FemaHttpConnector connector = new FemaHttpConnector();

        RawContent content = connector.fetch(CAREGIVERS_GUIDE_URL);

        assertThat(content.bytes()).isNotEmpty();
        assertThat(content.bytes().length).isGreaterThan(100_000); // real PDF is ~2.5MB
        // PDF files start with the magic bytes "%PDF"
        assertThat(new String(content.bytes(), 0, 4)).isEqualTo("%PDF");

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] expectedHash = digest.digest(content.bytes());
        StringBuilder expectedHex = new StringBuilder();
        for (byte b : expectedHash) {
            expectedHex.append(String.format("%02x", b));
        }
        assertThat(content.checksum().hex()).isEqualTo(expectedHex.toString());
    }

    @Test
    void metadataReturnsFemaAgencyAndTitle() {
        FemaHttpConnector connector = new FemaHttpConnector();

        SourceMetadata metadata = connector.metadata();

        assertThat(metadata.sourceAgency()).isEqualTo("FEMA");
        assertThat(metadata.title()).isNotBlank();
        assertThat(metadata.disasterTypes()).isNotEmpty();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd apps/backend && mvn -q test -Dtest=FemaHttpConnectorTest`
Expected: FAIL — `FemaHttpConnector` doesn't exist yet.

- [ ] **Step 3: Implement `FemaHttpConnector`**

```java
package com.sheltr.ingestion.infrastructure;

import com.sheltr.ingestion.domain.RawContent;
import com.sheltr.ingestion.domain.SourceConnector;
import com.sheltr.ingestion.domain.SourceMetadata;
import com.sheltr.knowledge.domain.Checksum;
import com.sheltr.knowledge.domain.DisasterType;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

public class FemaHttpConnector implements SourceConnector {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public RawContent fetch(String sourceUri) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(sourceUri)).GET().build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to download " + sourceUri + ": HTTP " + response.statusCode());
            }
            byte[] bytes = response.body();
            return new RawContent(bytes, new Checksum(sha256Hex(bytes)));
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to download " + sourceUri, e);
        }
    }

    @Override
    public SourceMetadata metadata() {
        return new SourceMetadata(
                "Disaster Preparedness Guide for Caregivers",
                "FEMA",
                Set.of(DisasterType.values()));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
```

- [ ] **Step 4: Implement `SourceConnectorConfig`**

```java
package com.sheltr.ingestion.config;

import com.sheltr.ingestion.domain.SourceConnector;
import com.sheltr.ingestion.infrastructure.FemaHttpConnector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SourceConnectorConfig {

    @Bean
    public SourceConnector sourceConnector() {
        return new FemaHttpConnector();
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd apps/backend && mvn -q test -Dtest=FemaHttpConnectorTest`
Expected: `BUILD SUCCESS`, 2/2 passing (real network download, ~2.5MB, may take a few seconds).

- [ ] **Step 6: Commit**

```bash
git add apps/backend/src/main/java/com/sheltr/ingestion/infrastructure/FemaHttpConnector.java apps/backend/src/main/java/com/sheltr/ingestion/config/SourceConnectorConfig.java apps/backend/src/test/java/com/sheltr/ingestion/infrastructure/FemaHttpConnectorTest.java
git commit -m "feat(ingestion): add FemaHttpConnector"
```

---

### Task 7: JooqSourceDocumentRepository and JooqIngestionRunRepository

**Files:**
- Modify: `apps/backend/src/main/java/com/sheltr/ingestion/domain/SourceDocument.java` (add a reconstruction constructor)
- Modify: `apps/backend/src/main/java/com/sheltr/ingestion/domain/IngestionRun.java` (add a reconstruction constructor)
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/infrastructure/JooqSourceDocumentRepository.java`
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/infrastructure/JooqIngestionRunRepository.java`
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/config/SourceDocumentRepositoryConfig.java`
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/config/IngestionRunRepositoryConfig.java`
- Test: `apps/backend/src/test/java/com/sheltr/ingestion/infrastructure/JooqSourceDocumentRepositoryIntegrationTest.java`
- Test: `apps/backend/src/test/java/com/sheltr/ingestion/infrastructure/JooqIngestionRunRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: `SourceDocument`, `SourceDocumentRepository`, `IngestionRun`, `IngestionRunRepository`, `SourceType`, `IngestionStatus`, `RunStatus`, `IngestionRunId` (Tasks 1-3).
- Produces: `SourceDocument(DocumentId id, String sourceUri, SourceType sourceType, Checksum checksum, IngestionStatus status)` — a second, reconstruction constructor for rehydrating from the database (the original single-arg-set constructor from Task 1 stays for fresh discovery). `IngestionRun(IngestionRunId id, DocumentId sourceDocumentId, Instant startedAt, RunStatus status, Instant finishedAt, String errorMessage)` — same pattern for `IngestionRun`. `JooqSourceDocumentRepository(DataSource)` implementing `SourceDocumentRepository`, `JooqIngestionRunRepository(DataSource)` implementing `IngestionRunRepository`. Task 8 consumes both repository ports.

**Important schema constraint this task resolves:** `ingestion.source_documents.checksum` is `TEXT NOT NULL` with no default — a `SourceDocument` at `DISCOVERED` status (before download) has no checksum yet. `JooqSourceDocumentRepository.save()` therefore requires the document to already have a checksum (i.e., be at `DOWNLOADED` status or later) — Task 8's service must call `save()` for the first time only *after* `markDownloaded(checksum)`, never at plain `DISCOVERED` status. This is documented here rather than left as a surprise for Task 8.

- [ ] **Step 1: Write the failing tests**

```java
package com.sheltr.ingestion.infrastructure;

import com.sheltr.ingestion.domain.IngestionStatus;
import com.sheltr.ingestion.domain.SourceDocument;
import com.sheltr.ingestion.domain.SourceType;
import com.sheltr.knowledge.domain.Checksum;
import com.sheltr.knowledge.domain.DocumentId;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JooqSourceDocumentRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void savesAndReloadsASourceDocumentAcrossStatusTransitions() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        JooqSourceDocumentRepository repository = new JooqSourceDocumentRepository(dataSource);
        DocumentId id = new DocumentId(UUID.randomUUID());
        SourceDocument document = new SourceDocument(id, "https://example.org/doc.pdf", SourceType.PDF);
        document.markDownloaded(new Checksum("abc123"));

        repository.save(document);
        Optional<SourceDocument> reloaded = repository.findById(id);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().status()).isEqualTo(IngestionStatus.DOWNLOADED);
        assertThat(reloaded.get().checksum()).contains(new Checksum("abc123"));

        document.markIndexed();
        repository.save(document);
        Optional<SourceDocument> reloadedAgain = repository.findById(id);
        assertThat(reloadedAgain.get().status()).isEqualTo(IngestionStatus.INDEXED);
    }
}
```

```java
package com.sheltr.ingestion.infrastructure;

import com.sheltr.ingestion.domain.IngestionRun;
import com.sheltr.ingestion.domain.IngestionRunId;
import com.sheltr.ingestion.domain.RunStatus;
import com.sheltr.ingestion.domain.SourceDocument;
import com.sheltr.ingestion.domain.SourceType;
import com.sheltr.knowledge.domain.Checksum;
import com.sheltr.knowledge.domain.DocumentId;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JooqIngestionRunRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void savesAndReloadsAnIngestionRun() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        DocumentId documentId = new DocumentId(UUID.randomUUID());
        JooqSourceDocumentRepository documentRepository = new JooqSourceDocumentRepository(dataSource);
        SourceDocument document = new SourceDocument(documentId, "https://example.org/doc.pdf", SourceType.PDF);
        document.markDownloaded(new Checksum("abc123"));
        documentRepository.save(document);

        JooqIngestionRunRepository runRepository = new JooqIngestionRunRepository(dataSource);
        IngestionRunId runId = new IngestionRunId(UUID.randomUUID());
        IngestionRun run = new IngestionRun(runId, documentId);
        runRepository.save(run);

        run.markFailed("Download failed: HTTP 404");
        runRepository.save(run);

        Optional<IngestionRun> reloaded = runRepository.findById(runId);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().status()).isEqualTo(RunStatus.FAILED);
        assertThat(reloaded.get().errorMessage()).contains("Download failed: HTTP 404");
        assertThat(reloaded.get().finishedAt()).isPresent();
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd apps/backend && mvn -q test -Dtest=JooqSourceDocumentRepositoryIntegrationTest,JooqIngestionRunRepositoryIntegrationTest`
Expected: FAIL — repository classes and the reconstruction constructors don't exist yet.

- [ ] **Step 3: Add the reconstruction constructor to `SourceDocument`**

Add this constructor to `apps/backend/src/main/java/com/sheltr/ingestion/domain/SourceDocument.java`, alongside the existing one:

```java
    public SourceDocument(DocumentId id, String sourceUri, SourceType sourceType, Checksum checksum, IngestionStatus status) {
        this.id = id;
        this.sourceUri = sourceUri;
        this.sourceType = sourceType;
        this.checksum = checksum;
        this.status = status;
    }
```

- [ ] **Step 4: Add the reconstruction constructor to `IngestionRun`**

Add this constructor to `apps/backend/src/main/java/com/sheltr/ingestion/domain/IngestionRun.java`, alongside the existing one:

```java
    public IngestionRun(IngestionRunId id, DocumentId sourceDocumentId, java.time.Instant startedAt,
                         RunStatus status, java.time.Instant finishedAt, String errorMessage) {
        this.id = id;
        this.sourceDocumentId = sourceDocumentId;
        this.startedAt = startedAt;
        this.status = status;
        this.finishedAt = finishedAt;
        this.errorMessage = errorMessage;
    }
```

- [ ] **Step 5: Implement `JooqSourceDocumentRepository`**

```java
package com.sheltr.ingestion.infrastructure;

import com.sheltr.ingestion.domain.IngestionStatus;
import com.sheltr.ingestion.domain.SourceDocument;
import com.sheltr.ingestion.domain.SourceDocumentRepository;
import com.sheltr.ingestion.domain.SourceType;
import com.sheltr.knowledge.domain.Checksum;
import com.sheltr.knowledge.domain.DocumentId;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;

public class JooqSourceDocumentRepository implements SourceDocumentRepository {

    private final DSLContext dsl;

    public JooqSourceDocumentRepository(DataSource dataSource) {
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    @Override
    public void save(SourceDocument document) {
        Checksum checksum = document.checksum().orElseThrow(() -> new IllegalStateException(
                "SourceDocument must have a checksum (status DOWNLOADED or later) before it can be saved"));
        dsl.execute(
                "INSERT INTO ingestion.source_documents (id, source_uri, source_type, checksum, status) "
                        + "VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET checksum = EXCLUDED.checksum, status = EXCLUDED.status",
                document.id().value(), document.sourceUri(), document.sourceType().name(),
                checksum.hex(), document.status().name());
    }

    @Override
    public Optional<SourceDocument> findById(DocumentId id) {
        Record record = dsl.fetchOne(
                "SELECT id, source_uri, source_type, checksum, status FROM ingestion.source_documents WHERE id = ?",
                id.value());
        return Optional.ofNullable(record).map(JooqSourceDocumentRepository::mapRow);
    }

    private static SourceDocument mapRow(Record record) {
        return new SourceDocument(
                new DocumentId((UUID) record.get("id")),
                record.get("source_uri", String.class),
                SourceType.valueOf(record.get("source_type", String.class)),
                new Checksum(record.get("checksum", String.class)),
                IngestionStatus.valueOf(record.get("status", String.class)));
    }
}
```

- [ ] **Step 6: Implement `JooqIngestionRunRepository`**

```java
package com.sheltr.ingestion.infrastructure;

import com.sheltr.ingestion.domain.IngestionRun;
import com.sheltr.ingestion.domain.IngestionRunId;
import com.sheltr.ingestion.domain.IngestionRunRepository;
import com.sheltr.ingestion.domain.RunStatus;
import com.sheltr.knowledge.domain.DocumentId;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

public class JooqIngestionRunRepository implements IngestionRunRepository {

    private final DSLContext dsl;

    public JooqIngestionRunRepository(DataSource dataSource) {
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    @Override
    public void save(IngestionRun run) {
        Timestamp finishedAt = run.finishedAt().map(Timestamp::from).orElse(null);
        String errorMessage = run.errorMessage().orElse(null);
        dsl.execute(
                "INSERT INTO ingestion.ingestion_runs (id, source_document_id, started_at, finished_at, status, "
                        + "error_message) VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET finished_at = EXCLUDED.finished_at, "
                        + "status = EXCLUDED.status, error_message = EXCLUDED.error_message",
                run.id().value(), run.sourceDocumentId().value(), Timestamp.from(run.startedAt()),
                finishedAt, run.status().name(), errorMessage);
    }

    @Override
    public Optional<IngestionRun> findById(IngestionRunId id) {
        Record record = dsl.fetchOne(
                "SELECT id, source_document_id, started_at, finished_at, status, error_message "
                        + "FROM ingestion.ingestion_runs WHERE id = ?",
                id.value());
        return Optional.ofNullable(record).map(JooqIngestionRunRepository::mapRow);
    }

    private static IngestionRun mapRow(Record record) {
        Timestamp finishedAtTs = record.get("finished_at", Timestamp.class);
        return new IngestionRun(
                new IngestionRunId((UUID) record.get("id")),
                new DocumentId((UUID) record.get("source_document_id")),
                record.get("started_at", Timestamp.class).toInstant(),
                RunStatus.valueOf(record.get("status", String.class)),
                finishedAtTs == null ? null : finishedAtTs.toInstant(),
                record.get("error_message", String.class));
    }
}
```

- [ ] **Step 7: Implement the config classes**

```java
package com.sheltr.ingestion.config;

import com.sheltr.ingestion.domain.SourceDocumentRepository;
import com.sheltr.ingestion.infrastructure.JooqSourceDocumentRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class SourceDocumentRepositoryConfig {

    @Bean
    public SourceDocumentRepository sourceDocumentRepository(DataSource dataSource) {
        return new JooqSourceDocumentRepository(dataSource);
    }
}
```

```java
package com.sheltr.ingestion.config;

import com.sheltr.ingestion.domain.IngestionRunRepository;
import com.sheltr.ingestion.infrastructure.JooqIngestionRunRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class IngestionRunRepositoryConfig {

    @Bean
    public IngestionRunRepository ingestionRunRepository(DataSource dataSource) {
        return new JooqIngestionRunRepository(dataSource);
    }
}
```

- [ ] **Step 8: Run to verify tests pass**

Run: `cd apps/backend && mvn -q test -Dtest=JooqSourceDocumentRepositoryIntegrationTest,JooqIngestionRunRepositoryIntegrationTest`
Expected: `BUILD SUCCESS`, 2/2 passing.

- [ ] **Step 9: Commit**

```bash
git add apps/backend/src/main/java/com/sheltr/ingestion/domain/SourceDocument.java apps/backend/src/main/java/com/sheltr/ingestion/domain/IngestionRun.java apps/backend/src/main/java/com/sheltr/ingestion/infrastructure/JooqSourceDocumentRepository.java apps/backend/src/main/java/com/sheltr/ingestion/infrastructure/JooqIngestionRunRepository.java apps/backend/src/main/java/com/sheltr/ingestion/config/SourceDocumentRepositoryConfig.java apps/backend/src/main/java/com/sheltr/ingestion/config/IngestionRunRepositoryConfig.java apps/backend/src/test/java/com/sheltr/ingestion/infrastructure/JooqSourceDocumentRepositoryIntegrationTest.java apps/backend/src/test/java/com/sheltr/ingestion/infrastructure/JooqIngestionRunRepositoryIntegrationTest.java
git commit -m "feat(ingestion): add jOOQ SourceDocument/IngestionRun repositories"
```

---

### Task 8: IngestDocumentService

**Files:**
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/application/IngestDocumentService.java`
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/config/IngestDocumentServiceConfig.java`
- Test: `apps/backend/src/test/java/com/sheltr/ingestion/application/IngestDocumentServiceTest.java`

**Interfaces:**
- Consumes: `SourceConnector`, `SourceDocumentRepository`, `IngestionRunRepository`, `SourceDocument`, `IngestionRun`, `SourceType`, `RawContent`, `SourceMetadata`, `PageText`, `ChunkDraft`, `TextCleaner`, `Chunker` (Tasks 1-3, 6, 7); `PdfContentExtractor` (Task 5); `EmbeddingGenerator`, `KnowledgeDocumentRepository`, `ChunkRepository`, `KnowledgeDocument`, `Chunk`, `DocumentId`, `ChunkId`, `ChunkContent`, `ChunkMetadata`, `DocumentVersion`, `DocumentStatus`, `Embedding` (Phase 1/2, `KnowledgeDocumentRepository`/`ChunkRepository` as extended by Task 4).
- Produces: `IngestDocumentService(SourceConnector, PdfContentExtractor, EmbeddingGenerator, SourceDocumentRepository, IngestionRunRepository, KnowledgeDocumentRepository, ChunkRepository)` with method `IngestionRun ingest(String sourceUri, SourceType sourceType)` — Task 9's REST controller consumes this exact signature.

**Behavior on failure before a checksum is known** (e.g. the download itself fails): no `ingestion.source_documents` row is ever created — this follows directly from Task 7's checksum-`NOT NULL` constraint, not an oversight. Only the `IngestionRun` records the failure (status `FAILED`, `errorMessage` set), which is enough for a caller polling `GET /admin/ingestion/runs/{id}` to see what happened.

- [ ] **Step 1: Write the failing test**

```java
package com.sheltr.ingestion.application;

import com.sheltr.ingestion.domain.*;
import com.sheltr.ingestion.infrastructure.PdfContentExtractor;
import com.sheltr.knowledge.domain.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IngestDocumentServiceTest {

    @Test
    void ingestsAPdfEndToEndWithFakes() throws IOException {
        byte[] pdfBytes = buildTestPdf();
        FakeSourceConnector connector = new FakeSourceConnector(pdfBytes);
        FakeEmbeddingGenerator embeddingGenerator = new FakeEmbeddingGenerator();
        FakeSourceDocumentRepository sourceDocumentRepository = new FakeSourceDocumentRepository();
        FakeIngestionRunRepository ingestionRunRepository = new FakeIngestionRunRepository();
        FakeKnowledgeDocumentRepository knowledgeDocumentRepository = new FakeKnowledgeDocumentRepository();
        FakeChunkRepository chunkRepository = new FakeChunkRepository();

        IngestDocumentService service = new IngestDocumentService(connector, new PdfContentExtractor(),
                embeddingGenerator, sourceDocumentRepository, ingestionRunRepository,
                knowledgeDocumentRepository, chunkRepository);

        IngestionRun run = service.ingest("https://example.org/test.pdf", SourceType.PDF);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(knowledgeDocumentRepository.saved).hasSize(1);
        assertThat(chunkRepository.inserted).isNotEmpty();
        assertThat(sourceDocumentRepository.lastSaved().status()).isEqualTo(IngestionStatus.INDEXED);
    }

    @Test
    void marksRunFailedWhenDownloadFails() {
        FakeSourceConnector connector = new FakeSourceConnector(null);
        FakeEmbeddingGenerator embeddingGenerator = new FakeEmbeddingGenerator();
        FakeSourceDocumentRepository sourceDocumentRepository = new FakeSourceDocumentRepository();
        FakeIngestionRunRepository ingestionRunRepository = new FakeIngestionRunRepository();
        FakeKnowledgeDocumentRepository knowledgeDocumentRepository = new FakeKnowledgeDocumentRepository();
        FakeChunkRepository chunkRepository = new FakeChunkRepository();

        IngestDocumentService service = new IngestDocumentService(connector, new PdfContentExtractor(),
                embeddingGenerator, sourceDocumentRepository, ingestionRunRepository,
                knowledgeDocumentRepository, chunkRepository);

        IngestionRun run = service.ingest("https://example.org/test.pdf", SourceType.PDF);

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.errorMessage()).isPresent();
        assertThat(sourceDocumentRepository.saved).isEmpty();
        assertThat(knowledgeDocumentRepository.saved).isEmpty();
    }

    private static byte[] buildTestPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText("Boil water for one minute to make it safe to drink.");
                stream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static class FakeSourceConnector implements SourceConnector {
        private final byte[] pdfBytes;

        FakeSourceConnector(byte[] pdfBytes) { this.pdfBytes = pdfBytes; }

        @Override
        public RawContent fetch(String sourceUri) {
            if (pdfBytes == null) {
                throw new RuntimeException("Simulated download failure");
            }
            return new RawContent(pdfBytes, new Checksum("fakechecksum123"));
        }

        @Override
        public SourceMetadata metadata() {
            return new SourceMetadata("Test Document", "FEMA", Set.of(DisasterType.FLOOD));
        }
    }

    private static class FakeEmbeddingGenerator implements EmbeddingGenerator {
        @Override
        public Embedding embed(String text) {
            float[] values = new float[768];
            values[0] = 1.0f;
            return new Embedding(values);
        }
    }

    private static class FakeSourceDocumentRepository implements SourceDocumentRepository {
        final List<SourceDocument> saved = new ArrayList<>();

        @Override
        public void save(SourceDocument document) { saved.add(document); }

        @Override
        public Optional<SourceDocument> findById(DocumentId id) { return Optional.empty(); }

        SourceDocument lastSaved() { return saved.get(saved.size() - 1); }
    }

    private static class FakeIngestionRunRepository implements IngestionRunRepository {
        @Override
        public void save(IngestionRun run) { }

        @Override
        public Optional<IngestionRun> findById(IngestionRunId id) { return Optional.empty(); }
    }

    private static class FakeKnowledgeDocumentRepository implements KnowledgeDocumentRepository {
        final List<KnowledgeDocument> saved = new ArrayList<>();

        @Override
        public Optional<KnowledgeDocument> findById(DocumentId id) { return Optional.empty(); }

        @Override
        public void save(KnowledgeDocument document) { saved.add(document); }
    }

    private static class FakeChunkRepository implements ChunkRepository {
        final List<Chunk> inserted = new ArrayList<>();

        @Override
        public Optional<Chunk> findById(ChunkId id) { return Optional.empty(); }

        @Override
        public List<Chunk> findPendingEmbeddings() { return List.of(); }

        @Override
        public void save(Chunk chunk) { }

        @Override
        public void insert(Chunk chunk) { inserted.add(chunk); }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd apps/backend && mvn -q test -Dtest=IngestDocumentServiceTest`
Expected: FAIL — `IngestDocumentService` doesn't exist yet.

- [ ] **Step 3: Implement `IngestDocumentService`**

```java
package com.sheltr.ingestion.application;

import com.sheltr.ingestion.domain.*;
import com.sheltr.ingestion.infrastructure.PdfContentExtractor;
import com.sheltr.knowledge.domain.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class IngestDocumentService {

    private final SourceConnector connector;
    private final PdfContentExtractor pdfContentExtractor;
    private final EmbeddingGenerator embeddingGenerator;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final IngestionRunRepository ingestionRunRepository;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final ChunkRepository chunkRepository;

    public IngestDocumentService(SourceConnector connector, PdfContentExtractor pdfContentExtractor,
                                  EmbeddingGenerator embeddingGenerator,
                                  SourceDocumentRepository sourceDocumentRepository,
                                  IngestionRunRepository ingestionRunRepository,
                                  KnowledgeDocumentRepository knowledgeDocumentRepository,
                                  ChunkRepository chunkRepository) {
        this.connector = connector;
        this.pdfContentExtractor = pdfContentExtractor;
        this.embeddingGenerator = embeddingGenerator;
        this.sourceDocumentRepository = sourceDocumentRepository;
        this.ingestionRunRepository = ingestionRunRepository;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.chunkRepository = chunkRepository;
    }

    public IngestionRun ingest(String sourceUri, SourceType sourceType) {
        DocumentId documentId = new DocumentId(UUID.randomUUID());
        SourceDocument sourceDocument = new SourceDocument(documentId, sourceUri, sourceType);

        IngestionRun run = new IngestionRun(new IngestionRunId(UUID.randomUUID()), documentId);
        ingestionRunRepository.save(run);

        try {
            if (sourceType != SourceType.PDF) {
                throw new UnsupportedOperationException(
                        "Only PDF ingestion is supported in this slice, got " + sourceType);
            }

            RawContent raw = connector.fetch(sourceUri);
            sourceDocument.markDownloaded(raw.checksum());
            sourceDocumentRepository.save(sourceDocument);

            List<PageText> pages = pdfContentExtractor.extract(raw.bytes());
            sourceDocument.markExtracted();
            sourceDocumentRepository.save(sourceDocument);

            List<PageText> cleanedPages = pages.stream()
                    .map(page -> new PageText(page.pageNumber(), TextCleaner.clean(page.text())))
                    .toList();
            sourceDocument.markCleaned();
            sourceDocumentRepository.save(sourceDocument);

            List<ChunkDraft> chunkDrafts = Chunker.chunk(cleanedPages);
            sourceDocument.markChunked();
            sourceDocumentRepository.save(sourceDocument);

            SourceMetadata metadata = connector.metadata();
            KnowledgeDocument knowledgeDocument = new KnowledgeDocument(
                    documentId, metadata.title(), metadata.sourceAgency(), sourceUri, metadata.disasterTypes(),
                    LocalDate.now(), new DocumentVersion(1), raw.checksum(), null, DocumentStatus.ACTIVE);
            knowledgeDocumentRepository.save(knowledgeDocument);

            int chunkIndex = 0;
            for (ChunkDraft draft : chunkDrafts) {
                Embedding embedding = embeddingGenerator.embed(draft.content());
                Chunk chunk = new Chunk(
                        new ChunkId(UUID.randomUUID()), documentId, new ChunkContent(draft.content()), embedding,
                        draft.pageNumber(), chunkIndex++, new ChunkMetadata(null, metadata.disasterTypes()),
                        raw.checksum());
                chunkRepository.insert(chunk);
            }

            sourceDocument.markIndexed();
            sourceDocumentRepository.save(sourceDocument);

            run.markSucceeded();
            ingestionRunRepository.save(run);
        } catch (Exception e) {
            if (sourceDocument.checksum().isPresent()) {
                sourceDocument.markFailed();
                sourceDocumentRepository.save(sourceDocument);
            }
            run.markFailed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            ingestionRunRepository.save(run);
        }

        return run;
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd apps/backend && mvn -q test -Dtest=IngestDocumentServiceTest`
Expected: `BUILD SUCCESS`, 2/2 passing.

- [ ] **Step 5: Implement `IngestDocumentServiceConfig`**

```java
package com.sheltr.ingestion.config;

import com.sheltr.ingestion.application.IngestDocumentService;
import com.sheltr.ingestion.domain.IngestionRunRepository;
import com.sheltr.ingestion.domain.SourceConnector;
import com.sheltr.ingestion.domain.SourceDocumentRepository;
import com.sheltr.ingestion.infrastructure.PdfContentExtractor;
import com.sheltr.knowledge.domain.ChunkRepository;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IngestDocumentServiceConfig {

    @Bean
    public PdfContentExtractor pdfContentExtractor() {
        return new PdfContentExtractor();
    }

    @Bean
    public IngestDocumentService ingestDocumentService(
            SourceConnector sourceConnector, PdfContentExtractor pdfContentExtractor,
            EmbeddingGenerator embeddingGenerator, SourceDocumentRepository sourceDocumentRepository,
            IngestionRunRepository ingestionRunRepository, KnowledgeDocumentRepository knowledgeDocumentRepository,
            ChunkRepository chunkRepository) {
        return new IngestDocumentService(sourceConnector, pdfContentExtractor, embeddingGenerator,
                sourceDocumentRepository, ingestionRunRepository, knowledgeDocumentRepository, chunkRepository);
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add apps/backend/src/main/java/com/sheltr/ingestion/application/ apps/backend/src/main/java/com/sheltr/ingestion/config/IngestDocumentServiceConfig.java apps/backend/src/test/java/com/sheltr/ingestion/application/
git commit -m "feat(ingestion): add IngestDocumentService orchestrating the full pipeline"
```

---

### Task 9: IngestionController REST endpoints

**Files:**
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/interfaces/TriggerIngestionRequestDto.java`
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/interfaces/IngestionRunDto.java`
- Create: `apps/backend/src/main/java/com/sheltr/ingestion/interfaces/IngestionController.java`
- Test: `apps/backend/src/test/java/com/sheltr/ingestion/interfaces/IngestionControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `IngestDocumentService` (Task 8); `IngestionRunRepository`, `IngestionRun`, `IngestionRunId`, `SourceType` (Tasks 1-3, 7).
- Produces: `POST /api/v1/admin/ingestion/runs`, `GET /api/v1/admin/ingestion/runs/{id}` — matching `docs/openapi/openapi.yaml`'s `TriggerIngestionRequest`/`IngestionRun` schemas exactly (`id`, `sourceDocumentId`, `status`, `startedAt`, `finishedAt` nullable, `errorMessage` nullable).

Runs the pipeline **synchronously** within the HTTP request (per spec) — the `202 Accepted` response already carries the run's final status (`SUCCEEDED`/`FAILED`) by the time it's returned, since there's no async worker yet.

- [ ] **Step 1: Write DTOs**

```java
package com.sheltr.ingestion.interfaces;

public record TriggerIngestionRequestDto(String sourceUri, String sourceType) {
}
```

```java
package com.sheltr.ingestion.interfaces;

import java.time.Instant;
import java.util.UUID;

public record IngestionRunDto(UUID id, UUID sourceDocumentId, String status, Instant startedAt,
                               Instant finishedAt, String errorMessage) {
}
```

- [ ] **Step 2: Write the failing integration test**

```java
package com.sheltr.ingestion.interfaces;

import com.sheltr.SheltrBackendApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@SpringBootTest(classes = SheltrBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestionControllerIntegrationTest {

    private static final String CAREGIVERS_GUIDE_URL =
            "https://www.ready.gov/sites/default/files/2024-02/ready-gov_caregivers-preparedness-guide.pdf";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @org.springframework.beans.factory.annotation.Autowired
    TestRestTemplate restTemplate;

    @Test
    void triggersRealIngestionAndReachesIndexedStatus() {
        ResponseEntity<IngestionRunDto> response = restTemplate.postForEntity(
                "/api/v1/admin/ingestion/runs",
                new TriggerIngestionRequestDto(CAREGIVERS_GUIDE_URL, "PDF"),
                IngestionRunDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().status()).isEqualTo("SUCCEEDED");
        assertThat(response.getBody().finishedAt()).isNotNull();

        ResponseEntity<IngestionRunDto> fetched = restTemplate.getForEntity(
                "/api/v1/admin/ingestion/runs/" + response.getBody().id(), IngestionRunDto.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void returnsNotFoundForUnknownRun() {
        ResponseEntity<IngestionRunDto> response = restTemplate.getForEntity(
                "/api/v1/admin/ingestion/runs/" + java.util.UUID.randomUUID(), IngestionRunDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd apps/backend && GEMINI_API_KEY=$GEMINI_API_KEY mvn -q test -Dtest=IngestionControllerIntegrationTest`
Expected: FAIL — `IngestionController` doesn't exist yet.

- [ ] **Step 4: Implement `IngestionController`**

```java
package com.sheltr.ingestion.interfaces;

import com.sheltr.ingestion.application.IngestDocumentService;
import com.sheltr.ingestion.domain.IngestionRun;
import com.sheltr.ingestion.domain.IngestionRunId;
import com.sheltr.ingestion.domain.IngestionRunRepository;
import com.sheltr.ingestion.domain.SourceType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/ingestion/runs")
public class IngestionController {

    private final IngestDocumentService ingestDocumentService;
    private final IngestionRunRepository ingestionRunRepository;

    public IngestionController(IngestDocumentService ingestDocumentService,
                                IngestionRunRepository ingestionRunRepository) {
        this.ingestDocumentService = ingestDocumentService;
        this.ingestionRunRepository = ingestionRunRepository;
    }

    @PostMapping
    public ResponseEntity<IngestionRunDto> trigger(@RequestBody TriggerIngestionRequestDto request) {
        SourceType sourceType = SourceType.valueOf(request.sourceType());
        IngestionRun run = ingestDocumentService.ingest(request.sourceUri(), sourceType);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toDto(run));
    }

    @GetMapping("/{id}")
    public ResponseEntity<IngestionRunDto> get(@PathVariable UUID id) {
        return ingestionRunRepository.findById(new IngestionRunId(id))
                .map(run -> ResponseEntity.ok(toDto(run)))
                .orElse(ResponseEntity.notFound().build());
    }

    private static IngestionRunDto toDto(IngestionRun run) {
        return new IngestionRunDto(run.id().value(), run.sourceDocumentId().value(), run.status().name(),
                run.startedAt(), run.finishedAt().orElse(null), run.errorMessage().orElse(null));
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd apps/backend && GEMINI_API_KEY=$GEMINI_API_KEY mvn -q test -Dtest=IngestionControllerIntegrationTest`
Expected: `BUILD SUCCESS`, 2/2 passing (real FEMA download + real Gemini embedding calls — this is slow, expect a minute or more given the ~2.5MB PDF and however many chunks it produces).

- [ ] **Step 6: Run the full suite**

Run: `cd apps/backend && GEMINI_API_KEY=$GEMINI_API_KEY mvn -q test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add apps/backend/src/main/java/com/sheltr/ingestion/interfaces/ apps/backend/src/test/java/com/sheltr/ingestion/interfaces/
git commit -m "feat(ingestion): add admin ingestion REST endpoints"
```

---

### Task 10: End-to-end Ingestion→RAG loop test

**Files:**
- Create: `apps/backend/src/test/java/com/sheltr/ingestion/interfaces/IngestionToRagEndToEndTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1-9, plus Phase 2's `Retriever`/`SearchQuery`/`RankedResult` (`com.sheltr.search.domain`) — this task wires no new production code, it is a pure verification task proving Ingestion and the Phase 2 RAG loop are actually connected, not just that each works in isolation.

This is the capstone test for this spec: ingest the real FEMA PDF via the real REST endpoint, then query the newly-ingested content via Phase 2's real `Retriever` bean and confirm real, relevant chunks come back — proving data written by Ingestion is actually reachable by Search, not just present in the database.

- [ ] **Step 1: Write the test**

```java
package com.sheltr.ingestion.interfaces;

import com.sheltr.SheltrBackendApplication;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@SpringBootTest(classes = SheltrBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestionToRagEndToEndTest {

    private static final String CAREGIVERS_GUIDE_URL =
            "https://www.ready.gov/sites/default/files/2024-02/ready-gov_caregivers-preparedness-guide.pdf";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    Retriever retriever;

    @Test
    void ingestedDocumentIsRetrievableViaSemanticSearch() {
        ResponseEntity<IngestionRunDto> response = restTemplate.postForEntity(
                "/api/v1/admin/ingestion/runs",
                new TriggerIngestionRequestDto(CAREGIVERS_GUIDE_URL, "PDF"),
                IngestionRunDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().status()).isEqualTo("SUCCEEDED");

        List<RankedResult> results = retriever.retrieve(
                new SearchQuery("How can caregivers prepare for a disaster?"), 5);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).score()).isGreaterThan(0.5);
        assertThat(results.get(0).content()).isNotBlank();
    }
}
```

- [ ] **Step 2: Run to verify it passes**

Run: `cd apps/backend && GEMINI_API_KEY=$GEMINI_API_KEY mvn -q test -Dtest=IngestionToRagEndToEndTest`
Expected: `BUILD SUCCESS`, 1/1 passing. This test makes a real ~2.5MB download, real chunk-count-many embedding calls, and a real query embedding call — expect it to be the slowest single test in the suite; that's expected for the one test proving Ingestion and RAG are genuinely connected end to end.

- [ ] **Step 3: Run the entire backend test suite one more time**

Run: `cd apps/backend && GEMINI_API_KEY=$GEMINI_API_KEY mvn -q test`
Expected: `BUILD SUCCESS`, every test from Tasks 1-10 (plus all of Phase 1/2's existing tests) passing.

- [ ] **Step 4: Commit**

```bash
git add apps/backend/src/test/java/com/sheltr/ingestion/interfaces/IngestionToRagEndToEndTest.java
git commit -m "test: add end-to-end Ingestion-to-RAG test proving real connectivity"
```

---

## Final Verification

- [ ] **Run the full backend test suite from repo root**

```bash
cd apps/backend && GEMINI_API_KEY=$GEMINI_API_KEY mvn -q test && cd ../..
```
Expected: `BUILD SUCCESS`.

- [ ] **Manual smoke test against a real (Podman-started) Postgres**

```bash
make up
make migrate
cd apps/backend && GEMINI_API_KEY=$GEMINI_API_KEY mvn -q spring-boot:run &
sleep 10
curl -s -X POST http://localhost:8080/api/v1/admin/ingestion/runs \
  -H "Content-Type: application/json" \
  -d '{"sourceUri": "https://www.ready.gov/sites/default/files/2024-02/ready-gov_caregivers-preparedness-guide.pdf", "sourceType": "PDF"}' \
  | tee /tmp/run.json
kill %1
cd ../..
make down
```
Expected: a `202` response with `"status":"SUCCEEDED"`, a real `sourceDocumentId`, and a `finishedAt` timestamp.

- [ ] **Confirm git log shows all 10 tasks as separate commits**

```bash
git log --oneline
```
