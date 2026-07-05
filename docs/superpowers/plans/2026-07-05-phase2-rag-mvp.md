# Phase 2 MVP RAG Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire a real, citation-grounded RAG loop into the Phase 1 hexagonal skeleton — semantic search over seeded disaster-guidance chunks, Gemini-generated answers with citations and confidence scoring, persisted conversations.

**Architecture:** Fills in `knowledge`, `search`, `ai` bounded contexts (domain/application/infrastructure/interfaces) per `docs/superpowers/specs/2026-07-04-phase2-rag-mvp-design.md`. Repository adapters use jOOQ's dynamic DSL (`DSL.using(dataSource)`, `DSL.table(...)`/`DSL.field(...)`) rather than codegen'd static types — the codegen profile from Phase 1 stays opt-in/manual; promoting it to the default build is a later follow-up, not needed for this slice (YAGNI — dynamic DSL already gives typesafe-ish queries and avoids requiring a live Postgres at build time).

**Tech Stack:** Spring AI (Google GenAI/Gemini starter), jOOQ dynamic DSL, Flyway, Testcontainers, JUnit 5, Podman.

## Global Constraints

- Domain packages (`knowledge/domain`, `search/domain`, `ai/domain`) have zero framework imports — no `org.springframework.*`, no `org.jooq.*`, no Gemini SDK types (ADR-0002). Ports defined in domain are plain Java interfaces.
- No Gemini/Spring-AI-specific type may appear outside an `infrastructure` package (ADR-0003).
- `GEMINI_API_KEY` is read only from the environment, never hardcoded or committed (global constraint, reinforced this phase since a real key is in use).
- Embedding model: `text-embedding-004` (768-dim, matches `knowledge.chunks.embedding VECTOR(768)`). Generation model: `gemini-2.5-flash`. Both configurable via `application.yml`, not hardcoded in Java.
- Relevance threshold: `sheltr.search.relevance-threshold`, default `0.75`. Confidence bands: `HIGH` ≥ 0.90, `MEDIUM` ≥ 0.75 (see spec Section 4 for why `LOW` is currently unreachable — state this in code comments, don't silently leave dead code unexplained).
- Container runtime: Podman only (ADR-0010) — `make up` / `make migrate` for all local verification.
- Gemini-calling tests are gated behind `@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")` so they skip cleanly without a key and run for real when one is present.
- Gemini generation failures surface as a clean 5xx REST response (spec Section 8) — distinct from the `NoAnswerAvailable`/below-threshold case, which is a legitimate 200 "no reliable source found" product outcome, not an error. Do not conflate the two into a single 200 fallback-text response.

---

### Task 1: Knowledge domain value objects and aggregates

**Files:**
- Create: `apps/backend/src/main/java/com/sheltr/knowledge/domain/DocumentId.java`
- Create: `apps/backend/src/main/java/com/sheltr/knowledge/domain/ChunkId.java`
- Create: `apps/backend/src/main/java/com/sheltr/knowledge/domain/ChunkContent.java`
- Create: `apps/backend/src/main/java/com/sheltr/knowledge/domain/Embedding.java`
- Create: `apps/backend/src/main/java/com/sheltr/knowledge/domain/PageNumber.java`
- Create: `apps/backend/src/main/java/com/sheltr/knowledge/domain/DocumentVersion.java`
- Create: `apps/backend/src/main/java/com/sheltr/knowledge/domain/Checksum.java`
- Create: `apps/backend/src/main/java/com/sheltr/knowledge/domain/DisasterType.java`
- Create: `apps/backend/src/main/java/com/sheltr/knowledge/domain/DocumentStatus.java`
- Create: `apps/backend/src/main/java/com/sheltr/knowledge/domain/ChunkMetadata.java`
- Create: `apps/backend/src/main/java/com/sheltr/knowledge/domain/KnowledgeDocument.java`
- Create: `apps/backend/src/main/java/com/sheltr/knowledge/domain/Chunk.java`
- Test: `apps/backend/src/test/java/com/sheltr/knowledge/domain/ChunkTest.java`
- Test: `apps/backend/src/test/java/com/sheltr/knowledge/domain/KnowledgeDocumentTest.java`

**Interfaces:**
- Produces: `DocumentId(UUID value)`, `ChunkId(UUID value)`, `ChunkContent(String value)`, `Embedding(float[] values)` (768-length, validated), `PageNumber(int value)`, `DocumentVersion(int value)`, `Checksum(String hex)`, `DisasterType` enum (`EARTHQUAKE, FLOOD, HURRICANE, TORNADO, TSUNAMI, WILDFIRE, EXTREME_HEAT, WINTER_STORM`), `DocumentStatus` enum (`ACTIVE, SUPERSEDED, RETRACTED`), `ChunkMetadata(String sectionHeading, Set<DisasterType> disasterTypes)`, `Chunk` (fields below), `KnowledgeDocument` (fields below). All are Java `record`s except `Chunk`/`KnowledgeDocument` which are plain immutable classes (need `withEmbedding`-style wither methods for `Chunk`, since embedding is populated after creation by the backfill service).

- [ ] **Step 1: Write failing tests for the value objects**

```java
package com.sheltr.knowledge.domain;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkTest {

    @Test
    void createsChunkWithoutEmbedding() {
        ChunkId id = new ChunkId(UUID.randomUUID());
        DocumentId documentId = new DocumentId(UUID.randomUUID());
        ChunkContent content = new ChunkContent("Boil water for one minute to make it safe to drink.");
        ChunkMetadata metadata = new ChunkMetadata("Water Safety", java.util.Set.of(DisasterType.FLOOD));

        Chunk chunk = new Chunk(id, documentId, content, null, null, 0, metadata, new Checksum("abc123"));

        assertThat(chunk.id()).isEqualTo(id);
        assertThat(chunk.embedding()).isEmpty();
    }

    @Test
    void withEmbeddingReturnsNewChunkWithEmbeddingSet() {
        Chunk chunk = new Chunk(
                new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()),
                new ChunkContent("Test content"), null, null, 0,
                new ChunkMetadata("Section", java.util.Set.of(DisasterType.WILDFIRE)),
                new Checksum("abc123"));
        float[] vector = new float[768];
        vector[0] = 0.5f;
        Embedding embedding = new Embedding(vector);

        Chunk withEmbedding = chunk.withEmbedding(embedding);

        assertThat(withEmbedding.embedding()).contains(embedding);
        assertThat(chunk.embedding()).isEmpty();
    }

    @Test
    void embeddingRejectsWrongDimension() {
        assertThatThrownBy(() -> new Embedding(new float[10]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("768");
    }
}
```

```java
package com.sheltr.knowledge.domain;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeDocumentTest {

    @Test
    void createsActiveDocument() {
        KnowledgeDocument doc = new KnowledgeDocument(
                new DocumentId(UUID.randomUUID()), "Water Safety During Floods", "American Red Cross",
                "https://redcross.org/water-safety", java.util.Set.of(DisasterType.FLOOD),
                LocalDate.of(2024, 1, 1), new DocumentVersion(1), new Checksum("abc123"),
                null, DocumentStatus.ACTIVE);

        assertThat(doc.status()).isEqualTo(DocumentStatus.ACTIVE);
        assertThat(doc.supersedes()).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd apps/backend && mvn -q test -Dtest=ChunkTest,KnowledgeDocumentTest`
Expected: FAIL — compilation errors, classes don't exist yet.

- [ ] **Step 3: Implement the value objects**

```java
package com.sheltr.knowledge.domain;

import java.util.UUID;

public record DocumentId(UUID value) {
    public DocumentId {
        if (value == null) throw new IllegalArgumentException("DocumentId value must not be null");
    }
}
```

```java
package com.sheltr.knowledge.domain;

import java.util.UUID;

public record ChunkId(UUID value) {
    public ChunkId {
        if (value == null) throw new IllegalArgumentException("ChunkId value must not be null");
    }
}
```

```java
package com.sheltr.knowledge.domain;

public record ChunkContent(String value) {
    public ChunkContent {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ChunkContent value must not be blank");
        }
    }
}
```

```java
package com.sheltr.knowledge.domain;

import java.util.Arrays;

public record Embedding(float[] values) {
    public static final int DIMENSION = 768;

    public Embedding {
        if (values == null || values.length != DIMENSION) {
            throw new IllegalArgumentException(
                    "Embedding must have exactly " + DIMENSION + " dimensions, got "
                            + (values == null ? "null" : values.length));
        }
        values = Arrays.copyOf(values, values.length);
    }

    @Override
    public float[] values() {
        return Arrays.copyOf(values, values.length);
    }
}
```

```java
package com.sheltr.knowledge.domain;

public record PageNumber(int value) {
    public PageNumber {
        if (value < 1) throw new IllegalArgumentException("PageNumber must be >= 1");
    }
}
```

```java
package com.sheltr.knowledge.domain;

public record DocumentVersion(int value) {
    public DocumentVersion {
        if (value < 1) throw new IllegalArgumentException("DocumentVersion must be >= 1");
    }
}
```

```java
package com.sheltr.knowledge.domain;

public record Checksum(String hex) {
    public Checksum {
        if (hex == null || hex.isBlank()) {
            throw new IllegalArgumentException("Checksum hex must not be blank");
        }
    }
}
```

```java
package com.sheltr.knowledge.domain;

public enum DisasterType {
    EARTHQUAKE, FLOOD, HURRICANE, TORNADO, TSUNAMI, WILDFIRE, EXTREME_HEAT, WINTER_STORM
}
```

```java
package com.sheltr.knowledge.domain;

public enum DocumentStatus {
    ACTIVE, SUPERSEDED, RETRACTED
}
```

```java
package com.sheltr.knowledge.domain;

import java.util.Set;

public record ChunkMetadata(String sectionHeading, Set<DisasterType> disasterTypes) {
    public ChunkMetadata {
        disasterTypes = Set.copyOf(disasterTypes == null ? Set.of() : disasterTypes);
    }
}
```

```java
package com.sheltr.knowledge.domain;

import java.util.Optional;

public final class Chunk {
    private final ChunkId id;
    private final DocumentId documentId;
    private final ChunkContent content;
    private final Embedding embedding;
    private final PageNumber pageNumber;
    private final int chunkIndex;
    private final ChunkMetadata metadata;
    private final Checksum checksum;

    public Chunk(ChunkId id, DocumentId documentId, ChunkContent content, Embedding embedding,
                 PageNumber pageNumber, int chunkIndex, ChunkMetadata metadata, Checksum checksum) {
        this.id = id;
        this.documentId = documentId;
        this.content = content;
        this.embedding = embedding;
        this.pageNumber = pageNumber;
        this.chunkIndex = chunkIndex;
        this.metadata = metadata;
        this.checksum = checksum;
    }

    public Chunk withEmbedding(Embedding newEmbedding) {
        return new Chunk(id, documentId, content, newEmbedding, pageNumber, chunkIndex, metadata, checksum);
    }

    public ChunkId id() { return id; }
    public DocumentId documentId() { return documentId; }
    public ChunkContent content() { return content; }
    public Optional<Embedding> embedding() { return Optional.ofNullable(embedding); }
    public Optional<PageNumber> pageNumber() { return Optional.ofNullable(pageNumber); }
    public int chunkIndex() { return chunkIndex; }
    public ChunkMetadata metadata() { return metadata; }
    public Checksum checksum() { return checksum; }
}
```

```java
package com.sheltr.knowledge.domain;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

public final class KnowledgeDocument {
    private final DocumentId id;
    private final String title;
    private final String sourceAgency;
    private final String sourceUri;
    private final Set<DisasterType> disasterTypes;
    private final LocalDate publishedDate;
    private final DocumentVersion version;
    private final Checksum checksum;
    private final DocumentId supersedes;
    private final DocumentStatus status;

    public KnowledgeDocument(DocumentId id, String title, String sourceAgency, String sourceUri,
                              Set<DisasterType> disasterTypes, LocalDate publishedDate, DocumentVersion version,
                              Checksum checksum, DocumentId supersedes, DocumentStatus status) {
        this.id = id;
        this.title = title;
        this.sourceAgency = sourceAgency;
        this.sourceUri = sourceUri;
        this.disasterTypes = Set.copyOf(disasterTypes == null ? Set.of() : disasterTypes);
        this.publishedDate = publishedDate;
        this.version = version;
        this.checksum = checksum;
        this.supersedes = supersedes;
        this.status = status;
    }

    public DocumentId id() { return id; }
    public String title() { return title; }
    public String sourceAgency() { return sourceAgency; }
    public String sourceUri() { return sourceUri; }
    public Set<DisasterType> disasterTypes() { return disasterTypes; }
    public LocalDate publishedDate() { return publishedDate; }
    public DocumentVersion version() { return version; }
    public Checksum checksum() { return checksum; }
    public Optional<DocumentId> supersedes() { return Optional.ofNullable(supersedes); }
    public DocumentStatus status() { return status; }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd apps/backend && mvn -q test -Dtest=ChunkTest,KnowledgeDocumentTest`
Expected: `BUILD SUCCESS`, 4/4 tests passing.

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/main/java/com/sheltr/knowledge/domain/ apps/backend/src/test/java/com/sheltr/knowledge/domain/
git commit -m "feat(knowledge): add domain value objects and Chunk/KnowledgeDocument aggregates"
```

---

### Task 2: Knowledge repository ports and EmbeddingGenerator port

**Files:**
- Create: `apps/backend/src/main/java/com/sheltr/knowledge/domain/ChunkRepository.java`
- Create: `apps/backend/src/main/java/com/sheltr/knowledge/domain/KnowledgeDocumentRepository.java`
- Create: `apps/backend/src/main/java/com/sheltr/knowledge/domain/EmbeddingGenerator.java`

**Interfaces:**
- Consumes: `Chunk`, `ChunkId`, `DocumentId`, `KnowledgeDocument`, `Embedding` from Task 1.
- Produces: `ChunkRepository` (methods: `findById(ChunkId)`, `findPendingEmbeddings()`, `save(Chunk)`), `KnowledgeDocumentRepository` (methods: `findById(DocumentId)`), `EmbeddingGenerator` (method: `Embedding embed(String text)`). These are the exact signatures Tasks 3, 4, 6, and 8 depend on.

This task is pure interface definitions (no test needed — nothing to execute yet; verified indirectly when Task 3's adapter implements them and compiles).

- [ ] **Step 1: Write the repository and generator port interfaces**

```java
package com.sheltr.knowledge.domain;

import java.util.List;
import java.util.Optional;

public interface ChunkRepository {
    Optional<Chunk> findById(ChunkId id);
    List<Chunk> findPendingEmbeddings();
    void save(Chunk chunk);
}
```

```java
package com.sheltr.knowledge.domain;

import java.util.Optional;

public interface KnowledgeDocumentRepository {
    Optional<KnowledgeDocument> findById(DocumentId id);
}
```

```java
package com.sheltr.knowledge.domain;

public interface EmbeddingGenerator {
    Embedding embed(String text);
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd apps/backend && mvn -q -DskipTests compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add apps/backend/src/main/java/com/sheltr/knowledge/domain/ChunkRepository.java apps/backend/src/main/java/com/sheltr/knowledge/domain/KnowledgeDocumentRepository.java apps/backend/src/main/java/com/sheltr/knowledge/domain/EmbeddingGenerator.java
git commit -m "feat(knowledge): add ChunkRepository, KnowledgeDocumentRepository, EmbeddingGenerator ports"
```

---

### Task 3: jOOQ repository adapters (dynamic DSL)

**Files:**
- Create: `apps/backend/src/main/java/com/sheltr/knowledge/infrastructure/JooqChunkRepository.java`
- Create: `apps/backend/src/main/java/com/sheltr/knowledge/infrastructure/JooqKnowledgeDocumentRepository.java`
- Test: `apps/backend/src/test/java/com/sheltr/knowledge/infrastructure/JooqChunkRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: `ChunkRepository`, `KnowledgeDocumentRepository`, `Chunk`, `KnowledgeDocument`, `ChunkId`, `DocumentId`, `Embedding`, `ChunkContent`, `ChunkMetadata`, `Checksum`, `PageNumber`, `DisasterType`, `DocumentStatus`, `DocumentVersion` from Tasks 1-2.
- Produces: `JooqChunkRepository(DataSource dataSource)` implementing `ChunkRepository`; `JooqKnowledgeDocumentRepository(DataSource dataSource)` implementing `KnowledgeDocumentRepository`. Task 5's seed migration inserts rows these adapters read; Task 6's backfill service and Task 8's retrieval adapter consume `JooqChunkRepository`.

Uses jOOQ's dynamic DSL (`DSL.using(dataSource, SQLDialect.POSTGRES)` + plain parameterized SQL via `dsl.fetch(sql, bindings)`/`dsl.execute(sql, bindings)`) rather than codegen'd static table classes — see plan header for rationale. pgvector's `vector` column is written via a `'[0.1,0.2,...]'::vector` text literal since jOOQ has no native pgvector type.

- [ ] **Step 1: Write the failing integration test**

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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JooqChunkRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static DataSource dataSource() {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setURL(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    private static void migrate() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
    }

    @Test
    void savesAndReadsBackAnEmbedding() {
        migrate();
        DataSource dataSource = dataSource();
        JooqKnowledgeDocumentRepository documentRepository = new JooqKnowledgeDocumentRepository(dataSource);
        JooqChunkRepository chunkRepository = new JooqChunkRepository(dataSource);

        DocumentId documentId = new DocumentId(UUID.randomUUID());
        insertDocument(dataSource, documentId);
        ChunkId chunkId = new ChunkId(UUID.randomUUID());
        insertChunkWithoutEmbedding(dataSource, chunkId, documentId);

        List<Chunk> pending = chunkRepository.findPendingEmbeddings();
        assertThat(pending).extracting(Chunk::id).contains(chunkId);

        float[] vector = new float[768];
        vector[0] = 0.42f;
        Chunk toSave = pending.stream().filter(c -> c.id().equals(chunkId)).findFirst().orElseThrow()
                .withEmbedding(new Embedding(vector));
        chunkRepository.save(toSave);

        List<Chunk> pendingAfter = chunkRepository.findPendingEmbeddings();
        assertThat(pendingAfter).extracting(Chunk::id).doesNotContain(chunkId);

        Optional<KnowledgeDocument> document = documentRepository.findById(documentId);
        assertThat(document).isPresent();
        assertThat(document.get().title()).isEqualTo("Test Document");
    }

    private static void insertDocument(DataSource dataSource, DocumentId id) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "INSERT INTO knowledge.documents (id, title, source_agency, source_uri, disaster_types, "
                             + "published_date, version, status, checksum) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            stmt.setObject(1, id.value());
            stmt.setString(2, "Test Document");
            stmt.setString(3, "Test Agency");
            stmt.setString(4, "https://example.org/test");
            stmt.setArray(5, conn.createArrayOf("text", new String[]{"FLOOD"}));
            stmt.setObject(6, LocalDate.of(2024, 1, 1));
            stmt.setInt(7, 1);
            stmt.setString(8, "ACTIVE");
            stmt.setString(9, "checksum123");
            stmt.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void insertChunkWithoutEmbedding(DataSource dataSource, ChunkId chunkId, DocumentId documentId) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "INSERT INTO knowledge.chunks (id, document_id, content, chunk_index, section_heading, checksum) "
                             + "VALUES (?, ?, ?, ?, ?, ?)")) {
            stmt.setObject(1, chunkId.value());
            stmt.setObject(2, documentId.value());
            stmt.setString(3, "Boil water for one minute.");
            stmt.setInt(4, 0);
            stmt.setString(5, "Water Safety");
            stmt.setString(6, "chunkchecksum");
            stmt.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd apps/backend && mvn -q test -Dtest=JooqChunkRepositoryIntegrationTest`
Expected: FAIL — `JooqChunkRepository`/`JooqKnowledgeDocumentRepository` don't exist yet.

- [ ] **Step 3: Implement `JooqChunkRepository`**

```java
package com.sheltr.knowledge.infrastructure;

import com.sheltr.knowledge.domain.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class JooqChunkRepository implements ChunkRepository {

    private final DSLContext dsl;

    public JooqChunkRepository(DataSource dataSource) {
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    @Override
    public Optional<Chunk> findById(ChunkId id) {
        Record record = dsl.fetchOne(
                "SELECT id, document_id, content, page_number, chunk_index, section_heading, checksum "
                        + "FROM knowledge.chunks WHERE id = ?",
                id.value());
        return Optional.ofNullable(record).map(JooqChunkRepository::mapRow);
    }

    @Override
    public List<Chunk> findPendingEmbeddings() {
        var result = dsl.fetch(
                "SELECT id, document_id, content, page_number, chunk_index, section_heading, checksum "
                        + "FROM knowledge.chunks WHERE embedding IS NULL");
        List<Chunk> chunks = new ArrayList<>();
        for (Record record : result) {
            chunks.add(mapRow(record));
        }
        return chunks;
    }

    @Override
    public void save(Chunk chunk) {
        Embedding embedding = chunk.embedding().orElseThrow(
                () -> new IllegalStateException("save() only supports writing an embedding in this slice"));
        dsl.execute("UPDATE knowledge.chunks SET embedding = ?::vector WHERE id = ?",
                toVectorLiteral(embedding), chunk.id().value());
    }

    private static String toVectorLiteral(Embedding embedding) {
        float[] values = embedding.values();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        return sb.append(']').toString();
    }

    private static Chunk mapRow(Record record) {
        Integer pageNumberValue = record.get("page_number", Integer.class);
        String sectionHeading = record.get("section_heading", String.class);
        return new Chunk(
                new ChunkId((UUID) record.get("id")),
                new DocumentId((UUID) record.get("document_id")),
                new ChunkContent(record.get("content", String.class)),
                null,
                pageNumberValue == null ? null : new PageNumber(pageNumberValue),
                record.get("chunk_index", Integer.class),
                new ChunkMetadata(sectionHeading, Set.of()),
                new Checksum(record.get("checksum", String.class)));
    }
}
```

- [ ] **Step 4: Implement `JooqKnowledgeDocumentRepository`**

```java
package com.sheltr.knowledge.infrastructure;

import com.sheltr.knowledge.domain.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class JooqKnowledgeDocumentRepository implements KnowledgeDocumentRepository {

    private final DSLContext dsl;

    public JooqKnowledgeDocumentRepository(DataSource dataSource) {
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    @Override
    public Optional<KnowledgeDocument> findById(DocumentId id) {
        Record record = dsl.fetchOne(
                "SELECT id, title, source_agency, source_uri, disaster_types, published_date, version, "
                        + "checksum, supersedes_id, status FROM knowledge.documents WHERE id = ?",
                id.value());
        return Optional.ofNullable(record).map(JooqKnowledgeDocumentRepository::mapRow);
    }

    private static KnowledgeDocument mapRow(Record record) {
        String[] disasterTypeNames = record.get("disaster_types", String[].class);
        Set<DisasterType> disasterTypes = Arrays.stream(disasterTypeNames)
                .map(DisasterType::valueOf)
                .collect(Collectors.toSet());
        UUID supersedesId = record.get("supersedes_id", UUID.class);
        return new KnowledgeDocument(
                new DocumentId((UUID) record.get("id")),
                record.get("title", String.class),
                record.get("source_agency", String.class),
                record.get("source_uri", String.class),
                disasterTypes,
                record.get("published_date", LocalDate.class),
                new DocumentVersion(record.get("version", Integer.class)),
                new Checksum(record.get("checksum", String.class)),
                supersedesId == null ? null : new DocumentId(supersedesId),
                DocumentStatus.valueOf(record.get("status", String.class)));
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd apps/backend && mvn -q test -Dtest=JooqChunkRepositoryIntegrationTest`
Expected: `BUILD SUCCESS`, 1/1 passing.

- [ ] **Step 6: Commit**

```bash
git add apps/backend/src/main/java/com/sheltr/knowledge/infrastructure/ apps/backend/src/test/java/com/sheltr/knowledge/infrastructure/
git commit -m "feat(knowledge): add jOOQ dynamic-DSL repository adapters"
```

---

### Task 4: Spring AI Gemini dependency, config, and EmbeddingGenerator adapter

**Files:**
- Modify: `apps/backend/pom.xml` (add Spring AI BOM + Google GenAI starter)
- Modify: `apps/backend/src/main/resources/application.yml` (add Spring AI config)
- Create: `apps/backend/src/main/java/com/sheltr/knowledge/infrastructure/GeminiEmbeddingGeneratorAdapter.java`
- Create: `apps/backend/src/main/java/com/sheltr/knowledge/config/EmbeddingGeneratorConfig.java`
- Test: `apps/backend/src/test/java/com/sheltr/knowledge/infrastructure/GeminiEmbeddingGeneratorAdapterTest.java`

**Interfaces:**
- Consumes: `EmbeddingGenerator`, `Embedding` from Tasks 1-2.
- Produces: `GeminiEmbeddingGeneratorAdapter(EmbeddingModel embeddingModel)` implementing `EmbeddingGenerator`, and a Spring `@Bean EmbeddingGenerator embeddingGenerator(EmbeddingModel)` in `EmbeddingGeneratorConfig` — Tasks 6 and 8 wire against the `EmbeddingGenerator` port, not this class directly.

- [ ] **Step 1: Add Spring AI BOM and Google GenAI starter to `apps/backend/pom.xml`**

Add a `<dependencyManagement>` block (new, goes right after `<properties>`, before `<dependencies>`) and one new dependency:

```xml
  <properties>
    <java.version>25</java.version>
    <jooq.version>3.19.11</jooq.version>
    <testcontainers.version>1.20.4</testcontainers.version>
    <spring-ai.version>1.0.0</spring-ai.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-bom</artifactId>
        <version>${spring-ai.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
```

Add inside `<dependencies>`, alongside the existing entries:

```xml
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-starter-model-google-genai</artifactId>
    </dependency>
```

If `spring-ai-starter-model-google-genai` isn't the exact artifact id resolvable for `spring-ai.version` 1.0.0 (Spring AI's Gemini starter naming has shifted across releases), run `mvn -q dependency:tree -Dincludes=org.springframework.ai` after adding the BOM alone to find the actual available Gemini/Google-GenAI starter artifact id, and use that name instead — the BOM import is what matters; the exact starter artifact id is a one-line fix if it's changed.

- [ ] **Step 2: Add Spring AI config to `application.yml`**

```yaml
spring:
  application:
    name: sheltr-backend
  datasource:
    url: jdbc:postgresql://localhost:5432/sheltr
    username: sheltr
    password: sheltr
  flyway:
    enabled: true
  ai:
    google:
      genai:
        api-key: ${GEMINI_API_KEY}
        chat:
          options:
            model: gemini-2.5-flash
        embedding:
          options:
            model: text-embedding-004

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,info

sheltr:
  search:
    relevance-threshold: 0.75
```

- [ ] **Step 3: Write the failing adapter test**

```java
package com.sheltr.knowledge.infrastructure;

import com.sheltr.knowledge.domain.Embedding;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.GoogleGenAiEmbeddingModel;
import org.springframework.ai.google.genai.GoogleGenAiEmbeddingOptions;
import org.springframework.ai.google.genai.api.GoogleGenAiApi;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiEmbeddingGeneratorAdapterTest {

    @Test
    void embedsRealTextViaGemini() {
        GoogleGenAiApi api = new GoogleGenAiApi(System.getenv("GEMINI_API_KEY"));
        EmbeddingModel embeddingModel = new GoogleGenAiEmbeddingModel(api,
                GoogleGenAiEmbeddingOptions.builder().model("text-embedding-004").build());
        GeminiEmbeddingGeneratorAdapter adapter = new GeminiEmbeddingGeneratorAdapter(embeddingModel);

        Embedding embedding = adapter.embed("Boil water for one minute to make it safe to drink.");

        assertThat(embedding.values()).hasSize(768);
    }
}
```

If `GoogleGenAiEmbeddingModel`/`GoogleGenAiEmbeddingOptions`/`GoogleGenAiApi` aren't the exact class names shipped by the resolved starter version, check the starter's actual package (`mvn -q dependency:tree` plus browsing the downloaded jar's classes, or the Spring AI reference docs for the version pinned in `spring-ai.version`) and adjust the import/class names — the behavior under test (call real Gemini, get a 768-dim vector back) doesn't change.

- [ ] **Step 4: Run to verify it fails**

Run: `cd apps/backend && GEMINI_API_KEY=$GEMINI_API_KEY mvn -q test -Dtest=GeminiEmbeddingGeneratorAdapterTest`
Expected: FAIL — `GeminiEmbeddingGeneratorAdapter` doesn't exist yet.

- [ ] **Step 5: Implement `GeminiEmbeddingGeneratorAdapter`**

```java
package com.sheltr.knowledge.infrastructure;

import com.sheltr.knowledge.domain.Embedding;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import org.springframework.ai.embedding.EmbeddingModel;

public class GeminiEmbeddingGeneratorAdapter implements EmbeddingGenerator {

    private final EmbeddingModel embeddingModel;

    public GeminiEmbeddingGeneratorAdapter(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public Embedding embed(String text) {
        float[] vector = embeddingModel.embed(text);
        return new Embedding(vector);
    }
}
```

- [ ] **Step 6: Implement `EmbeddingGeneratorConfig`**

```java
package com.sheltr.knowledge.config;

import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.knowledge.infrastructure.GeminiEmbeddingGeneratorAdapter;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingGeneratorConfig {

    @Bean
    public EmbeddingGenerator embeddingGenerator(EmbeddingModel embeddingModel) {
        return new GeminiEmbeddingGeneratorAdapter(embeddingModel);
    }
}
```

- [ ] **Step 7: Run to verify it passes**

Run: `cd apps/backend && GEMINI_API_KEY=$GEMINI_API_KEY mvn -q test -Dtest=GeminiEmbeddingGeneratorAdapterTest`
Expected: `BUILD SUCCESS`, 1/1 passing (real Gemini call).

- [ ] **Step 8: Run the full suite to confirm nothing else broke**

Run: `cd apps/backend && GEMINI_API_KEY=$GEMINI_API_KEY mvn -q test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 9: Commit**

```bash
git add apps/backend/pom.xml apps/backend/src/main/resources/application.yml apps/backend/src/main/java/com/sheltr/knowledge/infrastructure/GeminiEmbeddingGeneratorAdapter.java apps/backend/src/main/java/com/sheltr/knowledge/config/EmbeddingGeneratorConfig.java apps/backend/src/test/java/com/sheltr/knowledge/infrastructure/GeminiEmbeddingGeneratorAdapterTest.java
git commit -m "feat(knowledge): add Spring AI Gemini embedding adapter and config"
```

---

### Task 5: Seed data Flyway migration

**Files:**
- Create: `apps/backend/src/main/resources/db/migration/V5__seed_knowledge_documents.sql`
- Test: `apps/backend/src/test/java/com/sheltr/knowledge/infrastructure/SeedDataMigrationTest.java`

**Interfaces:**
- Produces: ~34 rows in `knowledge.chunks` (embedding `NULL`), spread across 8 `DisasterType` values, each row's `document_id` referencing a row this same migration inserts into `knowledge.documents`. Task 6's backfill service and Task 8's retrieval adapter operate on these rows.

- [ ] **Step 1: Write the failing test asserting seed data shape**

```java
package com.sheltr.knowledge.infrastructure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SeedDataMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void seedsChunksAcrossAllEightDisasterTypes() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {

            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT count(*) FROM knowledge.chunks")) {
                rs.next();
                assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(30);
            }

            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT count(*) FROM knowledge.chunks WHERE embedding IS NULL")) {
                rs.next();
                assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(30);
            }

            Set<String> disasterTypesCovered = new HashSet<>();
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT DISTINCT unnest(disaster_types) FROM knowledge.documents")) {
                while (rs.next()) {
                    disasterTypesCovered.add(rs.getString(1));
                }
            }
            assertThat(disasterTypesCovered).containsExactlyInAnyOrder(
                    "EARTHQUAKE", "FLOOD", "HURRICANE", "TORNADO", "TSUNAMI", "WILDFIRE",
                    "EXTREME_HEAT", "WINTER_STORM");
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd apps/backend && mvn -q test -Dtest=SeedDataMigrationTest`
Expected: FAIL — `knowledge.chunks` has 0 rows (V5 doesn't exist yet).

- [ ] **Step 3: Write `V5__seed_knowledge_documents.sql`**

One document per disaster type (8 documents), 4-5 chunks each (34 chunks total). Real, short, public-domain-style disaster guidance text:

```sql
-- Earthquake
INSERT INTO knowledge.documents (id, title, source_agency, source_uri, disaster_types, published_date, version, status, checksum) VALUES
  ('a0000000-0000-0000-0000-000000000001', 'Earthquake Safety Guidance', 'FEMA', 'https://www.fema.gov/earthquakes', ARRAY['EARTHQUAKE'], '2023-01-01', 1, 'ACTIVE', 'seed-eq-1');
INSERT INTO knowledge.chunks (id, document_id, content, chunk_index, section_heading, checksum) VALUES
  ('b0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'Drop, Cover, and Hold On: drop to the ground, take cover under a sturdy table or desk, and hold on until the shaking stops.', 0, 'During an Earthquake', 'seed-eq-c1'),
  ('b0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001', 'If you are indoors, stay there. Do not run outside during shaking, as falling debris near buildings is a leading cause of injury.', 1, 'During an Earthquake', 'seed-eq-c2'),
  ('b0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001', 'After the shaking stops, check yourself and others for injuries. Expect aftershocks and be ready to drop, cover, and hold on again.', 2, 'After an Earthquake', 'seed-eq-c3'),
  ('b0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000001', 'Check gas, water, and electric lines for damage. If you smell gas, shut off the main valve, open windows, and leave immediately.', 3, 'After an Earthquake', 'seed-eq-c4');

-- Flood
INSERT INTO knowledge.documents (id, title, source_agency, source_uri, disaster_types, published_date, version, status, checksum) VALUES
  ('a0000000-0000-0000-0000-000000000002', 'Flood Safety and Water Purification', 'American Red Cross', 'https://www.redcross.org/floods', ARRAY['FLOOD'], '2023-01-01', 1, 'ACTIVE', 'seed-fl-1');
INSERT INTO knowledge.chunks (id, document_id, content, chunk_index, section_heading, checksum) VALUES
  ('b0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000002', 'Never drive or walk into flood waters. Just six inches of moving water can knock over an adult, and one foot can sweep away a vehicle.', 0, 'Flood Safety', 'seed-fl-c1'),
  ('b0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000002', 'If tap water is contaminated after a flood, boil it at a rolling boil for one full minute to make it safe to drink.', 1, 'Water Purification', 'seed-fl-c2'),
  ('b0000000-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000002', 'If you cannot boil water, disinfect it using unscented household liquid bleach: add 1/8 teaspoon per gallon of clear water and let stand 30 minutes.', 2, 'Water Purification', 'seed-fl-c3'),
  ('b0000000-0000-0000-0000-000000000008', 'a0000000-0000-0000-0000-000000000002', 'Boiled or chemically treated flood water that is cloudy should be filtered through clean cloth or allowed to settle before treatment.', 3, 'Water Purification', 'seed-fl-c4');

-- Hurricane
INSERT INTO knowledge.documents (id, title, source_agency, source_uri, disaster_types, published_date, version, status, checksum) VALUES
  ('a0000000-0000-0000-0000-000000000003', 'Hurricane Preparedness', 'FEMA', 'https://www.fema.gov/hurricanes', ARRAY['HURRICANE'], '2023-01-01', 1, 'ACTIVE', 'seed-hu-1');
INSERT INTO knowledge.chunks (id, document_id, content, chunk_index, section_heading, checksum) VALUES
  ('b0000000-0000-0000-0000-000000000009', 'a0000000-0000-0000-0000-000000000003', 'Before hurricane season, know your evacuation zone and route, and keep a disaster supply kit with at least three days of food and water.', 0, 'Preparedness', 'seed-hu-c1'),
  ('b0000000-0000-0000-0000-000000000010', 'a0000000-0000-0000-0000-000000000003', 'If a hurricane warning is issued for your area, evacuate immediately if told to do so by local officials.', 1, 'Evacuation', 'seed-hu-c2'),
  ('b0000000-0000-0000-0000-000000000011', 'a0000000-0000-0000-0000-000000000003', 'Board up windows, secure outdoor furniture, and turn your refrigerator to the coldest setting in case power is lost.', 2, 'Preparedness', 'seed-hu-c3'),
  ('b0000000-0000-0000-0000-000000000012', 'a0000000-0000-0000-0000-000000000003', 'After the storm, avoid downed power lines and standing water, which may be electrically charged.', 3, 'After a Hurricane', 'seed-hu-c4');

-- Tornado
INSERT INTO knowledge.documents (id, title, source_agency, source_uri, disaster_types, published_date, version, status, checksum) VALUES
  ('a0000000-0000-0000-0000-000000000004', 'Tornado Safety', 'American Red Cross', 'https://www.redcross.org/tornadoes', ARRAY['TORNADO'], '2023-01-01', 1, 'ACTIVE', 'seed-to-1');
INSERT INTO knowledge.chunks (id, document_id, content, chunk_index, section_heading, checksum) VALUES
  ('b0000000-0000-0000-0000-000000000013', 'a0000000-0000-0000-0000-000000000004', 'Go to a basement or an interior room on the lowest floor, away from windows, corners, and doors.', 0, 'During a Tornado', 'seed-to-c1'),
  ('b0000000-0000-0000-0000-000000000014', 'a0000000-0000-0000-0000-000000000004', 'If you are in a vehicle or mobile home, get out and go to a sturdy building or lie flat in a low, protected area.', 1, 'During a Tornado', 'seed-to-c2'),
  ('b0000000-0000-0000-0000-000000000015', 'a0000000-0000-0000-0000-000000000004', 'Cover your head and neck with your arms, and use a mattress, blanket, or helmet for extra protection if available.', 2, 'During a Tornado', 'seed-to-c3'),
  ('b0000000-0000-0000-0000-000000000016', 'a0000000-0000-0000-0000-000000000004', 'A tornado watch means conditions are favorable for a tornado; a tornado warning means one has been spotted and you must take shelter now.', 3, 'Watch vs Warning', 'seed-to-c4');

-- Tsunami
INSERT INTO knowledge.documents (id, title, source_agency, source_uri, disaster_types, published_date, version, status, checksum) VALUES
  ('a0000000-0000-0000-0000-000000000005', 'Tsunami Safety', 'FEMA', 'https://www.fema.gov/tsunamis', ARRAY['TSUNAMI'], '2023-01-01', 1, 'ACTIVE', 'seed-ts-1');
INSERT INTO knowledge.chunks (id, document_id, content, chunk_index, section_heading, checksum) VALUES
  ('b0000000-0000-0000-0000-000000000017', 'a0000000-0000-0000-0000-000000000005', 'If you feel a strong earthquake near the coast, move immediately to higher ground; do not wait for an official tsunami warning.', 0, 'Tsunami Warning Signs', 'seed-ts-c1'),
  ('b0000000-0000-0000-0000-000000000018', 'a0000000-0000-0000-0000-000000000005', 'A sudden, unusual recession of ocean water exposing the sea floor is a natural warning sign of an approaching tsunami.', 1, 'Tsunami Warning Signs', 'seed-ts-c2'),
  ('b0000000-0000-0000-0000-000000000019', 'a0000000-0000-0000-0000-000000000005', 'Move inland and to higher ground as quickly as possible; a tsunami can arrive within minutes of a nearby earthquake.', 2, 'During a Tsunami', 'seed-ts-c3'),
  ('b0000000-0000-0000-0000-000000000020', 'a0000000-0000-0000-0000-000000000005', 'Do not return to coastal areas until local officials say it is safe; tsunamis often arrive in multiple waves over several hours.', 3, 'After a Tsunami', 'seed-ts-c4');

-- Wildfire
INSERT INTO knowledge.documents (id, title, source_agency, source_uri, disaster_types, published_date, version, status, checksum) VALUES
  ('a0000000-0000-0000-0000-000000000006', 'Wildfire Preparedness and Evacuation', 'American Red Cross', 'https://www.redcross.org/wildfires', ARRAY['WILDFIRE'], '2023-01-01', 1, 'ACTIVE', 'seed-wf-1');
INSERT INTO knowledge.chunks (id, document_id, content, chunk_index, section_heading, checksum) VALUES
  ('b0000000-0000-0000-0000-000000000021', 'a0000000-0000-0000-0000-000000000006', 'If told to evacuate, leave immediately. Wear long sleeves and pants, and keep car windows and vents closed to keep smoke and embers out.', 0, 'Evacuation', 'seed-wf-c1'),
  ('b0000000-0000-0000-0000-000000000022', 'a0000000-0000-0000-0000-000000000006', 'Before wildfire season, clear dry leaves, brush, and flammable materials from within 30 feet of your home.', 1, 'Preparedness', 'seed-wf-c2'),
  ('b0000000-0000-0000-0000-000000000023', 'a0000000-0000-0000-0000-000000000006', 'If trapped by wildfire, call 911 and give your location, and look for a body of water, cleared area, or structure to shelter in.', 2, 'During a Wildfire', 'seed-wf-c3'),
  ('b0000000-0000-0000-0000-000000000024', 'a0000000-0000-0000-0000-000000000006', 'Wear an N95 mask if smoke is heavy, and keep windows and doors closed to limit smoke exposure indoors.', 3, 'Smoke Safety', 'seed-wf-c4');

-- Extreme Heat
INSERT INTO knowledge.documents (id, title, source_agency, source_uri, disaster_types, published_date, version, status, checksum) VALUES
  ('a0000000-0000-0000-0000-000000000007', 'Extreme Heat Safety', 'FEMA', 'https://www.fema.gov/extreme-heat', ARRAY['EXTREME_HEAT'], '2023-01-01', 1, 'ACTIVE', 'seed-eh-1');
INSERT INTO knowledge.chunks (id, document_id, content, chunk_index, section_heading, checksum) VALUES
  ('b0000000-0000-0000-0000-000000000025', 'a0000000-0000-0000-0000-000000000007', 'Drink plenty of water even if you do not feel thirsty, and avoid alcohol and caffeine, which can worsen dehydration.', 0, 'Heat Safety', 'seed-eh-c1'),
  ('b0000000-0000-0000-0000-000000000026', 'a0000000-0000-0000-0000-000000000007', 'Signs of heat stroke include a body temperature above 103F, hot red skin, and confusion; call 911 immediately if you suspect heat stroke.', 1, 'Heat Illness', 'seed-eh-c2'),
  ('b0000000-0000-0000-0000-000000000027', 'a0000000-0000-0000-0000-000000000007', 'Never leave children or pets in a parked vehicle; interior temperatures can rise to dangerous levels within minutes.', 2, 'Heat Safety', 'seed-eh-c3'),
  ('b0000000-0000-0000-0000-000000000028', 'a0000000-0000-0000-0000-000000000007', 'Check on elderly neighbors and relatives during heat waves, as older adults are at higher risk of heat-related illness.', 3, 'Vulnerable Populations', 'seed-eh-c4');

-- Winter Storm
INSERT INTO knowledge.documents (id, title, source_agency, source_uri, disaster_types, published_date, version, status, checksum) VALUES
  ('a0000000-0000-0000-0000-000000000008', 'Winter Storm Safety', 'American Red Cross', 'https://www.redcross.org/winter-storms', ARRAY['WINTER_STORM'], '2023-01-01', 1, 'ACTIVE', 'seed-ws-1');
INSERT INTO knowledge.chunks (id, document_id, content, chunk_index, section_heading, checksum) VALUES
  ('b0000000-0000-0000-0000-000000000029', 'a0000000-0000-0000-0000-000000000008', 'Never use a generator, grill, or camp stove indoors; they produce carbon monoxide, which can be fatal in enclosed spaces.', 0, 'Power Outage Safety', 'seed-ws-c1'),
  ('b0000000-0000-0000-0000-000000000030', 'a0000000-0000-0000-0000-000000000008', 'Insulin that has been exposed to freezing temperatures should not be used; store it between 36F and 46F, and use a cooler with ice packs if power is out.', 1, 'Medication Storage', 'seed-ws-c2'),
  ('b0000000-0000-0000-0000-000000000031', 'a0000000-0000-0000-0000-000000000008', 'Dress in layers and keep dry; wet clothing loses insulating value and increases the risk of hypothermia.', 2, 'Cold Weather Safety', 'seed-ws-c3'),
  ('b0000000-0000-0000-0000-000000000032', 'a0000000-0000-0000-0000-000000000008', 'Watch for signs of frostbite: numbness, white or grayish-yellow skin, and firm or waxy skin, especially on fingers, toes, ears, and nose.', 3, 'Frostbite', 'seed-ws-c4');
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd apps/backend && mvn -q test -Dtest=SeedDataMigrationTest`
Expected: `BUILD SUCCESS`, 1/1 passing.

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/main/resources/db/migration/V5__seed_knowledge_documents.sql apps/backend/src/test/java/com/sheltr/knowledge/infrastructure/SeedDataMigrationTest.java
git commit -m "feat(knowledge): add seed data migration covering all 8 disaster types"
```

---

### Task 6: ChunkEmbeddingBackfillService and `make seed-embeddings`

**Files:**
- Create: `apps/backend/src/main/java/com/sheltr/knowledge/application/ChunkEmbeddingBackfillService.java`
- Create: `apps/backend/src/main/java/com/sheltr/knowledge/config/SeedEmbeddingsRunner.java`
- Modify: `Makefile` (add `seed-embeddings` target)
- Test: `apps/backend/src/test/java/com/sheltr/knowledge/application/ChunkEmbeddingBackfillServiceTest.java`

**Interfaces:**
- Consumes: `ChunkRepository`, `EmbeddingGenerator`, `Chunk` from Tasks 1-2, 4.
- Produces: `ChunkEmbeddingBackfillService(ChunkRepository, EmbeddingGenerator)` with method `int backfillAll()` returning the count of chunks embedded — Task 14's end-to-end test relies on running this (via `make seed-embeddings`) before asserting retrieval works.

- [ ] **Step 1: Write the failing unit test (fake ports, no Spring/DB/Gemini)**

```java
package com.sheltr.knowledge.application;

import com.sheltr.knowledge.domain.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkEmbeddingBackfillServiceTest {

    @Test
    void embedsEveryPendingChunkAndSavesIt() {
        Chunk pending = new Chunk(
                new ChunkId(java.util.UUID.randomUUID()), new DocumentId(java.util.UUID.randomUUID()),
                new ChunkContent("Boil water for one minute."), null, null, 0,
                new ChunkMetadata("Water Safety", java.util.Set.of(DisasterType.FLOOD)),
                new Checksum("abc"));

        FakeChunkRepository chunkRepository = new FakeChunkRepository(List.of(pending));
        FakeEmbeddingGenerator embeddingGenerator = new FakeEmbeddingGenerator();
        ChunkEmbeddingBackfillService service = new ChunkEmbeddingBackfillService(chunkRepository, embeddingGenerator);

        int count = service.backfillAll();

        assertThat(count).isEqualTo(1);
        assertThat(chunkRepository.saved).hasSize(1);
        assertThat(chunkRepository.saved.get(0).embedding()).isPresent();
    }

    private static class FakeChunkRepository implements ChunkRepository {
        private final List<Chunk> pending;
        final List<Chunk> saved = new ArrayList<>();

        FakeChunkRepository(List<Chunk> pending) { this.pending = pending; }

        @Override
        public Optional<Chunk> findById(ChunkId id) { return Optional.empty(); }

        @Override
        public List<Chunk> findPendingEmbeddings() { return pending; }

        @Override
        public void save(Chunk chunk) { saved.add(chunk); }
    }

    private static class FakeEmbeddingGenerator implements EmbeddingGenerator {
        @Override
        public Embedding embed(String text) {
            float[] values = new float[768];
            values[0] = 1.0f;
            return new Embedding(values);
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd apps/backend && mvn -q test -Dtest=ChunkEmbeddingBackfillServiceTest`
Expected: FAIL — `ChunkEmbeddingBackfillService` doesn't exist yet.

- [ ] **Step 3: Implement `ChunkEmbeddingBackfillService`**

```java
package com.sheltr.knowledge.application;

import com.sheltr.knowledge.domain.Chunk;
import com.sheltr.knowledge.domain.ChunkRepository;
import com.sheltr.knowledge.domain.EmbeddingGenerator;

import java.util.List;

public class ChunkEmbeddingBackfillService {

    private final ChunkRepository chunkRepository;
    private final EmbeddingGenerator embeddingGenerator;

    public ChunkEmbeddingBackfillService(ChunkRepository chunkRepository, EmbeddingGenerator embeddingGenerator) {
        this.chunkRepository = chunkRepository;
        this.embeddingGenerator = embeddingGenerator;
    }

    public int backfillAll() {
        List<Chunk> pending = chunkRepository.findPendingEmbeddings();
        for (Chunk chunk : pending) {
            var embedding = embeddingGenerator.embed(chunk.content().value());
            chunkRepository.save(chunk.withEmbedding(embedding));
        }
        return pending.size();
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd apps/backend && mvn -q test -Dtest=ChunkEmbeddingBackfillServiceTest`
Expected: `BUILD SUCCESS`, 1/1 passing.

- [ ] **Step 5: Implement `SeedEmbeddingsRunner`**

```java
package com.sheltr.knowledge.config;

import com.sheltr.knowledge.application.ChunkEmbeddingBackfillService;
import com.sheltr.knowledge.domain.ChunkRepository;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.knowledge.infrastructure.JooqChunkRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
public class SeedEmbeddingsRunner implements ApplicationRunner {

    private final DataSource dataSource;
    private final EmbeddingGenerator embeddingGenerator;

    public SeedEmbeddingsRunner(DataSource dataSource, EmbeddingGenerator embeddingGenerator) {
        this.dataSource = dataSource;
        this.embeddingGenerator = embeddingGenerator;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.getNonOptionArgs().contains("seed-embeddings")) {
            return;
        }
        ChunkRepository chunkRepository = new JooqChunkRepository(dataSource);
        ChunkEmbeddingBackfillService service = new ChunkEmbeddingBackfillService(chunkRepository, embeddingGenerator);
        int count = service.backfillAll();
        System.out.println("Backfilled embeddings for " + count + " chunk(s).");
        System.exit(0);
    }
}
```

- [ ] **Step 6: Add `seed-embeddings` target to `Makefile`**

```makefile
seed-embeddings:
	cd apps/backend && mvn -q spring-boot:run -Dspring-boot.run.arguments=seed-embeddings
```

Add this target after `migrate:` and add `seed-embeddings` to the `.PHONY` line.

- [ ] **Step 7: Verify end-to-end against a real (Podman-started) Postgres**

```bash
make up
make migrate
GEMINI_API_KEY=$GEMINI_API_KEY make seed-embeddings
```
Expected: prints `Backfilled embeddings for 34 chunk(s).` (or however many `V5` actually inserted), process exits 0.

Then verify no chunks remain pending:
```bash
podman exec sheltr-postgres psql -U sheltr -d sheltr -c "SELECT count(*) FROM knowledge.chunks WHERE embedding IS NULL;"
```
Expected: `0`.

```bash
make down
```

- [ ] **Step 8: Commit**

```bash
git add apps/backend/src/main/java/com/sheltr/knowledge/application/ChunkEmbeddingBackfillService.java apps/backend/src/main/java/com/sheltr/knowledge/config/SeedEmbeddingsRunner.java apps/backend/src/test/java/com/sheltr/knowledge/application/ChunkEmbeddingBackfillServiceTest.java Makefile
git commit -m "feat(knowledge): add ChunkEmbeddingBackfillService and make seed-embeddings"
```

---

### Task 7: Search domain — SearchQuery, RankedResult, Retriever port

**Files:**
- Create: `apps/backend/src/main/java/com/sheltr/search/domain/SearchQuery.java`
- Create: `apps/backend/src/main/java/com/sheltr/search/domain/RankedResult.java`
- Create: `apps/backend/src/main/java/com/sheltr/search/domain/Retriever.java`
- Test: `apps/backend/src/test/java/com/sheltr/search/domain/RankedResultTest.java`

**Interfaces:**
- Consumes: `ChunkId`, `DocumentId` from Task 1 (`com.sheltr.knowledge.domain`) — cross-context reference, matches the existing Phase 1 domain model's pattern of `AnswerGenerator` referencing `search.domain` types.
- Produces: `SearchQuery(String text)`, `RankedResult(ChunkId chunkId, DocumentId documentId, String content, double score)`, `Retriever` interface with `List<RankedResult> retrieve(SearchQuery query, int topK)`. Task 8 implements `Retriever`; Task 10/12 consume `RankedResult`.

- [ ] **Step 1: Write the failing test**

```java
package com.sheltr.search.domain;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RankedResultTest {

    @Test
    void createsRankedResultWithScore() {
        RankedResult result = new RankedResult(
                new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()), "Boil water for one minute.", 0.87);

        assertThat(result.score()).isEqualTo(0.87);
    }

    @Test
    void rejectsScoreOutsideZeroToOne() {
        assertThatThrownBy(() -> new RankedResult(
                new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()), "text", 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd apps/backend && mvn -q test -Dtest=RankedResultTest`
Expected: FAIL — classes don't exist yet.

- [ ] **Step 3: Implement**

```java
package com.sheltr.search.domain;

public record SearchQuery(String text) {
    public SearchQuery {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("SearchQuery text must not be blank");
        }
    }
}
```

```java
package com.sheltr.search.domain;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;

public record RankedResult(ChunkId chunkId, DocumentId documentId, String content, double score) {
    public RankedResult {
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be between 0.0 and 1.0, got " + score);
        }
    }
}
```

```java
package com.sheltr.search.domain;

import java.util.List;

public interface Retriever {
    List<RankedResult> retrieve(SearchQuery query, int topK);
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd apps/backend && mvn -q test -Dtest=RankedResultTest`
Expected: `BUILD SUCCESS`, 2/2 passing.

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/main/java/com/sheltr/search/domain/ apps/backend/src/test/java/com/sheltr/search/domain/
git commit -m "feat(search): add SearchQuery, RankedResult, Retriever port"
```

---

### Task 8: PgvectorSemanticSearchAdapter

**Files:**
- Create: `apps/backend/src/main/java/com/sheltr/search/infrastructure/PgvectorSemanticSearchAdapter.java`
- Create: `apps/backend/src/main/java/com/sheltr/search/config/RetrieverConfig.java`
- Test: `apps/backend/src/test/java/com/sheltr/search/infrastructure/PgvectorSemanticSearchAdapterIntegrationTest.java`

**Interfaces:**
- Consumes: `Retriever`, `SearchQuery`, `RankedResult` from Task 7; `EmbeddingGenerator` from Task 2; `ChunkId`, `DocumentId` from Task 1.
- Produces: `PgvectorSemanticSearchAdapter(DataSource, EmbeddingGenerator)` implementing `Retriever`, and a `@Bean Retriever retriever(...)` in `RetrieverConfig`. Task 10/12 consume `Retriever`, not this class directly.

Cosine similarity via pgvector's `<=>` operator returns *distance* (0 = identical, larger = more different) for the default `vector_cosine_ops` index; this adapter converts to a similarity score in `[0, 1]` via `1 - distance` before constructing `RankedResult` (whose constructor validates the `[0, 1]` range from Task 7).

- [ ] **Step 1: Write the failing integration test (gated on GEMINI_API_KEY since it needs a real query embedding)**

```java
package com.sheltr.search.infrastructure;

import com.sheltr.knowledge.domain.Embedding;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class PgvectorSemanticSearchAdapterIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void findsMostSimilarChunkByRealEmbedding() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        var api = new org.springframework.ai.google.genai.api.GoogleGenAiApi(System.getenv("GEMINI_API_KEY"));
        var embeddingModel = new org.springframework.ai.google.genai.GoogleGenAiEmbeddingModel(api,
                org.springframework.ai.google.genai.GoogleGenAiEmbeddingOptions.builder().model("text-embedding-004").build());
        EmbeddingGenerator embeddingGenerator = new com.sheltr.knowledge.infrastructure.GeminiEmbeddingGeneratorAdapter(embeddingModel);

        // Manually embed and store the two seeded chunks this test cares about.
        embedAndStore(dataSource, embeddingGenerator, "b0000000-0000-0000-0000-000000000006");
        embedAndStore(dataSource, embeddingGenerator, "b0000000-0000-0000-0000-000000000029");

        PgvectorSemanticSearchAdapter adapter = new PgvectorSemanticSearchAdapter(dataSource, embeddingGenerator);

        List<RankedResult> results = adapter.retrieve(new SearchQuery("Is it safe to drink flood water?"), 5);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).chunkId().value())
                .isEqualTo(UUID.fromString("b0000000-0000-0000-0000-000000000006"));
    }

    private static void embedAndStore(DataSource dataSource, EmbeddingGenerator generator, String chunkId) throws Exception {
        String content;
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("SELECT content FROM knowledge.chunks WHERE id = ?")) {
            stmt.setObject(1, UUID.fromString(chunkId));
            try (var rs = stmt.executeQuery()) {
                rs.next();
                content = rs.getString(1);
            }
        }
        Embedding embedding = generator.embed(content);
        float[] values = embedding.values();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        sb.append(']');
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("UPDATE knowledge.chunks SET embedding = ?::vector WHERE id = ?")) {
            stmt.setString(1, sb.toString());
            stmt.setObject(2, UUID.fromString(chunkId));
            stmt.executeUpdate();
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd apps/backend && GEMINI_API_KEY=$GEMINI_API_KEY mvn -q test -Dtest=PgvectorSemanticSearchAdapterIntegrationTest`
Expected: FAIL — `PgvectorSemanticSearchAdapter` doesn't exist yet.

- [ ] **Step 3: Implement `PgvectorSemanticSearchAdapter`**

```java
package com.sheltr.search.infrastructure;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.knowledge.domain.Embedding;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PgvectorSemanticSearchAdapter implements Retriever {

    private final DSLContext dsl;
    private final EmbeddingGenerator embeddingGenerator;

    public PgvectorSemanticSearchAdapter(DataSource dataSource, EmbeddingGenerator embeddingGenerator) {
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
        this.embeddingGenerator = embeddingGenerator;
    }

    @Override
    public List<RankedResult> retrieve(SearchQuery query, int topK) {
        Embedding queryEmbedding = embeddingGenerator.embed(query.text());
        String vectorLiteral = toVectorLiteral(queryEmbedding);

        var rows = dsl.fetch(
                "SELECT id, document_id, content, embedding <=> ?::vector AS distance "
                        + "FROM knowledge.chunks WHERE embedding IS NOT NULL "
                        + "ORDER BY embedding <=> ?::vector ASC LIMIT ?",
                vectorLiteral, vectorLiteral, topK);

        List<RankedResult> results = new ArrayList<>();
        for (Record row : rows) {
            double distance = row.get("distance", Double.class);
            double similarity = clampToUnitRange(1.0 - distance);
            results.add(new RankedResult(
                    new ChunkId((UUID) row.get("id")),
                    new DocumentId((UUID) row.get("document_id")),
                    row.get("content", String.class),
                    similarity));
        }
        return results;
    }

    private static double clampToUnitRange(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String toVectorLiteral(Embedding embedding) {
        float[] values = embedding.values();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        return sb.append(']').toString();
    }
}
```

- [ ] **Step 4: Implement `RetrieverConfig`**

```java
package com.sheltr.search.config;

import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.infrastructure.PgvectorSemanticSearchAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class RetrieverConfig {

    @Bean
    public Retriever retriever(DataSource dataSource, EmbeddingGenerator embeddingGenerator) {
        return new PgvectorSemanticSearchAdapter(dataSource, embeddingGenerator);
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd apps/backend && GEMINI_API_KEY=$GEMINI_API_KEY mvn -q test -Dtest=PgvectorSemanticSearchAdapterIntegrationTest`
Expected: `BUILD SUCCESS`, 1/1 passing.

- [ ] **Step 6: Commit**

```bash
git add apps/backend/src/main/java/com/sheltr/search/infrastructure/ apps/backend/src/main/java/com/sheltr/search/config/ apps/backend/src/test/java/com/sheltr/search/infrastructure/
git commit -m "feat(search): add pgvector semantic search adapter"
```

---

### Task 9: AI domain — Conversation, Message, Citation, AnswerConfidence, RelevanceGate, ports

**Files:**
- Create: `apps/backend/src/main/java/com/sheltr/ai/domain/ConversationId.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/domain/MessageId.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/domain/MessageRole.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/domain/MessageContent.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/domain/ConfidenceLevel.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/domain/AnswerConfidence.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/domain/Citation.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/domain/Message.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/domain/Conversation.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/domain/AnswerOutcome.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/domain/RelevanceGate.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/domain/AnswerGenerator.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/domain/ConversationRepository.java`
- Test: `apps/backend/src/test/java/com/sheltr/ai/domain/RelevanceGateTest.java`
- Test: `apps/backend/src/test/java/com/sheltr/ai/domain/AnswerConfidenceTest.java`
- Test: `apps/backend/src/test/java/com/sheltr/ai/domain/ConversationTest.java`

**Interfaces:**
- Consumes: `ChunkId` (Task 1), `SearchQuery`/`RankedResult` (Task 7).
- Produces: `ConversationId(UUID)`, `MessageId(UUID)`, `MessageRole` enum, `MessageContent(String)`, `ConfidenceLevel` enum (`LOW, MEDIUM, HIGH`), `AnswerConfidence(double score, ConfidenceLevel level)` with static factory `AnswerConfidence.fromSimilarity(double topScore)`, `Citation(DocumentId documentId, ChunkId chunkId, String sourceTitle, String sourceUrl, Integer pageNumber)`, `Message` (fields below), `Conversation` (aggregate, fields below), `AnswerOutcome` sealed interface (`AnswerGenerated(Message message)`, `NoAnswerAvailable()`, `AnswerGenerationFailed(String reason)`), `RelevanceGate.passesThreshold(List<RankedResult>, double threshold)` static method, `AnswerGenerator` port (`AnswerOutcome generate(SearchQuery query, List<RankedResult> results)` — only ever returns `AnswerGenerated`/`AnswerGenerationFailed`, never `NoAnswerAvailable`, since threshold gating happens upstream via `RelevanceGate` before this port is called), `ConversationRepository` port (`findById`, `save`). Task 10 implements `AnswerGenerator`; Task 11 implements `ConversationRepository`; Task 12 orchestrates all of it.

- [ ] **Step 1: Write the failing tests**

```java
package com.sheltr.ai.domain;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.search.domain.RankedResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RelevanceGateTest {

    @Test
    void passesWhenTopScoreAtOrAboveThreshold() {
        List<RankedResult> results = List.of(
                new RankedResult(new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()), "text", 0.80));

        assertThat(RelevanceGate.passesThreshold(results, 0.75)).isTrue();
    }

    @Test
    void failsWhenTopScoreBelowThreshold() {
        List<RankedResult> results = List.of(
                new RankedResult(new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()), "text", 0.60));

        assertThat(RelevanceGate.passesThreshold(results, 0.75)).isFalse();
    }

    @Test
    void failsWhenNoResults() {
        assertThat(RelevanceGate.passesThreshold(List.of(), 0.75)).isFalse();
    }
}
```

```java
package com.sheltr.ai.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerConfidenceTest {

    @Test
    void highWhenScoreAtOrAboveNinetyPercent() {
        assertThat(AnswerConfidence.fromSimilarity(0.95).level()).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    void mediumWhenScoreBetweenSeventyFiveAndNinety() {
        assertThat(AnswerConfidence.fromSimilarity(0.80).level()).isEqualTo(ConfidenceLevel.MEDIUM);
    }

    @Test
    void lowWhenScoreBelowSeventyFive() {
        // Unreachable via the current 0.75 RelevanceGate default in ordinary operation,
        // but defined for correctness and for a future lower threshold.
        assertThat(AnswerConfidence.fromSimilarity(0.50).level()).isEqualTo(ConfidenceLevel.LOW);
    }
}
```

```java
package com.sheltr.ai.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationTest {

    @Test
    void addMessageAppendsAndUpdatesLastActiveAt() {
        Conversation conversation = new Conversation(new ConversationId(UUID.randomUUID()), null);
        var before = conversation.lastActiveAt();

        Message message = new Message(
                new MessageId(UUID.randomUUID()), MessageRole.USER,
                new MessageContent("Is it safe to drink flood water?"), List.of(), null, List.of());
        conversation.addMessage(message);

        assertThat(conversation.messages()).containsExactly(message);
        assertThat(conversation.lastActiveAt()).isAfterOrEqualTo(before);
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd apps/backend && mvn -q test -Dtest=RelevanceGateTest,AnswerConfidenceTest,ConversationTest`
Expected: FAIL — classes don't exist yet.

- [ ] **Step 3: Implement the value objects and enums**

```java
package com.sheltr.ai.domain;

import java.util.UUID;

public record ConversationId(UUID value) {
    public ConversationId {
        if (value == null) throw new IllegalArgumentException("ConversationId value must not be null");
    }
}
```

```java
package com.sheltr.ai.domain;

import java.util.UUID;

public record MessageId(UUID value) {
    public MessageId {
        if (value == null) throw new IllegalArgumentException("MessageId value must not be null");
    }
}
```

```java
package com.sheltr.ai.domain;

public enum MessageRole {
    USER, ASSISTANT
}
```

```java
package com.sheltr.ai.domain;

public record MessageContent(String value) {
    public MessageContent {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MessageContent value must not be blank");
        }
    }
}
```

```java
package com.sheltr.ai.domain;

public enum ConfidenceLevel {
    LOW, MEDIUM, HIGH
}
```

```java
package com.sheltr.ai.domain;

public record AnswerConfidence(double score, ConfidenceLevel level) {
    public static AnswerConfidence fromSimilarity(double topScore) {
        ConfidenceLevel level = topScore >= 0.90 ? ConfidenceLevel.HIGH
                : topScore >= 0.75 ? ConfidenceLevel.MEDIUM
                : ConfidenceLevel.LOW;
        return new AnswerConfidence(topScore, level);
    }
}
```

```java
package com.sheltr.ai.domain;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;

public record Citation(DocumentId documentId, ChunkId chunkId, String sourceTitle, String sourceUrl, Integer pageNumber) {
}
```

```java
package com.sheltr.ai.domain;

import com.sheltr.knowledge.domain.ChunkId;

import java.util.List;

public record Message(MessageId id, MessageRole role, MessageContent content, List<Citation> citations,
                       AnswerConfidence confidence, List<ChunkId> retrievedChunkIds) {
    public Message {
        citations = List.copyOf(citations == null ? List.of() : citations);
        retrievedChunkIds = List.copyOf(retrievedChunkIds == null ? List.of() : retrievedChunkIds);
    }
}
```

```java
package com.sheltr.ai.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class Conversation {
    private final ConversationId id;
    private final String userRef;
    private final List<Message> messages = new ArrayList<>();
    private final Instant createdAt;
    private Instant lastActiveAt;

    public Conversation(ConversationId id, String userRef) {
        this.id = id;
        this.userRef = userRef;
        this.createdAt = Instant.now();
        this.lastActiveAt = this.createdAt;
    }

    public Conversation(ConversationId id, String userRef, List<Message> existingMessages,
                         Instant createdAt, Instant lastActiveAt) {
        this.id = id;
        this.userRef = userRef;
        this.messages.addAll(existingMessages);
        this.createdAt = createdAt;
        this.lastActiveAt = lastActiveAt;
    }

    public void addMessage(Message message) {
        messages.add(message);
        lastActiveAt = Instant.now();
    }

    public ConversationId id() { return id; }
    public Optional<String> userRef() { return Optional.ofNullable(userRef); }
    public List<Message> messages() { return List.copyOf(messages); }
    public Instant createdAt() { return createdAt; }
    public Instant lastActiveAt() { return lastActiveAt; }
}
```

```java
package com.sheltr.ai.domain;

public sealed interface AnswerOutcome permits AnswerGenerated, NoAnswerAvailable, AnswerGenerationFailed {
}
```

```java
package com.sheltr.ai.domain;

public record AnswerGenerated(Message message) implements AnswerOutcome {
}
```

```java
package com.sheltr.ai.domain;

public record NoAnswerAvailable() implements AnswerOutcome {
}
```

```java
package com.sheltr.ai.domain;

public record AnswerGenerationFailed(String reason) implements AnswerOutcome {
}
```

```java
package com.sheltr.ai.domain;

import com.sheltr.search.domain.RankedResult;

import java.util.List;

public final class RelevanceGate {

    private RelevanceGate() {
    }

    public static boolean passesThreshold(List<RankedResult> results, double threshold) {
        return results.stream().findFirst().map(top -> top.score() >= threshold).orElse(false);
    }
}
```

```java
package com.sheltr.ai.domain;

import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;

import java.util.List;

public interface AnswerGenerator {
    AnswerOutcome generate(SearchQuery query, List<RankedResult> results);
}
```

```java
package com.sheltr.ai.domain;

import java.util.Optional;

public interface ConversationRepository {
    Optional<Conversation> findById(ConversationId id);
    void save(Conversation conversation);
}
```

- [ ] **Step 4: Run to verify they pass**

Run: `cd apps/backend && mvn -q test -Dtest=RelevanceGateTest,AnswerConfidenceTest,ConversationTest`
Expected: `BUILD SUCCESS`, 7/7 passing.

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/main/java/com/sheltr/ai/domain/ apps/backend/src/test/java/com/sheltr/ai/domain/
git commit -m "feat(ai): add Conversation aggregate, Message, Citation, RelevanceGate, ports"
```

---

### Task 10: GeminiAnswerGeneratorAdapter

**Files:**
- Create: `apps/backend/src/main/java/com/sheltr/ai/infrastructure/GeminiAnswerGeneratorAdapter.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/config/AnswerGeneratorConfig.java`
- Test: `apps/backend/src/test/java/com/sheltr/ai/infrastructure/GeminiAnswerGeneratorAdapterTest.java`

**Interfaces:**
- Consumes: `AnswerGenerator`, `AnswerOutcome`, `AnswerGenerated`, `AnswerGenerationFailed`, `Message`, `MessageId`, `MessageRole`, `MessageContent`, `Citation`, `AnswerConfidence` (Task 9); `KnowledgeDocumentRepository`, `KnowledgeDocument` (Tasks 1-2); `SearchQuery`, `RankedResult` (Task 7).
- Produces: `GeminiAnswerGeneratorAdapter(ChatModel, KnowledgeDocumentRepository)` implementing `AnswerGenerator`, and a `@Bean AnswerGenerator answerGenerator(...)` in `AnswerGeneratorConfig`. Task 12 consumes `AnswerGenerator`.

Builds a citation-grounded prompt from the retrieved chunks, calls Gemini via Spring AI's `ChatModel`, and constructs an `AnswerGenerated` `Message` with one `Citation` per retrieved chunk (looking up each chunk's parent document via `KnowledgeDocumentRepository` for `sourceTitle`/`sourceUrl`) and an `AnswerConfidence` derived from the top result's score. Gemini call failures (any exception from `chatModel.call(...)`) are caught and mapped to `AnswerGenerationFailed`.

- [ ] **Step 1: Write the failing test (real Gemini call, gated)**

```java
package com.sheltr.ai.infrastructure;

import com.sheltr.ai.domain.*;
import com.sheltr.knowledge.domain.*;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.api.GoogleGenAiApi;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiAnswerGeneratorAdapterTest {

    @Test
    void generatesCitationBackedAnswerFromRetrievedChunks() {
        GoogleGenAiApi api = new GoogleGenAiApi(System.getenv("GEMINI_API_KEY"));
        ChatModel chatModel = new GoogleGenAiChatModel(api,
                GoogleGenAiChatOptions.builder().model("gemini-2.5-flash").build());

        DocumentId documentId = new DocumentId(UUID.randomUUID());
        KnowledgeDocument document = new KnowledgeDocument(
                documentId, "Flood Safety and Water Purification", "American Red Cross",
                "https://www.redcross.org/floods", Set.of(DisasterType.FLOOD),
                LocalDate.of(2023, 1, 1), new DocumentVersion(1), new Checksum("seed-fl-1"),
                null, DocumentStatus.ACTIVE);
        KnowledgeDocumentRepository documentRepository = id -> id.equals(documentId)
                ? Optional.of(document) : Optional.empty();

        GeminiAnswerGeneratorAdapter adapter = new GeminiAnswerGeneratorAdapter(chatModel, documentRepository);

        ChunkId chunkId = new ChunkId(UUID.randomUUID());
        RankedResult result = new RankedResult(chunkId, documentId,
                "If tap water is contaminated after a flood, boil it at a rolling boil for one full minute.", 0.88);

        AnswerOutcome outcome = adapter.generate(new SearchQuery("Is it safe to drink flood water?"), List.of(result));

        assertThat(outcome).isInstanceOf(AnswerGenerated.class);
        Message message = ((AnswerGenerated) outcome).message();
        assertThat(message.role()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(message.content().value()).isNotBlank();
        assertThat(message.citations()).hasSize(1);
        assertThat(message.citations().get(0).sourceTitle()).isEqualTo("Flood Safety and Water Purification");
        assertThat(message.confidence().level()).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(message.retrievedChunkIds()).containsExactly(chunkId);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd apps/backend && GEMINI_API_KEY=$GEMINI_API_KEY mvn -q test -Dtest=GeminiAnswerGeneratorAdapterTest`
Expected: FAIL — `GeminiAnswerGeneratorAdapter` doesn't exist yet.

- [ ] **Step 3: Implement `GeminiAnswerGeneratorAdapter`**

```java
package com.sheltr.ai.infrastructure;

import com.sheltr.ai.domain.*;
import com.sheltr.knowledge.domain.KnowledgeDocument;
import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class GeminiAnswerGeneratorAdapter implements AnswerGenerator {

    private final ChatModel chatModel;
    private final KnowledgeDocumentRepository documentRepository;

    public GeminiAnswerGeneratorAdapter(ChatModel chatModel, KnowledgeDocumentRepository documentRepository) {
        this.chatModel = chatModel;
        this.documentRepository = documentRepository;
    }

    @Override
    public AnswerOutcome generate(SearchQuery query, List<RankedResult> results) {
        String prompt = buildPrompt(query, results);
        String answerText;
        try {
            answerText = chatModel.call(prompt);
        } catch (Exception e) {
            return new AnswerGenerationFailed("Gemini call failed: " + e.getMessage());
        }

        List<Citation> citations = new ArrayList<>();
        for (RankedResult result : results) {
            KnowledgeDocument document = documentRepository.findById(result.documentId()).orElse(null);
            if (document != null) {
                citations.add(new Citation(result.documentId(), result.chunkId(),
                        document.title(), document.sourceUri(), null));
            }
        }

        double topScore = results.get(0).score();
        Message message = new Message(
                new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT, new MessageContent(answerText),
                citations, AnswerConfidence.fromSimilarity(topScore),
                results.stream().map(RankedResult::chunkId).toList());

        return new AnswerGenerated(message);
    }

    private static String buildPrompt(SearchQuery query, List<RankedResult> results) {
        StringBuilder context = new StringBuilder();
        for (RankedResult result : results) {
            context.append("- ").append(result.content()).append('\n');
        }
        return """
                You are a disaster-response assistant. Answer the citizen's question using ONLY the \
                context below. If the context does not contain enough information to answer, say so \
                clearly rather than guessing.

                Context:
                %s
                Question: %s
                """.formatted(context, query.text());
    }
}
```

- [ ] **Step 4: Implement `AnswerGeneratorConfig`**

```java
package com.sheltr.ai.config;

import com.sheltr.ai.domain.AnswerGenerator;
import com.sheltr.ai.infrastructure.GeminiAnswerGeneratorAdapter;
import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnswerGeneratorConfig {

    @Bean
    public AnswerGenerator answerGenerator(ChatModel chatModel, KnowledgeDocumentRepository documentRepository) {
        return new GeminiAnswerGeneratorAdapter(chatModel, documentRepository);
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd apps/backend && GEMINI_API_KEY=$GEMINI_API_KEY mvn -q test -Dtest=GeminiAnswerGeneratorAdapterTest`
Expected: `BUILD SUCCESS`, 1/1 passing (real Gemini call — a genuine, specific water-safety answer text, not blank).

- [ ] **Step 6: Commit**

```bash
git add apps/backend/src/main/java/com/sheltr/ai/infrastructure/ apps/backend/src/main/java/com/sheltr/ai/config/ apps/backend/src/test/java/com/sheltr/ai/infrastructure/
git commit -m "feat(ai): add Gemini answer generator adapter with citations and confidence"
```

---

### Task 11: JooqConversationRepository

**Files:**
- Create: `apps/backend/src/main/java/com/sheltr/ai/infrastructure/JooqConversationRepository.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/config/ConversationRepositoryConfig.java`
- Test: `apps/backend/src/test/java/com/sheltr/ai/infrastructure/JooqConversationRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: `ConversationRepository`, `Conversation`, `Message`, `Citation`, `MessageId`, `ConversationId`, `MessageRole`, `MessageContent`, `AnswerConfidence`, `ConfidenceLevel` (Task 9); `ChunkId` (Task 1).
- Produces: `JooqConversationRepository(DataSource)` implementing `ConversationRepository`. Task 12 and Task 13 consume `ConversationRepository`.

`save()` is idempotent: the conversation row upserts `last_active_at`; each message inserts with `ON CONFLICT (id) DO NOTHING`; citations are only inserted when the owning message's insert actually affected a row (avoids duplicating citations if `save()` is called twice with the same in-memory `Conversation` — a real risk since `Conversation.messages()` returns the full history every time, not just newly-added messages). `findById()` reconstructs citations by joining `ai.citations` to `knowledge.chunks` on `chunk_id` to recover `document_id`, since `ai.citations` itself has no `document_id` column (matches the Phase 1 schema — the only cross-schema reference is `ai.citations.chunk_id → knowledge.chunks.id`).

- [ ] **Step 1: Write the failing integration test**

```java
package com.sheltr.ai.infrastructure;

import com.sheltr.ai.domain.*;
import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JooqConversationRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void savesAndReloadsConversationWithCitationsWithoutDuplicating() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        // Seed data already provides a real chunk/document pair to satisfy the FK.
        ChunkId chunkId = new ChunkId(UUID.fromString("b0000000-0000-0000-0000-000000000006"));
        DocumentId documentId = new DocumentId(UUID.fromString("a0000000-0000-0000-0000-000000000002"));

        JooqConversationRepository repository = new JooqConversationRepository(dataSource);

        Conversation conversation = new Conversation(new ConversationId(UUID.randomUUID()), null);
        Message userMessage = new Message(new MessageId(UUID.randomUUID()), MessageRole.USER,
                new MessageContent("Is flood water safe to drink?"), List.of(), null, List.of());
        conversation.addMessage(userMessage);
        Message assistantMessage = new Message(new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT,
                new MessageContent("Boil it first."),
                List.of(new Citation(documentId, chunkId, "Flood Safety", "https://example.org", null)),
                AnswerConfidence.fromSimilarity(0.88), List.of(chunkId));
        conversation.addMessage(assistantMessage);

        repository.save(conversation);
        repository.save(conversation); // idempotency check — must not duplicate citations

        Optional<Conversation> reloaded = repository.findById(conversation.id());

        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().messages()).hasSize(2);
        Message reloadedAssistantMessage = reloaded.get().messages().get(1);
        assertThat(reloadedAssistantMessage.citations()).hasSize(1);
        assertThat(reloadedAssistantMessage.citations().get(0).documentId()).isEqualTo(documentId);
        assertThat(reloadedAssistantMessage.confidence().level()).isEqualTo(ConfidenceLevel.MEDIUM);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd apps/backend && mvn -q test -Dtest=JooqConversationRepositoryIntegrationTest`
Expected: FAIL — `JooqConversationRepository` doesn't exist yet.

- [ ] **Step 3: Implement `JooqConversationRepository`**

```java
package com.sheltr.ai.infrastructure;

import com.sheltr.ai.domain.*;
import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JooqConversationRepository implements ConversationRepository {

    private final DSLContext dsl;

    public JooqConversationRepository(DataSource dataSource) {
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    @Override
    public Optional<Conversation> findById(ConversationId id) {
        Record conversationRow = dsl.fetchOne(
                "SELECT id, user_ref, created_at, last_active_at FROM ai.conversations WHERE id = ?",
                id.value());
        if (conversationRow == null) {
            return Optional.empty();
        }

        List<Message> messages = new ArrayList<>();
        var messageRows = dsl.fetch(
                "SELECT id, role, content, confidence_score, confidence_level FROM ai.messages "
                        + "WHERE conversation_id = ? ORDER BY created_at ASC",
                id.value());
        for (Record messageRow : messageRows) {
            UUID messageId = (UUID) messageRow.get("id");
            List<Citation> citations = new ArrayList<>();
            var citationRows = dsl.fetch(
                    "SELECT c.chunk_id, ch.document_id, c.source_title, c.source_url, c.page_number "
                            + "FROM ai.citations c JOIN knowledge.chunks ch ON ch.id = c.chunk_id "
                            + "WHERE c.message_id = ?",
                    messageId);
            for (Record citationRow : citationRows) {
                citations.add(new Citation(
                        new DocumentId((UUID) citationRow.get("document_id")),
                        new ChunkId((UUID) citationRow.get("chunk_id")),
                        citationRow.get("source_title", String.class),
                        citationRow.get("source_url", String.class),
                        citationRow.get("page_number", Integer.class)));
            }
            Double confidenceScore = messageRow.get("confidence_score", Double.class);
            String confidenceLevel = messageRow.get("confidence_level", String.class);
            AnswerConfidence confidence = confidenceScore == null ? null
                    : new AnswerConfidence(confidenceScore, ConfidenceLevel.valueOf(confidenceLevel));
            messages.add(new Message(
                    new MessageId(messageId),
                    MessageRole.valueOf(messageRow.get("role", String.class)),
                    new MessageContent(messageRow.get("content", String.class)),
                    citations, confidence, List.of()));
        }

        String userRef = conversationRow.get("user_ref", String.class);
        Instant createdAt = conversationRow.get("created_at", Timestamp.class).toInstant();
        Instant lastActiveAt = conversationRow.get("last_active_at", Timestamp.class).toInstant();
        return Optional.of(new Conversation(
                new ConversationId((UUID) conversationRow.get("id")), userRef, messages, createdAt, lastActiveAt));
    }

    @Override
    public void save(Conversation conversation) {
        dsl.execute(
                "INSERT INTO ai.conversations (id, user_ref, created_at, last_active_at) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET last_active_at = EXCLUDED.last_active_at",
                conversation.id().value(), conversation.userRef().orElse(null),
                Timestamp.from(conversation.createdAt()), Timestamp.from(conversation.lastActiveAt()));

        for (Message message : conversation.messages()) {
            Double confidenceScore = message.confidence() == null ? null : message.confidence().score();
            String confidenceLevel = message.confidence() == null ? null : message.confidence().level().name();
            int inserted = dsl.execute(
                    "INSERT INTO ai.messages (id, conversation_id, role, content, confidence_score, "
                            + "confidence_level, created_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                    message.id().value(), conversation.id().value(), message.role().name(),
                    message.content().value(), confidenceScore, confidenceLevel, Timestamp.from(Instant.now()));

            if (inserted > 0) {
                for (Citation citation : message.citations()) {
                    dsl.execute(
                            "INSERT INTO ai.citations (id, message_id, chunk_id, source_title, source_url, page_number) "
                                    + "VALUES (?, ?, ?, ?, ?, ?)",
                            UUID.randomUUID(), message.id().value(), citation.chunkId().value(),
                            citation.sourceTitle(), citation.sourceUrl(), citation.pageNumber());
                }
            }
        }
    }
}
```

- [ ] **Step 4: Implement `ConversationRepositoryConfig`**

```java
package com.sheltr.ai.config;

import com.sheltr.ai.domain.ConversationRepository;
import com.sheltr.ai.infrastructure.JooqConversationRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class ConversationRepositoryConfig {

    @Bean
    public ConversationRepository conversationRepository(DataSource dataSource) {
        return new JooqConversationRepository(dataSource);
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd apps/backend && mvn -q test -Dtest=JooqConversationRepositoryIntegrationTest`
Expected: `BUILD SUCCESS`, 1/1 passing.

- [ ] **Step 6: Commit**

```bash
git add apps/backend/src/main/java/com/sheltr/ai/infrastructure/JooqConversationRepository.java apps/backend/src/main/java/com/sheltr/ai/config/ConversationRepositoryConfig.java apps/backend/src/test/java/com/sheltr/ai/infrastructure/JooqConversationRepositoryIntegrationTest.java
git commit -m "feat(ai): add idempotent jOOQ conversation/message/citation repository"
```

---

### Task 12: AskQuestionService

**Files:**
- Create: `apps/backend/src/main/java/com/sheltr/ai/application/ConversationNotFoundException.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/application/AnswerGenerationFailedException.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/application/AskQuestionService.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/config/AskQuestionServiceConfig.java`
- Test: `apps/backend/src/test/java/com/sheltr/ai/application/AskQuestionServiceTest.java`

**Interfaces:**
- Consumes: `Retriever`, `SearchQuery`, `RankedResult` (Task 7); `AnswerGenerator`, `AnswerOutcome`, `AnswerGenerated`, `AnswerGenerationFailed`, `NoAnswerAvailable`, `RelevanceGate`, `Conversation`, `ConversationRepository`, `Message`, `MessageId`, `MessageRole`, `MessageContent`, `ConversationId` (Task 9).
- Produces: `AskQuestionService(Retriever, AnswerGenerator, ConversationRepository, double relevanceThreshold)` with method `Message ask(ConversationId conversationId, String questionText)` — Task 13's REST controller consumes this exact signature. Throws `ConversationNotFoundException` if the conversation doesn't exist, and `AnswerGenerationFailedException` if Gemini generation fails (spec Section 8 — distinct from the `NoAnswerAvailable` below-threshold case, which is a normal 200 response, not an error).

- [ ] **Step 1: Write the failing unit test (fake ports, no Spring/DB/Gemini)**

```java
package com.sheltr.ai.application;

import com.sheltr.ai.domain.*;
import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AskQuestionServiceTest {

    private static final double THRESHOLD = 0.75;

    @Test
    void returnsGeneratedAnswerWhenRetrievalIsRelevant() {
        ConversationId conversationId = new ConversationId(UUID.randomUUID());
        FakeConversationRepository conversations = new FakeConversationRepository(
                new Conversation(conversationId, null));
        RankedResult relevantResult = new RankedResult(
                new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()), "Boil water first.", 0.90);
        Retriever retriever = (query, topK) -> List.of(relevantResult);
        Message expectedAnswer = new Message(new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT,
                new MessageContent("Boil the water for one minute."), List.of(), AnswerConfidence.fromSimilarity(0.90), List.of());
        AnswerGenerator answerGenerator = (query, results) -> new AnswerGenerated(expectedAnswer);

        AskQuestionService service = new AskQuestionService(retriever, answerGenerator, conversations, THRESHOLD);

        Message actual = service.ask(conversationId, "Is flood water safe to drink?");

        assertThat(actual).isEqualTo(expectedAnswer);
        assertThat(conversations.saved.messages()).hasSize(2); // user + assistant
    }

    @Test
    void returnsFallbackWithoutCallingGeneratorWhenBelowThreshold() {
        ConversationId conversationId = new ConversationId(UUID.randomUUID());
        FakeConversationRepository conversations = new FakeConversationRepository(
                new Conversation(conversationId, null));
        RankedResult irrelevantResult = new RankedResult(
                new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()), "Unrelated text.", 0.40);
        Retriever retriever = (query, topK) -> List.of(irrelevantResult);
        AnswerGenerator answerGenerator = (query, results) -> {
            throw new AssertionError("AnswerGenerator must not be called when below threshold");
        };

        AskQuestionService service = new AskQuestionService(retriever, answerGenerator, conversations, THRESHOLD);

        Message actual = service.ask(conversationId, "What is the capital of France?");

        assertThat(actual.role()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(actual.citations()).isEmpty();
    }

    @Test
    void throwsAnswerGenerationFailedExceptionWhenGenerationFails() {
        ConversationId conversationId = new ConversationId(UUID.randomUUID());
        FakeConversationRepository conversations = new FakeConversationRepository(
                new Conversation(conversationId, null));
        RankedResult relevantResult = new RankedResult(
                new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()), "Boil water first.", 0.90);
        Retriever retriever = (query, topK) -> List.of(relevantResult);
        AnswerGenerator answerGenerator = (query, results) -> new AnswerGenerationFailed("Gemini timed out");

        AskQuestionService service = new AskQuestionService(retriever, answerGenerator, conversations, THRESHOLD);

        assertThatThrownBy(() -> service.ask(conversationId, "Is flood water safe to drink?"))
                .isInstanceOf(AnswerGenerationFailedException.class)
                .hasMessageContaining("Gemini timed out");
    }

    @Test
    void throwsWhenConversationDoesNotExist() {
        ConversationId conversationId = new ConversationId(UUID.randomUUID());
        FakeConversationRepository conversations = new FakeConversationRepository(null);
        Retriever retriever = (query, topK) -> List.of();
        AnswerGenerator answerGenerator = (query, results) -> new AnswerGenerationFailed("unused");

        AskQuestionService service = new AskQuestionService(retriever, answerGenerator, conversations, THRESHOLD);

        assertThatThrownBy(() -> service.ask(conversationId, "question"))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    private static class FakeConversationRepository implements ConversationRepository {
        private Conversation stored;
        Conversation saved;

        FakeConversationRepository(Conversation stored) { this.stored = stored; }

        @Override
        public Optional<Conversation> findById(ConversationId id) { return Optional.ofNullable(stored); }

        @Override
        public void save(Conversation conversation) { this.saved = conversation; }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd apps/backend && mvn -q test -Dtest=AskQuestionServiceTest`
Expected: FAIL — `AskQuestionService`/`ConversationNotFoundException` don't exist yet.

- [ ] **Step 3: Implement `ConversationNotFoundException` and `AnswerGenerationFailedException`**

```java
package com.sheltr.ai.application;

import com.sheltr.ai.domain.ConversationId;

public class ConversationNotFoundException extends RuntimeException {
    public ConversationNotFoundException(ConversationId id) {
        super("Conversation not found: " + id.value());
    }
}
```

```java
package com.sheltr.ai.application;

public class AnswerGenerationFailedException extends RuntimeException {
    public AnswerGenerationFailedException(String reason) {
        super(reason);
    }
}
```

`AnswerGenerationFailedException` is what the spec's Section 8 error-handling requirement (Gemini failures surfaced as a clean 5xx, not swallowed into a 200 chat bubble) maps to — distinct from the `NoAnswerAvailable`/below-threshold case, which is a legitimate 200 "no reliable source found" product outcome, not an error.

- [ ] **Step 4: Implement `AskQuestionService`**

```java
package com.sheltr.ai.application;

import com.sheltr.ai.domain.*;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;

import java.util.List;
import java.util.UUID;

public class AskQuestionService {

    private static final int TOP_K = 5;

    private final Retriever retriever;
    private final AnswerGenerator answerGenerator;
    private final ConversationRepository conversationRepository;
    private final double relevanceThreshold;

    public AskQuestionService(Retriever retriever, AnswerGenerator answerGenerator,
                               ConversationRepository conversationRepository, double relevanceThreshold) {
        this.retriever = retriever;
        this.answerGenerator = answerGenerator;
        this.conversationRepository = conversationRepository;
        this.relevanceThreshold = relevanceThreshold;
    }

    public Message ask(ConversationId conversationId, String questionText) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));

        conversation.addMessage(new Message(
                new MessageId(UUID.randomUUID()), MessageRole.USER, new MessageContent(questionText),
                List.of(), null, List.of()));

        SearchQuery query = new SearchQuery(questionText);
        List<RankedResult> results = retriever.retrieve(query, TOP_K);

        Message assistantMessage;
        if (!RelevanceGate.passesThreshold(results, relevanceThreshold)) {
            assistantMessage = fallbackMessage(
                    "I don't have enough reliable information from trusted sources to answer that question.");
            conversation.addMessage(assistantMessage);
            conversationRepository.save(conversation);
        } else {
            AnswerOutcome outcome = answerGenerator.generate(query, results);
            if (outcome instanceof AnswerGenerationFailed failed) {
                // Persist the user's question even though generation failed, so
                // conversation history reflects what was actually asked; do not
                // persist a fabricated assistant message for a real failure.
                conversationRepository.save(conversation);
                throw new AnswerGenerationFailedException(failed.reason());
            }
            assistantMessage = switch (outcome) {
                case AnswerGenerated generated -> generated.message();
                case NoAnswerAvailable ignored -> fallbackMessage(
                        "I don't have enough reliable information from trusted sources to answer that question.");
                case AnswerGenerationFailed ignored -> throw new IllegalStateException("handled above");
            };
            conversation.addMessage(assistantMessage);
            conversationRepository.save(conversation);
        }

        return assistantMessage;
    }

    private static Message fallbackMessage(String text) {
        return new Message(new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT,
                new MessageContent(text), List.of(), null, List.of());
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd apps/backend && mvn -q test -Dtest=AskQuestionServiceTest`
Expected: `BUILD SUCCESS`, 4/4 passing.

- [ ] **Step 6: Implement `AskQuestionServiceConfig`**

```java
package com.sheltr.ai.config;

import com.sheltr.ai.application.AskQuestionService;
import com.sheltr.ai.domain.AnswerGenerator;
import com.sheltr.ai.domain.ConversationRepository;
import com.sheltr.search.domain.Retriever;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AskQuestionServiceConfig {

    @Bean
    public AskQuestionService askQuestionService(
            Retriever retriever, AnswerGenerator answerGenerator, ConversationRepository conversationRepository,
            @Value("${sheltr.search.relevance-threshold}") double relevanceThreshold) {
        return new AskQuestionService(retriever, answerGenerator, conversationRepository, relevanceThreshold);
    }
}
```

- [ ] **Step 7: Commit**

```bash
git add apps/backend/src/main/java/com/sheltr/ai/application/ apps/backend/src/main/java/com/sheltr/ai/config/AskQuestionServiceConfig.java apps/backend/src/test/java/com/sheltr/ai/application/
git commit -m "feat(ai): add AskQuestionService orchestrating retrieval, generation, and persistence"
```

---

### Task 13: REST controllers (conversations, documents)

**Files:**
- Modify: `apps/backend/src/main/java/com/sheltr/ai/domain/ConversationRepository.java` (add `findCitationsByDocumentId`)
- Modify: `apps/backend/src/main/java/com/sheltr/ai/infrastructure/JooqConversationRepository.java` (implement it)
- Create: `apps/backend/src/main/java/com/sheltr/ai/interfaces/ConversationDto.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/interfaces/MessageDto.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/interfaces/CitationDto.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/interfaces/AskRequestDto.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/interfaces/ConversationController.java`
- Create: `apps/backend/src/main/java/com/sheltr/knowledge/interfaces/DocumentMetadataDto.java`
- Create: `apps/backend/src/main/java/com/sheltr/knowledge/interfaces/DocumentController.java`
- Test: `apps/backend/src/test/java/com/sheltr/ai/interfaces/ConversationControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `AskQuestionService`, `ConversationNotFoundException` (Task 12); `ConversationRepository`, `Conversation`, `Message`, `Citation`, `AnswerConfidence` (Task 9); `KnowledgeDocumentRepository`, `KnowledgeDocument` (Tasks 1-2).
- Produces: `POST /api/v1/conversations`, `GET /api/v1/conversations/{id}`, `POST /api/v1/conversations/{id}/ask`, `GET /api/v1/documents/{id}`, `GET /api/v1/documents/{id}/citations` — matching the paths and shapes in `docs/openapi/openapi.yaml`.

`ConversationRepository.findCitationsByDocumentId(DocumentId)` is a new port method discovered while wiring `GET /documents/{id}/citations` — no earlier task needed it; added here rather than left as a TODO.

- [ ] **Step 1: Add `findCitationsByDocumentId` to `ConversationRepository` and implement it**

Modify `apps/backend/src/main/java/com/sheltr/ai/domain/ConversationRepository.java`:

```java
package com.sheltr.ai.domain;

import com.sheltr.knowledge.domain.DocumentId;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository {
    Optional<Conversation> findById(ConversationId id);
    void save(Conversation conversation);
    List<Citation> findCitationsByDocumentId(DocumentId documentId);
}
```

Add to `apps/backend/src/main/java/com/sheltr/ai/infrastructure/JooqConversationRepository.java` (new method, alongside `findById`/`save`):

```java
    @Override
    public List<Citation> findCitationsByDocumentId(DocumentId documentId) {
        var rows = dsl.fetch(
                "SELECT c.chunk_id, ch.document_id, c.source_title, c.source_url, c.page_number "
                        + "FROM ai.citations c JOIN knowledge.chunks ch ON ch.id = c.chunk_id "
                        + "WHERE ch.document_id = ?",
                documentId.value());
        List<Citation> citations = new ArrayList<>();
        for (Record row : rows) {
            citations.add(new Citation(
                    new DocumentId((UUID) row.get("document_id")),
                    new ChunkId((UUID) row.get("chunk_id")),
                    row.get("source_title", String.class),
                    row.get("source_url", String.class),
                    row.get("page_number", Integer.class)));
        }
        return citations;
    }
```

(Add `import com.sheltr.knowledge.domain.DocumentId;` if not already present from Task 11 — it is, since `Citation` construction already uses it.)

- [ ] **Step 2: Verify compilation after the port change**

Run: `cd apps/backend && mvn -q -DskipTests compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Write DTOs**

```java
package com.sheltr.ai.interfaces;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationDto(UUID id, Instant createdAt, Instant lastActiveAt, List<MessageDto> messages) {
}
```

```java
package com.sheltr.ai.interfaces;

import java.util.List;
import java.util.UUID;

public record MessageDto(UUID id, String role, String content, Double confidenceScore,
                          String confidenceLevel, List<CitationDto> citations) {
}
```

```java
package com.sheltr.ai.interfaces;

import java.util.UUID;

public record CitationDto(UUID documentId, UUID chunkId, String sourceTitle, String sourceUrl, Integer pageNumber) {
}
```

```java
package com.sheltr.ai.interfaces;

public record AskRequestDto(String question) {
}
```

```java
package com.sheltr.knowledge.interfaces;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record DocumentMetadataDto(UUID id, String title, String sourceAgency, String sourceUri,
                                   List<String> disasterTypes, LocalDate publishedDate, int version, String status) {
}
```

- [ ] **Step 4: Write the failing controller integration test**

```java
package com.sheltr.ai.interfaces;

import com.sheltr.SheltrBackendApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = SheltrBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConversationControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort
    int port;

    @org.springframework.beans.factory.annotation.Autowired
    TestRestTemplate restTemplate;

    @Test
    void createsAndFetchesAConversation() {
        ResponseEntity<ConversationDto> created =
                restTemplate.postForEntity("/api/v1/conversations", null, ConversationDto.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID conversationId = created.getBody().id();

        ResponseEntity<ConversationDto> fetched =
                restTemplate.getForEntity("/api/v1/conversations/" + conversationId, ConversationDto.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().id()).isEqualTo(conversationId);
    }

    @Test
    void returnsNotFoundForUnknownConversation() {
        ResponseEntity<ConversationDto> response =
                restTemplate.getForEntity("/api/v1/conversations/" + UUID.randomUUID(), ConversationDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
    void askReturnsACitationBearingAnswerForAnInCorpusQuestion() {
        ResponseEntity<ConversationDto> created =
                restTemplate.postForEntity("/api/v1/conversations", null, ConversationDto.class);
        UUID conversationId = created.getBody().id();

        ResponseEntity<MessageDto> response = restTemplate.postForEntity(
                "/api/v1/conversations/" + conversationId + "/ask",
                new AskRequestDto("Is it safe to drink flood water?"),
                MessageDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().role()).isEqualTo("ASSISTANT");
        assertThat(response.getBody().content()).isNotBlank();
    }
}
```

- [ ] **Step 5: Run to verify it fails**

Run: `cd apps/backend && mvn -q test -Dtest=ConversationControllerIntegrationTest`
Expected: FAIL — `ConversationController` doesn't exist yet (404s / connection errors on every request).

- [ ] **Step 6: Implement `ConversationController`**

```java
package com.sheltr.ai.interfaces;

import com.sheltr.ai.application.AnswerGenerationFailedException;
import com.sheltr.ai.application.AskQuestionService;
import com.sheltr.ai.application.ConversationNotFoundException;
import com.sheltr.ai.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationRepository conversationRepository;
    private final AskQuestionService askQuestionService;

    public ConversationController(ConversationRepository conversationRepository, AskQuestionService askQuestionService) {
        this.conversationRepository = conversationRepository;
        this.askQuestionService = askQuestionService;
    }

    @PostMapping
    public ResponseEntity<ConversationDto> create() {
        Conversation conversation = new Conversation(new ConversationId(UUID.randomUUID()), null);
        conversationRepository.save(conversation);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(conversation));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConversationDto> get(@PathVariable UUID id) {
        return conversationRepository.findById(new ConversationId(id))
                .map(conversation -> ResponseEntity.ok(toDto(conversation)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/ask")
    public ResponseEntity<MessageDto> ask(@PathVariable UUID id, @RequestBody AskRequestDto request) {
        Message message = askQuestionService.ask(new ConversationId(id), request.question());
        return ResponseEntity.ok(toDto(message));
    }

    @ExceptionHandler(ConversationNotFoundException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(AnswerGenerationFailedException.class)
    public ResponseEntity<Void> handleGenerationFailed() {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }

    private static ConversationDto toDto(Conversation conversation) {
        List<MessageDto> messages = conversation.messages().stream().map(ConversationController::toDto).toList();
        return new ConversationDto(conversation.id().value(), conversation.createdAt(), conversation.lastActiveAt(), messages);
    }

    private static MessageDto toDto(Message message) {
        List<CitationDto> citations = message.citations().stream()
                .map(c -> new CitationDto(c.documentId().value(), c.chunkId().value(), c.sourceTitle(), c.sourceUrl(), c.pageNumber()))
                .toList();
        Double confidenceScore = message.confidence() == null ? null : message.confidence().score();
        String confidenceLevel = message.confidence() == null ? null : message.confidence().level().name();
        return new MessageDto(message.id().value(), message.role().name(), message.content().value(),
                confidenceScore, confidenceLevel, citations);
    }
}
```

- [ ] **Step 7: Implement `DocumentController`**

```java
package com.sheltr.knowledge.interfaces;

import com.sheltr.ai.domain.ConversationRepository;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.knowledge.domain.KnowledgeDocument;
import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import com.sheltr.ai.interfaces.CitationDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final KnowledgeDocumentRepository documentRepository;
    private final ConversationRepository conversationRepository;

    public DocumentController(KnowledgeDocumentRepository documentRepository, ConversationRepository conversationRepository) {
        this.documentRepository = documentRepository;
        this.conversationRepository = conversationRepository;
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentMetadataDto> get(@PathVariable UUID id) {
        return documentRepository.findById(new DocumentId(id))
                .map(document -> ResponseEntity.ok(toDto(document)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/citations")
    public ResponseEntity<List<CitationDto>> citations(@PathVariable UUID id) {
        List<CitationDto> citations = conversationRepository.findCitationsByDocumentId(new DocumentId(id)).stream()
                .map(c -> new CitationDto(c.documentId().value(), c.chunkId().value(), c.sourceTitle(), c.sourceUrl(), c.pageNumber()))
                .toList();
        return ResponseEntity.ok(citations);
    }

    private static DocumentMetadataDto toDto(KnowledgeDocument document) {
        List<String> disasterTypes = document.disasterTypes().stream().map(Enum::name).toList();
        return new DocumentMetadataDto(document.id().value(), document.title(), document.sourceAgency(),
                document.sourceUri(), disasterTypes, document.publishedDate(), document.version().value(),
                document.status().name());
    }
}
```

- [ ] **Step 8: Run to verify tests pass**

Run: `cd apps/backend && GEMINI_API_KEY=$GEMINI_API_KEY mvn -q test -Dtest=ConversationControllerIntegrationTest`
Expected: `BUILD SUCCESS`, 3/3 passing (the `@EnabledIfEnvironmentVariable`-gated one runs for real given the key is set).

- [ ] **Step 9: Run the full suite**

Run: `cd apps/backend && GEMINI_API_KEY=$GEMINI_API_KEY mvn -q test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 10: Commit**

```bash
git add apps/backend/src/main/java/com/sheltr/ai/domain/ConversationRepository.java apps/backend/src/main/java/com/sheltr/ai/infrastructure/JooqConversationRepository.java apps/backend/src/main/java/com/sheltr/ai/interfaces/ apps/backend/src/main/java/com/sheltr/knowledge/interfaces/ apps/backend/src/test/java/com/sheltr/ai/interfaces/
git commit -m "feat(ai,knowledge): add conversation and document REST controllers"
```

---

### Task 14: End-to-end RAG loop test (real Gemini, seeded corpus)

**Files:**
- Create: `apps/backend/src/test/java/com/sheltr/ai/interfaces/RagLoopEndToEndTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1-13 — this task wires no new production code, it is a pure verification task proving the full stack together.

This is the capstone test for the spec: seeded chunks get real embeddings backfilled, then a real end-to-end HTTP call proves both halves of ADR-0008 — a genuine in-corpus question gets a citation-bearing answer, and a genuine out-of-corpus question gets the "no reliable information" fallback rather than an invented answer.

- [ ] **Step 1: Write the test**

```java
package com.sheltr.ai.interfaces;

import com.sheltr.SheltrBackendApplication;
import com.sheltr.knowledge.application.ChunkEmbeddingBackfillService;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.knowledge.infrastructure.GeminiEmbeddingGeneratorAdapter;
import com.sheltr.knowledge.infrastructure.JooqChunkRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiEmbeddingModel;
import org.springframework.ai.google.genai.GoogleGenAiEmbeddingOptions;
import org.springframework.ai.google.genai.api.GoogleGenAiApi;
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

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@SpringBootTest(classes = SheltrBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RagLoopEndToEndTest {

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

    @BeforeAll
    static void backfillEmbeddingsForAllSeededChunks() {
        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        GoogleGenAiApi api = new GoogleGenAiApi(System.getenv("GEMINI_API_KEY"));
        EmbeddingModel embeddingModel = new GoogleGenAiEmbeddingModel(api,
                GoogleGenAiEmbeddingOptions.builder().model("text-embedding-004").build());
        EmbeddingGenerator embeddingGenerator = new GeminiEmbeddingGeneratorAdapter(embeddingModel);

        // Flyway (including the V5 seed migration) already ran via Spring Boot's
        // spring.flyway.enabled=true on application context startup, before this
        // @BeforeAll runs. Backfilling ~34 real embeddings here is slow (network calls)
        // but this is the one test meant to prove the whole stack for real.
        new ChunkEmbeddingBackfillService(new JooqChunkRepository(dataSource), embeddingGenerator).backfillAll();
    }

    @Test
    void inCorpusQuestionReturnsCitationBearingAnswer() {
        UUID conversationId = createConversation();

        ResponseEntity<MessageDto> response = restTemplate.postForEntity(
                "/api/v1/conversations/" + conversationId + "/ask",
                new AskRequestDto("Is it safe to drink flood water?"),
                MessageDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().role()).isEqualTo("ASSISTANT");
        assertThat(response.getBody().citations()).isNotEmpty();
        assertThat(response.getBody().confidenceLevel()).isIn("MEDIUM", "HIGH");
    }

    @Test
    void outOfCorpusQuestionReturnsNoInventedAnswer() {
        UUID conversationId = createConversation();

        ResponseEntity<MessageDto> response = restTemplate.postForEntity(
                "/api/v1/conversations/" + conversationId + "/ask",
                new AskRequestDto("What is the capital of France?"),
                MessageDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().citations()).isEmpty();
        assertThat(response.getBody().confidenceLevel()).isNull();
    }

    private UUID createConversation() {
        ResponseEntity<ConversationDto> created =
                restTemplate.postForEntity("/api/v1/conversations", null, ConversationDto.class);
        return created.getBody().id();
    }
}
```

Note: `java.util.UUID` import is required (`import java.util.UUID;`) — add it alongside the others.

- [ ] **Step 2: Run to verify it passes**

Run: `cd apps/backend && GEMINI_API_KEY=$GEMINI_API_KEY mvn -q test -Dtest=RagLoopEndToEndTest`
Expected: `BUILD SUCCESS`, 2/2 passing. This test makes ~34 real embedding calls plus 2 generation calls — expect it to take longer than the rest of the suite combined; that's expected for the one test meant to prove the real stack end-to-end.

- [ ] **Step 3: Run the entire backend test suite one more time**

Run: `cd apps/backend && GEMINI_API_KEY=$GEMINI_API_KEY mvn -q test`
Expected: `BUILD SUCCESS`, every test from Tasks 1-14 passing.

- [ ] **Step 4: Commit**

```bash
git add apps/backend/src/test/java/com/sheltr/ai/interfaces/RagLoopEndToEndTest.java
git commit -m "test: add end-to-end RAG loop test proving ADR-0008's threshold gate"
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
GEMINI_API_KEY=$GEMINI_API_KEY make seed-embeddings
cd apps/backend && GEMINI_API_KEY=$GEMINI_API_KEY mvn -q spring-boot:run &
sleep 10
curl -s -X POST http://localhost:8080/api/v1/conversations | tee /tmp/conv.json
CONV_ID=$(cat /tmp/conv.json | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
curl -s -X POST http://localhost:8080/api/v1/conversations/$CONV_ID/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "How should I store insulin during a power outage?"}'
kill %1
cd ../..
make down
```
Expected: a real, specific answer about insulin storage (referencing the seeded winter-storm chunk about keeping insulin between 36-46°F), with a non-empty `citations` array pointing at the "Winter Storm Safety" document.

- [ ] **Confirm git log shows all 14 tasks as separate commits**

```bash
git log --oneline
```
