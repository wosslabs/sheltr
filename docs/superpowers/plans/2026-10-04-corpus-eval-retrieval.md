# Real Corpus, Eval Harness, and Retrieval Quality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Sheltr's hand-written seed corpus with a reproducible corpus of official documents, measure answer quality with an eval harness, and raise retrieval quality with hybrid search, reranking, and a relevance judge.

**Architecture:** Three phases on the existing hexagonal layout. Phase A makes the corpus real: a migration, per-document ingestion metadata, and a manifest-driven loader. Phase B adds retrieval stages behind ports (`Retriever`, `Reranker`, `RelevanceJudge`) composed by one `AnswerPipeline`. Phase C adds a new `eval` package that runs the pipeline over a question file and scores it in a second pass with a different model.

**Tech Stack:** Java 25, Spring Boot 4.1.0, Spring AI 2.0.0, PostgreSQL 16 + pgvector, jOOQ 3.19.11 (plain SQL), Flyway, JUnit 5 + AssertJ + Mockito + Testcontainers, SnakeYAML 2.6, Jackson 2.21 (`com.fasterxml.jackson.databind`), Ollama, Podman.

**Spec:** `docs/superpowers/specs/2026-10-04-corpus-eval-retrieval-design.md`

## Global Constraints

- English only. No multilingual code, fields, or prompts.
- Global corpus only. No `country` field. No country-specific content.
- Official text only: no step may add guidance text written by a person or a model to `knowledge.*` tables. Test fixtures are the only exception.
- PDF is the only supported source type.
- Embedding dimension stays 768 (`Embedding.DIMENSION`).
- `DisasterType` values, exact: `EARTHQUAKE`, `FLOOD`, `TROPICAL_CYCLONE`, `TORNADO`, `TSUNAMI`, `WILDFIRE`, `EXTREME_HEAT`, `WINTER_STORM`, `LANDSLIDE`, `GLACIAL_LAKE_FLOOD`, `VOLCANO`, `DROUGHT`.
- Candidates retrieved per question: `20`. Passages sent to the model: `5`.
- Refusal text is `NoAnswerAvailable.MESSAGE`. The relevance judge fails closed: any judge error is a refusal.
- Targets on the `heldout` split: false-accept rate `0`, false-refuse rate at most `0.10`, recall at 5 at least `0.85`, faithfulness at least `0.95`, judge and maintainer agreement at least `0.90`.
- JSON is read and written with Jackson 2 (`com.fasterxml.jackson.databind.ObjectMapper`), constructed directly. Do not use `tools.jackson`.
- Path shorthand used below: `MAIN` = `apps/backend/src/main/java/com/sheltr`, `TEST` = `apps/backend/src/test/java/com/sheltr`, `RES` = `apps/backend/src/main/resources`.
- Maven runs from `apps/backend/`. `$MVN` below means:
  `env -u SPRING_CONFIG_IMPORT -u SPRING_PROFILES_ACTIVE -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_DRIVER_CLASS_NAME -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD mvn -q`
- Testcontainers tests need a running Podman machine.
- Work on branch `feat/corpus-eval-retrieval`. Do not touch the two already-modified files under `docs/superpowers/specs/2026-08-07-*`.

## Review Focus

1. A question made only of common words ("what should I do?") produces an empty keyword query. The user still gets vector results, not an error. Pinned in Task 6.
2. A scanned PDF with no extractable text. The run fails with a clear message and no document row is saved. Pinned in Task 3.
3. An unknown hazard name in `activeHazards`, as a feed might send. The API answers 400, not 500. Pinned in Task 9.
4. The model cites a passage number that does not exist, or cites nothing. No citation is invented, and an uncited answer is refused. Pinned in Task 9.
5. A hand-edited manifest with a duplicate `source_uri`, or an unquoted date that YAML turns into a date object. Duplicates are rejected with the URI named; both date forms load. Pinned in Task 4.

## File structure

**Phase A — corpus**
- Create `RES/db/migration/V7__corpus_foundation.sql` — licence, seed status, embedding model, generated `content_tsv`, hazard rename, related hazards table
- Modify `MAIN/knowledge/domain/DisasterType.java`, `DocumentStatus.java`, `KnowledgeDocument.java`, `EmbeddingGenerator.java`, `ChunkRepository.java`
- Create `MAIN/knowledge/domain/StaleEmbeddingsException.java`
- Modify `MAIN/knowledge/infrastructure/JooqChunkRepository.java`, `JooqKnowledgeDocumentRepository.java`, `SpringAiEmbeddingGeneratorAdapter.java`
- Modify `MAIN/knowledge/application/ChunkEmbeddingBackfillService.java`, `MAIN/knowledge/config/EmbeddingGeneratorConfig.java`
- Modify `MAIN/search/infrastructure/PgvectorSemanticSearchAdapter.java`, `MAIN/search/config/RetrieverConfig.java`
- Create `MAIN/search/infrastructure/GuardedRetriever.java`
- Modify `MAIN/ingestion/domain/SourceMetadata.java`, `SourceConnector.java`; `MAIN/ingestion/application/IngestDocumentService.java`; `MAIN/ingestion/interfaces/IngestionController.java`, `TriggerIngestionRequestDto.java`; `MAIN/ingestion/config/SourceConnectorConfig.java`
- Rename `MAIN/ingestion/infrastructure/FemaHttpConnector.java` → `HttpPdfConnector.java`
- Create `MAIN/ingestion/application/CorpusManifest.java`, `IngestCorpusService.java`; `MAIN/ingestion/domain/CorpusCatalog.java`; `MAIN/ingestion/infrastructure/JooqCorpusCatalog.java`; `MAIN/ingestion/config/IngestCorpusRunner.java`
- Create `corpus/manifest.yaml`

**Phase B — retrieval**
- Modify `MAIN/search/domain/SearchQuery.java`
- Create `MAIN/search/domain/RankFusion.java`, `HazardBoost.java`, `HybridRanker.java`, `RelatedHazards.java`, `Reranker.java`, `NoOpReranker.java`
- Create `MAIN/search/infrastructure/HybridSearchAdapter.java`, `JooqRelatedHazards.java`, `ModelReranker.java`; `MAIN/search/config/RerankerConfig.java`
- Create `MAIN/ai/domain/RelevanceVerdict.java`, `RelevanceJudge.java`, `ThresholdRelevanceJudge.java`, `ConfidenceBands.java`, `CitationSelector.java`
- Create `MAIN/ai/infrastructure/ModelRelevanceJudge.java`; `MAIN/ai/application/AnswerPipeline.java`; `MAIN/ai/config/AnswerPipelineConfig.java`
- Modify `MAIN/ai/domain/AnswerConfidence.java`, `AnswerGenerator.java`; `MAIN/ai/application/AnswerContextBuilder.java`, `AskQuestionService.java`; `MAIN/ai/infrastructure/SpringAiAnswerGeneratorAdapter.java`; `MAIN/ai/interfaces/LuminaChatApp.java`, `ConversationController.java`, `AskRequestDto.java`; `MAIN/ai/config/AskQuestionServiceConfig.java`, `LuminaChatAppConfig.java`, `AnswerContextBuilderConfig.java`
- Delete `MAIN/ai/domain/RelevanceGate.java`

**Phase C — eval**
- Create `MAIN/eval/domain/EvalKind.java`, `ExpectedPassage.java`, `EvalQuestion.java`, `RetrievedPassage.java`, `EvalResult.java`, `EvalRun.java`, `Metrics.java`, `SplitScores.java`, `EvalReport.java`, `SpotcheckItem.java`
- Create `MAIN/eval/application/EvalJson.java`, `EvalQuestionLoader.java`, `EvalRunService.java`, `AnswerJudge.java`, `EvalScoreService.java`, `ReportWriter.java`
- Create `MAIN/eval/config/EvalRunner.java`
- Create `apps/backend/src/test/resources/eval/questions.jsonl`
- Create `docs/eval/`, `docs/adr/0012-*.md`, `docs/adr/0013-*.md`
- Modify `Makefile`, `README.md`, `.gitignore`, `RES/application*.yml`, `apps/backend/pom.xml`

---

# Phase A — Corpus

### Task 1: Schema migration and hazard taxonomy

**Files:**
- Create: `RES/db/migration/V7__corpus_foundation.sql`
- Create: `TEST/knowledge/infrastructure/CorpusFoundationMigrationTest.java`
- Modify: `MAIN/knowledge/domain/DisasterType.java`, `MAIN/knowledge/domain/DocumentStatus.java`
- Modify: `TEST/knowledge/infrastructure/SeedDataMigrationTest.java:54-56`, `TEST/MigrationIntegrationTest.java:44-52`

**Interfaces:**
- Consumes: migrations V1–V6.
- Produces: columns `knowledge.documents.license TEXT`, `knowledge.chunks.embedding_model TEXT`; generated `knowledge.chunks.content_tsv`; table `knowledge.related_hazards(hazard, related_hazard, source_document_id, note)`; seed documents with `status = 'SEED'`; enum values `DocumentStatus.SEED` and the twelve `DisasterType` values from Global Constraints.

- [ ] **Step 1: Write the failing test**

`TEST/knowledge/infrastructure/CorpusFoundationMigrationTest.java`:

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

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class CorpusFoundationMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void appliesCorpusFoundationChanges() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            assertThat(count(conn, "SELECT count(*) FROM knowledge.documents WHERE status = 'SEED'")).isEqualTo(8);
            assertThat(count(conn, "SELECT count(*) FROM knowledge.documents WHERE status = 'ACTIVE'")).isZero();
            assertThat(count(conn,
                    "SELECT count(*) FROM knowledge.documents WHERE 'HURRICANE' = ANY(disaster_types)")).isZero();
            assertThat(count(conn,
                    "SELECT count(*) FROM knowledge.documents WHERE 'TROPICAL_CYCLONE' = ANY(disaster_types)"))
                    .isEqualTo(1);
            assertThat(count(conn,
                    "SELECT count(*) FROM knowledge.chunks WHERE content_tsv @@ to_tsquery('english', 'boil')"))
                    .isGreaterThanOrEqualTo(1);
            assertThat(count(conn, "SELECT count(*) FROM knowledge.chunks WHERE embedding_model IS NOT NULL"))
                    .isZero();
            assertThat(count(conn, "SELECT count(*) FROM knowledge.documents WHERE license IS NOT NULL")).isZero();
            assertThat(count(conn, "SELECT count(*) FROM knowledge.related_hazards")).isZero();
        }
    }

    private static int count(Connection conn, String sql) throws Exception {
        try (ResultSet rs = conn.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `$MVN test -Dtest=CorpusFoundationMigrationTest`
Expected: FAIL. The first assertion finds 0 `SEED` documents.

- [ ] **Step 3: Write the migration**

`RES/db/migration/V7__corpus_foundation.sql`:

```sql
-- Reuse terms of each ingested document. Required by ingestion; seed rows have none.
ALTER TABLE knowledge.documents ADD COLUMN license TEXT;

-- The V5 rows are hand-written test fixtures, not official text. Keep them out of
-- real retrieval, which only returns statuses listed in sheltr.search.statuses.
UPDATE knowledge.documents SET status = 'SEED' WHERE checksum LIKE 'seed-%';

UPDATE knowledge.documents
SET disaster_types = array_replace(disaster_types, 'HURRICANE', 'TROPICAL_CYCLONE');

-- Which model produced each vector, so vectors from different models are never mixed.
ALTER TABLE knowledge.chunks ADD COLUMN embedding_model TEXT;
-- Existing vectors have no recorded model; clear them so backfill re-embeds and records it.
UPDATE knowledge.chunks SET embedding = NULL WHERE embedding IS NOT NULL;

-- content_tsv existed but was never populated. Dropping the column drops its GIN index.
ALTER TABLE knowledge.chunks DROP COLUMN content_tsv;
ALTER TABLE knowledge.chunks ADD COLUMN content_tsv TSVECTOR
    GENERATED ALWAYS AS (to_tsvector('english'::regconfig, content)) STORED;
CREATE INDEX idx_knowledge_chunks_content_tsv ON knowledge.chunks USING gin (content_tsv);

CREATE TABLE knowledge.related_hazards (
    hazard TEXT NOT NULL,
    related_hazard TEXT NOT NULL,
    source_document_id UUID NOT NULL REFERENCES knowledge.documents(id),
    note TEXT,
    PRIMARY KEY (hazard, related_hazard)
);
```

- [ ] **Step 4: Update the enums**

`MAIN/knowledge/domain/DisasterType.java`:

```java
package com.sheltr.knowledge.domain;

public enum DisasterType {
    EARTHQUAKE, FLOOD, TROPICAL_CYCLONE, TORNADO, TSUNAMI, WILDFIRE, EXTREME_HEAT, WINTER_STORM,
    LANDSLIDE, GLACIAL_LAKE_FLOOD, VOLCANO, DROUGHT
}
```

`MAIN/knowledge/domain/DocumentStatus.java`:

```java
package com.sheltr.knowledge.domain;

public enum DocumentStatus {
    ACTIVE, SUPERSEDED, RETRACTED, SEED
}
```

- [ ] **Step 5: Update the two existing migration tests**

In `TEST/knowledge/infrastructure/SeedDataMigrationTest.java`, replace `"HURRICANE"` with `"TROPICAL_CYCLONE"` in the `containsExactlyInAnyOrder` call.

In `TEST/MigrationIntegrationTest.java`, add `"knowledge.related_hazards",` after `"knowledge.chunks",` in the `containsExactlyInAnyOrder` call.

- [ ] **Step 6: Run the migration tests**

Run: `$MVN test -Dtest='CorpusFoundationMigrationTest,SeedDataMigrationTest,MigrationIntegrationTest'`
Expected: PASS, 3 tests.

- [ ] **Step 7: Commit**

```bash
git add apps/backend/src/main/resources/db/migration/V7__corpus_foundation.sql \
  apps/backend/src/main/java/com/sheltr/knowledge/domain/DisasterType.java \
  apps/backend/src/main/java/com/sheltr/knowledge/domain/DocumentStatus.java \
  apps/backend/src/test/java/com/sheltr/knowledge/infrastructure/CorpusFoundationMigrationTest.java \
  apps/backend/src/test/java/com/sheltr/knowledge/infrastructure/SeedDataMigrationTest.java \
  apps/backend/src/test/java/com/sheltr/MigrationIntegrationTest.java
git commit -m "feat(knowledge): add corpus foundation migration and wider hazard taxonomy"
```

---

### Task 2: Embedding model tracking, status filter, stale-embedding guard

**Files:**
- Modify: `MAIN/knowledge/domain/EmbeddingGenerator.java`, `ChunkRepository.java`
- Create: `MAIN/knowledge/domain/StaleEmbeddingsException.java`
- Modify: `MAIN/knowledge/infrastructure/SpringAiEmbeddingGeneratorAdapter.java`, `JooqChunkRepository.java`
- Modify: `MAIN/knowledge/application/ChunkEmbeddingBackfillService.java`, `MAIN/knowledge/config/EmbeddingGeneratorConfig.java`
- Modify: `MAIN/ingestion/application/IngestDocumentService.java:77-83`
- Modify: `MAIN/search/infrastructure/PgvectorSemanticSearchAdapter.java`, `MAIN/search/config/RetrieverConfig.java`
- Create: `MAIN/search/infrastructure/GuardedRetriever.java`
- Modify: `MAIN/ai/interfaces/ConversationController.java`
- Modify: `RES/application.yml`, `RES/application-local.yml`, `RES/application-gemini.yml`, `apps/backend/src/test/resources/application.properties`
- Create: `TEST/search/infrastructure/RetrievalFilterIntegrationTest.java`, `TEST/search/infrastructure/GuardedRetrieverTest.java`
- Modify (signature changes only): `TEST/knowledge/application/ChunkEmbeddingBackfillServiceTest.java`, `TEST/ingestion/application/IngestDocumentServiceTest.java`, `TEST/knowledge/infrastructure/JooqChunkRepositoryInsertTest.java`, `TEST/knowledge/infrastructure/JooqChunkRepositoryIntegrationTest.java`, `TEST/knowledge/infrastructure/SpringAiEmbeddingGeneratorAdapterTest.java`, `TEST/search/infrastructure/PgvectorSemanticSearchAdapterIntegrationTest.java`

**Interfaces:**
- Consumes: `knowledge.chunks.embedding_model`, `knowledge.documents.status` from Task 1.
- Produces:
  - `EmbeddingGenerator`: `Embedding embed(String text)` for documents, `Embedding embedQuery(String text)` for questions, `String modelName()`.
  - `ChunkRepository`: `List<Chunk> findPendingEmbeddings(String embeddingModel)`, `void save(Chunk chunk, String embeddingModel)`, `void insert(Chunk chunk, String embeddingModel)`, `int countEmbeddedWithOtherModel(String embeddingModel)`.
  - `new PgvectorSemanticSearchAdapter(DataSource, EmbeddingGenerator, Set<String> statuses)`.
  - `new GuardedRetriever(Retriever delegate, ChunkRepository chunks, String embeddingModel)`; throws `StaleEmbeddingsException`.
  - Properties `sheltr.embedding.model-name`, `sheltr.embedding.query-prefix`, `sheltr.embedding.document-prefix`, `sheltr.search.statuses`.

- [ ] **Step 1: Write the failing filter test**

`TEST/search/infrastructure/RetrievalFilterIntegrationTest.java`:

```java
package com.sheltr.search.infrastructure;

import com.sheltr.knowledge.domain.Embedding;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.search.domain.SearchQuery;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RetrievalFilterIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final EmbeddingGenerator MODEL_A = new EmbeddingGenerator() {
        @Override
        public Embedding embed(String text) {
            float[] values = new float[Embedding.DIMENSION];
            values[0] = 1.0f;
            return new Embedding(values);
        }

        @Override
        public String modelName() {
            return "model-a";
        }
    };

    private PGSimpleDataSource dataSource;

    @BeforeEach
    void migrateAndEmbedSeedChunks() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        setAllEmbeddings("model-a");
    }

    @Test
    void returnsNothingWhenSeedStatusIsNotAllowed() {
        var adapter = new PgvectorSemanticSearchAdapter(dataSource, MODEL_A, Set.of("ACTIVE"));

        assertThat(adapter.retrieve(new SearchQuery("flood water"), 5)).isEmpty();
    }

    @Test
    void returnsSeedChunksWhenSeedStatusIsAllowed() {
        var adapter = new PgvectorSemanticSearchAdapter(dataSource, MODEL_A, Set.of("ACTIVE", "SEED"));

        assertThat(adapter.retrieve(new SearchQuery("flood water"), 5)).hasSize(5);
    }

    @Test
    void ignoresChunksEmbeddedByAnotherModel() throws Exception {
        setAllEmbeddings("model-b");
        var adapter = new PgvectorSemanticSearchAdapter(dataSource, MODEL_A, Set.of("ACTIVE", "SEED"));

        assertThat(adapter.retrieve(new SearchQuery("flood water"), 5)).isEmpty();
    }

    private void setAllEmbeddings(String model) throws Exception {
        StringBuilder vector = new StringBuilder("[1");
        for (int i = 1; i < Embedding.DIMENSION; i++) {
            vector.append(",0");
        }
        vector.append(']');
        try (Connection conn = dataSource.getConnection();
             PreparedStatement statement = conn.prepareStatement(
                     "UPDATE knowledge.chunks SET embedding = ?::vector, embedding_model = ?")) {
            statement.setString(1, vector.toString());
            statement.setString(2, model);
            statement.executeUpdate();
        }
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `$MVN test -Dtest=RetrievalFilterIntegrationTest`
Expected: compilation FAIL. `EmbeddingGenerator` has no `modelName()` and the adapter has no three-argument constructor.

- [ ] **Step 3: Extend the embedding port and adapter**

`MAIN/knowledge/domain/EmbeddingGenerator.java`:

```java
package com.sheltr.knowledge.domain;

public interface EmbeddingGenerator {

    /** Embeds corpus text. */
    Embedding embed(String text);

    /** Embeds a user question. Some models want a different prefix for questions than for documents. */
    default Embedding embedQuery(String text) {
        return embed(text);
    }

    /** Name recorded on every chunk this generator embeds. Change it whenever vectors would differ. */
    String modelName();
}
```

`MAIN/knowledge/infrastructure/SpringAiEmbeddingGeneratorAdapter.java`:

```java
package com.sheltr.knowledge.infrastructure;

import com.sheltr.knowledge.domain.Embedding;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import org.springframework.ai.embedding.EmbeddingModel;

public class SpringAiEmbeddingGeneratorAdapter implements EmbeddingGenerator {

    private final EmbeddingModel embeddingModel;
    private final String modelName;
    private final String queryPrefix;
    private final String documentPrefix;

    public SpringAiEmbeddingGeneratorAdapter(EmbeddingModel embeddingModel, String modelName) {
        this(embeddingModel, modelName, "", "");
    }

    public SpringAiEmbeddingGeneratorAdapter(EmbeddingModel embeddingModel, String modelName,
                                             String queryPrefix, String documentPrefix) {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName must not be blank");
        }
        this.embeddingModel = embeddingModel;
        this.modelName = modelName;
        this.queryPrefix = queryPrefix == null ? "" : queryPrefix;
        this.documentPrefix = documentPrefix == null ? "" : documentPrefix;
    }

    @Override
    public Embedding embed(String text) {
        return new Embedding(embeddingModel.embed(documentPrefix + text));
    }

    @Override
    public Embedding embedQuery(String text) {
        return new Embedding(embeddingModel.embed(queryPrefix + text));
    }

    @Override
    public String modelName() {
        return modelName;
    }
}
```

`MAIN/knowledge/config/EmbeddingGeneratorConfig.java`:

```java
package com.sheltr.knowledge.config;

import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.knowledge.infrastructure.SpringAiEmbeddingGeneratorAdapter;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingGeneratorConfig {

    @Bean
    public EmbeddingGenerator embeddingGenerator(
            EmbeddingModel embeddingModel,
            @Value("${sheltr.embedding.model-name}") String modelName,
            @Value("${sheltr.embedding.query-prefix:}") String queryPrefix,
            @Value("${sheltr.embedding.document-prefix:}") String documentPrefix) {
        return new SpringAiEmbeddingGeneratorAdapter(embeddingModel, modelName, queryPrefix, documentPrefix);
    }
}
```

- [ ] **Step 4: Extend the chunk repository**

`MAIN/knowledge/domain/ChunkRepository.java`:

```java
package com.sheltr.knowledge.domain;

import java.util.List;
import java.util.Optional;

public interface ChunkRepository {
    Optional<Chunk> findById(ChunkId id);

    /** Chunks with no vector, or with a vector from a model other than {@code embeddingModel}. */
    List<Chunk> findPendingEmbeddings(String embeddingModel);

    void save(Chunk chunk, String embeddingModel);

    void insert(Chunk chunk, String embeddingModel);

    int countEmbeddedWithOtherModel(String embeddingModel);
}
```

In `MAIN/knowledge/infrastructure/JooqChunkRepository.java`, replace `findPendingEmbeddings`, `save`, and `insert` with the following, and add `countEmbeddedWithOtherModel`:

```java
    @Override
    public List<Chunk> findPendingEmbeddings(String embeddingModel) {
        var result = dsl.fetch(
                "SELECT id, document_id, content, page_number, chunk_index, section_heading, checksum "
                        + "FROM knowledge.chunks "
                        + "WHERE embedding IS NULL OR embedding_model IS DISTINCT FROM ?",
                embeddingModel);
        List<Chunk> chunks = new ArrayList<>();
        for (Record record : result) {
            chunks.add(mapRow(record));
        }
        return chunks;
    }

    @Override
    public void save(Chunk chunk, String embeddingModel) {
        Embedding embedding = chunk.embedding().orElseThrow(
                () -> new IllegalStateException("save() only supports writing an embedding in this slice"));
        dsl.execute("UPDATE knowledge.chunks SET embedding = ?::vector, embedding_model = ? WHERE id = ?",
                toVectorLiteral(embedding), embeddingModel, chunk.id().value());
    }

    @Override
    public void insert(Chunk chunk, String embeddingModel) {
        Embedding embedding = chunk.embedding().orElseThrow(
                () -> new IllegalStateException("insert() requires the chunk to already have an embedding"));
        dsl.execute(
                "INSERT INTO knowledge.chunks (id, document_id, content, embedding, embedding_model, "
                        + "page_number, chunk_index, section_heading, checksum) "
                        + "VALUES (?, ?, ?, ?::vector, ?, ?, ?, ?, ?)",
                chunk.id().value(), chunk.documentId().value(), chunk.content().value(),
                toVectorLiteral(embedding), embeddingModel,
                chunk.pageNumber().map(PageNumber::value).orElse(null),
                chunk.chunkIndex(), chunk.metadata().sectionHeading(), chunk.checksum().hex());
    }

    @Override
    public int countEmbeddedWithOtherModel(String embeddingModel) {
        return dsl.fetchOne(
                "SELECT count(*) AS n FROM knowledge.chunks "
                        + "WHERE embedding IS NOT NULL AND embedding_model IS DISTINCT FROM ?",
                embeddingModel).get("n", Integer.class);
    }
```

In `MAIN/knowledge/application/ChunkEmbeddingBackfillService.java`, replace `backfillAll`:

```java
    public int backfillAll() {
        String model = embeddingGenerator.modelName();
        List<Chunk> pending = chunkRepository.findPendingEmbeddings(model);
        for (Chunk chunk : pending) {
            var embedding = embeddingGenerator.embed(chunk.content().value());
            chunkRepository.save(chunk.withEmbedding(embedding), model);
        }
        return pending.size();
    }
```

In `MAIN/ingestion/application/IngestDocumentService.java`, change `chunkRepository.insert(chunk);` to `chunkRepository.insert(chunk, embeddingGenerator.modelName());`.

- [ ] **Step 5: Filter retrieval by status and model**

`MAIN/search/infrastructure/PgvectorSemanticSearchAdapter.java` — replace the fields, constructor, and `retrieve`:

```java
    private final DSLContext dsl;
    private final EmbeddingGenerator embeddingGenerator;
    private final String statusesLiteral;

    public PgvectorSemanticSearchAdapter(DataSource dataSource, EmbeddingGenerator embeddingGenerator,
                                         Set<String> statuses) {
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
        this.embeddingGenerator = embeddingGenerator;
        this.statusesLiteral = "{" + String.join(",", statuses) + "}";
    }

    @Override
    public List<RankedResult> retrieve(SearchQuery query, int topK) {
        Embedding queryEmbedding = embeddingGenerator.embedQuery(query.text());
        String vectorLiteral = toVectorLiteral(queryEmbedding);

        var rows = dsl.fetch(
                "SELECT c.id, c.document_id, c.content, c.embedding <=> ?::vector AS distance "
                        + "FROM knowledge.chunks c JOIN knowledge.documents d ON d.id = c.document_id "
                        + "WHERE c.embedding IS NOT NULL AND c.embedding_model = ? "
                        + "AND d.status = ANY(?::text[]) "
                        + "ORDER BY c.embedding <=> ?::vector ASC LIMIT ?",
                vectorLiteral, embeddingGenerator.modelName(), statusesLiteral, vectorLiteral, topK);

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
```

Add `import java.util.Set;`.

- [ ] **Step 6: Write the failing guard test**

`TEST/search/infrastructure/GuardedRetrieverTest.java`:

```java
package com.sheltr.search.infrastructure;

import com.sheltr.knowledge.domain.Chunk;
import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.ChunkRepository;
import com.sheltr.knowledge.domain.StaleEmbeddingsException;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardedRetrieverTest {

    private static final Retriever EMPTY = (query, topK) -> List.of();

    @Test
    void delegatesWhenNoStaleEmbeddingsExist() {
        var retriever = new GuardedRetriever(EMPTY, chunksWithStaleCount(0), "model-a");

        assertThat(retriever.retrieve(new SearchQuery("flood"), 5)).isEmpty();
    }

    @Test
    void refusesWhenChunksWereEmbeddedByAnotherModel() {
        var retriever = new GuardedRetriever(EMPTY, chunksWithStaleCount(12), "model-a");

        assertThatThrownBy(() -> retriever.retrieve(new SearchQuery("flood"), 5))
                .isInstanceOf(StaleEmbeddingsException.class)
                .hasMessageContaining("12")
                .hasMessageContaining("model-a")
                .hasMessageContaining("make seed-embeddings");
    }

    private static ChunkRepository chunksWithStaleCount(int staleCount) {
        return new ChunkRepository() {
            @Override
            public Optional<Chunk> findById(ChunkId id) { return Optional.empty(); }

            @Override
            public List<Chunk> findPendingEmbeddings(String embeddingModel) { return List.of(); }

            @Override
            public void save(Chunk chunk, String embeddingModel) { }

            @Override
            public void insert(Chunk chunk, String embeddingModel) { }

            @Override
            public int countEmbeddedWithOtherModel(String embeddingModel) { return staleCount; }
        };
    }
}
```

- [ ] **Step 7: Implement the guard**

`MAIN/knowledge/domain/StaleEmbeddingsException.java`:

```java
package com.sheltr.knowledge.domain;

public class StaleEmbeddingsException extends RuntimeException {
    public StaleEmbeddingsException(int staleCount, String activeModel) {
        super(staleCount + " chunk(s) were embedded by a model other than '" + activeModel
                + "'. Run `make seed-embeddings` to re-embed them before serving questions.");
    }
}
```

`MAIN/search/infrastructure/GuardedRetriever.java`:

```java
package com.sheltr.search.infrastructure;

import com.sheltr.knowledge.domain.ChunkRepository;
import com.sheltr.knowledge.domain.StaleEmbeddingsException;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;

import java.util.List;

/** Refuses to search while chunks embedded by another model exist, instead of degrading silently. */
public class GuardedRetriever implements Retriever {

    private final Retriever delegate;
    private final ChunkRepository chunkRepository;
    private final String embeddingModel;

    public GuardedRetriever(Retriever delegate, ChunkRepository chunkRepository, String embeddingModel) {
        this.delegate = delegate;
        this.chunkRepository = chunkRepository;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public List<RankedResult> retrieve(SearchQuery query, int topK) {
        int stale = chunkRepository.countEmbeddedWithOtherModel(embeddingModel);
        if (stale > 0) {
            throw new StaleEmbeddingsException(stale, embeddingModel);
        }
        return delegate.retrieve(query, topK);
    }
}
```

`MAIN/search/config/RetrieverConfig.java`:

```java
package com.sheltr.search.config;

import com.sheltr.knowledge.domain.ChunkRepository;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.infrastructure.GuardedRetriever;
import com.sheltr.search.infrastructure.PgvectorSemanticSearchAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Set;

@Configuration
public class RetrieverConfig {

    @Bean
    public Retriever retriever(DataSource dataSource, EmbeddingGenerator embeddingGenerator,
                               ChunkRepository chunkRepository,
                               @Value("${sheltr.search.statuses}") Set<String> statuses) {
        Retriever search = new PgvectorSemanticSearchAdapter(dataSource, embeddingGenerator, statuses);
        return new GuardedRetriever(search, chunkRepository, embeddingGenerator.modelName());
    }

    /** Says at startup what GuardedRetriever will refuse on, so the cause is in the log before a user asks. */
    @Bean
    public ApplicationRunner staleEmbeddingsWarning(ChunkRepository chunkRepository,
                                                    EmbeddingGenerator embeddingGenerator) {
        return args -> {
            int stale = chunkRepository.countEmbeddedWithOtherModel(embeddingGenerator.modelName());
            if (stale > 0) {
                LoggerFactory.getLogger(RetrieverConfig.class).error(
                        "{} chunk(s) were embedded by a model other than '{}'. Questions are refused "
                                + "until `make seed-embeddings` re-embeds them.",
                        stale, embeddingGenerator.modelName());
            }
        };
    }
}
```

Add the imports `org.slf4j.LoggerFactory` and `org.springframework.boot.ApplicationRunner`.

In `MAIN/ai/interfaces/ConversationController.java`, add after `handleGenerationFailed`:

```java
    @ExceptionHandler(com.sheltr.knowledge.domain.StaleEmbeddingsException.class)
    public ResponseEntity<Void> handleStaleEmbeddings() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
```

- [ ] **Step 8: Add configuration**

`RES/application.yml` — add at the end:

```yaml
sheltr:
  search:
    # Document statuses retrieval may return. SEED rows are test fixtures and are never listed here.
    statuses: ACTIVE
```

`RES/application-local.yml` — add under the existing top-level `sheltr:` key, as a sibling of `search:`:

```yaml
  embedding:
    # Recorded on every chunk. Change this name whenever the model tag or a prefix changes,
    # then run `make seed-embeddings`; otherwise old vectors are silently reused.
    model-name: nomic-embed-text:v1.5
```

`RES/application-gemini.yml` — add under the existing top-level `sheltr:` key, as a sibling of `search:`:

```yaml
  embedding:
    model-name: gemini-embedding-001
```

`apps/backend/src/test/resources/application.properties` — add:

```properties
sheltr.search.statuses=ACTIVE,SEED
```

- [ ] **Step 9: Fix the compile errors in existing tests**

Apply exactly these changes:

- `TEST/knowledge/application/ChunkEmbeddingBackfillServiceTest.java` — in `FakeChunkRepository`, change the three overridden signatures to `findPendingEmbeddings(String embeddingModel)`, `save(Chunk chunk, String embeddingModel)`, `insert(Chunk chunk, String embeddingModel)`, and add `@Override public int countEmbeddedWithOtherModel(String embeddingModel) { return 0; }`. In `FakeEmbeddingGenerator`, add `@Override public String modelName() { return "test-model"; }`.
- `TEST/ingestion/application/IngestDocumentServiceTest.java` — same four changes in `FakeChunkRepository`, same addition in `FakeEmbeddingGenerator`.
- `TEST/knowledge/infrastructure/JooqChunkRepositoryInsertTest.java` — `chunkRepository.insert(chunk)` becomes `chunkRepository.insert(chunk, "test-model")`.
- `TEST/knowledge/infrastructure/JooqChunkRepositoryIntegrationTest.java` — every `findPendingEmbeddings()` becomes `findPendingEmbeddings("test-model")`, every `save(x)` becomes `save(x, "test-model")`.
- `TEST/knowledge/infrastructure/SpringAiEmbeddingGeneratorAdapterTest.java` and `TEST/search/infrastructure/PgvectorSemanticSearchAdapterIntegrationTest.java` — `new SpringAiEmbeddingGeneratorAdapter(embeddingModel)` becomes `new SpringAiEmbeddingGeneratorAdapter(embeddingModel, "gemini-embedding-001")`.
- `TEST/search/infrastructure/PgvectorSemanticSearchAdapterIntegrationTest.java` — `new PgvectorSemanticSearchAdapter(dataSource, embeddingGenerator)` becomes `new PgvectorSemanticSearchAdapter(dataSource, embeddingGenerator, java.util.Set.of("ACTIVE", "SEED"))`. That test writes embeddings before searching; wherever it calls `save(chunk)` or the backfill service, the recorded model is now `gemini-embedding-001` through the adapter, so no further change is needed.

- [ ] **Step 10: Run the full suite**

Run: `$MVN test`
Expected: PASS. Tests gated on `GEMINI_API_KEY` or `OLLAMA_AVAILABLE` are skipped.

- [ ] **Step 11: Commit**

```bash
git add -A apps/backend/src
git commit -m "feat(search): record embedding model per chunk and filter retrieval by document status"
```

---

### Task 3: Ingestion with per-document metadata

**Files:**
- Modify: `MAIN/ingestion/domain/SourceMetadata.java`, `MAIN/ingestion/domain/SourceConnector.java`
- Rename: `MAIN/ingestion/infrastructure/FemaHttpConnector.java` → `HttpPdfConnector.java`; `TEST/ingestion/infrastructure/FemaHttpConnectorTest.java` → `HttpPdfConnectorTest.java`
- Modify: `MAIN/ingestion/config/SourceConnectorConfig.java`
- Modify: `MAIN/knowledge/domain/KnowledgeDocument.java`, `MAIN/knowledge/infrastructure/JooqKnowledgeDocumentRepository.java`
- Modify: `MAIN/ingestion/application/IngestDocumentService.java`
- Modify: `MAIN/ingestion/interfaces/TriggerIngestionRequestDto.java`, `IngestionController.java`
- Modify: `TEST/ingestion/application/IngestDocumentServiceTest.java`
- Create: `TEST/ingestion/interfaces/IngestionControllerValidationTest.java`
- Modify: `TEST/ingestion/interfaces/IngestionControllerIntegrationTest.java`, `IngestionToRagEndToEndTest.java`, `LocalIngestionToRagEndToEndTest.java`

**Interfaces:**
- Consumes: `knowledge.documents.license` (Task 1); `ChunkRepository.insert(Chunk, String)` and `EmbeddingGenerator.modelName()` (Task 2).
- Produces:
  - `record SourceMetadata(String title, String sourceAgency, Set<DisasterType> disasterTypes, LocalDate publishedDate, String license)` — throws `IllegalArgumentException` on a blank title, agency, or licence, an empty hazard set, or a null date.
  - `SourceConnector` with only `RawContent fetch(String sourceUri)`.
  - `IngestionRun IngestDocumentService.ingest(String sourceUri, SourceType sourceType, SourceMetadata metadata, String expectedChecksum)` — `expectedChecksum` may be null.
  - `KnowledgeDocument` 11-argument constructor ending `…, DocumentStatus status, String license)` and `Optional<String> license()`. The 10-argument constructor remains and sets no licence.
  - `record TriggerIngestionRequestDto(String sourceUri, String sourceType, String title, String sourceAgency, List<String> hazards, String publishedDate, String license, String checksum)`.

- [ ] **Step 1: Write the failing service tests**

In `TEST/ingestion/application/IngestDocumentServiceTest.java`:

Add imports `java.time.LocalDate` and `org.apache.pdfbox.pdmodel.PDDocument` (already present). Add this constant at the top of the class:

```java
    private static final SourceMetadata METADATA = new SourceMetadata(
            "Test Document", "Test Agency", Set.of(DisasterType.FLOOD),
            LocalDate.of(2021, 3, 15), "test-fixture-licence");
```

Change both existing calls from `service.ingest("https://example.org/test.pdf", SourceType.PDF)` to `service.ingest("https://example.org/test.pdf", SourceType.PDF, METADATA, null)`.

Delete the `metadata()` override from `FakeSourceConnector`.

Add these tests:

```java
    @Test
    void savesManifestMetadataOnTheKnowledgeDocument() throws IOException {
        FakeKnowledgeDocumentRepository knowledgeDocumentRepository = new FakeKnowledgeDocumentRepository();
        IngestDocumentService service = serviceWith(new FakeSourceConnector(buildTestPdf()),
                knowledgeDocumentRepository, new FakeSourceDocumentRepository());

        service.ingest("https://example.org/test.pdf", SourceType.PDF, METADATA, null);

        KnowledgeDocument saved = knowledgeDocumentRepository.saved.get(0);
        assertThat(saved.title()).isEqualTo("Test Document");
        assertThat(saved.sourceAgency()).isEqualTo("Test Agency");
        assertThat(saved.publishedDate()).isEqualTo(LocalDate.of(2021, 3, 15));
        assertThat(saved.license()).contains("test-fixture-licence");
    }

    @Test
    void failsTheRunWhenTheDownloadedChecksumDiffersFromTheApprovedOne() throws IOException {
        FakeKnowledgeDocumentRepository knowledgeDocumentRepository = new FakeKnowledgeDocumentRepository();
        IngestDocumentService service = serviceWith(new FakeSourceConnector(buildTestPdf()),
                knowledgeDocumentRepository, new FakeSourceDocumentRepository());

        IngestionRun run = service.ingest("https://example.org/test.pdf", SourceType.PDF, METADATA, "approved000");

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.errorMessage().orElseThrow()).contains("Checksum mismatch").contains("approved000");
        assertThat(knowledgeDocumentRepository.saved).isEmpty();
    }

    @Test
    void failsTheRunWhenThePdfHasNoExtractableText() throws IOException {
        FakeKnowledgeDocumentRepository knowledgeDocumentRepository = new FakeKnowledgeDocumentRepository();
        IngestDocumentService service = serviceWith(new FakeSourceConnector(buildBlankPdf()),
                knowledgeDocumentRepository, new FakeSourceDocumentRepository());

        IngestionRun run = service.ingest("https://example.org/scan.pdf", SourceType.PDF, METADATA, null);

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.errorMessage().orElseThrow()).contains("No extractable text");
        assertThat(knowledgeDocumentRepository.saved).isEmpty();
    }

    @Test
    void rejectsMetadataWithoutALicence() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SourceMetadata(
                        "Test Document", "Test Agency", Set.of(DisasterType.FLOOD), LocalDate.of(2021, 3, 15), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("license");
    }

    private static IngestDocumentService serviceWith(FakeSourceConnector connector,
                                                     FakeKnowledgeDocumentRepository knowledgeDocumentRepository,
                                                     FakeSourceDocumentRepository sourceDocumentRepository) {
        return new IngestDocumentService(connector, new PdfContentExtractor(), new FakeEmbeddingGenerator(),
                sourceDocumentRepository, new FakeIngestionRunRepository(), knowledgeDocumentRepository,
                new FakeChunkRepository());
    }

    private static byte[] buildBlankPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `$MVN test -Dtest=IngestDocumentServiceTest`
Expected: compilation FAIL. `SourceMetadata` has no five-argument constructor.

- [ ] **Step 3: Change the ingestion domain**

`MAIN/ingestion/domain/SourceMetadata.java`:

```java
package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.DisasterType;

import java.time.LocalDate;
import java.util.Set;

public record SourceMetadata(String title, String sourceAgency, Set<DisasterType> disasterTypes,
                             LocalDate publishedDate, String license) {
    public SourceMetadata {
        requireText(title, "title");
        requireText(sourceAgency, "sourceAgency");
        requireText(license, "license");
        if (disasterTypes == null || disasterTypes.isEmpty()) {
            throw new IllegalArgumentException("disasterTypes must not be empty");
        }
        if (publishedDate == null) {
            throw new IllegalArgumentException("publishedDate must not be null");
        }
        disasterTypes = Set.copyOf(disasterTypes);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
```

`MAIN/ingestion/domain/SourceConnector.java`:

```java
package com.sheltr.ingestion.domain;

public interface SourceConnector {
    RawContent fetch(String sourceUri);
}
```

- [ ] **Step 4: Rename the connector**

```bash
git mv apps/backend/src/main/java/com/sheltr/ingestion/infrastructure/FemaHttpConnector.java \
       apps/backend/src/main/java/com/sheltr/ingestion/infrastructure/HttpPdfConnector.java
git mv apps/backend/src/test/java/com/sheltr/ingestion/infrastructure/FemaHttpConnectorTest.java \
       apps/backend/src/test/java/com/sheltr/ingestion/infrastructure/HttpPdfConnectorTest.java
```

In `HttpPdfConnector.java`: rename the class to `HttpPdfConnector`, delete the `metadata()` method, and delete the now-unused imports `SourceMetadata`, `DisasterType`, and `java.util.Set`.

In `HttpPdfConnectorTest.java`: rename the class to `HttpPdfConnectorTest`, replace both `FemaHttpConnector` references with `HttpPdfConnector`, delete the test `metadataReturnsFemaAgencyAndTitle`, and delete the unused `SourceMetadata` import.

In `MAIN/ingestion/config/SourceConnectorConfig.java`: replace the import and `new FemaHttpConnector()` with `HttpPdfConnector`.

- [ ] **Step 5: Add the licence to the knowledge document**

In `MAIN/knowledge/domain/KnowledgeDocument.java`, add the field `private final String license;`, replace the constructor with these two, and add the accessor:

```java
    public KnowledgeDocument(DocumentId id, String title, String sourceAgency, String sourceUri,
                              Set<DisasterType> disasterTypes, LocalDate publishedDate, DocumentVersion version,
                              Checksum checksum, DocumentId supersedes, DocumentStatus status) {
        this(id, title, sourceAgency, sourceUri, disasterTypes, publishedDate, version, checksum, supersedes,
                status, null);
    }

    public KnowledgeDocument(DocumentId id, String title, String sourceAgency, String sourceUri,
                              Set<DisasterType> disasterTypes, LocalDate publishedDate, DocumentVersion version,
                              Checksum checksum, DocumentId supersedes, DocumentStatus status, String license) {
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
        this.license = license;
    }

    public Optional<String> license() { return Optional.ofNullable(license); }
```

In `MAIN/knowledge/infrastructure/JooqKnowledgeDocumentRepository.java`:

- In `findById`, change the select list to end `…, checksum, supersedes_id, status, license FROM knowledge.documents WHERE id = ?`.
- Replace the `dsl.execute` in `save` with:

```java
        dsl.execute(
                "INSERT INTO knowledge.documents (id, title, source_agency, source_uri, source_type, "
                        + "disaster_types, published_date, version, supersedes_id, status, checksum, license) "
                        + "VALUES (?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?, ?, ?)",
                document.id().value(), document.title(), document.sourceAgency(), document.sourceUri(),
                "PDF", disasterTypesLiteral, document.publishedDate(), document.version().value(),
                document.supersedes().map(DocumentId::value).orElse(null),
                document.status().name(), document.checksum().hex(), document.license().orElse(null));
```

- In `mapRow`, add `, record.get("license", String.class)` as the last constructor argument, after the `DocumentStatus.valueOf(...)` argument.

- [ ] **Step 6: Rewrite the ingestion service**

Replace the `ingest` method of `MAIN/ingestion/application/IngestDocumentService.java`, and delete the `java.time.LocalDate` import:

```java
    public IngestionRun ingest(String sourceUri, SourceType sourceType, SourceMetadata metadata,
                               String expectedChecksum) {
        DocumentId documentId = new DocumentId(UUID.randomUUID());
        SourceDocument sourceDocument = new SourceDocument(documentId, sourceUri, sourceType);
        // Persist DISCOVERED source document first so ingestion_runs FK is satisfiable.
        sourceDocumentRepository.save(sourceDocument);

        IngestionRun run = new IngestionRun(new IngestionRunId(UUID.randomUUID()), documentId);
        ingestionRunRepository.save(run);

        try {
            if (sourceType != SourceType.PDF) {
                throw new UnsupportedOperationException(
                        "Only PDF ingestion is supported in this slice, got " + sourceType);
            }

            RawContent raw = connector.fetch(sourceUri);
            if (expectedChecksum != null && !expectedChecksum.equalsIgnoreCase(raw.checksum().hex())) {
                throw new IllegalStateException("Checksum mismatch for " + sourceUri + ": approved "
                        + expectedChecksum + " but downloaded " + raw.checksum().hex()
                        + ". The document changed since approval and needs re-approval.");
            }
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
            if (chunkDrafts.isEmpty()) {
                throw new IllegalStateException("No extractable text in " + sourceUri
                        + ". Scanned or image-only PDFs are not supported.");
            }
            sourceDocument.markChunked();
            sourceDocumentRepository.save(sourceDocument);

            KnowledgeDocument knowledgeDocument = new KnowledgeDocument(
                    documentId, metadata.title(), metadata.sourceAgency(), sourceUri, metadata.disasterTypes(),
                    metadata.publishedDate(), new DocumentVersion(1), raw.checksum(), null,
                    DocumentStatus.ACTIVE, metadata.license());
            knowledgeDocumentRepository.save(knowledgeDocument);

            int chunkIndex = 0;
            for (ChunkDraft draft : chunkDrafts) {
                Embedding embedding = embeddingGenerator.embed(draft.content());
                Chunk chunk = new Chunk(
                        new ChunkId(UUID.randomUUID()), documentId, new ChunkContent(draft.content()), embedding,
                        draft.pageNumber(), chunkIndex++, new ChunkMetadata(null, metadata.disasterTypes()),
                        raw.checksum());
                chunkRepository.insert(chunk, embeddingGenerator.modelName());
            }

            sourceDocument.markIndexed();
            sourceDocumentRepository.save(sourceDocument);

            run.markSucceeded();
            ingestionRunRepository.save(run);
        } catch (Exception e) {
            sourceDocument.markFailed();
            sourceDocumentRepository.save(sourceDocument);
            run.markFailed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            ingestionRunRepository.save(run);
        }

        return run;
    }
```

- [ ] **Step 7: Run the service tests**

Run: `$MVN test -Dtest=IngestDocumentServiceTest`
Expected: PASS, 6 tests. If `failsTheRunWhenThePdfHasNoExtractableText` fails because `Chunker.chunk` throws on blank pages instead of returning an empty list, that still marks the run failed; in that case change the assertion to `.isNotBlank()` and keep the `chunkDrafts.isEmpty()` check.

- [ ] **Step 8: Write the failing controller test**

`TEST/ingestion/interfaces/IngestionControllerValidationTest.java`:

```java
package com.sheltr.ingestion.interfaces;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionControllerValidationTest {

    // The service is never reached for an invalid request, so null collaborators are safe here.
    private final IngestionController controller = new IngestionController(null, null);

    @Test
    void rejectsARequestWithoutALicence() {
        var response = controller.trigger(new TriggerIngestionRequestDto(
                "https://example.org/a.pdf", "PDF", "Title", "Agency", List.of("FLOOD"), "2021-03-15", "", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsAnUnknownHazard() {
        var response = controller.trigger(new TriggerIngestionRequestDto(
                "https://example.org/a.pdf", "PDF", "Title", "Agency", List.of("METEOR"), "2021-03-15",
                "test-fixture-licence", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsAMalformedDate() {
        var response = controller.trigger(new TriggerIngestionRequestDto(
                "https://example.org/a.pdf", "PDF", "Title", "Agency", List.of("FLOOD"), "15 March 2021",
                "test-fixture-licence", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
```

- [ ] **Step 9: Implement the request DTO and controller**

`MAIN/ingestion/interfaces/TriggerIngestionRequestDto.java`:

```java
package com.sheltr.ingestion.interfaces;

import java.util.List;

public record TriggerIngestionRequestDto(String sourceUri, String sourceType, String title, String sourceAgency,
                                         List<String> hazards, String publishedDate, String license,
                                         String checksum) {
}
```

In `MAIN/ingestion/interfaces/IngestionController.java`, replace `trigger` and add the imports `com.sheltr.ingestion.domain.SourceMetadata`, `com.sheltr.knowledge.domain.DisasterType`, `java.time.LocalDate`, `java.time.format.DateTimeParseException`, `java.util.Set`, `java.util.stream.Collectors`:

```java
    @PostMapping
    public ResponseEntity<IngestionRunDto> trigger(@RequestBody TriggerIngestionRequestDto request) {
        SourceType sourceType;
        SourceMetadata metadata;
        try {
            sourceType = SourceType.valueOf(request.sourceType());
            Set<DisasterType> hazards = request.hazards() == null ? Set.of()
                    : request.hazards().stream().map(DisasterType::valueOf).collect(Collectors.toSet());
            LocalDate publishedDate = request.publishedDate() == null ? null
                    : LocalDate.parse(request.publishedDate());
            metadata = new SourceMetadata(request.title(), request.sourceAgency(), hazards, publishedDate,
                    request.license());
        } catch (IllegalArgumentException | NullPointerException | DateTimeParseException e) {
            return ResponseEntity.badRequest().build();
        }
        IngestionRun run = ingestDocumentService.ingest(request.sourceUri(), sourceType, metadata,
                request.checksum());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toDto(run));
    }
```

- [ ] **Step 10: Update the three gated tests that build the request**

In `TEST/ingestion/interfaces/IngestionControllerIntegrationTest.java`, `IngestionToRagEndToEndTest.java`, and `LocalIngestionToRagEndToEndTest.java`, replace `new TriggerIngestionRequestDto(CAREGIVERS_GUIDE_URL, "PDF")` with:

```java
new TriggerIngestionRequestDto(CAREGIVERS_GUIDE_URL, "PDF", "Disaster Preparedness Guide for Caregivers",
        "FEMA", java.util.List.of("FLOOD"), "2024-02-01", "test-fixture-licence", null)
```

- [ ] **Step 11: Run the full suite**

Run: `$MVN test`
Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add -A apps/backend/src
git commit -m "feat(ingestion): take title, agency, hazards, date, and licence per document"
```

---

### Task 4: Corpus manifest and loader

**Files:**
- Create: `corpus/manifest.yaml`
- Create: `MAIN/ingestion/application/CorpusManifest.java`, `IngestCorpusService.java`
- Create: `MAIN/ingestion/domain/CorpusCatalog.java`, `MAIN/ingestion/infrastructure/JooqCorpusCatalog.java`
- Create: `MAIN/ingestion/config/IngestCorpusConfig.java`, `IngestCorpusRunner.java`
- Create: `TEST/ingestion/application/CorpusManifestTest.java`, `IngestCorpusServiceTest.java`
- Create: `TEST/ingestion/infrastructure/JooqCorpusCatalogIntegrationTest.java`
- Modify: `Makefile`

**Interfaces:**
- Consumes: `IngestDocumentService.ingest(String, SourceType, SourceMetadata, String)` (Task 3); `knowledge.related_hazards` (Task 1).
- Produces:
  - `CorpusManifest.parse(InputStream)` → `CorpusManifest(List<Document> documents, List<RelatedHazard> relatedHazards)`, with `Document(String sourceUri, SourceMetadata metadata, String checksum)` and `RelatedHazard(DisasterType hazard, DisasterType relatedHazard, String sourceUri, String note)`.
  - `CorpusCatalog`: `Optional<DocumentId> findActiveDocumentId(String sourceUri)`, `void upsertRelatedHazard(DisasterType hazard, DisasterType relatedHazard, DocumentId source, String note)`.
  - `IngestCorpusService.ingest(CorpusManifest)` → `Summary(List<String> ingested, List<String> skipped, Map<String, String> failed, int relatedHazards)`.
  - `make ingest-corpus`.

- [ ] **Step 1: Write the failing manifest tests**

`TEST/ingestion/application/CorpusManifestTest.java`:

```java
package com.sheltr.ingestion.application;

import com.sheltr.knowledge.domain.DisasterType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorpusManifestTest {

    private static final String VALID = """
            documents:
              - source_uri: https://example.org/flood.pdf
                title: Flood Guide
                source_agency: Example Agency
                hazards: [FLOOD, LANDSLIDE]
                published_date: 2021-03-15
                license: Example licence, https://example.org/terms
                checksum: abc123
              - source_uri: https://example.org/quake.pdf
                title: Earthquake Guide
                source_agency: Example Agency
                hazards: [EARTHQUAKE]
                published_date: "2019-11-02"
                license: Example licence, https://example.org/terms
                checksum: def456
            related_hazards:
              - hazard: EARTHQUAKE
                related_hazard: LANDSLIDE
                source_uri: https://example.org/quake.pdf
                note: Stated on page 4
            """;

    @Test
    void parsesDocumentsWithBothQuotedAndUnquotedDates() {
        CorpusManifest manifest = parse(VALID);

        assertThat(manifest.documents()).hasSize(2);
        CorpusManifest.Document flood = manifest.documents().get(0);
        assertThat(flood.sourceUri()).isEqualTo("https://example.org/flood.pdf");
        assertThat(flood.checksum()).isEqualTo("abc123");
        assertThat(flood.metadata().disasterTypes())
                .containsExactlyInAnyOrder(DisasterType.FLOOD, DisasterType.LANDSLIDE);
        assertThat(flood.metadata().publishedDate()).isEqualTo(LocalDate.of(2021, 3, 15));
        assertThat(manifest.documents().get(1).metadata().publishedDate()).isEqualTo(LocalDate.of(2019, 11, 2));
    }

    @Test
    void parsesRelatedHazards() {
        CorpusManifest.RelatedHazard related = parse(VALID).relatedHazards().get(0);

        assertThat(related.hazard()).isEqualTo(DisasterType.EARTHQUAKE);
        assertThat(related.relatedHazard()).isEqualTo(DisasterType.LANDSLIDE);
        assertThat(related.sourceUri()).isEqualTo("https://example.org/quake.pdf");
    }

    @Test
    void acceptsAnEmptyManifest() {
        CorpusManifest manifest = parse("documents: []\nrelated_hazards: []\n");

        assertThat(manifest.documents()).isEmpty();
        assertThat(manifest.relatedHazards()).isEmpty();
    }

    @Test
    void rejectsADuplicateSourceUriAndNamesIt() {
        String duplicated = VALID.replace("https://example.org/quake.pdf\n    title",
                "https://example.org/flood.pdf\n    title");

        assertThatThrownBy(() -> parse(duplicated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate source_uri")
                .hasMessageContaining("https://example.org/flood.pdf");
    }

    @Test
    void rejectsAnUnknownHazardAndNamesTheEntry() {
        assertThatThrownBy(() -> parse(VALID.replace("[EARTHQUAKE]", "[METEOR]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("METEOR")
                .hasMessageContaining("https://example.org/quake.pdf");
    }

    @Test
    void rejectsAnEntryWithoutALicence() {
        String withoutLicence = VALID.replaceFirst(
                "    license: Example licence, https://example.org/terms\n", "");

        assertThatThrownBy(() -> parse(withoutLicence))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("license")
                .hasMessageContaining("https://example.org/flood.pdf");
    }

    @Test
    void rejectsARelatedHazardWhoseSourceIsNotInTheManifest() {
        String dangling = VALID.replace(
                "    source_uri: https://example.org/quake.pdf\n    note", "    source_uri: https://example.org/missing.pdf\n    note");

        assertThatThrownBy(() -> parse(dangling))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https://example.org/missing.pdf");
    }

    private static CorpusManifest parse(String yaml) {
        return CorpusManifest.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }
}
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `$MVN test -Dtest=CorpusManifestTest`
Expected: compilation FAIL, `CorpusManifest` does not exist.

- [ ] **Step 3: Implement the manifest parser**

`MAIN/ingestion/application/CorpusManifest.java`:

```java
package com.sheltr.ingestion.application;

import com.sheltr.ingestion.domain.SourceMetadata;
import com.sheltr.knowledge.domain.DisasterType;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The approved corpus, as written in corpus/manifest.yaml. */
public record CorpusManifest(List<Document> documents, List<RelatedHazard> relatedHazards) {

    public record Document(String sourceUri, SourceMetadata metadata, String checksum) {
    }

    public record RelatedHazard(DisasterType hazard, DisasterType relatedHazard, String sourceUri, String note) {
    }

    public CorpusManifest {
        documents = List.copyOf(documents);
        relatedHazards = List.copyOf(relatedHazards);
    }

    public static CorpusManifest parse(InputStream in) {
        Object loaded = new Yaml().load(in);
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("Manifest must be a YAML mapping with a 'documents' list");
        }

        List<Document> documents = new ArrayList<>();
        Set<String> uris = new HashSet<>();
        for (Map<?, ?> entry : mappings(root.get("documents"), "documents")) {
            String uri = text(entry, "source_uri", "(entry without source_uri)");
            if (!uris.add(uri)) {
                throw new IllegalArgumentException("Duplicate source_uri in manifest: " + uri);
            }
            SourceMetadata metadata;
            try {
                metadata = new SourceMetadata(text(entry, "title", uri), text(entry, "source_agency", uri),
                        hazards(entry.get("hazards"), uri), date(entry.get("published_date"), uri),
                        text(entry, "license", uri));
            } catch (IllegalArgumentException e) {
                throw e.getMessage().contains(uri) ? e
                        : new IllegalArgumentException("Manifest entry " + uri + ": " + e.getMessage(), e);
            }
            documents.add(new Document(uri, metadata, text(entry, "checksum", uri)));
        }

        List<RelatedHazard> relatedHazards = new ArrayList<>();
        for (Map<?, ?> entry : mappings(root.get("related_hazards"), "related_hazards")) {
            String uri = text(entry, "source_uri", "(related hazard without source_uri)");
            if (!uris.contains(uri)) {
                throw new IllegalArgumentException(
                        "Related hazard cites " + uri + ", which is not a document in the manifest");
            }
            Object note = entry.get("note");
            relatedHazards.add(new RelatedHazard(hazard(text(entry, "hazard", uri), uri),
                    hazard(text(entry, "related_hazard", uri), uri), uri, note == null ? null : note.toString()));
        }
        return new CorpusManifest(documents, relatedHazards);
    }

    private static List<Map<?, ?>> mappings(Object value, String key) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("'" + key + "' must be a list");
        }
        List<Map<?, ?>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Every item under '" + key + "' must be a mapping");
            }
            result.add(map);
        }
        return result;
    }

    private static String text(Map<?, ?> entry, String key, String uri) {
        Object value = entry.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Manifest entry " + uri + " is missing '" + key + "'");
        }
        return value.toString().trim();
    }

    private static Set<DisasterType> hazards(Object value, String uri) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("Manifest entry " + uri + " is missing 'hazards'");
        }
        Set<DisasterType> hazards = EnumSet.noneOf(DisasterType.class);
        for (Object item : list) {
            hazards.add(hazard(String.valueOf(item), uri));
        }
        return hazards;
    }

    private static DisasterType hazard(String name, String uri) {
        try {
            return DisasterType.valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Manifest entry " + uri + " has unknown hazard '" + name + "'");
        }
    }

    // YAML reads an unquoted 2021-03-15 as a date object and a quoted one as text; accept both.
    private static LocalDate date(Object value, String uri) {
        if (value instanceof Date date) {
            return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (value instanceof String string) {
            try {
                return LocalDate.parse(string.trim());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "Manifest entry " + uri + " has published_date '" + string + "', expected YYYY-MM-DD");
            }
        }
        throw new IllegalArgumentException("Manifest entry " + uri + " is missing 'published_date'");
    }
}
```

- [ ] **Step 4: Run the manifest tests**

Run: `$MVN test -Dtest=CorpusManifestTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Write the failing loader test**

`TEST/ingestion/application/IngestCorpusServiceTest.java`:

```java
package com.sheltr.ingestion.application;

import com.sheltr.ingestion.domain.CorpusCatalog;
import com.sheltr.ingestion.domain.IngestionRun;
import com.sheltr.ingestion.domain.IngestionRunId;
import com.sheltr.ingestion.domain.SourceMetadata;
import com.sheltr.knowledge.domain.DisasterType;
import com.sheltr.knowledge.domain.DocumentId;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IngestCorpusServiceTest {

    private static final String OLD = "https://example.org/old.pdf";
    private static final String NEW = "https://example.org/new.pdf";
    private static final String BAD = "https://example.org/bad.pdf";

    @Test
    void ingestsNewDocumentsSkipsExistingOnesAndReportsFailures() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.active.put(OLD, new DocumentId(UUID.randomUUID()));
        List<String> ingestedUris = new ArrayList<>();
        IngestCorpusService service = new IngestCorpusService((uri, type, metadata, checksum) -> {
            ingestedUris.add(uri + "#" + checksum);
            IngestionRun run = new IngestionRun(new IngestionRunId(UUID.randomUUID()),
                    new DocumentId(UUID.randomUUID()));
            if (uri.equals(BAD)) {
                run.markFailed("Checksum mismatch");
            } else {
                run.markSucceeded();
                catalog.active.put(uri, new DocumentId(UUID.randomUUID()));
            }
            return run;
        }, catalog);

        IngestCorpusService.Summary summary = service.ingest(new CorpusManifest(
                List.of(document(OLD), document(NEW), document(BAD)), List.of()));

        assertThat(ingestedUris).containsExactly(NEW + "#sum", BAD + "#sum");
        assertThat(summary.ingested()).containsExactly(NEW);
        assertThat(summary.skipped()).containsExactly(OLD);
        assertThat(summary.failed()).containsEntry(BAD, "Checksum mismatch");
    }

    @Test
    void storesRelatedHazardsOnlyWhenTheirSourceDocumentIsInTheCorpus() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.active.put(OLD, new DocumentId(UUID.randomUUID()));
        IngestCorpusService service = new IngestCorpusService((uri, type, metadata, checksum) -> {
            IngestionRun run = new IngestionRun(new IngestionRunId(UUID.randomUUID()),
                    new DocumentId(UUID.randomUUID()));
            run.markFailed("download failed");
            return run;
        }, catalog);

        IngestCorpusService.Summary summary = service.ingest(new CorpusManifest(
                List.of(document(OLD), document(BAD)),
                List.of(new CorpusManifest.RelatedHazard(DisasterType.EARTHQUAKE, DisasterType.LANDSLIDE, OLD, null),
                        new CorpusManifest.RelatedHazard(DisasterType.FLOOD, DisasterType.LANDSLIDE, BAD, null))));

        assertThat(catalog.related).containsExactly("EARTHQUAKE->LANDSLIDE");
        assertThat(summary.relatedHazards()).isEqualTo(1);
        assertThat(summary.failed()).containsKey("related hazard FLOOD->LANDSLIDE");
    }

    private static CorpusManifest.Document document(String uri) {
        return new CorpusManifest.Document(uri, new SourceMetadata("Title", "Agency",
                Set.of(DisasterType.FLOOD), LocalDate.of(2021, 3, 15), "test-fixture-licence"), "sum");
    }

    private static class FakeCatalog implements CorpusCatalog {
        final Map<String, DocumentId> active = new HashMap<>();
        final List<String> related = new ArrayList<>();

        @Override
        public Optional<DocumentId> findActiveDocumentId(String sourceUri) {
            return Optional.ofNullable(active.get(sourceUri));
        }

        @Override
        public void upsertRelatedHazard(DisasterType hazard, DisasterType relatedHazard, DocumentId source,
                                        String note) {
            related.add(hazard + "->" + relatedHazard);
        }
    }
}
```

- [ ] **Step 6: Implement the port and the loader**

`MAIN/ingestion/domain/CorpusCatalog.java`:

```java
package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.DisasterType;
import com.sheltr.knowledge.domain.DocumentId;

import java.util.Optional;

/** What the corpus loader needs to know about, and record in, the knowledge store. */
public interface CorpusCatalog {
    Optional<DocumentId> findActiveDocumentId(String sourceUri);

    void upsertRelatedHazard(DisasterType hazard, DisasterType relatedHazard, DocumentId source, String note);
}
```

`MAIN/ingestion/application/IngestCorpusService.java`:

```java
package com.sheltr.ingestion.application;

import com.sheltr.ingestion.domain.CorpusCatalog;
import com.sheltr.ingestion.domain.IngestionRun;
import com.sheltr.ingestion.domain.RunStatus;
import com.sheltr.ingestion.domain.SourceMetadata;
import com.sheltr.ingestion.domain.SourceType;
import com.sheltr.knowledge.domain.DocumentId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class IngestCorpusService {

    @FunctionalInterface
    public interface DocumentIngestor {
        IngestionRun ingest(String sourceUri, SourceType sourceType, SourceMetadata metadata, String checksum);
    }

    public record Summary(List<String> ingested, List<String> skipped, Map<String, String> failed,
                          int relatedHazards) {
    }

    private final DocumentIngestor ingestor;
    private final CorpusCatalog catalog;

    public IngestCorpusService(DocumentIngestor ingestor, CorpusCatalog catalog) {
        this.ingestor = ingestor;
        this.catalog = catalog;
    }

    public Summary ingest(CorpusManifest manifest) {
        List<String> ingested = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();

        for (CorpusManifest.Document document : manifest.documents()) {
            if (catalog.findActiveDocumentId(document.sourceUri()).isPresent()) {
                skipped.add(document.sourceUri());
                continue;
            }
            IngestionRun run = ingestor.ingest(document.sourceUri(), SourceType.PDF, document.metadata(),
                    document.checksum());
            if (run.status() == RunStatus.SUCCEEDED) {
                ingested.add(document.sourceUri());
            } else {
                failed.put(document.sourceUri(), run.errorMessage().orElse("unknown error"));
            }
        }

        int relatedHazards = 0;
        for (CorpusManifest.RelatedHazard related : manifest.relatedHazards()) {
            Optional<DocumentId> source = catalog.findActiveDocumentId(related.sourceUri());
            if (source.isEmpty()) {
                failed.put("related hazard " + related.hazard() + "->" + related.relatedHazard(),
                        "source document " + related.sourceUri() + " is not in the corpus");
                continue;
            }
            catalog.upsertRelatedHazard(related.hazard(), related.relatedHazard(), source.get(), related.note());
            relatedHazards++;
        }
        return new Summary(ingested, skipped, failed, relatedHazards);
    }
}
```

- [ ] **Step 7: Run the loader test**

Run: `$MVN test -Dtest=IngestCorpusServiceTest`
Expected: PASS, 2 tests.

- [ ] **Step 8: Write the failing catalog integration test**

`TEST/ingestion/infrastructure/JooqCorpusCatalogIntegrationTest.java`:

```java
package com.sheltr.ingestion.infrastructure;

import com.sheltr.knowledge.domain.*;
import com.sheltr.knowledge.infrastructure.JooqKnowledgeDocumentRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JooqCorpusCatalogIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void findsOnlyActiveDocumentsAndUpsertsRelatedHazards() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        DocumentId documentId = new DocumentId(UUID.randomUUID());
        new JooqKnowledgeDocumentRepository(dataSource).save(new KnowledgeDocument(
                documentId, "Quake Guide", "Agency", "https://example.org/quake.pdf",
                Set.of(DisasterType.EARTHQUAKE), LocalDate.of(2019, 11, 2), new DocumentVersion(1),
                new Checksum("abc"), null, DocumentStatus.ACTIVE, "test-fixture-licence"));
        JooqCorpusCatalog catalog = new JooqCorpusCatalog(dataSource);

        assertThat(catalog.findActiveDocumentId("https://example.org/quake.pdf")).contains(documentId);
        assertThat(catalog.findActiveDocumentId("https://example.org/unknown.pdf")).isEmpty();
        // Seed rows have status SEED and must never count as corpus documents.
        assertThat(catalog.findActiveDocumentId("https://www.fema.gov/earthquakes")).isEmpty();

        catalog.upsertRelatedHazard(DisasterType.EARTHQUAKE, DisasterType.LANDSLIDE, documentId, "first");
        catalog.upsertRelatedHazard(DisasterType.EARTHQUAKE, DisasterType.LANDSLIDE, documentId, "second");

        try (Connection conn = dataSource.getConnection();
             ResultSet rs = conn.createStatement().executeQuery(
                     "SELECT count(*), max(note) FROM knowledge.related_hazards")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
            assertThat(rs.getString(2)).isEqualTo("second");
        }
    }
}
```

- [ ] **Step 9: Implement the catalog, the configuration, and the runner**

`MAIN/ingestion/infrastructure/JooqCorpusCatalog.java`:

```java
package com.sheltr.ingestion.infrastructure;

import com.sheltr.ingestion.domain.CorpusCatalog;
import com.sheltr.knowledge.domain.DisasterType;
import com.sheltr.knowledge.domain.DocumentId;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;

public class JooqCorpusCatalog implements CorpusCatalog {

    private final DSLContext dsl;

    public JooqCorpusCatalog(DataSource dataSource) {
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    @Override
    public Optional<DocumentId> findActiveDocumentId(String sourceUri) {
        Record record = dsl.fetchOne(
                "SELECT id FROM knowledge.documents WHERE source_uri = ? AND status = 'ACTIVE' LIMIT 1",
                sourceUri);
        return Optional.ofNullable(record).map(r -> new DocumentId((UUID) r.get("id")));
    }

    @Override
    public void upsertRelatedHazard(DisasterType hazard, DisasterType relatedHazard, DocumentId source,
                                    String note) {
        dsl.execute(
                "INSERT INTO knowledge.related_hazards (hazard, related_hazard, source_document_id, note) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT (hazard, related_hazard) DO UPDATE "
                        + "SET source_document_id = EXCLUDED.source_document_id, note = EXCLUDED.note",
                hazard.name(), relatedHazard.name(), source.value(), note);
    }
}
```

`MAIN/ingestion/config/IngestCorpusConfig.java`:

```java
package com.sheltr.ingestion.config;

import com.sheltr.ingestion.application.IngestCorpusService;
import com.sheltr.ingestion.application.IngestDocumentService;
import com.sheltr.ingestion.domain.CorpusCatalog;
import com.sheltr.ingestion.infrastructure.JooqCorpusCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class IngestCorpusConfig {

    @Bean
    public CorpusCatalog corpusCatalog(DataSource dataSource) {
        return new JooqCorpusCatalog(dataSource);
    }

    @Bean
    public IngestCorpusService ingestCorpusService(IngestDocumentService ingestDocumentService,
                                                   CorpusCatalog corpusCatalog) {
        return new IngestCorpusService(ingestDocumentService::ingest, corpusCatalog);
    }
}
```

`MAIN/ingestion/config/IngestCorpusRunner.java`:

```java
package com.sheltr.ingestion.config;

import com.sheltr.ingestion.application.CorpusManifest;
import com.sheltr.ingestion.application.IngestCorpusService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class IngestCorpusRunner implements ApplicationRunner {

    private final IngestCorpusService ingestCorpusService;

    public IngestCorpusRunner(IngestCorpusService ingestCorpusService) {
        this.ingestCorpusService = ingestCorpusService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.getNonOptionArgs().contains("ingest-corpus")) {
            return;
        }
        String path = args.containsOption("manifest")
                ? args.getOptionValues("manifest").get(0) : "../../corpus/manifest.yaml";
        CorpusManifest manifest;
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            manifest = CorpusManifest.parse(in);
        }
        IngestCorpusService.Summary summary = ingestCorpusService.ingest(manifest);
        System.out.println("Ingested: " + summary.ingested().size()
                + ", skipped (already in corpus): " + summary.skipped().size()
                + ", related hazards stored: " + summary.relatedHazards()
                + ", failed: " + summary.failed().size());
        summary.failed().forEach((what, why) -> System.out.println("FAILED " + what + ": " + why));
        System.exit(summary.failed().isEmpty() ? 0 : 1);
    }
}
```

- [ ] **Step 10: Add the manifest file and the make target**

`corpus/manifest.yaml`:

```yaml
# The approved Sheltr corpus. Every entry is an official English document of global
# guidance whose reuse terms allow storing and quoting it with attribution.
# Load with `make ingest-corpus`. See docs/adr/0013-corpus-manifest.md.
#
# documents:
#   - source_uri: direct URL of the PDF
#     title: title as printed on the document
#     source_agency: publishing organisation
#     hazards: [FLOOD, LANDSLIDE]
#     published_date: 2021-03-15
#     license: reuse terms, plus the URL where they are stated
#     checksum: SHA-256 of the file when it was approved
#
# related_hazards:
#   - hazard: EARTHQUAKE
#     related_hazard: LANDSLIDE
#     source_uri: a document above that states the relationship
#     note: where in the document it is stated
documents: []
related_hazards: []
```

In `Makefile`, add `ingest-corpus` to the `.PHONY` line and append:

```make
ingest-corpus:
	cd apps/backend && mvn -q spring-boot:run -Dspring-boot.run.arguments=ingest-corpus
```

- [ ] **Step 11: Run the tests**

Run: `$MVN test -Dtest='CorpusManifestTest,IngestCorpusServiceTest,JooqCorpusCatalogIntegrationTest'`
Expected: PASS, 10 tests.

- [ ] **Step 12: Commit**

```bash
git add corpus/manifest.yaml Makefile apps/backend/src
git commit -m "feat(ingestion): load the corpus from an approved manifest"
```

---

### Task 5: Curate and ingest the baseline corpus

This task is content work. The maintainer approves every entry; nobody writes guidance text.

**Files:**
- Modify: `corpus/manifest.yaml`

**Interfaces:**
- Consumes: `make ingest-corpus` (Task 4).
- Produces: 20–30 `ACTIVE` documents in `knowledge.documents`, covering at least 10 of the 12 hazard types, and the related-hazard rows the documents support.

- [ ] **Step 1: List candidate documents**

Search the publications pages of international bodies whose guidance is written for general use: the International Federation of Red Cross and Red Crescent Societies, the World Health Organization, the UN Office for Disaster Risk Reduction, UNICEF, and the UN Office for the Coordination of Humanitarian Affairs. For each candidate, check the four selection rules in the spec, section 3.2: international or general-use publisher, English and written for the public or volunteers, not tied to one country, reuse terms that allow storing and quoting with attribution.

Record candidates in a scratch list with: URL of the PDF, title, publisher, hazards covered, date printed on the document, and the URL of the page that states the reuse terms.

- [ ] **Step 2: Check each candidate is a text PDF and compute its checksum**

For each candidate URL:

```bash
curl -L -o /tmp/candidate.pdf "<url>"
file /tmp/candidate.pdf            # must say "PDF document"
pdftotext /tmp/candidate.pdf - | head -40   # must print readable text, not blank lines
shasum -a 256 /tmp/candidate.pdf   # the checksum for the manifest
```

Drop any candidate where `pdftotext` prints no readable text: it is a scan and ingestion will reject it. If `pdftotext` is missing, install it with `brew install poppler`.

- [ ] **Step 3: Get maintainer approval**

Show the maintainer the candidate list with the reuse terms quoted for each. Add an entry to `corpus/manifest.yaml` only for candidates the maintainer approves. Leave out any document whose terms are unclear.

- [ ] **Step 4: Add related hazards**

For each pair of hazards where an approved document states that one commonly triggers the other, add a `related_hazards` entry naming that document and the page. Do not add a pair that no approved document states.

- [ ] **Step 5: Ingest**

```bash
make up
make migrate
make ingest-corpus
```

Expected: `Ingested: N, skipped (already in corpus): 0, related hazards stored: M, failed: 0` with N between 20 and 30. Fix or remove any entry reported as `FAILED`, then rerun; already-ingested entries are skipped.

- [ ] **Step 6: Verify coverage**

```bash
psql "postgresql://sheltr:sheltr@localhost:5432/sheltr" -c \
  "SELECT h, count(*) FROM knowledge.documents, unnest(disaster_types) AS h WHERE status = 'ACTIVE' GROUP BY h ORDER BY h;"
psql "postgresql://sheltr:sheltr@localhost:5432/sheltr" -c \
  "SELECT count(*) AS chunks, count(DISTINCT embedding_model) AS models FROM knowledge.chunks c JOIN knowledge.documents d ON d.id = c.document_id WHERE d.status = 'ACTIVE';"
```

Expected: at least 10 hazard types listed, and `models` equal to 1. If a hazard type has no document, tell the maintainer which one; eval questions for it will be `UNCOVERED`.

- [ ] **Step 7: Commit**

```bash
git add corpus/manifest.yaml
git commit -m "feat(corpus): add approved baseline corpus manifest"
```

---

# Phase B — Retrieval

### Task 6: Hybrid search with rank fusion and hazard boost

**Files:**
- Modify: `MAIN/search/domain/SearchQuery.java`
- Create: `MAIN/search/domain/RankFusion.java`, `HazardBoost.java`, `HybridRanker.java`, `RelatedHazards.java`
- Create: `MAIN/search/infrastructure/HybridSearchAdapter.java`, `JooqRelatedHazards.java`
- Modify: `MAIN/search/config/RetrieverConfig.java`, `RES/application.yml`
- Create: `TEST/search/domain/RankFusionTest.java`, `HazardBoostTest.java`, `HybridRankerTest.java`
- Create: `TEST/search/infrastructure/HybridSearchAdapterIntegrationTest.java`

**Interfaces:**
- Consumes: generated `content_tsv` and `related_hazards` (Task 1); `EmbeddingGenerator.embedQuery`/`modelName`, `GuardedRetriever`, `sheltr.search.statuses` (Task 2).
- Produces:
  - `record SearchQuery(String text, Set<DisasterType> activeHazards)` plus the one-argument constructor `SearchQuery(String text)`.
  - `RankFusion.fuse(List<List<T>> rankings)` → `Map<T, Double>` in first-seen order.
  - `new HazardBoost(double activeBoost, double relatedBoost)`, `double factor(Set<DisasterType> documentHazards, Set<DisasterType> active, Set<DisasterType> related)`.
  - `HybridRanker.Candidate(ChunkId chunkId, DocumentId documentId, String content, double similarity, Set<DisasterType> hazards)`; `new HybridRanker(HazardBoost)`; `List<RankedResult> rank(List<Candidate> vectorHits, List<Candidate> keywordHits, Set<DisasterType> active, Set<DisasterType> related, int topK)`.
  - `RelatedHazards`: `Set<DisasterType> relatedTo(Set<DisasterType> hazards)`.
  - `new HybridSearchAdapter(DataSource, EmbeddingGenerator, Set<String> statuses, RelatedHazards, HybridRanker, int candidatesPerList)`.
  - Properties `sheltr.search.mode` (`vector` or `hybrid`), `sheltr.search.candidates-per-list`, `sheltr.search.hazard-boost`, `sheltr.search.related-hazard-boost`.

- [ ] **Step 1: Write the failing domain tests**

`TEST/search/domain/RankFusionTest.java`:

```java
package com.sheltr.search.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RankFusionTest {

    @Test
    void scoresAnItemByItsRankInEveryListItAppearsIn() {
        Map<String, Double> fused = RankFusion.fuse(List.of(List.of("a", "b"), List.of("b", "c")));

        assertThat(fused.get("a")).isCloseTo(1.0 / 61, within(1e-9));
        assertThat(fused.get("b")).isCloseTo(1.0 / 62 + 1.0 / 61, within(1e-9));
        assertThat(fused.get("c")).isCloseTo(1.0 / 62, within(1e-9));
    }

    @Test
    void keepsFirstSeenOrderAndHandlesEmptyLists() {
        assertThat(RankFusion.fuse(List.of(List.of("a", "b"), List.<String>of())).keySet())
                .containsExactly("a", "b");
        assertThat(RankFusion.fuse(List.<List<String>>of())).isEmpty();
    }
}
```

`TEST/search/domain/HazardBoostTest.java`:

```java
package com.sheltr.search.domain;

import com.sheltr.knowledge.domain.DisasterType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HazardBoostTest {

    private final HazardBoost boost = new HazardBoost(0.3, 0.15);

    @Test
    void boostsADocumentAboutAnActiveHazardMost() {
        assertThat(boost.factor(Set.of(DisasterType.FLOOD), Set.of(DisasterType.FLOOD),
                Set.of(DisasterType.LANDSLIDE))).isCloseTo(1.3, within(1e-9));
    }

    @Test
    void boostsADocumentAboutARelatedHazardLess() {
        assertThat(boost.factor(Set.of(DisasterType.LANDSLIDE), Set.of(DisasterType.FLOOD),
                Set.of(DisasterType.LANDSLIDE))).isCloseTo(1.15, within(1e-9));
    }

    @Test
    void leavesOtherDocumentsAndQueriesWithoutHazardsUnchanged() {
        assertThat(boost.factor(Set.of(DisasterType.WILDFIRE), Set.of(DisasterType.FLOOD), Set.of()))
                .isEqualTo(1.0);
        assertThat(boost.factor(Set.of(DisasterType.FLOOD), Set.of(), Set.of())).isEqualTo(1.0);
    }
}
```

`TEST/search/domain/HybridRankerTest.java`:

```java
package com.sheltr.search.domain;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DisasterType;
import com.sheltr.knowledge.domain.DocumentId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HybridRankerTest {

    private final HybridRanker ranker = new HybridRanker(new HazardBoost(0.3, 0.15));
    private final HybridRanker.Candidate quake = candidate("quake text", 0.9, DisasterType.EARTHQUAKE);
    private final HybridRanker.Candidate flood = candidate("flood text", 0.8, DisasterType.FLOOD);
    private final HybridRanker.Candidate fire = candidate("fire text", 0.7, DisasterType.WILDFIRE);

    @Test
    void ranksACandidateFoundByBothSearchesFirst() {
        List<RankedResult> results = ranker.rank(List.of(quake, flood), List.of(flood, fire),
                Set.of(), Set.of(), 3);

        assertThat(results).extracting(RankedResult::content)
                .containsExactly("flood text", "quake text", "fire text");
    }

    @Test
    void keepsVectorOrderWhenThereAreNoKeywordHitsOrHazards() {
        List<RankedResult> results = ranker.rank(List.of(quake, flood), List.of(), Set.of(), Set.of(), 5);

        assertThat(results).extracting(RankedResult::content).containsExactly("quake text", "flood text");
        assertThat(results.get(0).score()).isEqualTo(0.9);
    }

    @Test
    void liftsACandidateAboutAnActiveHazardAboveItsNeighbour() {
        List<RankedResult> results = ranker.rank(List.of(quake, flood), List.of(),
                Set.of(DisasterType.FLOOD), Set.of(), 5);

        assertThat(results).extracting(RankedResult::content).containsExactly("flood text", "quake text");
    }

    @Test
    void neverDropsACandidateBecauseOfActiveHazards() {
        List<RankedResult> results = ranker.rank(List.of(quake, flood, fire), List.of(),
                Set.of(DisasterType.FLOOD), Set.of(), 5);

        assertThat(results).hasSize(3);
    }

    @Test
    void returnsAtMostTopK() {
        assertThat(ranker.rank(List.of(quake, flood, fire), List.of(), Set.of(), Set.of(), 2)).hasSize(2);
    }

    private static HybridRanker.Candidate candidate(String content, double similarity, DisasterType hazard) {
        return new HybridRanker.Candidate(new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()),
                content, similarity, Set.of(hazard));
    }
}
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `$MVN test -Dtest='RankFusionTest,HazardBoostTest,HybridRankerTest'`
Expected: compilation FAIL, the three classes do not exist.

- [ ] **Step 3: Implement the domain classes**

`MAIN/search/domain/SearchQuery.java`:

```java
package com.sheltr.search.domain;

import com.sheltr.knowledge.domain.DisasterType;

import java.util.Set;

/**
 * @param activeHazards hazards an official feed reports near the asker. They raise matching
 *                      passages in the ranking and are never used to filter passages out.
 */
public record SearchQuery(String text, Set<DisasterType> activeHazards) {
    public SearchQuery {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("SearchQuery text must not be blank");
        }
        activeHazards = Set.copyOf(activeHazards == null ? Set.of() : activeHazards);
    }

    public SearchQuery(String text) {
        this(text, Set.of());
    }
}
```

`MAIN/search/domain/RankFusion.java`:

```java
package com.sheltr.search.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reciprocal rank fusion: combines rankings without needing their scores to be comparable. */
public final class RankFusion {

    static final int K = 60;

    private RankFusion() {
    }

    public static <T> Map<T, Double> fuse(List<List<T>> rankings) {
        Map<T, Double> scores = new LinkedHashMap<>();
        for (List<T> ranking : rankings) {
            for (int i = 0; i < ranking.size(); i++) {
                scores.merge(ranking.get(i), 1.0 / (K + i + 1), Double::sum);
            }
        }
        return scores;
    }
}
```

`MAIN/search/domain/HazardBoost.java`:

```java
package com.sheltr.search.domain;

import com.sheltr.knowledge.domain.DisasterType;

import java.util.Collections;
import java.util.Set;

public record HazardBoost(double activeBoost, double relatedBoost) {

    public double factor(Set<DisasterType> documentHazards, Set<DisasterType> active, Set<DisasterType> related) {
        if (!Collections.disjoint(documentHazards, active)) {
            return 1.0 + activeBoost;
        }
        if (!Collections.disjoint(documentHazards, related)) {
            return 1.0 + relatedBoost;
        }
        return 1.0;
    }
}
```

`MAIN/search/domain/HybridRanker.java`:

```java
package com.sheltr.search.domain;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DisasterType;
import com.sheltr.knowledge.domain.DocumentId;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HybridRanker {

    public record Candidate(ChunkId chunkId, DocumentId documentId, String content, double similarity,
                            Set<DisasterType> hazards) {
    }

    private record Scored(Candidate candidate, double score) {
    }

    private final HazardBoost hazardBoost;

    public HybridRanker(HazardBoost hazardBoost) {
        this.hazardBoost = hazardBoost;
    }

    public List<RankedResult> rank(List<Candidate> vectorHits, List<Candidate> keywordHits,
                                   Set<DisasterType> active, Set<DisasterType> related, int topK) {
        Map<ChunkId, Candidate> byId = new LinkedHashMap<>();
        vectorHits.forEach(candidate -> byId.putIfAbsent(candidate.chunkId(), candidate));
        keywordHits.forEach(candidate -> byId.putIfAbsent(candidate.chunkId(), candidate));

        Map<ChunkId, Double> fused = RankFusion.fuse(List.of(
                vectorHits.stream().map(Candidate::chunkId).toList(),
                keywordHits.stream().map(Candidate::chunkId).toList()));

        // Stream.sorted is stable, so equal scores keep fusion order.
        return fused.entrySet().stream()
                .map(entry -> {
                    Candidate candidate = byId.get(entry.getKey());
                    return new Scored(candidate,
                            entry.getValue() * hazardBoost.factor(candidate.hazards(), active, related));
                })
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(topK)
                .map(scored -> new RankedResult(scored.candidate().chunkId(), scored.candidate().documentId(),
                        scored.candidate().content(),
                        Math.max(0.0, Math.min(1.0, scored.candidate().similarity()))))
                .toList();
    }
}
```

`MAIN/search/domain/RelatedHazards.java`:

```java
package com.sheltr.search.domain;

import com.sheltr.knowledge.domain.DisasterType;

import java.util.Set;

public interface RelatedHazards {
    /** Hazards that approved corpus documents say commonly follow any of {@code hazards}. */
    Set<DisasterType> relatedTo(Set<DisasterType> hazards);
}
```

- [ ] **Step 4: Run the domain tests**

Run: `$MVN test -Dtest='RankFusionTest,HazardBoostTest,HybridRankerTest'`
Expected: PASS, 10 tests.

- [ ] **Step 5: Write the failing adapter integration test**

`TEST/search/infrastructure/HybridSearchAdapterIntegrationTest.java`:

```java
package com.sheltr.search.infrastructure;

import com.sheltr.knowledge.domain.DisasterType;
import com.sheltr.knowledge.domain.Embedding;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.search.domain.HazardBoost;
import com.sheltr.search.domain.HybridRanker;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every seed chunk gets the same vector, so vector search cannot tell them apart and any
 * ordering seen here comes from keyword search and fusion.
 */
@Testcontainers
class HybridSearchAdapterIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final EmbeddingGenerator SAME_VECTOR = new EmbeddingGenerator() {
        @Override
        public Embedding embed(String text) {
            float[] values = new float[Embedding.DIMENSION];
            values[0] = 1.0f;
            return new Embedding(values);
        }

        @Override
        public String modelName() {
            return "model-a";
        }
    };

    private HybridSearchAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        StringBuilder vector = new StringBuilder("[1");
        for (int i = 1; i < Embedding.DIMENSION; i++) {
            vector.append(",0");
        }
        vector.append(']');
        try (Connection conn = dataSource.getConnection();
             PreparedStatement statement = conn.prepareStatement(
                     "UPDATE knowledge.chunks SET embedding = ?::vector, embedding_model = 'model-a'")) {
            statement.setString(1, vector.toString());
            statement.executeUpdate();
        }

        adapter = new HybridSearchAdapter(dataSource, SAME_VECTOR, Set.of("ACTIVE", "SEED"),
                new JooqRelatedHazards(dataSource), new HybridRanker(new HazardBoost(0.3, 0.15)), 40);
    }

    @Test
    void keywordMatchPutsTheOnlyMatchingChunkFirst() {
        // Only one seed chunk mentions a generator or carbon monoxide.
        List<RankedResult> results = adapter.retrieve(new SearchQuery("generator carbon monoxide"), 5);

        assertThat(results).hasSize(5);
        assertThat(results.get(0).content()).contains("carbon monoxide");
    }

    @Test
    void aQuestionOfOnlyCommonWordsStillReturnsVectorResults() {
        // Every word is an English stop word, so the keyword query is empty.
        List<RankedResult> results = adapter.retrieve(new SearchQuery("what should I do"), 5);

        assertThat(results).hasSize(5);
    }

    @Test
    void activeHazardsNeverReduceTheNumberOfResults() {
        List<RankedResult> results = adapter.retrieve(
                new SearchQuery("generator carbon monoxide", Set.of(DisasterType.FLOOD)), 5);

        assertThat(results).hasSize(5);
        assertThat(results.get(0).content()).contains("carbon monoxide");
    }
}
```

- [ ] **Step 6: Implement the adapters**

`MAIN/search/infrastructure/JooqRelatedHazards.java`:

```java
package com.sheltr.search.infrastructure;

import com.sheltr.knowledge.domain.DisasterType;
import com.sheltr.search.domain.RelatedHazards;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

public class JooqRelatedHazards implements RelatedHazards {

    private final DSLContext dsl;

    public JooqRelatedHazards(DataSource dataSource) {
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    @Override
    public Set<DisasterType> relatedTo(Set<DisasterType> hazards) {
        if (hazards.isEmpty()) {
            return Set.of();
        }
        String literal = "{" + hazards.stream().map(Enum::name).collect(Collectors.joining(",")) + "}";
        Set<DisasterType> related = EnumSet.noneOf(DisasterType.class);
        for (Record row : dsl.fetch(
                "SELECT DISTINCT related_hazard FROM knowledge.related_hazards WHERE hazard = ANY(?::text[])",
                literal)) {
            related.add(DisasterType.valueOf(row.get("related_hazard", String.class)));
        }
        related.removeAll(hazards);
        return related;
    }
}
```

`MAIN/search/infrastructure/HybridSearchAdapter.java`:

```java
package com.sheltr.search.infrastructure;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DisasterType;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.search.domain.HybridRanker;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.RelatedHazards;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Vector search plus English keyword search, fused by rank and boosted for active hazards. */
public class HybridSearchAdapter implements Retriever {

    private static final String COLUMNS =
            "SELECT c.id, c.document_id, c.content, d.disaster_types, "
                    + "1 - (c.embedding <=> ?::vector) AS similarity ";
    private static final String FILTER =
            "WHERE c.embedding IS NOT NULL AND c.embedding_model = ? AND d.status = ANY(?::text[]) ";

    private final DSLContext dsl;
    private final EmbeddingGenerator embeddingGenerator;
    private final String statusesLiteral;
    private final RelatedHazards relatedHazards;
    private final HybridRanker ranker;
    private final int candidatesPerList;

    public HybridSearchAdapter(DataSource dataSource, EmbeddingGenerator embeddingGenerator,
                               Set<String> statuses, RelatedHazards relatedHazards, HybridRanker ranker,
                               int candidatesPerList) {
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
        this.embeddingGenerator = embeddingGenerator;
        this.statusesLiteral = "{" + String.join(",", statuses) + "}";
        this.relatedHazards = relatedHazards;
        this.ranker = ranker;
        this.candidatesPerList = candidatesPerList;
    }

    @Override
    public List<RankedResult> retrieve(SearchQuery query, int topK) {
        String vector = toVectorLiteral(embeddingGenerator.embedQuery(query.text()).values());
        String model = embeddingGenerator.modelName();

        List<HybridRanker.Candidate> vectorHits = candidates(dsl.fetch(
                COLUMNS + "FROM knowledge.chunks c JOIN knowledge.documents d ON d.id = c.document_id "
                        + FILTER + "ORDER BY c.embedding <=> ?::vector ASC LIMIT ?",
                vector, model, statusesLiteral, vector, candidatesPerList));

        // plainto_tsquery joins words with AND, which a natural question almost never satisfies.
        // Rewriting to OR lets ts_rank_cd order passages by how many question words they share.
        // A question of only stop words yields an empty query, which matches nothing.
        List<HybridRanker.Candidate> keywordHits = candidates(dsl.fetch(
                "WITH q AS (SELECT replace(plainto_tsquery('english', ?)::text, '&', '|')::tsquery AS tsq) "
                        + COLUMNS
                        + "FROM knowledge.chunks c JOIN knowledge.documents d ON d.id = c.document_id, q "
                        + FILTER + "AND c.content_tsv @@ q.tsq "
                        + "ORDER BY ts_rank_cd(c.content_tsv, q.tsq) DESC LIMIT ?",
                query.text(), vector, model, statusesLiteral, candidatesPerList));

        Set<DisasterType> active = query.activeHazards();
        return ranker.rank(vectorHits, keywordHits, active, relatedHazards.relatedTo(active), topK);
    }

    private static List<HybridRanker.Candidate> candidates(Iterable<? extends Record> rows) {
        List<HybridRanker.Candidate> candidates = new ArrayList<>();
        for (Record row : rows) {
            Set<DisasterType> hazards = Arrays.stream(row.get("disaster_types", String[].class))
                    .map(DisasterType::valueOf)
                    .collect(Collectors.toSet());
            candidates.add(new HybridRanker.Candidate(
                    new ChunkId((UUID) row.get("id")),
                    new DocumentId((UUID) row.get("document_id")),
                    row.get("content", String.class),
                    row.get("similarity", Double.class),
                    hazards));
        }
        return candidates;
    }

    private static String toVectorLiteral(float[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        return sb.append(']').toString();
    }
}
```

- [ ] **Step 7: Run the integration test**

Run: `$MVN test -Dtest=HybridSearchAdapterIntegrationTest`
Expected: PASS, 3 tests. If jOOQ's plain-SQL parser rejects either statement, keep the SQL text and parameter order exactly as written and run both through plain JDBC instead: `dataSource.getConnection().prepareStatement(sql)`, bind the same values in the same order, and build each `Candidate` from the `ResultSet` (`rs.getArray("disaster_types").getArray()` gives the `String[]`).

- [ ] **Step 8: Wire the search mode**

`MAIN/search/config/RetrieverConfig.java`:

```java
package com.sheltr.search.config;

import com.sheltr.knowledge.domain.ChunkRepository;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.search.domain.HazardBoost;
import com.sheltr.search.domain.HybridRanker;
import com.sheltr.search.domain.RelatedHazards;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.infrastructure.GuardedRetriever;
import com.sheltr.search.infrastructure.HybridSearchAdapter;
import com.sheltr.search.infrastructure.JooqRelatedHazards;
import com.sheltr.search.infrastructure.PgvectorSemanticSearchAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Set;

@Configuration
public class RetrieverConfig {

    @Bean
    public RelatedHazards relatedHazards(DataSource dataSource) {
        return new JooqRelatedHazards(dataSource);
    }

    @Bean
    public Retriever retriever(DataSource dataSource, EmbeddingGenerator embeddingGenerator,
                               ChunkRepository chunkRepository, RelatedHazards relatedHazards,
                               @Value("${sheltr.search.statuses}") Set<String> statuses,
                               @Value("${sheltr.search.mode}") String mode,
                               @Value("${sheltr.search.candidates-per-list}") int candidatesPerList,
                               @Value("${sheltr.search.hazard-boost}") double hazardBoost,
                               @Value("${sheltr.search.related-hazard-boost}") double relatedHazardBoost) {
        Retriever search = switch (mode) {
            case "vector" -> new PgvectorSemanticSearchAdapter(dataSource, embeddingGenerator, statuses);
            case "hybrid" -> new HybridSearchAdapter(dataSource, embeddingGenerator, statuses, relatedHazards,
                    new HybridRanker(new HazardBoost(hazardBoost, relatedHazardBoost)), candidatesPerList);
            default -> throw new IllegalArgumentException(
                    "sheltr.search.mode must be 'vector' or 'hybrid', got '" + mode + "'");
        };
        return new GuardedRetriever(search, chunkRepository, embeddingGenerator.modelName());
    }

    /** Says at startup what GuardedRetriever will refuse on, so the cause is in the log before a user asks. */
    @Bean
    public ApplicationRunner staleEmbeddingsWarning(ChunkRepository chunkRepository,
                                                    EmbeddingGenerator embeddingGenerator) {
        return args -> {
            int stale = chunkRepository.countEmbeddedWithOtherModel(embeddingGenerator.modelName());
            if (stale > 0) {
                LoggerFactory.getLogger(RetrieverConfig.class).error(
                        "{} chunk(s) were embedded by a model other than '{}'. Questions are refused "
                                + "until `make seed-embeddings` re-embeds them.",
                        stale, embeddingGenerator.modelName());
            }
        };
    }
}
```

Keep the imports `org.slf4j.LoggerFactory` and `org.springframework.boot.ApplicationRunner` from Task 2.

In `RES/application.yml`, replace the `sheltr:` block added in Task 2 with:

```yaml
sheltr:
  search:
    # Document statuses retrieval may return. SEED rows are test fixtures and are never listed here.
    statuses: ACTIVE
    # vector | hybrid. Switched to hybrid only if the eval shows a gain (Task 14).
    mode: vector
    candidates-per-list: 20
    hazard-boost: 0.3
    related-hazard-boost: 0.15
```

- [ ] **Step 9: Run the full suite and commit**

Run: `$MVN test`
Expected: PASS.

```bash
git add -A apps/backend/src
git commit -m "feat(search): add hybrid search with rank fusion and hazard boost"
```

---

### Task 7: Reranker

**Files:**
- Create: `MAIN/search/domain/Reranker.java`, `NoOpReranker.java`
- Create: `MAIN/search/infrastructure/ModelReranker.java`, `MAIN/search/config/RerankerConfig.java`
- Create: `TEST/search/infrastructure/ModelRerankerTest.java`
- Modify: `RES/application.yml`

**Interfaces:**
- Consumes: `SearchQuery`, `RankedResult`.
- Produces:
  - `Reranker`: `List<RankedResult> rerank(SearchQuery query, List<RankedResult> candidates, int topK)`.
  - `new NoOpReranker()`; `new ModelReranker(UnaryOperator<String> llm)`.
  - Property `sheltr.search.reranker` (`none` or `model`).

- [ ] **Step 1: Write the failing test**

`TEST/search/infrastructure/ModelRerankerTest.java`:

```java
package com.sheltr.search.infrastructure;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.search.domain.NoOpReranker;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRerankerTest {

    private static final SearchQuery QUERY = new SearchQuery("Is flood water safe to drink?");
    private final List<RankedResult> candidates = List.of(passage("one"), passage("two"), passage("three"));

    @Test
    void ordersCandidatesAsTheModelRanksThem() {
        List<RankedResult> results = new ModelReranker(prompt -> "3, 1, 2").rerank(QUERY, candidates, 2);

        assertThat(results).extracting(RankedResult::content).containsExactly("three", "one");
    }

    @Test
    void sendsTheQuestionAndNumberedPassagesToTheModel() {
        StringBuilder seen = new StringBuilder();
        new ModelReranker(prompt -> {
            seen.append(prompt);
            return "1";
        }).rerank(QUERY, candidates, 3);

        assertThat(seen.toString()).contains("Is flood water safe to drink?", "[1] one", "[3] three");
    }

    @Test
    void ignoresNumbersOutOfRangeAndRepeatsThenAppendsWhatTheModelLeftOut() {
        List<RankedResult> results = new ModelReranker(prompt -> "2, 9, 2, 0").rerank(QUERY, candidates, 3);

        assertThat(results).extracting(RankedResult::content).containsExactly("two", "one", "three");
    }

    @Test
    void keepsTheOriginalOrderWhenTheReplyHasNoNumbers() {
        List<RankedResult> results = new ModelReranker(prompt -> "I cannot rank these.")
                .rerank(QUERY, candidates, 2);

        assertThat(results).extracting(RankedResult::content).containsExactly("one", "two");
    }

    @Test
    void keepsTheOriginalOrderWhenTheModelFails() {
        List<RankedResult> results = new ModelReranker(prompt -> {
            throw new IllegalStateException("model unavailable");
        }).rerank(QUERY, candidates, 2);

        assertThat(results).extracting(RankedResult::content).containsExactly("one", "two");
    }

    @Test
    void noOpRerankerTruncatesWithoutReordering() {
        assertThat(new NoOpReranker().rerank(QUERY, candidates, 2))
                .extracting(RankedResult::content).containsExactly("one", "two");
    }

    private static RankedResult passage(String content) {
        return new RankedResult(new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()), content, 0.8);
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `$MVN test -Dtest=ModelRerankerTest`
Expected: compilation FAIL.

- [ ] **Step 3: Implement**

`MAIN/search/domain/Reranker.java`:

```java
package com.sheltr.search.domain;

import java.util.List;

public interface Reranker {
    List<RankedResult> rerank(SearchQuery query, List<RankedResult> candidates, int topK);
}
```

`MAIN/search/domain/NoOpReranker.java`:

```java
package com.sheltr.search.domain;

import java.util.List;

public class NoOpReranker implements Reranker {
    @Override
    public List<RankedResult> rerank(SearchQuery query, List<RankedResult> candidates, int topK) {
        return candidates.stream().limit(topK).toList();
    }
}
```

`MAIN/search/infrastructure/ModelReranker.java`:

```java
package com.sheltr.search.infrastructure;

import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Reranker;
import com.sheltr.search.domain.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Asks a chat model to order the candidates. Any failure falls back to the incoming order. */
public class ModelReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(ModelReranker.class);
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private final UnaryOperator<String> llm;

    public ModelReranker(UnaryOperator<String> llm) {
        this.llm = llm;
    }

    @Override
    public List<RankedResult> rerank(SearchQuery query, List<RankedResult> candidates, int topK) {
        if (candidates.size() <= 1) {
            return candidates.stream().limit(topK).toList();
        }
        Set<Integer> order = new LinkedHashSet<>();
        try {
            Matcher matcher = NUMBER.matcher(llm.apply(prompt(query, candidates)));
            while (matcher.find()) {
                int index = parse(matcher.group()) - 1;
                if (index >= 0 && index < candidates.size()) {
                    order.add(index);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Reranker failed, keeping retrieval order: {}", e.getMessage());
            order.clear();
        }
        for (int i = 0; i < candidates.size(); i++) {
            order.add(i);
        }
        List<RankedResult> reranked = new ArrayList<>();
        for (int index : order) {
            reranked.add(candidates.get(index));
        }
        return reranked.stream().limit(topK).toList();
    }

    private static int parse(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String prompt(SearchQuery query, List<RankedResult> candidates) {
        StringBuilder prompt = new StringBuilder("""
                Rank the passages by how well they answer the question. Reply with the passage \
                numbers only, best first, separated by commas. Example: 3,1,2

                Question: %s

                Passages:
                """.formatted(query.text()));
        for (int i = 0; i < candidates.size(); i++) {
            prompt.append('[').append(i + 1).append("] ").append(candidates.get(i).content()).append('\n');
        }
        return prompt.toString();
    }
}
```

`MAIN/search/config/RerankerConfig.java`:

```java
package com.sheltr.search.config;

import com.sheltr.search.domain.NoOpReranker;
import com.sheltr.search.domain.Reranker;
import com.sheltr.search.infrastructure.ModelReranker;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RerankerConfig {

    @Bean
    public Reranker reranker(ChatModel chatModel, @Value("${sheltr.search.reranker}") String type) {
        return switch (type) {
            case "none" -> new NoOpReranker();
            case "model" -> new ModelReranker(chatModel::call);
            default -> throw new IllegalArgumentException(
                    "sheltr.search.reranker must be 'none' or 'model', got '" + type + "'");
        };
    }
}
```

In `RES/application.yml`, add under `sheltr.search`:

```yaml
    # none | model. Switched to model only if the eval shows a gain (Task 14).
    reranker: none
```

- [ ] **Step 4: Run and commit**

Run: `$MVN test -Dtest=ModelRerankerTest`
Expected: PASS, 6 tests.

```bash
git add -A apps/backend/src
git commit -m "feat(search): add reranker port with model-based implementation"
```

---

### Task 8: Relevance judge, confidence bands, and the answer pipeline

**Files:**
- Create: `MAIN/ai/domain/RelevanceVerdict.java`, `RelevanceJudge.java`, `ThresholdRelevanceJudge.java`, `ConfidenceBands.java`
- Create: `MAIN/ai/infrastructure/ModelRelevanceJudge.java`
- Create: `MAIN/ai/application/AnswerPipeline.java`, `MAIN/ai/config/AnswerPipelineConfig.java`
- Modify: `MAIN/ai/domain/AnswerConfidence.java`, `AnswerGenerator.java`
- Modify: `MAIN/ai/application/AnswerContextBuilder.java`, `AskQuestionService.java`
- Modify: `MAIN/ai/infrastructure/SpringAiAnswerGeneratorAdapter.java`
- Modify: `MAIN/ai/interfaces/LuminaChatApp.java`
- Modify: `MAIN/ai/config/AskQuestionServiceConfig.java`, `LuminaChatAppConfig.java`, `AnswerContextBuilderConfig.java`
- Delete: `MAIN/ai/domain/RelevanceGate.java`, `TEST/ai/domain/RelevanceGateTest.java`
- Create: `TEST/ai/domain/ThresholdRelevanceJudgeTest.java`, `TEST/ai/infrastructure/ModelRelevanceJudgeTest.java`, `TEST/ai/application/AnswerPipelineTest.java`
- Modify: `TEST/ai/application/AskQuestionServiceTest.java`, `AnswerContextBuilderTest.java`, `TEST/ai/interfaces/LuminaChatAppTest.java`, `TEST/ai/infrastructure/SpringAiAnswerGeneratorAdapterTest.java`, `TEST/ai/domain/AnswerConfidenceTest.java`
- Modify: `RES/application.yml`

**Interfaces:**
- Consumes: `Retriever`, `Reranker` (Task 7), `SearchQuery` with hazards (Task 6).
- Produces:
  - `record RelevanceVerdict(boolean answerable, double probability)` with constant `RelevanceVerdict.REFUSE`.
  - `RelevanceJudge`: `RelevanceVerdict judge(SearchQuery query, List<RankedResult> passages)`.
  - `new ThresholdRelevanceJudge(double threshold)`; `new ModelRelevanceJudge(UnaryOperator<String> llm, double minProbability)`.
  - `record ConfidenceBands(double high, double medium)` with `ConfidenceBands.DEFAULT` = `(0.90, 0.75)`; `AnswerConfidence.of(double probability, ConfidenceBands bands)`.
  - `AnswerGenerator.generate(SearchQuery query, List<RankedResult> results, RelevanceVerdict verdict)`.
  - `new AnswerContextBuilder(KnowledgeDocumentRepository, ConfidenceBands)`; `buildContext(SearchQuery, List<RankedResult>, RelevanceVerdict)`.
  - `new AnswerPipeline(Retriever, Reranker, RelevanceJudge, AnswerGenerator)`; `JudgedPassages retrieve(SearchQuery)`; `PipelineResult answer(SearchQuery)`; records `JudgedPassages(List<RankedResult> passages, RelevanceVerdict verdict)` and `PipelineResult(List<RankedResult> passages, RelevanceVerdict verdict, AnswerOutcome outcome)`; constants `CANDIDATES = 20`, `TOP_K = 5`.
  - `new AskQuestionService(AnswerPipeline, ConversationRepository)`; `Message ask(ConversationId, String)` and `Message ask(ConversationId, String, Set<DisasterType> activeHazards)`.
  - `new LuminaChatApp(ConversationRepository, AnswerPipeline, AnswerContextBuilder, ChatClient)`.
  - Properties `sheltr.judge.type` (`threshold` or `model`), `sheltr.judge.min-probability`, `sheltr.confidence.high`, `sheltr.confidence.medium`.

- [ ] **Step 1: Write the failing judge tests**

`TEST/ai/domain/ThresholdRelevanceJudgeTest.java`:

```java
package com.sheltr.ai.domain;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ThresholdRelevanceJudgeTest {

    private static final SearchQuery QUERY = new SearchQuery("question");
    private final ThresholdRelevanceJudge judge = new ThresholdRelevanceJudge(0.75);

    @Test
    void answerableWhenTopScoreMeetsThreshold() {
        RelevanceVerdict verdict = judge.judge(QUERY, List.of(passage(0.80), passage(0.10)));

        assertThat(verdict.answerable()).isTrue();
        assertThat(verdict.probability()).isEqualTo(0.80);
    }

    @Test
    void usesTheBestScoreEvenWhenItIsNotFirst() {
        assertThat(judge.judge(QUERY, List.of(passage(0.10), passage(0.80))).answerable()).isTrue();
    }

    @Test
    void notAnswerableBelowThresholdOrWithNoPassages() {
        assertThat(judge.judge(QUERY, List.of(passage(0.70))).answerable()).isFalse();
        assertThat(judge.judge(QUERY, List.of())).isEqualTo(RelevanceVerdict.REFUSE);
    }

    private static RankedResult passage(double score) {
        return new RankedResult(new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()), "text", score);
    }
}
```

`TEST/ai/infrastructure/ModelRelevanceJudgeTest.java`:

```java
package com.sheltr.ai.infrastructure;

import com.sheltr.ai.domain.RelevanceVerdict;
import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRelevanceJudgeTest {

    private static final SearchQuery QUERY = new SearchQuery("Is flood water safe to drink?");
    private static final List<RankedResult> PASSAGES = List.of(new RankedResult(
            new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()), "Boil water for one minute.", 0.8));

    @Test
    void parsesAnAnswerableVerdict() {
        RelevanceVerdict verdict = judge("ANSWERABLE: yes\nPROBABILITY: 0.85").judge(QUERY, PASSAGES);

        assertThat(verdict.answerable()).isTrue();
        assertThat(verdict.probability()).isEqualTo(0.85);
    }

    @Test
    void refusesWhenTheModelSaysNo() {
        assertThat(judge("ANSWERABLE: no\nPROBABILITY: 0.10").judge(QUERY, PASSAGES).answerable()).isFalse();
    }

    @Test
    void refusesWhenProbabilityIsBelowTheMinimumEvenIfTheModelSaysYes() {
        RelevanceVerdict verdict = judge("ANSWERABLE: yes\nPROBABILITY: 0.30").judge(QUERY, PASSAGES);

        assertThat(verdict.answerable()).isFalse();
        assertThat(verdict.probability()).isEqualTo(0.30);
    }

    @Test
    void failsClosedOnAnUnreadableReply() {
        assertThat(judge("Sure! These passages look helpful.").judge(QUERY, PASSAGES))
                .isEqualTo(RelevanceVerdict.REFUSE);
        assertThat(judge("ANSWERABLE: yes\nPROBABILITY: 7").judge(QUERY, PASSAGES))
                .isEqualTo(RelevanceVerdict.REFUSE);
    }

    @Test
    void failsClosedWhenTheModelThrows() {
        ModelRelevanceJudge judge = new ModelRelevanceJudge(prompt -> {
            throw new IllegalStateException("model unavailable");
        }, 0.5);

        assertThat(judge.judge(QUERY, PASSAGES)).isEqualTo(RelevanceVerdict.REFUSE);
    }

    @Test
    void refusesWithoutCallingTheModelWhenThereAreNoPassages() {
        ModelRelevanceJudge judge = new ModelRelevanceJudge(prompt -> {
            throw new AssertionError("model must not be called");
        }, 0.5);

        assertThat(judge.judge(QUERY, List.of())).isEqualTo(RelevanceVerdict.REFUSE);
    }

    private static ModelRelevanceJudge judge(String reply) {
        return new ModelRelevanceJudge(prompt -> reply, 0.5);
    }
}
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `$MVN test -Dtest='ThresholdRelevanceJudgeTest,ModelRelevanceJudgeTest'`
Expected: compilation FAIL.

- [ ] **Step 3: Implement verdict, judges, and confidence bands**

`MAIN/ai/domain/RelevanceVerdict.java`:

```java
package com.sheltr.ai.domain;

public record RelevanceVerdict(boolean answerable, double probability) {

    public static final RelevanceVerdict REFUSE = new RelevanceVerdict(false, 0.0);

    public RelevanceVerdict {
        if (Double.isNaN(probability) || probability < 0.0 || probability > 1.0) {
            throw new IllegalArgumentException("probability must be between 0.0 and 1.0, got " + probability);
        }
    }
}
```

`MAIN/ai/domain/RelevanceJudge.java`:

```java
package com.sheltr.ai.domain;

import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;

import java.util.List;

/** Decides whether the retrieved passages are enough to answer the question. */
public interface RelevanceJudge {
    RelevanceVerdict judge(SearchQuery query, List<RankedResult> passages);
}
```

`MAIN/ai/domain/ThresholdRelevanceJudge.java`:

```java
package com.sheltr.ai.domain;

import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;

import java.util.List;

/** Answerable when the best passage's similarity score reaches a fixed threshold. */
public class ThresholdRelevanceJudge implements RelevanceJudge {

    private final double threshold;

    public ThresholdRelevanceJudge(double threshold) {
        this.threshold = threshold;
    }

    @Override
    public RelevanceVerdict judge(SearchQuery query, List<RankedResult> passages) {
        if (passages.isEmpty()) {
            return RelevanceVerdict.REFUSE;
        }
        double best = passages.stream().mapToDouble(RankedResult::score).max().orElse(0.0);
        return new RelevanceVerdict(best >= threshold, best);
    }
}
```

`MAIN/ai/domain/ConfidenceBands.java`:

```java
package com.sheltr.ai.domain;

/** Probability cut-offs for HIGH and MEDIUM confidence. Fitted per judge on the eval tuning split. */
public record ConfidenceBands(double high, double medium) {

    public static final ConfidenceBands DEFAULT = new ConfidenceBands(0.90, 0.75);

    public ConfidenceBands {
        if (medium > high) {
            throw new IllegalArgumentException("medium (" + medium + ") must not exceed high (" + high + ")");
        }
    }
}
```

`MAIN/ai/domain/AnswerConfidence.java`:

```java
package com.sheltr.ai.domain;

public record AnswerConfidence(double score, ConfidenceLevel level) {

    public static AnswerConfidence of(double probability, ConfidenceBands bands) {
        ConfidenceLevel level = probability >= bands.high() ? ConfidenceLevel.HIGH
                : probability >= bands.medium() ? ConfidenceLevel.MEDIUM
                : ConfidenceLevel.LOW;
        return new AnswerConfidence(probability, level);
    }

    /** Convenience for tests and stored fixtures; production code passes configured bands to {@link #of}. */
    public static AnswerConfidence fromSimilarity(double topScore) {
        return of(topScore, ConfidenceBands.DEFAULT);
    }
}
```

`MAIN/ai/infrastructure/ModelRelevanceJudge.java`:

```java
package com.sheltr.ai.infrastructure;

import com.sheltr.ai.domain.RelevanceJudge;
import com.sheltr.ai.domain.RelevanceVerdict;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Asks a chat model whether the passages answer the question. Fails closed on any problem. */
public class ModelRelevanceJudge implements RelevanceJudge {

    private static final Logger log = LoggerFactory.getLogger(ModelRelevanceJudge.class);
    private static final Pattern ANSWERABLE = Pattern.compile("ANSWERABLE:\\s*(yes|no)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROBABILITY = Pattern.compile("PROBABILITY:\\s*([0-9]*\\.?[0-9]+)",
            Pattern.CASE_INSENSITIVE);

    private final UnaryOperator<String> llm;
    private final double minProbability;

    public ModelRelevanceJudge(UnaryOperator<String> llm, double minProbability) {
        this.llm = llm;
        this.minProbability = minProbability;
    }

    @Override
    public RelevanceVerdict judge(SearchQuery query, List<RankedResult> passages) {
        if (passages.isEmpty()) {
            return RelevanceVerdict.REFUSE;
        }
        try {
            String reply = llm.apply(prompt(query, passages));
            Matcher answerable = ANSWERABLE.matcher(reply);
            Matcher probability = PROBABILITY.matcher(reply);
            if (!answerable.find() || !probability.find()) {
                log.warn("Relevance judge reply was unreadable, refusing");
                return RelevanceVerdict.REFUSE;
            }
            double p = Double.parseDouble(probability.group(1));
            if (p < 0.0 || p > 1.0) {
                log.warn("Relevance judge returned probability {} outside 0..1, refusing", p);
                return RelevanceVerdict.REFUSE;
            }
            boolean yes = answerable.group(1).equalsIgnoreCase("yes");
            return new RelevanceVerdict(yes && p >= minProbability, p);
        } catch (RuntimeException e) {
            log.warn("Relevance judge failed, refusing: {}", e.getMessage());
            return RelevanceVerdict.REFUSE;
        }
    }

    private static String prompt(SearchQuery query, List<RankedResult> passages) {
        StringBuilder prompt = new StringBuilder("""
                You check whether passages from official disaster guidance contain the answer to a \
                question. Judge only what the passages say. If they are about a related topic but do \
                not answer this question, the answer is no.

                Reply in exactly this form:
                ANSWERABLE: yes or no
                PROBABILITY: a number from 0.00 to 1.00, your probability that the passages fully answer it

                Question: %s

                Passages:
                """.formatted(query.text()));
        for (int i = 0; i < passages.size(); i++) {
            prompt.append('[').append(i + 1).append("] ").append(passages.get(i).content()).append('\n');
        }
        return prompt.toString();
    }
}
```

- [ ] **Step 4: Run the judge tests**

Run: `$MVN test -Dtest='ThresholdRelevanceJudgeTest,ModelRelevanceJudgeTest'`
Expected: PASS, 9 tests.

- [ ] **Step 5: Write the failing pipeline test**

`TEST/ai/application/AnswerPipelineTest.java`:

```java
package com.sheltr.ai.application;

import com.sheltr.ai.domain.*;
import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.search.domain.NoOpReranker;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Reranker;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerPipelineTest {

    private static final SearchQuery QUERY = new SearchQuery("Is flood water safe to drink?");
    private static final Message ANSWER = new Message(new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT,
            new MessageContent("Boil it [1]."), List.of(), AnswerConfidence.fromSimilarity(0.9), List.of());

    @Test
    void asksForTwentyCandidatesAndPassesFiveToTheGenerator() {
        List<Integer> requested = new ArrayList<>();
        List<Integer> generatedWith = new ArrayList<>();
        Retriever retriever = (query, topK) -> {
            requested.add(topK);
            return passages(20, 0.9);
        };
        AnswerGenerator generator = (query, results, verdict) -> {
            generatedWith.add(results.size());
            return new AnswerGenerated(ANSWER);
        };

        AnswerPipeline.PipelineResult result = new AnswerPipeline(retriever, new NoOpReranker(),
                new ThresholdRelevanceJudge(0.75), generator).answer(QUERY);

        assertThat(requested).containsExactly(20);
        assertThat(generatedWith).containsExactly(5);
        assertThat(result.outcome()).isEqualTo(new AnswerGenerated(ANSWER));
        assertThat(result.verdict().answerable()).isTrue();
    }

    @Test
    void refusesWithoutGeneratingWhenTheJudgeSaysNotAnswerable() {
        AnswerGenerator generator = (query, results, verdict) -> {
            throw new AssertionError("generator must not be called");
        };

        AnswerPipeline.PipelineResult result = new AnswerPipeline((query, topK) -> passages(3, 0.2),
                new NoOpReranker(), new ThresholdRelevanceJudge(0.75), generator).answer(QUERY);

        assertThat(result.outcome()).isInstanceOf(NoAnswerAvailable.class);
        assertThat(result.passages()).hasSize(3);
    }

    @Test
    void refusesWhenNothingIsRetrieved() {
        RelevanceJudge judge = (query, passages) -> {
            throw new AssertionError("judge must not be called with no passages");
        };

        AnswerPipeline.PipelineResult result = new AnswerPipeline((query, topK) -> List.of(),
                new NoOpReranker(), judge, (query, results, verdict) -> new AnswerGenerated(ANSWER)).answer(QUERY);

        assertThat(result.outcome()).isInstanceOf(NoAnswerAvailable.class);
    }

    @Test
    void refusesWhenTheJudgeThrows() {
        RelevanceJudge judge = (query, passages) -> {
            throw new IllegalStateException("judge unavailable");
        };

        AnswerPipeline.PipelineResult result = new AnswerPipeline((query, topK) -> passages(3, 0.9),
                new NoOpReranker(), judge, (query, results, verdict) -> new AnswerGenerated(ANSWER)).answer(QUERY);

        assertThat(result.outcome()).isInstanceOf(NoAnswerAvailable.class);
    }

    @Test
    void keepsRetrievalOrderWhenTheRerankerThrows() {
        Reranker reranker = (query, candidates, topK) -> {
            throw new IllegalStateException("reranker unavailable");
        };

        AnswerPipeline.PipelineResult result = new AnswerPipeline((query, topK) -> passages(8, 0.9),
                reranker, new ThresholdRelevanceJudge(0.75),
                (query, results, verdict) -> new AnswerGenerated(ANSWER)).answer(QUERY);

        assertThat(result.passages()).hasSize(5);
        assertThat(result.outcome()).isEqualTo(new AnswerGenerated(ANSWER));
    }

    private static List<RankedResult> passages(int count, double score) {
        List<RankedResult> passages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            passages.add(new RankedResult(new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()),
                    "passage " + i, score));
        }
        return passages;
    }
}
```

- [ ] **Step 6: Implement the pipeline and change the generator port**

`MAIN/ai/domain/AnswerGenerator.java`:

```java
package com.sheltr.ai.domain;

import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;

import java.util.List;

public interface AnswerGenerator {
    AnswerOutcome generate(SearchQuery query, List<RankedResult> results, RelevanceVerdict verdict);
}
```

`MAIN/ai/application/AnswerPipeline.java`:

```java
package com.sheltr.ai.application;

import com.sheltr.ai.domain.AnswerGenerator;
import com.sheltr.ai.domain.AnswerOutcome;
import com.sheltr.ai.domain.NoAnswerAvailable;
import com.sheltr.ai.domain.RelevanceJudge;
import com.sheltr.ai.domain.RelevanceVerdict;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Reranker;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Retrieve, rerank, judge, then generate. The one place that order is defined. */
public class AnswerPipeline {

    public static final int CANDIDATES = 20;
    public static final int TOP_K = 5;

    private static final Logger log = LoggerFactory.getLogger(AnswerPipeline.class);

    public record JudgedPassages(List<RankedResult> passages, RelevanceVerdict verdict) {
    }

    public record PipelineResult(List<RankedResult> passages, RelevanceVerdict verdict, AnswerOutcome outcome) {
    }

    private final Retriever retriever;
    private final Reranker reranker;
    private final RelevanceJudge judge;
    private final AnswerGenerator answerGenerator;

    public AnswerPipeline(Retriever retriever, Reranker reranker, RelevanceJudge judge,
                          AnswerGenerator answerGenerator) {
        this.retriever = retriever;
        this.reranker = reranker;
        this.judge = judge;
        this.answerGenerator = answerGenerator;
    }

    public JudgedPassages retrieve(SearchQuery query) {
        List<RankedResult> candidates = retriever.retrieve(query, CANDIDATES);
        List<RankedResult> passages = rerank(query, candidates);
        if (passages.isEmpty()) {
            return new JudgedPassages(passages, RelevanceVerdict.REFUSE);
        }
        return new JudgedPassages(passages, judge(query, passages));
    }

    public PipelineResult answer(SearchQuery query) {
        JudgedPassages judged = retrieve(query);
        if (!judged.verdict().answerable()) {
            return new PipelineResult(judged.passages(), judged.verdict(), new NoAnswerAvailable());
        }
        return new PipelineResult(judged.passages(), judged.verdict(),
                answerGenerator.generate(query, judged.passages(), judged.verdict()));
    }

    // A failed reranker costs ranking quality, not safety: keep the retrieval order.
    private List<RankedResult> rerank(SearchQuery query, List<RankedResult> candidates) {
        try {
            return reranker.rerank(query, candidates, TOP_K);
        } catch (RuntimeException e) {
            log.warn("Reranker failed, keeping retrieval order: {}", e.getMessage());
            return candidates.stream().limit(TOP_K).toList();
        }
    }

    // A failed judge could let an unsupported answer through: refuse.
    private RelevanceVerdict judge(SearchQuery query, List<RankedResult> passages) {
        try {
            return judge.judge(query, passages);
        } catch (RuntimeException e) {
            log.warn("Relevance judge failed, refusing: {}", e.getMessage());
            return RelevanceVerdict.REFUSE;
        }
    }
}
```

- [ ] **Step 7: Update the context builder, generator adapter, and service**

In `MAIN/ai/application/AnswerContextBuilder.java`, replace the field, constructor, the `buildContext` signature, and its `return` statement. The prompt text is unchanged in this task; Task 9 rewrites it.

```java
    private final KnowledgeDocumentRepository documentRepository;
    private final ConfidenceBands confidenceBands;

    public AnswerContextBuilder(KnowledgeDocumentRepository documentRepository, ConfidenceBands confidenceBands) {
        this.documentRepository = documentRepository;
        this.confidenceBands = confidenceBands;
    }

    public AnswerContext buildContext(SearchQuery query, List<RankedResult> results, RelevanceVerdict verdict) {
```

```java
        return new AnswerContext(prompt, citations, AnswerConfidence.of(verdict.probability(), confidenceBands));
```

Add imports `com.sheltr.ai.domain.ConfidenceBands` and `com.sheltr.ai.domain.RelevanceVerdict`.

In `MAIN/ai/infrastructure/SpringAiAnswerGeneratorAdapter.java`, change the method signature and first line:

```java
    @Override
    public AnswerOutcome generate(SearchQuery query, List<RankedResult> results, RelevanceVerdict verdict) {
        AnswerContext context = answerContextBuilder.buildContext(query, results, verdict);
```

`MAIN/ai/application/AskQuestionService.java`:

```java
package com.sheltr.ai.application;

import com.sheltr.ai.domain.*;
import com.sheltr.knowledge.domain.DisasterType;
import com.sheltr.search.domain.SearchQuery;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public class AskQuestionService {

    private final AnswerPipeline pipeline;
    private final ConversationRepository conversationRepository;

    public AskQuestionService(AnswerPipeline pipeline, ConversationRepository conversationRepository) {
        this.pipeline = pipeline;
        this.conversationRepository = conversationRepository;
    }

    public Message ask(ConversationId conversationId, String questionText) {
        return ask(conversationId, questionText, Set.of());
    }

    public Message ask(ConversationId conversationId, String questionText, Set<DisasterType> activeHazards) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));

        conversation.addMessage(new Message(
                new MessageId(UUID.randomUUID()), MessageRole.USER, new MessageContent(questionText),
                List.of(), null, List.of()));

        AnswerOutcome outcome = pipeline.answer(new SearchQuery(questionText, activeHazards)).outcome();
        if (outcome instanceof AnswerGenerationFailed failed) {
            // Persist the user's question even though generation failed, so conversation
            // history reflects what was actually asked; do not persist a fabricated
            // assistant message for a real failure.
            conversationRepository.save(conversation);
            throw new AnswerGenerationFailedException(failed.reason());
        }
        Message assistantMessage = switch (outcome) {
            case AnswerGenerated generated -> generated.message();
            case NoAnswerAvailable ignored -> fallbackMessage(NoAnswerAvailable.MESSAGE);
            case AnswerGenerationFailed ignored -> throw new IllegalStateException("handled above");
        };
        conversation.addMessage(assistantMessage);
        conversationRepository.save(conversation);
        return assistantMessage;
    }

    private static Message fallbackMessage(String text) {
        return new Message(new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT,
                new MessageContent(text), List.of(), null, List.of());
    }
}
```

- [ ] **Step 8: Update the Lumina chat app**

In `MAIN/ai/interfaces/LuminaChatApp.java`:

- Remove the `TOP_K` constant, the `retriever` and `relevanceThreshold` fields, and the imports of `Retriever` and `RankedResult` if unused after the edits below. Add the field `private final AnswerPipeline pipeline;` and imports `com.sheltr.ai.application.AnswerPipeline` and `com.sheltr.knowledge.domain.StaleEmbeddingsException`.
- Replace the constructor:

```java
    public LuminaChatApp(ConversationRepository conversationRepository, AnswerPipeline pipeline,
                         AnswerContextBuilder answerContextBuilder, ChatClient chatClient) {
        this.conversationRepository = conversationRepository;
        this.pipeline = pipeline;
        this.answerContextBuilder = answerContextBuilder;
        this.chatClient = chatClient;
    }
```

- Replace everything from `SearchQuery query = new SearchQuery(prompt);` up to and including the line `AnswerContext context = answerContextBuilder.buildContext(query, results);` with:

```java
        SearchQuery query = new SearchQuery(prompt);
        AnswerPipeline.JudgedPassages judged;
        try {
            judged = pipeline.retrieve(query);
        } catch (StaleEmbeddingsException e) {
            conversationRepository.save(conversation);
            ui.state().set("error", GENERATION_ERROR);
            ui.text(GENERATION_ERROR);
            return;
        }
        List<RankedResult> results = judged.passages();
        if (!judged.verdict().answerable()) {
            ui.ai(NoAnswerAvailable.MESSAGE);
            conversation.addMessage(new Message(
                    new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT,
                    new MessageContent(NoAnswerAvailable.MESSAGE), List.of(), null, List.of()));
            conversationRepository.save(conversation);
            return;
        }

        AnswerContext context = answerContextBuilder.buildContext(query, results, judged.verdict());
```

Keep the `RankedResult` import; it is still used.

- [ ] **Step 9: Update the configuration classes**

`MAIN/ai/config/AnswerContextBuilderConfig.java`:

```java
package com.sheltr.ai.config;

import com.sheltr.ai.application.AnswerContextBuilder;
import com.sheltr.ai.domain.ConfidenceBands;
import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnswerContextBuilderConfig {

    @Bean
    public ConfidenceBands confidenceBands(@Value("${sheltr.confidence.high}") double high,
                                           @Value("${sheltr.confidence.medium}") double medium) {
        return new ConfidenceBands(high, medium);
    }

    @Bean
    public AnswerContextBuilder answerContextBuilder(KnowledgeDocumentRepository documentRepository,
                                                     ConfidenceBands confidenceBands) {
        return new AnswerContextBuilder(documentRepository, confidenceBands);
    }
}
```

`MAIN/ai/config/AnswerPipelineConfig.java`:

```java
package com.sheltr.ai.config;

import com.sheltr.ai.application.AnswerPipeline;
import com.sheltr.ai.domain.AnswerGenerator;
import com.sheltr.ai.domain.RelevanceJudge;
import com.sheltr.ai.domain.ThresholdRelevanceJudge;
import com.sheltr.ai.infrastructure.ModelRelevanceJudge;
import com.sheltr.search.domain.Reranker;
import com.sheltr.search.domain.Retriever;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnswerPipelineConfig {

    @Bean
    public RelevanceJudge relevanceJudge(ChatModel chatModel,
                                         @Value("${sheltr.judge.type}") String type,
                                         @Value("${sheltr.judge.min-probability}") double minProbability,
                                         @Value("${sheltr.search.relevance-threshold}") double threshold) {
        return switch (type) {
            case "threshold" -> new ThresholdRelevanceJudge(threshold);
            case "model" -> new ModelRelevanceJudge(chatModel::call, minProbability);
            default -> throw new IllegalArgumentException(
                    "sheltr.judge.type must be 'threshold' or 'model', got '" + type + "'");
        };
    }

    @Bean
    public AnswerPipeline answerPipeline(Retriever retriever, Reranker reranker, RelevanceJudge relevanceJudge,
                                         AnswerGenerator answerGenerator) {
        return new AnswerPipeline(retriever, reranker, relevanceJudge, answerGenerator);
    }
}
```

`MAIN/ai/config/AskQuestionServiceConfig.java`:

```java
package com.sheltr.ai.config;

import com.sheltr.ai.application.AnswerPipeline;
import com.sheltr.ai.application.AskQuestionService;
import com.sheltr.ai.domain.ConversationRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AskQuestionServiceConfig {

    @Bean
    public AskQuestionService askQuestionService(AnswerPipeline answerPipeline,
                                                 ConversationRepository conversationRepository) {
        return new AskQuestionService(answerPipeline, conversationRepository);
    }
}
```

In `MAIN/ai/config/LuminaChatAppConfig.java`, replace the `luminaApp` bean method, drop the `Retriever` and `@Value` imports, and import `com.sheltr.ai.application.AnswerPipeline`:

```java
    @Bean
    public LuminaApp luminaApp(ConversationRepository conversationRepository, AnswerPipeline answerPipeline,
                               AnswerContextBuilder answerContextBuilder, ChatClient chatClient) {
        return new LuminaChatApp(conversationRepository, answerPipeline, answerContextBuilder, chatClient);
    }
```

In `RES/application.yml`, add under `sheltr:` as siblings of `search:`:

```yaml
  judge:
    # threshold | model. threshold uses sheltr.search.relevance-threshold from the active profile.
    type: threshold
    min-probability: 0.5
  confidence:
    # Refitted on the eval tuning split for the chosen judge (Task 14).
    high: 0.90
    medium: 0.75
```

- [ ] **Step 10: Delete the old gate and update existing tests**

```bash
git rm apps/backend/src/main/java/com/sheltr/ai/domain/RelevanceGate.java \
       apps/backend/src/test/java/com/sheltr/ai/domain/RelevanceGateTest.java
```

`TEST/ai/application/AskQuestionServiceTest.java` — add imports `com.sheltr.search.domain.NoOpReranker`; add this helper to the class:

```java
    private static AskQuestionService service(Retriever retriever, AnswerGenerator answerGenerator,
                                              ConversationRepository conversations) {
        return new AskQuestionService(new AnswerPipeline(retriever, new NoOpReranker(),
                new ThresholdRelevanceJudge(THRESHOLD), answerGenerator), conversations);
    }
```

Replace each `new AskQuestionService(retriever, answerGenerator, conversations, THRESHOLD)` with `service(retriever, answerGenerator, conversations)`. Change every `AnswerGenerator` lambda from `(query, results) ->` to `(query, results, verdict) ->`.

`TEST/ai/application/AnswerContextBuilderTest.java` — both `new AnswerContextBuilder(documents)` become `new AnswerContextBuilder(documents, ConfidenceBands.DEFAULT)`. The first `buildContext` call gains a third argument `new RelevanceVerdict(true, 0.88)`; the second gains `new RelevanceVerdict(true, 0.91)`. Add imports `com.sheltr.ai.domain.ConfidenceBands` and `com.sheltr.ai.domain.RelevanceVerdict`.

`TEST/ai/infrastructure/SpringAiAnswerGeneratorAdapterTest.java` — `new AnswerContextBuilder(documentRepository)` becomes `new AnswerContextBuilder(documentRepository, ConfidenceBands.DEFAULT)`, and `adapter.generate(query, List.of(result))` gains a third argument `new RelevanceVerdict(true, 0.88)`.

`TEST/ai/interfaces/LuminaChatAppTest.java` — add this helper and the imports `com.sheltr.ai.application.AnswerPipeline`, `com.sheltr.ai.domain.ThresholdRelevanceJudge`, `com.sheltr.search.domain.NoOpReranker`:

```java
    private static AnswerPipeline pipeline(Retriever retriever) {
        return new AnswerPipeline(retriever, new NoOpReranker(), new ThresholdRelevanceJudge(THRESHOLD),
                (query, results, verdict) -> {
                    throw new AssertionError("Lumina streams through ChatClient, not AnswerGenerator");
                });
    }
```

Replace both `new LuminaChatApp(conversations, retriever, contextBuilder, chatClient, THRESHOLD)` with `new LuminaChatApp(conversations, pipeline(retriever), contextBuilder, chatClient)`. Change the stub `contextBuilder.buildContext(any(), anyList())` to `contextBuilder.buildContext(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any())`.

`TEST/ai/domain/AnswerConfidenceTest.java` — in the comment that mentions `RelevanceGate`, write `ThresholdRelevanceJudge` instead. Add this test:

```java
    @Test
    void usesTheGivenBandsInsteadOfTheDefaults() {
        ConfidenceBands bands = new ConfidenceBands(0.60, 0.40);

        assertThat(AnswerConfidence.of(0.65, bands).level()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(AnswerConfidence.of(0.45, bands).level()).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(AnswerConfidence.of(0.10, bands).level()).isEqualTo(ConfidenceLevel.LOW);
    }
```

- [ ] **Step 11: Run the full suite and commit**

Run: `$MVN test`
Expected: PASS.

```bash
git add -A apps/backend/src
git commit -m "feat(ai): add relevance judge port and single answer pipeline"
```

---

### Task 9: Used-passage citations and active hazards in the API

**Files:**
- Create: `MAIN/ai/domain/CitationSelector.java`, `TEST/ai/domain/CitationSelectorTest.java`
- Modify: `MAIN/ai/application/AnswerContextBuilder.java`
- Modify: `MAIN/ai/infrastructure/SpringAiAnswerGeneratorAdapter.java`
- Modify: `MAIN/ai/interfaces/LuminaChatApp.java`, `AskRequestDto.java`, `ConversationController.java`
- Create: `TEST/ai/infrastructure/SpringAiAnswerGeneratorAdapterCitationTest.java`, `TEST/ai/interfaces/ConversationControllerHazardTest.java`
- Modify: `TEST/ai/application/AnswerContextBuilderTest.java`

**Interfaces:**
- Consumes: `AnswerContextBuilder.buildContext(SearchQuery, List<RankedResult>, RelevanceVerdict)`, `AskQuestionService.ask(ConversationId, String, Set<DisasterType>)` (Task 8).
- Produces:
  - `CitationSelector.select(String answer, List<RankedResult> passages, List<Citation> citations)` → `List<Citation>` for the passage numbers the answer cites.
  - `new SpringAiAnswerGeneratorAdapter(UnaryOperator<String> llm, AnswerContextBuilder)`; returns `NoAnswerAvailable` when the answer cites no valid passage.
  - `record AskRequestDto(String question, List<String> activeHazards)` plus the one-argument constructor.
  - `LuminaChatApp.UNCITED_WARNING`.

- [ ] **Step 1: Write the failing selector test**

`TEST/ai/domain/CitationSelectorTest.java`:

```java
package com.sheltr.ai.domain;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.search.domain.RankedResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CitationSelectorTest {

    private final RankedResult first = passage();
    private final RankedResult second = passage();
    private final RankedResult third = passage();
    private final List<RankedResult> passages = List.of(first, second, third);
    private final List<Citation> citations = List.of(citation(first, "One"), citation(second, "Two"),
            citation(third, "Three"));

    @Test
    void keepsOnlyThePassagesTheAnswerCites() {
        List<Citation> used = CitationSelector.select("Boil it [3]. Then cool it [1].", passages, citations);

        assertThat(used).extracting(Citation::sourceTitle).containsExactly("One", "Three");
    }

    @Test
    void ignoresPassageNumbersThatDoNotExist() {
        assertThat(CitationSelector.select("Boil it [7]. Or [0].", passages, citations)).isEmpty();
    }

    @Test
    void returnsNothingWhenTheAnswerCitesNothing() {
        assertThat(CitationSelector.select("Boil it for one minute.", passages, citations)).isEmpty();
        assertThat(CitationSelector.select(null, passages, citations)).isEmpty();
    }

    @Test
    void skipsACitedPassageWhoseDocumentHadNoCitation() {
        List<Citation> withoutSecond = List.of(citation(first, "One"), citation(third, "Three"));

        assertThat(CitationSelector.select("See [2] and [3].", passages, withoutSecond))
                .extracting(Citation::sourceTitle).containsExactly("Three");
    }

    private static RankedResult passage() {
        return new RankedResult(new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()), "text", 0.8);
    }

    private static Citation citation(RankedResult passage, String title) {
        return new Citation(passage.documentId(), passage.chunkId(), title, "https://example.org", null);
    }
}
```

- [ ] **Step 2: Run it, confirm it fails, then implement**

Run: `$MVN test -Dtest=CitationSelectorTest`
Expected: compilation FAIL.

`MAIN/ai/domain/CitationSelector.java`:

```java
package com.sheltr.ai.domain;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.search.domain.RankedResult;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds which numbered passages an answer cites, written as [1], [2], and so on. */
public final class CitationSelector {

    private static final Pattern MARKER = Pattern.compile("\\[(\\d{1,3})\\]");

    private CitationSelector() {
    }

    public static List<Citation> select(String answer, List<RankedResult> passages, List<Citation> citations) {
        Set<ChunkId> cited = new HashSet<>();
        Matcher matcher = MARKER.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            int number = Integer.parseInt(matcher.group(1));
            if (number >= 1 && number <= passages.size()) {
                cited.add(passages.get(number - 1).chunkId());
            }
        }
        return citations.stream().filter(citation -> cited.contains(citation.chunkId())).toList();
    }
}
```

Run: `$MVN test -Dtest=CitationSelectorTest`
Expected: PASS, 4 tests.

- [ ] **Step 3: Write the failing prompt tests**

In `TEST/ai/application/AnswerContextBuilderTest.java`, leave the two existing tests as they are and add this helper and these two tests:

```java
    private record Fixture(KnowledgeDocumentRepository documents, RankedResult result) {
    }

    private static Fixture floodFixture() {
        DocumentId documentId = new DocumentId(UUID.randomUUID());
        KnowledgeDocument document = new KnowledgeDocument(
                documentId, "Flood Safety and Water Purification", "American Red Cross",
                "https://www.redcross.org/floods", Set.of(DisasterType.FLOOD),
                LocalDate.of(2023, 1, 1), new DocumentVersion(1), new Checksum("seed-fl-1"),
                null, DocumentStatus.ACTIVE);
        KnowledgeDocumentRepository documents = new KnowledgeDocumentRepository() {
            @Override
            public Optional<KnowledgeDocument> findById(DocumentId id) {
                return id.equals(documentId) ? Optional.of(document) : Optional.empty();
            }

            @Override
            public void save(KnowledgeDocument ignored) {
                throw new UnsupportedOperationException("not needed by this test");
            }
        };
        RankedResult result = new RankedResult(new ChunkId(UUID.randomUUID()), documentId,
                "If tap water is contaminated after a flood, boil it at a rolling boil for one full minute.", 0.88);
        return new Fixture(documents, result);
    }

    @Test
    void numbersThePassagesAndAsksForCitationMarkers() {
        Fixture fixture = floodFixture();

        AnswerContext context = new AnswerContextBuilder(fixture.documents(), ConfidenceBands.DEFAULT)
                .buildContext(new SearchQuery("Is it safe to drink flood water?"), List.of(fixture.result()),
                        new RelevanceVerdict(true, 0.88));

        assertThat(context.prompt()).contains("[1] If tap water is contaminated after a flood");
        assertThat(context.prompt()).contains("like [1]");
        assertThat(context.prompt()).doesNotContain("Official alerts report");
    }

    @Test
    void statesActiveHazardsAsReportedContextWithoutAskingTheModelToConfirmThem() {
        Fixture fixture = floodFixture();

        AnswerContext context = new AnswerContextBuilder(fixture.documents(), ConfidenceBands.DEFAULT)
                .buildContext(new SearchQuery("What should I do?", java.util.Set.of(DisasterType.FLOOD)),
                        List.of(fixture.result()), new RelevanceVerdict(true, 0.88));

        assertThat(context.prompt()).contains("Official alerts report these hazards near the user: FLOOD");
        assertThat(context.prompt()).contains("Do not state whether any hazard is or is not occurring");
    }
```

- [ ] **Step 4: Rewrite the context builder**

`MAIN/ai/application/AnswerContextBuilder.java`:

```java
package com.sheltr.ai.application;

import com.sheltr.ai.domain.AnswerConfidence;
import com.sheltr.ai.domain.Citation;
import com.sheltr.ai.domain.ConfidenceBands;
import com.sheltr.ai.domain.RelevanceVerdict;
import com.sheltr.knowledge.domain.KnowledgeDocument;
import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class AnswerContextBuilder {

    private final KnowledgeDocumentRepository documentRepository;
    private final ConfidenceBands confidenceBands;

    public AnswerContextBuilder(KnowledgeDocumentRepository documentRepository, ConfidenceBands confidenceBands) {
        this.documentRepository = documentRepository;
        this.confidenceBands = confidenceBands;
    }

    public AnswerContext buildContext(SearchQuery query, List<RankedResult> results, RelevanceVerdict verdict) {
        StringBuilder passages = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            passages.append('[').append(i + 1).append("] ").append(results.get(i).content()).append('\n');
        }

        String hazards = "";
        if (!query.activeHazards().isEmpty()) {
            hazards = "Official alerts report these hazards near the user: "
                    + query.activeHazards().stream().map(Enum::name).sorted().collect(Collectors.joining(", "))
                    + ". Do not state whether any hazard is or is not occurring; only give guidance.\n\n";
        }

        String prompt = """
                You are a disaster-response assistant. Answer the question using ONLY the numbered \
                passages below. After every sentence, cite the passage that supports it like [1]. \
                If the passages do not contain enough information to answer, say so clearly rather \
                than guessing.

                %sPassages:
                %s
                Question: %s
                """.formatted(hazards, passages, query.text());

        List<Citation> citations = new ArrayList<>();
        for (RankedResult result : results) {
            KnowledgeDocument document = documentRepository.findById(result.documentId()).orElse(null);
            if (document != null) {
                citations.add(new Citation(result.documentId(), result.chunkId(),
                        document.title(), document.sourceUri(), null));
            }
        }

        return new AnswerContext(prompt, citations, AnswerConfidence.of(verdict.probability(), confidenceBands));
    }
}
```

Run: `$MVN test -Dtest=AnswerContextBuilderTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Write the failing adapter test**

`TEST/ai/infrastructure/SpringAiAnswerGeneratorAdapterCitationTest.java`:

```java
package com.sheltr.ai.infrastructure;

import com.sheltr.ai.application.AnswerContextBuilder;
import com.sheltr.ai.domain.*;
import com.sheltr.knowledge.domain.*;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiAnswerGeneratorAdapterCitationTest {

    private static final SearchQuery QUERY = new SearchQuery("Is it safe to drink flood water?");
    private static final RelevanceVerdict VERDICT = new RelevanceVerdict(true, 0.88);

    private final DocumentId documentId = new DocumentId(UUID.randomUUID());
    private final RankedResult boil = passage("Boil water for one minute.");
    private final RankedResult bleach = passage("Disinfect with unscented bleach.");

    @Test
    void citesOnlyThePassagesTheAnswerUsed() {
        AnswerOutcome outcome = adapter("Boil it for one minute [1].").generate(QUERY, List.of(boil, bleach), VERDICT);

        Message message = ((AnswerGenerated) outcome).message();
        assertThat(message.citations()).extracting(Citation::chunkId).containsExactly(boil.chunkId());
        assertThat(message.retrievedChunkIds()).containsExactly(boil.chunkId(), bleach.chunkId());
    }

    @Test
    void refusesAnAnswerThatCitesNothing() {
        assertThat(adapter("Boil it for one minute.").generate(QUERY, List.of(boil, bleach), VERDICT))
                .isInstanceOf(NoAnswerAvailable.class);
    }

    @Test
    void refusesAnAnswerThatOnlyCitesAPassageThatDoesNotExist() {
        assertThat(adapter("Boil it for one minute [7].").generate(QUERY, List.of(boil, bleach), VERDICT))
                .isInstanceOf(NoAnswerAvailable.class);
    }

    @Test
    void reportsAFailedGeneration() {
        SpringAiAnswerGeneratorAdapter adapter = new SpringAiAnswerGeneratorAdapter(prompt -> {
            throw new IllegalStateException("model unavailable");
        }, new AnswerContextBuilder(documents(), ConfidenceBands.DEFAULT));

        assertThat(adapter.generate(QUERY, List.of(boil), VERDICT)).isInstanceOf(AnswerGenerationFailed.class);
    }

    private SpringAiAnswerGeneratorAdapter adapter(String reply) {
        return new SpringAiAnswerGeneratorAdapter(prompt -> reply,
                new AnswerContextBuilder(documents(), ConfidenceBands.DEFAULT));
    }

    private RankedResult passage(String content) {
        return new RankedResult(new ChunkId(UUID.randomUUID()), documentId, content, 0.88);
    }

    private KnowledgeDocumentRepository documents() {
        KnowledgeDocument document = new KnowledgeDocument(documentId, "Flood Safety", "Example Agency",
                "https://example.org/flood.pdf", Set.of(DisasterType.FLOOD), LocalDate.of(2021, 3, 15),
                new DocumentVersion(1), new Checksum("abc"), null, DocumentStatus.ACTIVE);
        return new KnowledgeDocumentRepository() {
            @Override
            public Optional<KnowledgeDocument> findById(DocumentId id) {
                return id.equals(documentId) ? Optional.of(document) : Optional.empty();
            }

            @Override
            public void save(KnowledgeDocument ignored) {
                throw new UnsupportedOperationException("not needed by this test");
            }
        };
    }
}
```

- [ ] **Step 6: Rewrite the generator adapter**

`MAIN/ai/infrastructure/SpringAiAnswerGeneratorAdapter.java`:

```java
package com.sheltr.ai.infrastructure;

import com.sheltr.ai.application.AnswerContext;
import com.sheltr.ai.application.AnswerContextBuilder;
import com.sheltr.ai.domain.*;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;

import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

public class SpringAiAnswerGeneratorAdapter implements AnswerGenerator {

    private final UnaryOperator<String> llm;
    private final AnswerContextBuilder answerContextBuilder;

    public SpringAiAnswerGeneratorAdapter(UnaryOperator<String> llm, AnswerContextBuilder answerContextBuilder) {
        this.llm = llm;
        this.answerContextBuilder = answerContextBuilder;
    }

    @Override
    public AnswerOutcome generate(SearchQuery query, List<RankedResult> results, RelevanceVerdict verdict) {
        AnswerContext context = answerContextBuilder.buildContext(query, results, verdict);
        String answerText;
        try {
            answerText = llm.apply(context.prompt());
        } catch (Exception e) {
            return new AnswerGenerationFailed("Answer generation failed: " + e.getMessage());
        }

        // ADR-0008: an answer that cannot be tied to a retrieved passage is never shown.
        List<Citation> used = CitationSelector.select(answerText, results, context.citations());
        if (used.isEmpty()) {
            return new NoAnswerAvailable();
        }

        Message message = new Message(
                new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT, new MessageContent(answerText),
                used, context.confidence(),
                results.stream().map(RankedResult::chunkId).toList());

        return new AnswerGenerated(message);
    }
}
```

In `MAIN/ai/config/AnswerGeneratorConfig.java`, change the bean body to `return new SpringAiAnswerGeneratorAdapter(chatModel::call, answerContextBuilder);`.

In `TEST/ai/infrastructure/SpringAiAnswerGeneratorAdapterTest.java`, change `new SpringAiAnswerGeneratorAdapter(chatModel, ...)` to `new SpringAiAnswerGeneratorAdapter(chatModel::call, ...)`.

Run: `$MVN test -Dtest=SpringAiAnswerGeneratorAdapterCitationTest`
Expected: PASS, 4 tests.

- [ ] **Step 7: Store used citations in the Lumina chat app**

In `MAIN/ai/interfaces/LuminaChatApp.java`, add the constant:

```java
    static final String UNCITED_WARNING =
            "This answer could not be tied to a source passage and should not be relied on.";
```

Inside the `try` block, replace the lines from `String reply = ui.ai(chatClient.stream(context.prompt()));` through `ui.text("Confidence: " + context.confidence().level().name());` with:

```java
            String reply = ui.ai(chatClient.stream(context.prompt()));
            List<Citation> used = CitationSelector.select(reply, results, context.citations());
            conversation.addMessage(new Message(
                    new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT, new MessageContent(reply),
                    used, context.confidence(),
                    results.stream().map(RankedResult::chunkId).toList()));
            conversationRepository.save(conversation);
            if (used.isEmpty()) {
                ui.text(UNCITED_WARNING);
            } else {
                ui.text("Confidence: " + context.confidence().level().name());
            }
```

- [ ] **Step 8: Write the failing API test**

`TEST/ai/interfaces/ConversationControllerHazardTest.java`:

```java
package com.sheltr.ai.interfaces;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationControllerHazardTest {

    // An invalid request never reaches the collaborators, so nulls are safe here.
    private final ConversationController controller = new ConversationController(null, null);

    @Test
    void rejectsAnUnknownActiveHazardWithBadRequest() {
        var response = controller.ask(UUID.randomUUID(), new AskRequestDto("What should I do?", List.of("METEOR")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsABlankQuestionWithBadRequest() {
        var response = controller.ask(UUID.randomUUID(), new AskRequestDto("  ", List.of("FLOOD")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
```

- [ ] **Step 9: Implement the request DTO and controller change**

`MAIN/ai/interfaces/AskRequestDto.java`:

```java
package com.sheltr.ai.interfaces;

import java.util.List;

/**
 * @param activeHazards optional hazard type names reported near the asker by an official feed
 */
public record AskRequestDto(String question, List<String> activeHazards) {
    public AskRequestDto(String question) {
        this(question, null);
    }
}
```

In `MAIN/ai/interfaces/ConversationController.java`, replace `ask` and add imports `com.sheltr.knowledge.domain.DisasterType`, `java.util.Set`, `java.util.stream.Collectors`:

```java
    @PostMapping("/{id}/ask")
    public ResponseEntity<MessageDto> ask(@PathVariable UUID id, @RequestBody AskRequestDto request) {
        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Set<DisasterType> activeHazards;
        try {
            activeHazards = request.activeHazards() == null ? Set.of()
                    : request.activeHazards().stream().map(DisasterType::valueOf).collect(Collectors.toSet());
        } catch (IllegalArgumentException | NullPointerException e) {
            return ResponseEntity.badRequest().build();
        }
        Message message = askQuestionService.ask(new ConversationId(id), request.question(), activeHazards);
        return ResponseEntity.ok(toDto(message));
    }
```

- [ ] **Step 10: Run the full suite and commit**

Run: `$MVN test`
Expected: PASS.

If Ollama is running, also run the gated local tests: `OLLAMA_AVAILABLE=true $MVN test -Dtest='LocalRagLoopEndToEndTest,LocalLuminaChatEndToEndTest'`. They may now fail because the small local model answers without `[1]` markers and is refused. Do not loosen the citation rule to make them pass. Record the failure in the commit message; Task 14 measures how often each model cites correctly and chooses the default from that.

```bash
git add -A apps/backend/src
git commit -m "feat(ai): cite only used passages and accept active hazards on ask"
```

---

# Phase C — Eval

### Task 10: Eval question model, loader, and metrics

**Files:**
- Create: `MAIN/eval/domain/EvalKind.java`, `ExpectedPassage.java`, `EvalQuestion.java`, `RetrievedPassage.java`, `EvalResult.java`, `EvalRun.java`, `Metrics.java`
- Create: `MAIN/eval/application/EvalJson.java`, `EvalQuestionLoader.java`
- Create: `TEST/eval/domain/MetricsTest.java`, `TEST/eval/application/EvalQuestionLoaderTest.java`

**Interfaces:**
- Consumes: `DisasterType`.
- Produces:
  - `enum EvalKind { ANSWERABLE, MULTI_STEP, OFF_TOPIC, UNCOVERED }` with `boolean mustRefuse()`.
  - `record ExpectedPassage(String sourceUri, String contains)`.
  - `record EvalQuestion(String id, String question, EvalKind kind, Set<DisasterType> activeHazards, List<ExpectedPassage> expectedPassages, List<String> keyFacts, String split)`.
  - `record RetrievedPassage(String sourceUri, String content)`.
  - `record EvalResult(String questionId, List<RetrievedPassage> passages, boolean refused, String answer, Double confidence, List<RetrievedPassage> cited, long latencyMs, String error)`.
  - `record EvalRun(String config, String startedAt, Map<String, String> settings, List<EvalResult> results)`.
  - `EvalJson.MAPPER` (snake_case, pretty) and `EvalJson.LINE_WRITER` (one object per line).
  - `EvalQuestionLoader.load(Path)` → `List<EvalQuestion>`.
  - `Metrics.Scored(EvalQuestion question, EvalResult result)`; static `matches`, `hit`, `firstHitRank`, `recallAtK`, `meanReciprocalRank`, `falseAcceptRate`, `falseRefuseRate`, `calibrationError`, `percentile`.

- [ ] **Step 1: Write the failing metrics test**

`TEST/eval/domain/MetricsTest.java`:

```java
package com.sheltr.eval.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MetricsTest {

    private static final String URI = "https://example.org/flood.pdf";
    private static final ExpectedPassage BOIL = new ExpectedPassage(URI, "rolling boil for one minute");
    private static final ExpectedPassage BLEACH = new ExpectedPassage(URI, "unscented bleach");

    @Test
    void matchesIgnoringCaseAndLineBreaksButNotAcrossDocuments() {
        assertThat(Metrics.matches(BOIL, new RetrievedPassage(URI, "Bring to a ROLLING\n boil   for one minute.")))
                .isTrue();
        assertThat(Metrics.matches(BOIL,
                new RetrievedPassage("https://example.org/other.pdf", "rolling boil for one minute"))).isFalse();
    }

    @Test
    void anAnswerableQuestionIsHitWhenAnyExpectedPassageIsInTheTopK() {
        EvalQuestion question = question("q1", EvalKind.ANSWERABLE, List.of(BOIL, BLEACH));
        List<RetrievedPassage> passages = List.of(passage("unrelated"), passage("use unscented bleach"));

        assertThat(Metrics.hit(question, passages, 5)).isTrue();
        assertThat(Metrics.hit(question, passages, 1)).isFalse();
        assertThat(Metrics.firstHitRank(question, passages)).isEqualTo(2);
    }

    @Test
    void aMultiStepQuestionIsHitOnlyWhenEveryExpectedPassageIsInTheTopK() {
        EvalQuestion question = question("q1", EvalKind.MULTI_STEP, List.of(BOIL, BLEACH));

        assertThat(Metrics.hit(question, List.of(passage("use unscented bleach")), 5)).isFalse();
        assertThat(Metrics.hit(question,
                List.of(passage("use unscented bleach"), passage("rolling boil for one minute")), 5)).isTrue();
    }

    @Test
    void recallAndReciprocalRankCoverOnlyQuestionsThatShouldBeAnswered() {
        List<Metrics.Scored> scored = List.of(
                scored(question("hit-first", EvalKind.ANSWERABLE, List.of(BOIL)),
                        result("hit-first", false, List.of(passage("rolling boil for one minute")))),
                scored(question("miss", EvalKind.ANSWERABLE, List.of(BOIL)),
                        result("miss", false, List.of(passage("unrelated")))),
                scored(question("off", EvalKind.OFF_TOPIC, List.of()), result("off", true, List.of())));

        assertThat(Metrics.recallAtK(scored, 5)).isEqualTo(0.5);
        assertThat(Metrics.meanReciprocalRank(scored)).isEqualTo(0.5);
    }

    @Test
    void falseAcceptsAreMustRefuseQuestionsThatGotAnAnswer() {
        List<Metrics.Scored> scored = List.of(
                scored(question("off", EvalKind.OFF_TOPIC, List.of()), result("off", false, List.of())),
                scored(question("uncovered", EvalKind.UNCOVERED, List.of()), result("uncovered", true, List.of())),
                scored(question("ok", EvalKind.ANSWERABLE, List.of(BOIL)), result("ok", true, List.of())));

        assertThat(Metrics.falseAcceptRate(scored)).isEqualTo(0.5);
        assertThat(Metrics.falseRefuseRate(scored)).isEqualTo(1.0);
    }

    @Test
    void ratesAreNotANumberWhenNoQuestionApplies() {
        assertThat(Metrics.falseAcceptRate(List.of())).isNaN();
        assertThat(Metrics.recallAtK(List.of(), 5)).isNaN();
    }

    @Test
    void calibrationErrorIsTheGapBetweenStatedConfidenceAndObservedAccuracy() {
        // Two answers at 0.9 confidence, one right: accuracy 0.5, gap 0.4.
        assertThat(Metrics.calibrationError(List.of(new double[]{0.9, 1}, new double[]{0.9, 0}), 5))
                .isCloseTo(0.4, within(1e-9));
        assertThat(Metrics.calibrationError(List.of(new double[]{1.0, 1}, new double[]{0.0, 0}), 5))
                .isCloseTo(0.0, within(1e-9));
    }

    @Test
    void percentilePicksTheNearestRank() {
        assertThat(Metrics.percentile(List.of(10L, 20L, 30L, 40L), 0.5)).isEqualTo(20L);
        assertThat(Metrics.percentile(List.of(10L, 20L, 30L, 40L), 0.95)).isEqualTo(40L);
        assertThat(Metrics.percentile(List.of(), 0.5)).isZero();
    }

    private static EvalQuestion question(String id, EvalKind kind, List<ExpectedPassage> expected) {
        return new EvalQuestion(id, "question", kind, Set.of(), expected, List.of(), "tune");
    }

    private static RetrievedPassage passage(String content) {
        return new RetrievedPassage(URI, content);
    }

    private static EvalResult result(String id, boolean refused, List<RetrievedPassage> passages) {
        return new EvalResult(id, passages, refused, refused ? null : "answer", 0.8, List.of(), 100, null);
    }

    private static Metrics.Scored scored(EvalQuestion question, EvalResult result) {
        return new Metrics.Scored(question, result);
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `$MVN test -Dtest=MetricsTest`
Expected: compilation FAIL.

- [ ] **Step 3: Implement the eval domain**

`MAIN/eval/domain/EvalKind.java`:

```java
package com.sheltr.eval.domain;

public enum EvalKind {
    /** One corpus passage answers it. */
    ANSWERABLE,
    /** Needs passages about more than one hazard or document. */
    MULTI_STEP,
    /** Nothing to do with disasters. */
    OFF_TOPIC,
    /** About disasters, but the corpus does not answer it. */
    UNCOVERED;

    public boolean mustRefuse() {
        return this == OFF_TOPIC || this == UNCOVERED;
    }
}
```

`MAIN/eval/domain/ExpectedPassage.java`:

```java
package com.sheltr.eval.domain;

/** A passage is identified by a verbatim phrase, not a chunk ID, so it survives re-ingestion. */
public record ExpectedPassage(String sourceUri, String contains) {
}
```

`MAIN/eval/domain/EvalQuestion.java`:

```java
package com.sheltr.eval.domain;

import com.sheltr.knowledge.domain.DisasterType;

import java.util.List;
import java.util.Set;

public record EvalQuestion(String id, String question, EvalKind kind, Set<DisasterType> activeHazards,
                           List<ExpectedPassage> expectedPassages, List<String> keyFacts, String split) {
    public EvalQuestion {
        activeHazards = Set.copyOf(activeHazards == null ? Set.of() : activeHazards);
        expectedPassages = List.copyOf(expectedPassages == null ? List.of() : expectedPassages);
        keyFacts = List.copyOf(keyFacts == null ? List.of() : keyFacts);
    }
}
```

`MAIN/eval/domain/RetrievedPassage.java`:

```java
package com.sheltr.eval.domain;

public record RetrievedPassage(String sourceUri, String content) {
}
```

`MAIN/eval/domain/EvalResult.java`:

```java
package com.sheltr.eval.domain;

import java.util.List;

/**
 * @param confidence the relevance judge's probability; null when the pipeline failed before judging
 * @param error      set when generation or the pipeline failed; such a result counts as refused
 */
public record EvalResult(String questionId, List<RetrievedPassage> passages, boolean refused, String answer,
                         Double confidence, List<RetrievedPassage> cited, long latencyMs, String error) {
    public EvalResult {
        passages = List.copyOf(passages == null ? List.of() : passages);
        cited = List.copyOf(cited == null ? List.of() : cited);
    }
}
```

`MAIN/eval/domain/EvalRun.java`:

```java
package com.sheltr.eval.domain;

import java.util.List;
import java.util.Map;

public record EvalRun(String config, String startedAt, Map<String, String> settings, List<EvalResult> results) {
}
```

`MAIN/eval/domain/Metrics.java`:

```java
package com.sheltr.eval.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Metrics {

    public record Scored(EvalQuestion question, EvalResult result) {
    }

    private Metrics() {
    }

    public static boolean matches(ExpectedPassage expected, RetrievedPassage passage) {
        return expected.sourceUri().equals(passage.sourceUri())
                && normalise(passage.content()).contains(normalise(expected.contains()));
    }

    /** ANSWERABLE needs any expected passage in the top k; MULTI_STEP needs all of them. */
    public static boolean hit(EvalQuestion question, List<RetrievedPassage> passages, int k) {
        List<RetrievedPassage> top = passages.stream().limit(k).toList();
        if (question.expectedPassages().isEmpty()) {
            return false;
        }
        if (question.kind() == EvalKind.MULTI_STEP) {
            return question.expectedPassages().stream()
                    .allMatch(expected -> top.stream().anyMatch(passage -> matches(expected, passage)));
        }
        return question.expectedPassages().stream()
                .anyMatch(expected -> top.stream().anyMatch(passage -> matches(expected, passage)));
    }

    /** One-based rank of the first retrieved passage matching any expected passage; 0 when none does. */
    public static int firstHitRank(EvalQuestion question, List<RetrievedPassage> passages) {
        for (int i = 0; i < passages.size(); i++) {
            RetrievedPassage passage = passages.get(i);
            if (question.expectedPassages().stream().anyMatch(expected -> matches(expected, passage))) {
                return i + 1;
            }
        }
        return 0;
    }

    public static double recallAtK(List<Scored> scored, int k) {
        List<Scored> answerable = answerable(scored);
        return ratio(answerable.stream().filter(s -> hit(s.question(), s.result().passages(), k)).count(),
                answerable.size());
    }

    public static double meanReciprocalRank(List<Scored> scored) {
        List<Scored> answerable = answerable(scored);
        if (answerable.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0;
        for (Scored s : answerable) {
            int rank = firstHitRank(s.question(), s.result().passages());
            sum += rank == 0 ? 0.0 : 1.0 / rank;
        }
        return sum / answerable.size();
    }

    public static double falseAcceptRate(List<Scored> scored) {
        List<Scored> mustRefuse = scored.stream().filter(s -> s.question().kind().mustRefuse()).toList();
        return ratio(mustRefuse.stream().filter(s -> !s.result().refused()).count(), mustRefuse.size());
    }

    public static double falseRefuseRate(List<Scored> scored) {
        List<Scored> answerable = answerable(scored);
        return ratio(answerable.stream().filter(s -> s.result().refused()).count(), answerable.size());
    }

    /**
     * Expected calibration error over equal-width confidence bins.
     *
     * @param confidenceAndCorrect pairs of {confidence in 0..1, 1 if correct else 0}
     */
    public static double calibrationError(List<double[]> confidenceAndCorrect, int bins) {
        if (confidenceAndCorrect.isEmpty()) {
            return Double.NaN;
        }
        double[] confidenceSum = new double[bins];
        double[] correctSum = new double[bins];
        int[] count = new int[bins];
        for (double[] pair : confidenceAndCorrect) {
            int bin = Math.min(bins - 1, (int) (pair[0] * bins));
            confidenceSum[bin] += pair[0];
            correctSum[bin] += pair[1];
            count[bin]++;
        }
        double error = 0;
        for (int bin = 0; bin < bins; bin++) {
            if (count[bin] > 0) {
                error += Math.abs(confidenceSum[bin] / count[bin] - correctSum[bin] / count[bin])
                        * count[bin] / confidenceAndCorrect.size();
            }
        }
        return error;
    }

    public static long percentile(List<Long> values, double p) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static List<Scored> answerable(List<Scored> scored) {
        return scored.stream().filter(s -> !s.question().kind().mustRefuse()).toList();
    }

    private static double ratio(long numerator, int denominator) {
        return denominator == 0 ? Double.NaN : (double) numerator / denominator;
    }

    private static String normalise(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
```

- [ ] **Step 4: Run the metrics test**

Run: `$MVN test -Dtest=MetricsTest`
Expected: PASS, 8 tests.

- [ ] **Step 5: Write the failing loader test**

`TEST/eval/application/EvalQuestionLoaderTest.java`:

```java
package com.sheltr.eval.application;

import com.sheltr.eval.domain.EvalKind;
import com.sheltr.eval.domain.EvalQuestion;
import com.sheltr.knowledge.domain.DisasterType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvalQuestionLoaderTest {

    private static final String ANSWERABLE = """
            {"id":"flood-001","question":"How long should I boil water?","kind":"ANSWERABLE","active_hazards":["FLOOD"],"expected_passages":[{"source_uri":"https://example.org/flood.pdf","contains":"rolling boil"}],"key_facts":["one minute"],"split":"tune"}""";
    private static final String OFF_TOPIC = """
            {"id":"off-001","question":"How do I bake bread?","kind":"OFF_TOPIC","split":"heldout"}""";

    @TempDir
    Path dir;

    @Test
    void loadsQuestionsAndSkipsBlankLines() throws IOException {
        List<EvalQuestion> questions = EvalQuestionLoader.load(file(ANSWERABLE + "\n\n" + OFF_TOPIC + "\n"));

        assertThat(questions).hasSize(2);
        EvalQuestion first = questions.get(0);
        assertThat(first.id()).isEqualTo("flood-001");
        assertThat(first.kind()).isEqualTo(EvalKind.ANSWERABLE);
        assertThat(first.activeHazards()).containsExactly(DisasterType.FLOOD);
        assertThat(first.expectedPassages().get(0).contains()).isEqualTo("rolling boil");
        assertThat(first.keyFacts()).containsExactly("one minute");
        assertThat(questions.get(1).expectedPassages()).isEmpty();
    }

    @Test
    void rejectsADuplicateId() {
        assertThatThrownBy(() -> EvalQuestionLoader.load(file(OFF_TOPIC + "\n" + OFF_TOPIC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line 2")
                .hasMessageContaining("off-001");
    }

    @Test
    void rejectsAnAnswerableQuestionWithoutAnExpectedPassageOrKeyFacts() {
        String noPassage = ANSWERABLE.replace(
                "[{\"source_uri\":\"https://example.org/flood.pdf\",\"contains\":\"rolling boil\"}]", "[]");

        assertThatThrownBy(() -> EvalQuestionLoader.load(file(noPassage)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected_passages");
        assertThatThrownBy(() -> EvalQuestionLoader.load(file(ANSWERABLE.replace("[\"one minute\"]", "[]"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key_facts");
    }

    @Test
    void rejectsAMustRefuseQuestionThatNamesAnExpectedPassage() {
        String wrong = ANSWERABLE.replace("\"ANSWERABLE\"", "\"UNCOVERED\"");

        assertThatThrownBy(() -> EvalQuestionLoader.load(file(wrong)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not have expected_passages");
    }

    @Test
    void rejectsAnUnknownSplitAndUnreadableJson() {
        assertThatThrownBy(() -> EvalQuestionLoader.load(file(OFF_TOPIC.replace("heldout", "test"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("split");
        assertThatThrownBy(() -> EvalQuestionLoader.load(file("{not json")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line 1");
    }

    private Path file(String content) throws IOException {
        Path path = dir.resolve("questions.jsonl");
        Files.writeString(path, content);
        return path;
    }
}
```

- [ ] **Step 6: Implement JSON settings and the loader**

`MAIN/eval/application/EvalJson.java`:

```java
package com.sheltr.eval.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;

/** JSON settings shared by every eval file: snake_case names, unknown fields ignored. */
public final class EvalJson {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** Writes one object per line, for .jsonl files. */
    public static final ObjectWriter LINE_WRITER = MAPPER.writer().without(SerializationFeature.INDENT_OUTPUT);

    private EvalJson() {
    }
}
```

`MAIN/eval/application/EvalQuestionLoader.java`:

```java
package com.sheltr.eval.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sheltr.eval.domain.EvalQuestion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class EvalQuestionLoader {

    private static final Set<String> SPLITS = Set.of("tune", "heldout");

    private EvalQuestionLoader() {
    }

    public static List<EvalQuestion> load(Path path) throws IOException {
        List<EvalQuestion> questions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        List<String> lines = Files.readAllLines(path);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            String where = "line " + (i + 1);
            EvalQuestion question;
            try {
                question = EvalJson.MAPPER.readValue(line, EvalQuestion.class);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException(where + ": not valid JSON: " + e.getOriginalMessage());
            }
            validate(question, where);
            if (!ids.add(question.id())) {
                throw new IllegalArgumentException(where + ": duplicate id " + question.id());
            }
            questions.add(question);
        }
        return questions;
    }

    private static void validate(EvalQuestion question, String where) {
        if (question.id() == null || question.id().isBlank()) {
            throw new IllegalArgumentException(where + ": id is required");
        }
        where = where + " (" + question.id() + ")";
        if (question.question() == null || question.question().isBlank()) {
            throw new IllegalArgumentException(where + ": question is required");
        }
        if (question.kind() == null) {
            throw new IllegalArgumentException(where + ": kind is required");
        }
        if (question.split() == null || !SPLITS.contains(question.split())) {
            throw new IllegalArgumentException(where + ": split must be 'tune' or 'heldout'");
        }
        if (question.kind().mustRefuse()) {
            if (!question.expectedPassages().isEmpty()) {
                throw new IllegalArgumentException(
                        where + ": a " + question.kind() + " question must not have expected_passages");
            }
        } else {
            if (question.expectedPassages().isEmpty()) {
                throw new IllegalArgumentException(where + ": expected_passages is required");
            }
            if (question.keyFacts().isEmpty()) {
                throw new IllegalArgumentException(where + ": key_facts is required");
            }
            if (question.expectedPassages().stream().anyMatch(passage -> passage.sourceUri() == null
                    || passage.contains() == null || passage.contains().isBlank())) {
                throw new IllegalArgumentException(
                        where + ": every expected passage needs source_uri and contains");
            }
        }
    }
}
```

- [ ] **Step 7: Run and commit**

Run: `$MVN test -Dtest='MetricsTest,EvalQuestionLoaderTest'`
Expected: PASS, 13 tests.

```bash
git add -A apps/backend/src
git commit -m "feat(eval): add question model, loader, and retrieval metrics"
```

---

### Task 11: Eval run

**Files:**
- Create: `MAIN/eval/application/EvalRunService.java`
- Create: `TEST/eval/application/EvalRunServiceTest.java`

**Interfaces:**
- Consumes: `AnswerPipeline.answer(SearchQuery)` → `PipelineResult(passages, verdict, outcome)` (Task 8); `KnowledgeDocumentRepository.findById`; eval records (Task 10).
- Produces: `new EvalRunService(AnswerPipeline, KnowledgeDocumentRepository)`; `EvalResult run(EvalQuestion)`.

- [ ] **Step 1: Write the failing test**

`TEST/eval/application/EvalRunServiceTest.java`:

```java
package com.sheltr.eval.application;

import com.sheltr.ai.application.AnswerPipeline;
import com.sheltr.ai.domain.*;
import com.sheltr.eval.domain.EvalKind;
import com.sheltr.eval.domain.EvalQuestion;
import com.sheltr.eval.domain.EvalResult;
import com.sheltr.knowledge.domain.*;
import com.sheltr.search.domain.NoOpReranker;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EvalRunServiceTest {

    private static final String URI = "https://example.org/flood.pdf";
    private final DocumentId documentId = new DocumentId(UUID.randomUUID());
    private final RankedResult boil = new RankedResult(new ChunkId(UUID.randomUUID()), documentId,
            "Boil water for one minute.", 0.9);
    private final RankedResult bleach = new RankedResult(new ChunkId(UUID.randomUUID()), documentId,
            "Use unscented bleach.", 0.9);
    private final EvalQuestion question = new EvalQuestion("flood-001", "How long should I boil water?",
            EvalKind.ANSWERABLE, Set.of(DisasterType.FLOOD), List.of(), List.of(), "tune");

    @Test
    void recordsPassagesAnswerConfidenceAndCitedPassages() {
        List<SearchQuery> queries = new ArrayList<>();
        Retriever retriever = (query, topK) -> {
            queries.add(query);
            return List.of(boil, bleach);
        };
        AnswerGenerator generator = (query, results, verdict) -> new AnswerGenerated(new Message(
                new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT, new MessageContent("One minute [1]."),
                List.of(new Citation(documentId, boil.chunkId(), "Flood", URI, null)),
                AnswerConfidence.fromSimilarity(0.9), List.of(boil.chunkId(), bleach.chunkId())));

        EvalResult result = service(retriever, generator).run(question);

        assertThat(queries.get(0).activeHazards()).containsExactly(DisasterType.FLOOD);
        assertThat(result.questionId()).isEqualTo("flood-001");
        assertThat(result.refused()).isFalse();
        assertThat(result.answer()).isEqualTo("One minute [1].");
        assertThat(result.confidence()).isEqualTo(0.9);
        assertThat(result.passages()).extracting("sourceUri").containsExactly(URI, URI);
        assertThat(result.cited()).extracting("content").containsExactly("Boil water for one minute.");
        assertThat(result.error()).isNull();
    }

    @Test
    void recordsARefusal() {
        RankedResult weak = new RankedResult(new ChunkId(UUID.randomUUID()), documentId, "unrelated", 0.2);

        EvalResult result = service((query, topK) -> List.of(weak), (query, results, verdict) -> {
            throw new AssertionError("generator must not be called");
        }).run(question);

        assertThat(result.refused()).isTrue();
        assertThat(result.answer()).isNull();
        assertThat(result.confidence()).isEqualTo(0.2);
        assertThat(result.passages()).hasSize(1);
    }

    @Test
    void recordsAFailedGenerationAsRefusedWithTheReason() {
        EvalResult result = service((query, topK) -> List.of(boil),
                (query, results, verdict) -> new AnswerGenerationFailed("model unavailable")).run(question);

        assertThat(result.refused()).isTrue();
        assertThat(result.error()).isEqualTo("model unavailable");
    }

    @Test
    void recordsAPipelineExceptionInsteadOfStoppingTheRun() {
        EvalResult result = service((query, topK) -> {
            throw new IllegalStateException("database down");
        }, (query, results, verdict) -> new AnswerGenerationFailed("unused")).run(question);

        assertThat(result.refused()).isTrue();
        assertThat(result.error()).isEqualTo("database down");
        assertThat(result.passages()).isEmpty();
    }

    private EvalRunService service(Retriever retriever, AnswerGenerator generator) {
        KnowledgeDocument document = new KnowledgeDocument(documentId, "Flood", "Agency", URI,
                Set.of(DisasterType.FLOOD), LocalDate.of(2021, 3, 15), new DocumentVersion(1),
                new Checksum("abc"), null, DocumentStatus.ACTIVE);
        KnowledgeDocumentRepository documents = new KnowledgeDocumentRepository() {
            @Override
            public Optional<KnowledgeDocument> findById(DocumentId id) {
                return id.equals(documentId) ? Optional.of(document) : Optional.empty();
            }

            @Override
            public void save(KnowledgeDocument ignored) {
                throw new UnsupportedOperationException("not needed by this test");
            }
        };
        return new EvalRunService(new AnswerPipeline(retriever, new NoOpReranker(),
                new ThresholdRelevanceJudge(0.75), generator), documents);
    }
}
```

- [ ] **Step 2: Run it, confirm it fails, then implement**

Run: `$MVN test -Dtest=EvalRunServiceTest`
Expected: compilation FAIL.

`MAIN/eval/application/EvalRunService.java`:

```java
package com.sheltr.eval.application;

import com.sheltr.ai.application.AnswerPipeline;
import com.sheltr.ai.domain.AnswerGenerated;
import com.sheltr.ai.domain.AnswerGenerationFailed;
import com.sheltr.ai.domain.Citation;
import com.sheltr.ai.domain.NoAnswerAvailable;
import com.sheltr.eval.domain.EvalQuestion;
import com.sheltr.eval.domain.EvalResult;
import com.sheltr.eval.domain.RetrievedPassage;
import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.knowledge.domain.KnowledgeDocument;
import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Runs one eval question through the same pipeline that serves users and records what happened. */
public class EvalRunService {

    private final AnswerPipeline pipeline;
    private final KnowledgeDocumentRepository documents;
    private final Map<DocumentId, String> sourceUris = new HashMap<>();

    public EvalRunService(AnswerPipeline pipeline, KnowledgeDocumentRepository documents) {
        this.pipeline = pipeline;
        this.documents = documents;
    }

    public EvalResult run(EvalQuestion question) {
        long start = System.nanoTime();
        try {
            AnswerPipeline.PipelineResult result =
                    pipeline.answer(new SearchQuery(question.question(), question.activeHazards()));
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            List<RetrievedPassage> passages = result.passages().stream().map(this::toPassage).toList();
            double confidence = result.verdict().probability();
            return switch (result.outcome()) {
                case AnswerGenerated generated -> {
                    Set<ChunkId> citedIds = generated.message().citations().stream()
                            .map(Citation::chunkId).collect(Collectors.toSet());
                    List<RetrievedPassage> cited = result.passages().stream()
                            .filter(passage -> citedIds.contains(passage.chunkId()))
                            .map(this::toPassage).toList();
                    yield new EvalResult(question.id(), passages, false,
                            generated.message().content().value(), confidence, cited, latencyMs, null);
                }
                case NoAnswerAvailable ignored -> new EvalResult(question.id(), passages, true, null,
                        confidence, List.of(), latencyMs, null);
                case AnswerGenerationFailed failed -> new EvalResult(question.id(), passages, true, null,
                        confidence, List.of(), latencyMs, failed.reason());
            };
        } catch (RuntimeException e) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            return new EvalResult(question.id(), List.of(), true, null, null, List.of(), latencyMs,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private RetrievedPassage toPassage(RankedResult result) {
        String sourceUri = sourceUris.computeIfAbsent(result.documentId(),
                id -> documents.findById(id).map(KnowledgeDocument::sourceUri).orElse(""));
        return new RetrievedPassage(sourceUri, result.content());
    }
}
```

- [ ] **Step 3: Run and commit**

Run: `$MVN test -Dtest=EvalRunServiceTest`
Expected: PASS, 4 tests.

```bash
git add -A apps/backend/src
git commit -m "feat(eval): run eval questions through the answer pipeline"
```

---

### Task 12: Eval scoring, report, and command-line runner

**Files:**
- Create: `MAIN/eval/domain/SplitScores.java`, `ConfidenceRow.java`, `EvalReport.java`, `SpotcheckItem.java`
- Create: `MAIN/eval/application/AnswerJudge.java`, `EvalScoreService.java`, `ReportWriter.java`
- Create: `MAIN/eval/config/EvalRunner.java`
- Create: `TEST/eval/application/AnswerJudgeTest.java`, `EvalScoreServiceTest.java`, `ReportWriterTest.java`
- Modify: `Makefile`, `.gitignore`

**Interfaces:**
- Consumes: eval records, `Metrics`, `EvalJson`, `EvalQuestionLoader` (Task 10); `EvalRunService` (Task 11).
- Produces:
  - `record SplitScores(int questions, double recallAt5, double meanReciprocalRank, double falseAcceptRate, double falseRefuseRate, double faithfulness, double keyFactCoverage, double calibrationError, long medianLatencyMs, long p95LatencyMs, int errors)`.
  - `record ConfidenceRow(double threshold, int answers, double accuracy)`.
  - `record EvalReport(String config, Map<String, String> settings, Map<String, SplitScores> splits, List<ConfidenceRow> tuneConfidenceTable, int judgeErrors)`.
  - `record SpotcheckItem(String id, String question, String answer, List<String> passages, boolean judgeFaithful, Boolean humanFaithful)`.
  - `new AnswerJudge(UnaryOperator<String> llm)`; `double faithfulness(String answer, List<String> passages)`; `double keyFactCoverage(String answer, List<String> keyFacts)`; `int errors()`.
  - `new EvalScoreService(AnswerJudge)`; `Output score(List<EvalQuestion>, EvalRun)` → `Output(EvalReport report, List<SpotcheckItem> spotcheck)`; static `double agreement(List<SpotcheckItem>)`.
  - `ReportWriter.markdown(EvalReport)` → `String`.
  - `make eval-validate`, `make eval CONFIG=<name>`, `make eval-score RAW=<path>`, `make eval-agreement SPOTCHECK=<path>`.

- [ ] **Step 1: Write the failing judge test**

`TEST/eval/application/AnswerJudgeTest.java`:

```java
package com.sheltr.eval.application;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerJudgeTest {

    private static final List<String> PASSAGES = List.of("Boil water at a rolling boil for one minute.");

    @Test
    void faithfulnessIsTheShareOfSentencesThePassagesSupport() {
        AnswerJudge judge = new AnswerJudge(prompt ->
                prompt.contains("Sentence: Boil it for one minute.") ? "SUPPORTED" : "UNSUPPORTED");

        assertThat(judge.faithfulness("Boil it for one minute [1]. Then add salt [1].", PASSAGES)).isEqualTo(0.5);
        assertThat(judge.errors()).isZero();
    }

    @Test
    void stripsCitationMarkersBeforeJudgingASentence() {
        List<String> prompts = new ArrayList<>();
        new AnswerJudge(prompt -> {
            prompts.add(prompt);
            return "SUPPORTED";
        }).faithfulness("Boil it for one minute [1].", PASSAGES);

        assertThat(prompts).hasSize(1);
        assertThat(prompts.get(0)).contains("Sentence: Boil it for one minute.").doesNotContain("[1].");
    }

    @Test
    void anUnreadableOrFailedReplyCountsAsUnsupportedAndIsCounted() {
        AnswerJudge unreadable = new AnswerJudge(prompt -> "Probably fine?");
        AnswerJudge failing = new AnswerJudge(prompt -> {
            throw new IllegalStateException("model unavailable");
        });

        assertThat(unreadable.faithfulness("Boil it.", PASSAGES)).isZero();
        assertThat(unreadable.errors()).isEqualTo(1);
        assertThat(failing.faithfulness("Boil it.", PASSAGES)).isZero();
        assertThat(failing.errors()).isEqualTo(1);
    }

    @Test
    void anEmptyAnswerHasZeroFaithfulness() {
        assertThat(new AnswerJudge(prompt -> "SUPPORTED").faithfulness("  ", PASSAGES)).isZero();
    }

    @Test
    void keyFactCoverageIsTheShareOfFactsTheAnswerStates() {
        AnswerJudge judge = new AnswerJudge(prompt -> prompt.contains("Fact: one minute") ? "YES" : "NO");

        assertThat(judge.keyFactCoverage("Boil it for one minute.", List.of("one minute", "use bleach")))
                .isEqualTo(0.5);
    }

    @Test
    void unsupportedIsNotMistakenForSupported() {
        assertThat(new AnswerJudge(prompt -> "UNSUPPORTED").faithfulness("Boil it.", PASSAGES)).isZero();
    }
}
```

- [ ] **Step 2: Run it, confirm it fails, then implement the judge**

Run: `$MVN test -Dtest=AnswerJudgeTest`
Expected: compilation FAIL.

`MAIN/eval/application/AnswerJudge.java`:

```java
package com.sheltr.eval.application;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * Scores answers with a chat model. Run it under a different model than the one that wrote
 * the answers. Anything the judge cannot read counts against the answer and is counted in
 * {@link #errors()}.
 */
public class AnswerJudge {

    private final UnaryOperator<String> llm;
    private int errors;

    public AnswerJudge(UnaryOperator<String> llm) {
        this.llm = llm;
    }

    public double faithfulness(String answer, List<String> passages) {
        List<String> sentences = sentences(answer);
        if (sentences.isEmpty()) {
            return 0.0;
        }
        String context = String.join("\n", passages);
        long supported = sentences.stream().filter(sentence -> verdict("""
                Passages:
                %s

                Sentence: %s

                Is every claim in the sentence stated in the passages? Reply with one word: \
                SUPPORTED or UNSUPPORTED.
                """.formatted(context, sentence), "SUPPORTED", "UNSUPPORTED")).count();
        return (double) supported / sentences.size();
    }

    public double keyFactCoverage(String answer, List<String> keyFacts) {
        if (keyFacts.isEmpty()) {
            return Double.NaN;
        }
        long stated = keyFacts.stream().filter(fact -> verdict("""
                Answer: %s

                Fact: %s

                Does the answer state this fact? Reply with one word: YES or NO.
                """.formatted(answer, fact), "YES", "NO")).count();
        return (double) stated / keyFacts.size();
    }

    public int errors() {
        return errors;
    }

    private boolean verdict(String prompt, String positive, String negative) {
        try {
            String reply = llm.apply(prompt).trim().toUpperCase(Locale.ROOT);
            // Check the negative word first: "UNSUPPORTED" contains "SUPPORTED".
            if (reply.startsWith(negative)) {
                return false;
            }
            if (reply.startsWith(positive)) {
                return true;
            }
        } catch (RuntimeException e) {
            // counted below
        }
        errors++;
        return false;
    }

    private static List<String> sentences(String answer) {
        if (answer == null) {
            return List.of();
        }
        return Arrays.stream(answer.replaceAll("\\s*\\[\\d{1,3}\\]", "").split("(?<=[.!?])\\s+"))
                .map(String::trim)
                .filter(sentence -> !sentence.isEmpty())
                .toList();
    }
}
```

Run: `$MVN test -Dtest=AnswerJudgeTest`
Expected: PASS, 6 tests.

- [ ] **Step 3: Write the failing scoring and report tests**

`TEST/eval/application/EvalScoreServiceTest.java`:

```java
package com.sheltr.eval.application;

import com.sheltr.eval.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvalScoreServiceTest {

    private static final String URI = "https://example.org/flood.pdf";
    private static final RetrievedPassage BOIL = new RetrievedPassage(URI, "rolling boil for one minute");

    private final List<EvalQuestion> questions = List.of(
            new EvalQuestion("good", "q", EvalKind.ANSWERABLE, Set.of(),
                    List.of(new ExpectedPassage(URI, "rolling boil")), List.of("one minute"), "tune"),
            new EvalQuestion("refused", "q", EvalKind.ANSWERABLE, Set.of(),
                    List.of(new ExpectedPassage(URI, "rolling boil")), List.of("one minute"), "tune"),
            new EvalQuestion("off", "q", EvalKind.OFF_TOPIC, Set.of(), List.of(), List.of(), "tune"),
            new EvalQuestion("held", "q", EvalKind.UNCOVERED, Set.of(), List.of(), List.of(), "heldout"));

    private final EvalRun run = new EvalRun("baseline", "2026-10-04T00:00:00Z", Map.of("mode", "vector"), List.of(
            new EvalResult("good", List.of(BOIL), false, "Boil for one minute [1].", 0.9, List.of(BOIL), 100, null),
            new EvalResult("refused", List.of(BOIL), true, null, 0.3, List.of(), 200, null),
            new EvalResult("off", List.of(), false, "Bake at 200 degrees [1].", 0.8, List.of(BOIL), 300, null),
            new EvalResult("held", List.of(), true, null, 0.1, List.of(), 400, null)));

    // Supports and covers everything except the off-topic answer about baking.
    private final AnswerJudge judge = new AnswerJudge(prompt ->
            prompt.contains("Bake") ? "UNSUPPORTED" : prompt.contains("Fact:") ? "YES" : "SUPPORTED");

    @Test
    void scoresEachSplitSeparately() {
        EvalReport report = new EvalScoreService(judge).score(questions, run).report();

        SplitScores tune = report.splits().get("tune");
        assertThat(tune.questions()).isEqualTo(3);
        assertThat(tune.recallAt5()).isEqualTo(1.0);
        assertThat(tune.falseAcceptRate()).isEqualTo(1.0);
        assertThat(tune.falseRefuseRate()).isEqualTo(0.5);
        assertThat(tune.faithfulness()).isEqualTo(0.5);
        assertThat(tune.keyFactCoverage()).isEqualTo(1.0);
        assertThat(tune.medianLatencyMs()).isEqualTo(200);

        SplitScores heldout = report.splits().get("heldout");
        assertThat(heldout.questions()).isEqualTo(1);
        assertThat(heldout.falseAcceptRate()).isZero();
        assertThat(report.config()).isEqualTo("baseline");
    }

    @Test
    void buildsAConfidenceTableFromTheTuneSplit() {
        List<ConfidenceRow> table = new EvalScoreService(judge).score(questions, run).report()
                .tuneConfidenceTable();

        // Two tune answers: "good" at 0.9 (correct) and "off" at 0.8 (wrong).
        ConfidenceRow atHalf = table.stream().filter(row -> row.threshold() == 0.50).findFirst().orElseThrow();
        ConfidenceRow atNinety = table.stream().filter(row -> row.threshold() == 0.90).findFirst().orElseThrow();
        assertThat(atHalf.answers()).isEqualTo(2);
        assertThat(atHalf.accuracy()).isEqualTo(0.5);
        assertThat(atNinety.answers()).isEqualTo(1);
        assertThat(atNinety.accuracy()).isEqualTo(1.0);
    }

    @Test
    void spotcheckListsAnsweredQuestionsWithTheJudgesVerdictAndABlankHumanColumn() {
        List<SpotcheckItem> spotcheck = new EvalScoreService(judge).score(questions, run).spotcheck();

        assertThat(spotcheck).extracting(SpotcheckItem::id).containsExactlyInAnyOrder("good", "off");
        assertThat(spotcheck).allSatisfy(item -> assertThat(item.humanFaithful()).isNull());
        assertThat(spotcheck.stream().filter(item -> item.id().equals("good")).findFirst().orElseThrow()
                .judgeFaithful()).isTrue();
    }

    @Test
    void rejectsARunThatIsMissingAQuestion() {
        EvalRun partial = new EvalRun("baseline", "t", Map.of(), run.results().subList(0, 2));

        assertThatThrownBy(() -> new EvalScoreService(judge).score(questions, partial))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("off");
    }

    @Test
    void agreementIsTheShareOfItemsWhereJudgeAndHumanMatch() {
        List<SpotcheckItem> items = List.of(
                new SpotcheckItem("a", "q", "a", List.of(), true, true),
                new SpotcheckItem("b", "q", "a", List.of(), true, false),
                new SpotcheckItem("c", "q", "a", List.of(), false, false),
                new SpotcheckItem("d", "q", "a", List.of(), false, false));

        assertThat(EvalScoreService.agreement(items)).isEqualTo(0.75);
    }

    @Test
    void agreementRefusesItemsTheHumanHasNotScored() {
        assertThatThrownBy(() -> EvalScoreService.agreement(
                List.of(new SpotcheckItem("a", "q", "a", List.of(), true, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a");
    }
}
```

`TEST/eval/application/ReportWriterTest.java`:

```java
package com.sheltr.eval.application;

import com.sheltr.eval.domain.ConfidenceRow;
import com.sheltr.eval.domain.EvalReport;
import com.sheltr.eval.domain.SplitScores;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportWriterTest {

    @Test
    void marksEachHeldoutTargetAsPassOrFail() {
        SplitScores heldout = new SplitScores(50, 0.90, 0.80, 0.02, 0.05, 0.97, 0.90, 0.08, 800, 2000, 0);
        EvalReport report = new EvalReport("hybrid", Map.of("sheltr.search.mode", "hybrid"),
                Map.of("heldout", heldout), List.of(new ConfidenceRow(0.5, 10, 0.9)), 0);

        String markdown = ReportWriter.markdown(report);

        assertThat(markdown).contains("# Eval report: hybrid");
        assertThat(markdown).contains("sheltr.search.mode");
        assertThat(markdown).contains("| False-accept rate | 0.020 | 0 | FAIL |");
        assertThat(markdown).contains("| False-refuse rate | 0.050 | at most 0.10 | PASS |");
        assertThat(markdown).contains("| Recall at 5 | 0.900 | at least 0.85 | PASS |");
        assertThat(markdown).contains("| Faithfulness | 0.970 | at least 0.95 | PASS |");
    }

    @Test
    void showsNotANumberAsNotApplicable() {
        SplitScores tune = new SplitScores(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, 0, 0, 0);

        assertThat(ReportWriter.markdown(new EvalReport("x", Map.of(), Map.of("tune", tune), List.of(), 0)))
                .contains("n/a").doesNotContain("NaN");
    }
}
```

- [ ] **Step 4: Implement the report records, scoring, and writer**

`MAIN/eval/domain/SplitScores.java`:

```java
package com.sheltr.eval.domain;

public record SplitScores(int questions, double recallAt5, double meanReciprocalRank, double falseAcceptRate,
                          double falseRefuseRate, double faithfulness, double keyFactCoverage,
                          double calibrationError, long medianLatencyMs, long p95LatencyMs, int errors) {
}
```

`MAIN/eval/domain/ConfidenceRow.java`:

```java
package com.sheltr.eval.domain;

/** Of the answers given with at least this confidence, how many there were and what share were correct. */
public record ConfidenceRow(double threshold, int answers, double accuracy) {
}
```

`MAIN/eval/domain/EvalReport.java`:

```java
package com.sheltr.eval.domain;

import java.util.List;
import java.util.Map;

public record EvalReport(String config, Map<String, String> settings, Map<String, SplitScores> splits,
                         List<ConfidenceRow> tuneConfidenceTable, int judgeErrors) {
}
```

`MAIN/eval/domain/SpotcheckItem.java`:

```java
package com.sheltr.eval.domain;

import java.util.List;

/**
 * @param judgeFaithful true when the judge model found every sentence supported
 * @param humanFaithful filled in by the maintainer; null until scored
 */
public record SpotcheckItem(String id, String question, String answer, List<String> passages,
                            boolean judgeFaithful, Boolean humanFaithful) {
}
```

`MAIN/eval/application/EvalScoreService.java`:

```java
package com.sheltr.eval.application;

import com.sheltr.eval.domain.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

public class EvalScoreService {

    public static final int SPOTCHECK_SIZE = 30;

    public record Output(EvalReport report, List<SpotcheckItem> spotcheck) {
    }

    private record Judged(Metrics.Scored scored, double faithfulness, double keyFactCoverage) {
        boolean correct() {
            return !scored.question().kind().mustRefuse() && faithfulness == 1.0 && keyFactCoverage == 1.0;
        }
    }

    private final AnswerJudge judge;

    public EvalScoreService(AnswerJudge judge) {
        this.judge = judge;
    }

    public Output score(List<EvalQuestion> questions, EvalRun run) {
        Map<String, EvalResult> results = run.results().stream()
                .collect(Collectors.toMap(EvalResult::questionId, Function.identity()));
        List<String> missing = questions.stream().map(EvalQuestion::id)
                .filter(id -> !results.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("The run has no result for question(s): " + missing);
        }

        List<Metrics.Scored> scored = questions.stream()
                .map(question -> new Metrics.Scored(question, results.get(question.id()))).toList();
        List<Judged> judged = new ArrayList<>();
        for (Metrics.Scored item : scored) {
            if (item.result().refused()) {
                continue;
            }
            List<String> cited = item.result().cited().stream().map(RetrievedPassage::content).toList();
            double faithfulness = judge.faithfulness(item.result().answer(), cited);
            double coverage = item.question().keyFacts().isEmpty() ? Double.NaN
                    : judge.keyFactCoverage(item.result().answer(), item.question().keyFacts());
            judged.add(new Judged(item, faithfulness, coverage));
        }

        Map<String, SplitScores> splits = new LinkedHashMap<>();
        for (String split : List.of("tune", "heldout")) {
            splits.put(split, scoreSplit(
                    scored.stream().filter(s -> s.question().split().equals(split)).toList(),
                    judged.stream().filter(j -> j.scored().question().split().equals(split)).toList()));
        }

        List<Judged> tune = judged.stream().filter(j -> j.scored().question().split().equals("tune")).toList();
        EvalReport report = new EvalReport(run.config(), run.settings(), splits, confidenceTable(tune),
                judge.errors());
        return new Output(report, spotcheck(judged));
    }

    public static double agreement(List<SpotcheckItem> items) {
        List<String> unscored = items.stream().filter(item -> item.humanFaithful() == null)
                .map(SpotcheckItem::id).toList();
        if (!unscored.isEmpty()) {
            throw new IllegalArgumentException("human_faithful is not filled in for: " + unscored);
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("The spotcheck file is empty");
        }
        long agreed = items.stream().filter(item -> item.judgeFaithful() == item.humanFaithful()).count();
        return (double) agreed / items.size();
    }

    private static SplitScores scoreSplit(List<Metrics.Scored> scored, List<Judged> judged) {
        List<Long> latencies = scored.stream().map(s -> s.result().latencyMs()).toList();
        List<double[]> calibration = judged.stream()
                .filter(j -> j.scored().result().confidence() != null)
                .map(j -> new double[]{j.scored().result().confidence(), j.correct() ? 1 : 0})
                .toList();
        return new SplitScores(
                scored.size(),
                Metrics.recallAtK(scored, 5),
                Metrics.meanReciprocalRank(scored),
                Metrics.falseAcceptRate(scored),
                Metrics.falseRefuseRate(scored),
                judged.stream().mapToDouble(Judged::faithfulness).average().orElse(Double.NaN),
                judged.stream().mapToDouble(Judged::keyFactCoverage).filter(v -> !Double.isNaN(v))
                        .average().orElse(Double.NaN),
                Metrics.calibrationError(calibration, 5),
                Metrics.percentile(latencies, 0.5),
                Metrics.percentile(latencies, 0.95),
                (int) scored.stream().filter(s -> s.result().error() != null).count());
    }

    private static List<ConfidenceRow> confidenceTable(List<Judged> tune) {
        List<ConfidenceRow> rows = new ArrayList<>();
        for (int step = 10; step <= 19; step++) {
            double threshold = step / 20.0;
            List<Judged> atLeast = tune.stream()
                    .filter(j -> j.scored().result().confidence() != null
                            && j.scored().result().confidence() >= threshold)
                    .toList();
            double accuracy = atLeast.isEmpty() ? Double.NaN
                    : (double) atLeast.stream().filter(Judged::correct).count() / atLeast.size();
            rows.add(new ConfidenceRow(threshold, atLeast.size(), accuracy));
        }
        return rows;
    }

    private static List<SpotcheckItem> spotcheck(List<Judged> judged) {
        List<Judged> shuffled = new ArrayList<>(judged);
        // Fixed seed: the same run always yields the same sample.
        Collections.shuffle(shuffled, new Random(42));
        return shuffled.stream().limit(SPOTCHECK_SIZE)
                .map(j -> new SpotcheckItem(j.scored().question().id(), j.scored().question().question(),
                        j.scored().result().answer(),
                        j.scored().result().cited().stream().map(RetrievedPassage::content).toList(),
                        j.faithfulness() == 1.0, null))
                .toList();
    }
}
```

`MAIN/eval/application/ReportWriter.java`:

```java
package com.sheltr.eval.application;

import com.sheltr.eval.domain.ConfidenceRow;
import com.sheltr.eval.domain.EvalReport;
import com.sheltr.eval.domain.SplitScores;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class ReportWriter {

    private ReportWriter() {
    }

    public static String markdown(EvalReport report) {
        StringBuilder out = new StringBuilder("# Eval report: " + report.config() + "\n\n");

        out.append("## Settings\n\n| Setting | Value |\n|---|---|\n");
        new TreeMap<>(report.settings()).forEach((key, value) ->
                out.append("| ").append(key).append(" | ").append(value).append(" |\n"));

        SplitScores heldout = report.splits().get("heldout");
        if (heldout != null) {
            out.append("\n## Targets (heldout)\n\n| Metric | Value | Target | Result |\n|---|---|---|---|\n");
            target(out, "False-accept rate", heldout.falseAcceptRate(), "0", heldout.falseAcceptRate() == 0.0);
            target(out, "False-refuse rate", heldout.falseRefuseRate(), "at most 0.10",
                    heldout.falseRefuseRate() <= 0.10);
            target(out, "Recall at 5", heldout.recallAt5(), "at least 0.85", heldout.recallAt5() >= 0.85);
            target(out, "Faithfulness", heldout.faithfulness(), "at least 0.95", heldout.faithfulness() >= 0.95);
        }

        out.append("\n## Scores by split\n\n")
                .append("| Split | Questions | Recall at 5 | MRR | False accept | False refuse | Faithfulness ")
                .append("| Key facts | Calibration error | Median ms | p95 ms | Errors |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, SplitScores> entry : report.splits().entrySet()) {
            SplitScores s = entry.getValue();
            out.append("| ").append(entry.getKey()).append(" | ").append(s.questions())
                    .append(" | ").append(number(s.recallAt5()))
                    .append(" | ").append(number(s.meanReciprocalRank()))
                    .append(" | ").append(number(s.falseAcceptRate()))
                    .append(" | ").append(number(s.falseRefuseRate()))
                    .append(" | ").append(number(s.faithfulness()))
                    .append(" | ").append(number(s.keyFactCoverage()))
                    .append(" | ").append(number(s.calibrationError()))
                    .append(" | ").append(s.medianLatencyMs())
                    .append(" | ").append(s.p95LatencyMs())
                    .append(" | ").append(s.errors()).append(" |\n");
        }

        out.append("\n## Confidence table (tune)\n\n")
                .append("Of the answers given with at least this confidence, the share that were correct.\n\n")
                .append("| Confidence at least | Answers | Accuracy |\n|---|---|---|\n");
        for (ConfidenceRow row : report.tuneConfidenceTable()) {
            out.append("| ").append(String.format(Locale.ROOT, "%.2f", row.threshold()))
                    .append(" | ").append(row.answers())
                    .append(" | ").append(number(row.accuracy())).append(" |\n");
        }

        out.append("\nJudge replies that could not be read: ").append(report.judgeErrors()).append("\n");
        return out.toString();
    }

    private static void target(StringBuilder out, String name, double value, String target, boolean pass) {
        out.append("| ").append(name).append(" | ").append(number(value)).append(" | ").append(target)
                .append(" | ").append(!Double.isNaN(value) && pass ? "PASS" : "FAIL").append(" |\n");
    }

    private static String number(double value) {
        return Double.isNaN(value) ? "n/a" : String.format(Locale.ROOT, "%.3f", value);
    }
}
```

- [ ] **Step 5: Run the scoring and report tests**

Run: `$MVN test -Dtest='EvalScoreServiceTest,ReportWriterTest'`
Expected: PASS, 8 tests.

- [ ] **Step 6: Add the command-line runner**

`MAIN/eval/config/EvalRunner.java`:

```java
package com.sheltr.eval.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.sheltr.ai.application.AnswerPipeline;
import com.sheltr.eval.application.AnswerJudge;
import com.sheltr.eval.application.EvalJson;
import com.sheltr.eval.application.EvalQuestionLoader;
import com.sheltr.eval.application.EvalRunService;
import com.sheltr.eval.application.EvalScoreService;
import com.sheltr.eval.application.ReportWriter;
import com.sheltr.eval.domain.EvalQuestion;
import com.sheltr.eval.domain.EvalResult;
import com.sheltr.eval.domain.EvalRun;
import com.sheltr.eval.domain.ExpectedPassage;
import com.sheltr.eval.domain.SpotcheckItem;
import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Command-line eval modes:
 * eval-validate, eval-run, eval-score, eval-agreement. See the Makefile targets.
 */
@Component
public class EvalRunner implements ApplicationRunner {

    private static final List<String> RECORDED_SETTINGS = List.of(
            "sheltr.search.mode", "sheltr.search.reranker", "sheltr.search.hazard-boost",
            "sheltr.search.related-hazard-boost", "sheltr.search.relevance-threshold", "sheltr.judge.type",
            "sheltr.judge.min-probability", "sheltr.embedding.model-name", "sheltr.embedding.query-prefix",
            "sheltr.embedding.document-prefix");

    private final AnswerPipeline pipeline;
    private final KnowledgeDocumentRepository documents;
    private final ChatModel chatModel;
    private final DataSource dataSource;
    private final Environment environment;

    public EvalRunner(AnswerPipeline pipeline, KnowledgeDocumentRepository documents, ChatModel chatModel,
                      DataSource dataSource, Environment environment) {
        this.pipeline = pipeline;
        this.documents = documents;
        this.chatModel = chatModel;
        this.dataSource = dataSource;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> modes = args.getNonOptionArgs();
        if (modes.contains("eval-validate")) {
            System.exit(validate(Path.of(option(args, "questions"))));
        } else if (modes.contains("eval-run")) {
            runQuestions(option(args, "config"), Path.of(option(args, "questions")), Path.of(option(args, "out")));
            System.exit(0);
        } else if (modes.contains("eval-score")) {
            score(Path.of(option(args, "raw")), Path.of(option(args, "questions")));
            System.exit(0);
        } else if (modes.contains("eval-agreement")) {
            agreement(Path.of(option(args, "spotcheck")));
            System.exit(0);
        }
    }

    /** Every expected passage phrase must exist in a chunk of the named active document. */
    private int validate(Path questionsPath) throws Exception {
        List<EvalQuestion> questions = EvalQuestionLoader.load(questionsPath);
        var dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
        int missing = 0;
        for (EvalQuestion question : questions) {
            for (ExpectedPassage expected : question.expectedPassages()) {
                int found = dsl.fetchOne(
                        "SELECT count(*) AS n FROM knowledge.chunks c "
                                + "JOIN knowledge.documents d ON d.id = c.document_id "
                                + "WHERE d.source_uri = ? AND d.status = 'ACTIVE' AND position("
                                + "lower(regexp_replace(?, '\\s+', ' ', 'g')) in "
                                + "lower(regexp_replace(c.content, '\\s+', ' ', 'g'))) > 0",
                        expected.sourceUri(), expected.contains()).get("n", Integer.class);
                if (found == 0) {
                    missing++;
                    System.out.println("MISSING " + question.id() + ": \"" + expected.contains()
                            + "\" not found in any chunk of " + expected.sourceUri());
                }
            }
        }
        System.out.println(questions.size() + " question(s) checked, " + missing + " expected passage(s) missing.");
        return missing == 0 ? 0 : 1;
    }

    private void runQuestions(String config, Path questionsPath, Path outDir) throws Exception {
        List<EvalQuestion> questions = EvalQuestionLoader.load(questionsPath);
        EvalRunService service = new EvalRunService(pipeline, documents);
        Instant started = Instant.now();
        List<EvalResult> results = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            results.add(service.run(questions.get(i)));
            System.out.println("[" + (i + 1) + "/" + questions.size() + "] " + questions.get(i).id());
        }
        Files.createDirectories(outDir);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(started);
        Path raw = outDir.resolve(stamp + "-" + config + ".raw.json");
        EvalJson.MAPPER.writeValue(raw.toFile(), new EvalRun(config, started.toString(), settings(), results));
        System.out.println("Wrote " + raw);
    }

    private void score(Path rawPath, Path questionsPath) throws Exception {
        List<EvalQuestion> questions = EvalQuestionLoader.load(questionsPath);
        EvalRun run = EvalJson.MAPPER.readValue(rawPath.toFile(), EvalRun.class);
        EvalScoreService.Output output = new EvalScoreService(new AnswerJudge(chatModel::call))
                .score(questions, run);

        String base = rawPath.toString().replaceFirst("\\.raw\\.json$", "");
        Map<String, Object> reportWithJudge = new LinkedHashMap<>();
        reportWithJudge.put("report", output.report());
        reportWithJudge.put("judge_profiles", String.join(",", environment.getActiveProfiles()));
        EvalJson.MAPPER.writeValue(Path.of(base + ".report.json").toFile(), reportWithJudge);
        Files.writeString(Path.of(base + ".report.md"), ReportWriter.markdown(output.report())
                + "\nJudged under profile(s): " + String.join(",", environment.getActiveProfiles()) + "\n");
        StringBuilder lines = new StringBuilder();
        for (SpotcheckItem item : output.spotcheck()) {
            lines.append(EvalJson.LINE_WRITER.writeValueAsString(item)).append('\n');
        }
        Files.writeString(Path.of(base + ".spotcheck.jsonl"), lines);
        System.out.println(ReportWriter.markdown(output.report()));
        System.out.println("Wrote " + base + ".report.md, .report.json, .spotcheck.jsonl");
    }

    private void agreement(Path spotcheckPath) throws Exception {
        List<SpotcheckItem> items = new ArrayList<>();
        for (String line : Files.readAllLines(spotcheckPath)) {
            if (!line.isBlank()) {
                JsonNode node = EvalJson.MAPPER.readTree(line);
                items.add(EvalJson.MAPPER.treeToValue(node, SpotcheckItem.class));
            }
        }
        double agreement = EvalScoreService.agreement(items);
        System.out.printf("Judge and maintainer agree on %.0f%% of %d answers (target: at least 90%%).%n",
                agreement * 100, items.size());
    }

    private Map<String, String> settings() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("spring.profiles.active", String.join(",", environment.getActiveProfiles()));
        for (String key : RECORDED_SETTINGS) {
            settings.put(key, environment.getProperty(key, ""));
        }
        return settings;
    }

    private static String option(ApplicationArguments args, String name) {
        if (!args.containsOption(name) || args.getOptionValues(name).isEmpty()) {
            throw new IllegalArgumentException("Missing required option --" + name + "=<value>");
        }
        return args.getOptionValues(name).get(0);
    }
}
```

- [ ] **Step 7: Add make targets and ignore raw reports**

In `Makefile`, add `eval-validate eval eval-score eval-agreement` to the `.PHONY` line and append:

```make
EVAL_QUESTIONS ?= src/test/resources/eval/questions.jsonl

# Check that every expected passage in the question set exists in the ingested corpus.
eval-validate:
	cd apps/backend && mvn -q spring-boot:run -Dspring-boot.run.arguments="eval-validate --questions=$(EVAL_QUESTIONS)"

# Run the question set. Pipeline settings come from the environment, for example:
#   SHELTR_SEARCH_MODE=hybrid SHELTR_SEARCH_RERANKER=model make eval CONFIG=hybrid-rerank
eval:
	cd apps/backend && mvn -q spring-boot:run -Dspring-boot.run.arguments="eval-run --config=$(CONFIG) --questions=$(EVAL_QUESTIONS) --out=../../eval/reports"

# Score a run. Use a profile whose chat model differs from the one that produced the run:
#   SPRING_PROFILES_ACTIVE=gemini make eval-score RAW=../../eval/reports/<file>.raw.json
eval-score:
	cd apps/backend && mvn -q spring-boot:run -Dspring-boot.run.arguments="eval-score --raw=$(RAW) --questions=$(EVAL_QUESTIONS)"

eval-agreement:
	cd apps/backend && mvn -q spring-boot:run -Dspring-boot.run.arguments="eval-agreement --spotcheck=$(SPOTCHECK)"
```

In `.gitignore`, append:

```gitignore

# Eval run output; chosen reports are copied to docs/eval/
eval/reports/
```

- [ ] **Step 8: Run the full suite and commit**

Run: `$MVN test`
Expected: PASS.

```bash
git add Makefile .gitignore apps/backend/src
git commit -m "feat(eval): score runs with a judge model and add eval command-line modes"
```

---

### Task 13: Write the question set and record the baseline

This task is content work. The maintainer reviews every question before it is used.

**Files:**
- Create: `apps/backend/src/test/resources/eval/questions.jsonl`
- Create: `TEST/eval/EvalQuestionSetTest.java`
- Create: `docs/eval/baseline.report.md`

**Interfaces:**
- Consumes: the ingested corpus (Task 5); `EvalQuestionLoader`, `make eval-validate`, `make eval`, `make eval-score` (Tasks 10–12).
- Produces: about 150 reviewed questions and the baseline report.

- [ ] **Step 1: Write the composition test**

`TEST/eval/EvalQuestionSetTest.java`:

```java
package com.sheltr.eval;

import com.sheltr.eval.application.EvalQuestionLoader;
import com.sheltr.eval.domain.EvalKind;
import com.sheltr.eval.domain.EvalQuestion;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the shape of the committed question set; the loader already checks each line. */
class EvalQuestionSetTest {

    @Test
    void questionSetHasTheAgreedComposition() throws Exception {
        List<EvalQuestion> questions = EvalQuestionLoader.load(Path.of("src/test/resources/eval/questions.jsonl"));
        Map<EvalKind, Long> byKind = questions.stream()
                .collect(Collectors.groupingBy(EvalQuestion::kind, Collectors.counting()));

        assertThat(questions).hasSizeBetween(140, 160);
        assertThat(byKind.get(EvalKind.ANSWERABLE)).isBetween(80L, 100L);
        assertThat(byKind.get(EvalKind.MULTI_STEP)).isBetween(12L, 18L);
        assertThat(byKind.get(EvalKind.OFF_TOPIC)).isBetween(15L, 25L);
        assertThat(byKind.get(EvalKind.UNCOVERED)).isBetween(20L, 30L);

        long heldout = questions.stream().filter(question -> question.split().equals("heldout")).count();
        assertThat((double) heldout / questions.size()).isBetween(0.30, 0.37);
        for (EvalKind kind : EvalKind.values()) {
            assertThat(questions.stream()
                    .filter(question -> question.kind() == kind && question.split().equals("heldout")).count())
                    .as("heldout questions of kind %s", kind).isGreaterThanOrEqualTo(4);
        }
    }
}
```

Run: `$MVN test -Dtest=EvalQuestionSetTest`
Expected: FAIL, the file does not exist.

- [ ] **Step 2: Draft the questions from the corpus**

For each ingested document, read it and write questions a member of the public would ask, one JSON object per line. Rules:

- `ANSWERABLE` (about 90): `contains` is a phrase copied exactly from the passage that answers the question, 4 to 10 words, taken from within one paragraph. `key_facts` lists the one to three facts a correct answer must state, in plain words. Phrase the question differently from the passage; do not reuse its wording.
- `MULTI_STEP` (about 15): the answer needs two or more passages, from different hazards or documents. List every needed passage.
- `OFF_TOPIC` (about 20): nothing to do with disasters. No `expected_passages`, no `key_facts`.
- `UNCOVERED` (about 25): a real disaster question that no corpus document answers. Include hazards the corpus does not cover, country-specific questions such as local emergency numbers or shelters, and medical questions beyond the guidance. Before using one, search the corpus text for an answer; if a document answers it, it is `ANSWERABLE`.
- About one in six `ANSWERABLE` and `MULTI_STEP` questions carries `active_hazards`. Half of those name the hazard the question is about; half name a different hazard, to check that active hazards never cause a wrong answer.
- `id` is the hazard or kind, a dash, and a three-digit number: `flood-001`, `multi-004`, `off-012`, `uncovered-007`.
- Assign `split` so about one third of each kind is `heldout`. Decide the split before running anything, and never tune on `heldout`.

Line format, shown with placeholder values:

```json
{"id":"flood-001","question":"<question in the asker's words>","kind":"ANSWERABLE","active_hazards":["FLOOD"],"expected_passages":[{"source_uri":"<source_uri from the manifest>","contains":"<exact phrase from the passage>"}],"key_facts":["<fact a correct answer must state>"],"split":"tune"}
{"id":"off-001","question":"<unrelated question>","kind":"OFF_TOPIC","split":"heldout"}
```

- [ ] **Step 3: Validate against the corpus**

```bash
$MVN test -Dtest=EvalQuestionSetTest     # from apps/backend
make eval-validate                       # from the repo root
```

Expected: the test passes, and `eval-validate` prints `0 expected passage(s) missing`. For each `MISSING` line, shorten the phrase so it sits inside one chunk, or correct a copying mistake.

- [ ] **Step 4: Maintainer review**

Give the maintainer the file. They confirm each question is one a real person might ask, each `ANSWERABLE` question is answered by its passage, and each `UNCOVERED` question is truly unanswered by the corpus. Apply their corrections and rerun Step 3.

- [ ] **Step 5: Commit the question set**

```bash
git add apps/backend/src/test/resources/eval/questions.jsonl \
        apps/backend/src/test/java/com/sheltr/eval/EvalQuestionSetTest.java
git commit -m "test(eval): add reviewed eval question set"
```

- [ ] **Step 6: Run and score the baseline**

The baseline is today's pipeline on the real corpus: vector search, no reranker, threshold judge, local models.

```bash
make eval CONFIG=baseline
SPRING_PROFILES_ACTIVE=gemini make eval-score RAW=../../eval/reports/<stamp>-baseline.raw.json
```

Scoring under `gemini` needs `GEMINI_API_KEY`. The judge must not be the model that wrote the answers. Scoring only calls the chat model, so the embedding guard is not involved.

Expected: a `.report.md` beside the raw file. Record the numbers as they are; the baseline is not expected to meet the targets.

- [ ] **Step 7: Commit the baseline report**

```bash
mkdir -p docs/eval
cp eval/reports/<stamp>-baseline.report.md docs/eval/baseline.report.md
git add docs/eval/baseline.report.md
git commit -m "docs(eval): record baseline eval report"
```

---

### Task 14: Tune the pipeline, choose the hosted model, and check the targets

Every decision here is made on the `tune` split. `heldout` is read once, at the end.

**Files:**
- Modify: `apps/backend/pom.xml`
- Create: `RES/application-claude.yml`
- Modify: `RES/application.yml`, `RES/application-local.yml`, `RES/application-gemini.yml`
- Create: `docs/eval/chosen.report.md`, `docs/eval/comparison.md`

**Interfaces:**
- Consumes: everything above.
- Produces: default values for `sheltr.search.mode`, `sheltr.search.reranker`, `sheltr.judge.type`, `sheltr.confidence.*`; a `claude` profile; the chosen report.

- [ ] **Step 1: Check the judge before trusting any score**

Open `eval/reports/<stamp>-baseline.spotcheck.jsonl`. For each line, read the answer and its passages and change `"human_faithful":null` to `true` if every sentence of the answer is supported by the passages, otherwise `false`. The maintainer does this, without looking at `judge_faithful` first. Then:

```bash
make eval-agreement SPOTCHECK=../../eval/reports/<stamp>-baseline.spotcheck.jsonl
```

Expected: agreement of at least 90%. If it is lower, read the disagreements, tighten the wording of the faithfulness prompt in `AnswerJudge`, update `AnswerJudgeTest` if the prompt text it checks changed, rescore, and repeat this step. Do not continue until agreement is at least 90%.

- [ ] **Step 2: Run the retrieval ladder on local models**

Run each configuration, score it, and note the `tune` row.

```bash
SHELTR_SEARCH_MODE=hybrid make eval CONFIG=hybrid
SHELTR_SEARCH_MODE=hybrid SHELTR_SEARCH_RERANKER=model make eval CONFIG=hybrid-rerank
SHELTR_SEARCH_MODE=hybrid SHELTR_SEARCH_RERANKER=model SHELTR_JUDGE_TYPE=model make eval CONFIG=hybrid-rerank-modeljudge
```

Score each with `SPRING_PROFILES_ACTIVE=gemini make eval-score RAW=...`.

Keep a stage only if it improves recall at 5, faithfulness, or false-refuse rate on `tune` without raising the false-accept rate. If hybrid does not help, run the later rungs with `SHELTR_SEARCH_MODE=vector`.

- [ ] **Step 3: Try the embedding experiments**

Each experiment changes what a vector means, so each needs a new `model-name` and a re-embed. The guard from Task 2 blocks questions until the re-embed is done.

Task prefixes on the current model:

```bash
export SHELTR_EMBEDDING_MODEL_NAME="nomic-embed-text:v1.5+prefix"
export SHELTR_EMBEDDING_QUERY_PREFIX="search_query: "
export SHELTR_EMBEDDING_DOCUMENT_PREFIX="search_document: "
make seed-embeddings
make eval CONFIG=<best-so-far>-prefix
```

One alternative model. First confirm it produces 768 numbers, since the column is `VECTOR(768)`:

```bash
podman exec sheltr-ollama ollama pull embeddinggemma
curl -s localhost:11434/api/embed -d '{"model":"embeddinggemma","input":"test"}' \
  | python3 -c "import json,sys; print(len(json.load(sys.stdin)['embeddings'][0]))"
```

Expected: `768`. If it prints another number, skip this experiment; a different dimension needs a column migration, which the spec allows only for a gain already shown. If it prints 768:

```bash
unset SHELTR_EMBEDDING_QUERY_PREFIX SHELTR_EMBEDDING_DOCUMENT_PREFIX
export SHELTR_EMBEDDING_MODEL_NAME="embeddinggemma"
export SPRING_AI_OLLAMA_EMBEDDING_MODEL="embeddinggemma"
make seed-embeddings
make eval CONFIG=<best-so-far>-embeddinggemma
```

Score both. Adopt an embedding change only if recall at 5 on `tune` rises by at least 0.03. Whichever embedding setup wins, write it into `RES/application-local.yml` (`spring.ai.ollama.embedding.model`, `sheltr.embedding.model-name`, and the two prefixes), run `make seed-embeddings` once more with the environment variables unset, and if the model changed, update the `ollama pull` line in `Makefile`.

- [ ] **Step 4: Add the Claude profile**

In `apps/backend/pom.xml`, after the `spring-ai-starter-model-ollama` dependency:

```xml
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-starter-model-anthropic</artifactId>
    </dependency>
```

Find the auto-configuration class names in the jar this pulls in:

```bash
cd apps/backend && mvn -q dependency:resolve
unzip -l ~/.m2/repository/org/springframework/ai/spring-ai-autoconfigure-model-anthropic/2.0.0/*.jar \
  | grep 'AutoConfiguration.class'
```

Expected: one class, `org/springframework/ai/model/anthropic/autoconfigure/AnthropicChatAutoConfiguration.class`. Use the fully qualified name printed in the three places below; if the jar lists a different name, use that one.

Add that class to the `spring.autoconfigure.exclude` list in both `RES/application-local.yml` and `RES/application-gemini.yml`, so those profiles do not need an Anthropic key.

`RES/application-claude.yml` — Claude for chat, the local Ollama model for embeddings, since Anthropic has no embedding model:

```yaml
spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      chat:
        options:
          model: claude-haiku-4-5-20251001
    ollama:
      base-url: http://localhost:11434
      embedding:
        model: nomic-embed-text:v1.5
  autoconfigure:
    exclude:
      - org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration
      - org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration
      - org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration
      - org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration

sheltr:
  embedding:
    model-name: nomic-embed-text:v1.5
  search:
    # Same embedding space as the local profile, so its calibrated threshold applies.
    relevance-threshold: 0.65
```

If Step 3 changed the embedding setup, copy the same embedding values here.

Run: `$MVN test`
Expected: PASS. Then, with a real key: `SPRING_PROFILES_ACTIVE=claude ANTHROPIC_API_KEY=... make eval CONFIG=smoke` must print question IDs without a startup error.

```bash
git add apps/backend/pom.xml apps/backend/src/main/resources
git commit -m "feat(config): add claude profile for hosted answer generation"
```

- [ ] **Step 5: Compare generation models**

With the best retrieval configuration from Steps 2 and 3:

```bash
<settings> make eval CONFIG=best-local
SPRING_PROFILES_ACTIVE=claude <settings> make eval CONFIG=best-claude
SPRING_PROFILES_ACTIVE=gemini <settings> make seed-embeddings
SPRING_PROFILES_ACTIVE=gemini <settings> make eval CONFIG=best-gemini
```

The `gemini` profile uses its own embedding model, so it must re-embed first; afterwards run `make seed-embeddings` with no profile set to return to the local embeddings.

Score each run under a profile with a different chat model: score `best-local` and `best-gemini` under `claude`, and `best-claude` under `gemini`.

For cost per question, read each provider's usage page for the run's time window and divide by the number of questions. Local runs cost nothing per question.

- [ ] **Step 6: Write the comparison and choose**

`docs/eval/comparison.md`: one table with a row per configuration run in Steps 2, 3, and 5, and these columns from the `tune` split: recall at 5, false-accept rate, false-refuse rate, faithfulness, median latency, cost per question. Under the table, state which configuration was chosen and the one reason.

Choose the hosted configuration with the lowest cost per question among those meeting all four targets on `tune`. If none meets them, choose the one with the lowest false-accept rate and say plainly which targets it misses.

- [ ] **Step 7: Fit the confidence bands**

From the chosen run's report, read the confidence table. Set `high` to the lowest threshold whose accuracy is at least 0.95 with at least 10 answers, and `medium` to the lowest whose accuracy is at least 0.85 with at least 10 answers. If no row qualifies for `high`, set `high` to 1.0 so nothing is labelled HIGH, and say so in `comparison.md`.

- [ ] **Step 8: Make the chosen configuration the default**

In `RES/application.yml`, set `sheltr.search.mode`, `sheltr.search.reranker`, `sheltr.judge.type`, `sheltr.confidence.high`, and `sheltr.confidence.medium` to the chosen values, and update the comments beside them to cite `docs/eval/comparison.md`.

Run: `$MVN test`
Expected: PASS. If a test fails because a default changed, pin the old value in that test's properties; do not change the default back.

- [ ] **Step 9: Read the heldout split once**

Open the chosen run's `.report.md` and read the "Targets (heldout)" table. Copy the report:

```bash
cp eval/reports/<stamp>-<chosen>.report.md docs/eval/chosen.report.md
```

If every row says PASS, the sub-project meets its targets. If any row says FAIL, add a section "Targets not met" to `docs/eval/comparison.md` naming each failed target and its value, and tell the maintainer. Do not retune against `heldout`; that would make the number meaningless.

- [ ] **Step 10: Jev**

Jev is not built in this plan. It is available only by early access and no interface description is at hand to write an adapter against. If the maintainer obtains access, write a follow-up plan for a `JevRelevanceJudge` behind a `jev` value of `sheltr.judge.type`, and compare it to the chosen judge on false accepts, calibration error, and cost using the commands in this task. Record in `docs/eval/comparison.md` that Jev was not evaluated and why.

- [ ] **Step 11: Commit**

```bash
git add docs/eval apps/backend/src/main/resources Makefile
git commit -m "feat(config): adopt eval-chosen retrieval and judge defaults"
```

---

### Task 15: Documentation and cleanup

**Files:**
- Create: `docs/adr/0012-hybrid-retrieval-and-relevance-judge.md`, `docs/adr/0013-corpus-manifest.md`
- Modify: `docs/adr/0008-mandatory-citation-grounding.md`, `README.md`
- Delete: `TEST/search/infrastructure/RelevanceThresholdEvalTest.java`
- Create: `MAIN/eval/package-info.java`

**Interfaces:**
- Consumes: the outcome of Task 14.
- Produces: documentation matching the code.

- [ ] **Step 1: Write ADR-0012**

`docs/adr/0012-hybrid-retrieval-and-relevance-judge.md`:

```markdown
# ADR-0012: Staged retrieval behind ports, chosen by eval

Status: Accepted
Date: 2026-10-04

## Context

Retrieval was a single vector query, and an answer was allowed when the top cosine score
passed a fixed threshold. Confidence labels came from the same score with hardcoded bands.
None of this had been measured on real documents.

## Decision

Answering runs through one `AnswerPipeline`: retrieve 20 candidates, rerank to 5, judge
whether the passages answer the question, then generate. Each stage is a port:

- `Retriever`: vector-only, or hybrid (vector plus English keyword search, fused by
  reciprocal rank, with a boost for hazards reported active near the asker).
- `Reranker`: none, or a chat model ordering the candidates.
- `RelevanceJudge`: the score threshold, or a chat model returning a verdict and probability.

Which implementation is the default is decided by the eval harness on the tuning split, and
recorded in `docs/eval/comparison.md`. Confidence bands are fitted from the same data.

Active hazards raise matching passages and never filter passages out.

A failed reranker falls back to retrieval order. A failed judge refuses.

## Consequences

- Defaults can change when the corpus or models change; rerun the eval before changing them.
- A model-based reranker or judge adds a model call per question.
- Graph RAG was considered and deferred. It is reconsidered if recall at 5 on multi-step
  questions stays below 0.70.
```

- [ ] **Step 2: Write ADR-0013**

`docs/adr/0013-corpus-manifest.md`:

```markdown
# ADR-0013: Corpus defined by an approved manifest of official documents

Status: Accepted
Date: 2026-10-04

## Context

The seed rows in migration V5 are hand-written sentences attributed to real agencies. They
were cited to users as sources. Ingestion took its title, agency, and hazards from the
connector, so it could not load documents from more than one publisher.

## Decision

- The corpus is the list in `corpus/manifest.yaml`. Each entry is an official English
  document of global guidance, with its publisher, hazards, published date, reuse terms,
  and SHA-256 checksum. The maintainer approves every entry.
- `make ingest-corpus` loads entries not yet present. A checksum mismatch fails the entry:
  the document changed since approval.
- Ingestion refuses a document without recorded reuse terms.
- Related hazards are listed in the same file and must cite a corpus document.
- Seed rows have status `SEED`. Retrieval returns only statuses in `sheltr.search.statuses`,
  which is `ACTIVE` outside tests.
- No guidance text written by a person or a model is stored as corpus content.

## Consequences

- Anyone can rebuild the same corpus from the repository.
- Scanned PDFs cannot be ingested.
- Retired and superseded documents are no longer retrievable; before this change retrieval
  ignored document status.
```

- [ ] **Step 3: Amend ADR-0008**

Append to `docs/adr/0008-mandatory-citation-grounding.md`:

```markdown

## Amendment (2026-10-04): cite only the passages an answer uses

Passages are numbered in the prompt and the model marks each sentence with the passage that
supports it. Citations list only the marked passages. An answer that marks no retrieved
passage is not shown; the user gets the standard refusal. Previously every retrieved passage
was listed as a citation whether the answer used it or not.
```

- [ ] **Step 4: Update the README**

In `README.md`:

- In the opening paragraph, replace the sentence beginning "Sheltr never invents emergency guidance" with: "Sheltr answers only from official documents listed in `corpus/manifest.yaml`, and every answer cites the passages it used."
- Replace the hazard list in the first paragraph with: "earthquake, flood, tropical cyclone, tornado, tsunami, wildfire, extreme heat, winter storm, landslide, glacial lake flood, volcano, drought".
- Under "Status", add a paragraph: "The rows seeded by migration V5 are test fixtures, not official text. They have status `SEED` and are never returned to users."
- In the "Local development" command block, add `make ingest-corpus  # load the approved corpus from corpus/manifest.yaml` after `make migrate`, and `make eval CONFIG=name   # run the eval question set; see docs/eval/` after `make test`.
- Replace the whole "Switching between `local` and `gemini`" subsection body with: "Each chunk records the embedding model that produced its vector. If you switch to a profile with a different embedding model, Sheltr refuses to answer questions (HTTP 503) until you run `make seed-embeddings` under the new profile, which re-embeds every chunk."
- Add `claude` to the provider list: "**`claude` (opt-in)** — Claude Haiku for answers, the local Ollama model for embeddings. Requires `ANTHROPIC_API_KEY`."

- [ ] **Step 5: Retire the old threshold test and add the package description**

```bash
git rm apps/backend/src/test/java/com/sheltr/search/infrastructure/RelevanceThresholdEvalTest.java
```

In `RES/application-local.yml`, replace the comment block above `relevance-threshold: 0.65` with: `# Used by the threshold judge. See docs/eval/comparison.md for how the default judge was chosen.`

`MAIN/eval/package-info.java`:

```java
/**
 * Eval harness: runs a question set through the answer pipeline and scores the results.
 * Command-line only; nothing here serves user requests.
 */
package com.sheltr.eval;
```

- [ ] **Step 6: Run the full suite and commit**

Run: `$MVN test`
Expected: PASS.

```bash
git add -A README.md docs/adr apps/backend/src
git commit -m "docs: record retrieval and corpus decisions and update README"
```
