# Local/CPU Model Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a local, CPU-capable model path (Ollama, serving Hugging-Face-published weights) alongside the existing Gemini path, and make it the default so a stranger cloning the repo gets a fully working, offline, zero-API-key experience.

**Architecture:** Both providers implement the same Spring AI portable interfaces (`ChatModel`, `EmbeddingModel`) the existing adapters already depend on exclusively — so the migration is almost entirely configuration (a new Maven dependency, a Podman sidecar, two Spring profiles with mutually-exclusive autoconfiguration excludes) plus one empirically-derived safety number (the relevance threshold) and one new capstone test proving the local path works end to end, the same way the Gemini path already is proven.

**Tech Stack:** Spring Boot 3.5.0, Spring AI 1.1.2 (`spring-ai-starter-model-google-genai`, `spring-ai-starter-model-google-genai-embedding`, new: `spring-ai-starter-model-ollama`), Ollama (sidecar container, `qwen2.5:3b-instruct` + `nomic-embed-text`), Podman, jOOQ, Testcontainers/JUnit 5.

## Global Constraints

- Domain packages (`*/domain`) have zero framework imports (ADR-0002) — this migration touches only `infrastructure`, `config`, and `application-*.yml`.
- No vendor SDK type (Google GenAI or Ollama) may appear outside `infrastructure`/`config` packages (ADR-0003, now applies to both providers).
- Container runtime is Podman only (ADR-0010) — the Ollama sidecar goes in the existing `infra/podman/compose.yaml`.
- New dependencies/models must be license-compatible with the project's Apache-2.0 license (ADR-0009) — `spring-ai-starter-model-ollama`, `qwen2.5:3b-instruct`, and `nomic-embed-text` are all Apache-2.0.
- `Embedding.DIMENSION = 768` (`apps/backend/src/main/java/com/sheltr/knowledge/domain/Embedding.java`) and the `knowledge.chunks.embedding` column (`VECTOR(768)`, `vector_cosine_ops` index) are unchanged — `nomic-embed-text` is 768-dimensional, so no schema migration is needed.
- The relevance threshold (`sheltr.search.relevance-threshold`, read by `RelevanceGate.passesThreshold` via `AskQuestionService`) is safety-critical per ADR-0008: a wrong "no answer" is an accepted tradeoff, a wrong invented answer is not. Any new threshold must be derived empirically, favoring false-rejects over false-accepts.
- `local` becomes the default Spring profile (`spring.profiles.active`); `gemini` remains fully supported as an explicit opt-in.

---

### Task 1: Rename Gemini adapters to provider-neutral names

**Files:**
- Rename: `apps/backend/src/main/java/com/sheltr/knowledge/infrastructure/GeminiEmbeddingGeneratorAdapter.java` → `SpringAiEmbeddingGeneratorAdapter.java`
- Rename: `apps/backend/src/main/java/com/sheltr/ai/infrastructure/GeminiAnswerGeneratorAdapter.java` → `SpringAiAnswerGeneratorAdapter.java`
- Modify: `apps/backend/src/main/java/com/sheltr/knowledge/config/EmbeddingGeneratorConfig.java`
- Modify: `apps/backend/src/main/java/com/sheltr/ai/config/AnswerGeneratorConfig.java`
- Rename: `apps/backend/src/test/java/com/sheltr/knowledge/infrastructure/GeminiEmbeddingGeneratorAdapterTest.java` → `SpringAiEmbeddingGeneratorAdapterTest.java`
- Rename: `apps/backend/src/test/java/com/sheltr/ai/infrastructure/GeminiAnswerGeneratorAdapterTest.java` → `SpringAiAnswerGeneratorAdapterTest.java`
- Modify: `apps/backend/src/test/java/com/sheltr/search/infrastructure/PgvectorSemanticSearchAdapterIntegrationTest.java`

**Interfaces:**
- Produces: `SpringAiEmbeddingGeneratorAdapter(EmbeddingModel)` implementing `EmbeddingGenerator` (unchanged behavior/signature from `GeminiEmbeddingGeneratorAdapter`), `SpringAiAnswerGeneratorAdapter(ChatModel, KnowledgeDocumentRepository)` implementing `AnswerGenerator` (unchanged behavior/signature from `GeminiAnswerGeneratorAdapter`). Every later task that wires a `ChatModel`/`EmbeddingModel` bean (regardless of provider) uses these two classes — there is no separate Ollama-specific adapter class.

This is a pure rename — no behavior change, so it's verified by the existing test suite rather than a new failing test.

- [ ] **Step 1: Rename the two adapter classes and their test files with `git mv`, keeping content unchanged except the class name and error-message text**

```bash
git mv apps/backend/src/main/java/com/sheltr/knowledge/infrastructure/GeminiEmbeddingGeneratorAdapter.java \
       apps/backend/src/main/java/com/sheltr/knowledge/infrastructure/SpringAiEmbeddingGeneratorAdapter.java
git mv apps/backend/src/main/java/com/sheltr/ai/infrastructure/GeminiAnswerGeneratorAdapter.java \
       apps/backend/src/main/java/com/sheltr/ai/infrastructure/SpringAiAnswerGeneratorAdapter.java
git mv apps/backend/src/test/java/com/sheltr/knowledge/infrastructure/GeminiEmbeddingGeneratorAdapterTest.java \
       apps/backend/src/test/java/com/sheltr/knowledge/infrastructure/SpringAiEmbeddingGeneratorAdapterTest.java
git mv apps/backend/src/test/java/com/sheltr/ai/infrastructure/GeminiAnswerGeneratorAdapterTest.java \
       apps/backend/src/test/java/com/sheltr/ai/infrastructure/SpringAiAnswerGeneratorAdapterTest.java
```

- [ ] **Step 2: Update the renamed main-source files' content**

`SpringAiEmbeddingGeneratorAdapter.java` — only the class name changes:

```java
package com.sheltr.knowledge.infrastructure;

import com.sheltr.knowledge.domain.Embedding;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import org.springframework.ai.embedding.EmbeddingModel;

public class SpringAiEmbeddingGeneratorAdapter implements EmbeddingGenerator {

    private final EmbeddingModel embeddingModel;

    public SpringAiEmbeddingGeneratorAdapter(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public Embedding embed(String text) {
        float[] vector = embeddingModel.embed(text);
        return new Embedding(vector);
    }
}
```

`SpringAiAnswerGeneratorAdapter.java` — class name changes, and the error message generalizes since it's no longer Gemini-specific:

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

public class SpringAiAnswerGeneratorAdapter implements AnswerGenerator {

    private final ChatModel chatModel;
    private final KnowledgeDocumentRepository documentRepository;

    public SpringAiAnswerGeneratorAdapter(ChatModel chatModel, KnowledgeDocumentRepository documentRepository) {
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
            return new AnswerGenerationFailed("Answer generation failed: " + e.getMessage());
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

- [ ] **Step 3: Update the renamed test files' content (rename the class under test, keep assertions identical)**

In `SpringAiEmbeddingGeneratorAdapterTest.java`, rename the class and every `GeminiEmbeddingGeneratorAdapter` reference to `SpringAiEmbeddingGeneratorAdapter`:

```java
package com.sheltr.knowledge.infrastructure;

import com.sheltr.knowledge.domain.Embedding;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class SpringAiEmbeddingGeneratorAdapterTest {

    @Test
    void embedsRealTextViaGemini() {
        GoogleGenAiEmbeddingConnectionDetails connectionDetails = GoogleGenAiEmbeddingConnectionDetails.builder()
                .apiKey(System.getenv("GEMINI_API_KEY"))
                .build();
        EmbeddingModel embeddingModel = new GoogleGenAiTextEmbeddingModel(connectionDetails,
                GoogleGenAiTextEmbeddingOptions.builder().model("gemini-embedding-001").dimensions(768).build());
        SpringAiEmbeddingGeneratorAdapter adapter = new SpringAiEmbeddingGeneratorAdapter(embeddingModel);

        Embedding embedding = adapter.embed("Boil water for one minute to make it safe to drink.");

        assertThat(embedding.values()).hasSize(768);
    }
}
```

In `SpringAiAnswerGeneratorAdapterTest.java`, apply the same rename (`GeminiAnswerGeneratorAdapter` → `SpringAiAnswerGeneratorAdapter`, class name `GeminiAnswerGeneratorAdapterTest` → `SpringAiAnswerGeneratorAdapterTest`); every other line (the fake `KnowledgeDocumentRepository`, `RankedResult`, assertions) is unchanged from the current file.

- [ ] **Step 4: Update the two config classes to reference the renamed adapters**

`EmbeddingGeneratorConfig.java`:

```java
package com.sheltr.knowledge.config;

import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.knowledge.infrastructure.SpringAiEmbeddingGeneratorAdapter;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingGeneratorConfig {

    @Bean
    public EmbeddingGenerator embeddingGenerator(EmbeddingModel embeddingModel) {
        return new SpringAiEmbeddingGeneratorAdapter(embeddingModel);
    }
}
```

`AnswerGeneratorConfig.java`:

```java
package com.sheltr.ai.config;

import com.sheltr.ai.domain.AnswerGenerator;
import com.sheltr.ai.infrastructure.SpringAiAnswerGeneratorAdapter;
import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnswerGeneratorConfig {

    @Bean
    public AnswerGenerator answerGenerator(ChatModel chatModel, KnowledgeDocumentRepository documentRepository) {
        return new SpringAiAnswerGeneratorAdapter(chatModel, documentRepository);
    }
}
```

- [ ] **Step 5: Update `PgvectorSemanticSearchAdapterIntegrationTest.java`'s reference**

Change the import from `com.sheltr.knowledge.infrastructure.GeminiEmbeddingGeneratorAdapter` — this class isn't actually instantiated in that test (it builds `EmbeddingModel` directly and passes it to `PgvectorSemanticSearchAdapter` via a small inline `EmbeddingGenerator`), so confirm with:

```bash
grep -n "Gemini" apps/backend/src/test/java/com/sheltr/search/infrastructure/PgvectorSemanticSearchAdapterIntegrationTest.java
```

If the only match is the now-stale import line, delete that import line (it's unused after the rename — nothing else in the file references `GeminiEmbeddingGeneratorAdapter`).

- [ ] **Step 6: Run the full test suite to confirm the rename introduced no regressions**

Run: `cd apps/backend && mvn -q test`
Expected: `BUILD SUCCESS`. Tests gated on `GEMINI_API_KEY`/`OLLAMA_AVAILABLE` will skip if those aren't set in your environment — that's expected at this point in the plan.

- [ ] **Step 7: Commit**

```bash
git add apps/backend/src/main/java/com/sheltr/knowledge/infrastructure/SpringAiEmbeddingGeneratorAdapter.java \
        apps/backend/src/main/java/com/sheltr/ai/infrastructure/SpringAiAnswerGeneratorAdapter.java \
        apps/backend/src/main/java/com/sheltr/knowledge/config/EmbeddingGeneratorConfig.java \
        apps/backend/src/main/java/com/sheltr/ai/config/AnswerGeneratorConfig.java \
        apps/backend/src/test/java/com/sheltr/knowledge/infrastructure/SpringAiEmbeddingGeneratorAdapterTest.java \
        apps/backend/src/test/java/com/sheltr/ai/infrastructure/SpringAiAnswerGeneratorAdapterTest.java \
        apps/backend/src/test/java/com/sheltr/search/infrastructure/PgvectorSemanticSearchAdapterIntegrationTest.java
git commit -m "refactor(ai,knowledge): rename Gemini adapters to provider-neutral names

Both adapters only ever depended on Spring AI's portable ChatModel/
EmbeddingModel interfaces, never a Gemini-specific type, so they can
serve either provider. No behavior change."
```

---

### Task 2: Add Ollama dependency and Podman sidecar

**Files:**
- Modify: `apps/backend/pom.xml`
- Modify: `infra/podman/compose.yaml`
- Modify: `Makefile`

**Interfaces:**
- Produces: an `ollama` service reachable at `http://localhost:11434` once `make up` completes, with `qwen2.5:3b-instruct` and `nomic-embed-text` pulled and cached in a named volume. Task 3 configures Spring to talk to this URL.

- [ ] **Step 1: Add the Ollama starter dependency to `apps/backend/pom.xml`**

Add immediately after the two existing `spring-ai-starter-model-google-genai*` dependencies (around line 84):

```xml
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-starter-model-ollama</artifactId>
    </dependency>
```

- [ ] **Step 2: Resolve the dependency and confirm the autoconfiguration class names Task 3 needs to exclude**

Run: `cd apps/backend && mvn -q dependency:resolve`
Expected: `BUILD SUCCESS`, and `spring-ai-autoconfigure-model-ollama-1.1.2.jar` now present under `~/.m2/repository/org/springframework/ai/spring-ai-autoconfigure-model-ollama/1.1.2/`.

Then confirm the exact autoconfiguration class names (needed verbatim in Task 3):

```bash
unzip -l ~/.m2/repository/org/springframework/ai/spring-ai-autoconfigure-model-ollama/1.1.2/spring-ai-autoconfigure-model-ollama-1.1.2.jar | grep AutoConfiguration.class
```

Expected: a listing that includes `org/springframework/ai/model/ollama/autoconfigure/OllamaChatAutoConfiguration.class` and `org/springframework/ai/model/ollama/autoconfigure/OllamaEmbeddingAutoConfiguration.class` (these are the two Task 3 excludes; if the actual package or class names printed differ from this, use the printed ones in Task 3 instead — this step exists specifically to catch that before Task 3 is written into config).

- [ ] **Step 3: Add the Ollama sidecar to `infra/podman/compose.yaml`**

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg16
    container_name: sheltr-postgres
    environment:
      POSTGRES_DB: sheltr
      POSTGRES_USER: sheltr
      POSTGRES_PASSWORD: sheltr
    ports:
      - "5432:5432"
    volumes:
      - sheltr-postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U sheltr -d sheltr"]
      interval: 5s
      timeout: 5s
      retries: 10

  ollama:
    image: docker.io/ollama/ollama:latest
    container_name: sheltr-ollama
    ports:
      - "11434:11434"
    volumes:
      - sheltr-ollama-data:/root/.ollama
    healthcheck:
      test: ["CMD-SHELL", "ollama list || exit 1"]
      interval: 5s
      timeout: 5s
      retries: 20

volumes:
  sheltr-postgres-data:
  sheltr-ollama-data:
```

- [ ] **Step 4: Extend `make up` to wait for Ollama and pull both models**

```makefile
.PHONY: up down build test migrate seed-embeddings

up:
	podman compose -f infra/podman/compose.yaml up -d --wait
	podman exec sheltr-ollama ollama pull qwen2.5:3b-instruct
	podman exec sheltr-ollama ollama pull nomic-embed-text

down:
	podman compose -f infra/podman/compose.yaml down

build:
	cd apps/backend && mvn -q -DskipTests package

test:
	cd apps/backend && mvn -q test

migrate:
	cd apps/backend && mvn -q flyway:migrate

seed-embeddings:
	cd apps/backend && mvn -q spring-boot:run -Dspring-boot.run.arguments=seed-embeddings
```

`ollama pull` is idempotent — after the first run it's a fast no-op against the cached model in `sheltr-ollama-data`.

- [ ] **Step 5: Run `make up` and confirm both models are pulled**

Run: `make up`
Expected: Postgres and Ollama both report healthy, then two successful `ollama pull` outputs. Confirm with:

```bash
podman exec sheltr-ollama ollama list
```

Expected: output lists both `qwen2.5:3b-instruct` and `nomic-embed-text`.

- [ ] **Step 6: Commit**

```bash
git add apps/backend/pom.xml infra/podman/compose.yaml Makefile
git commit -m "feat(infra): add Ollama sidecar with qwen2.5:3b-instruct and nomic-embed-text"
```

---

### Task 3: Split Spring config into `gemini`/`local` profiles, default to `local`

**Files:**
- Modify: `apps/backend/src/main/resources/application.yml`
- Create: `apps/backend/src/main/resources/application-gemini.yml`
- Create: `apps/backend/src/main/resources/application-local.yml`
- Modify: `apps/backend/src/test/java/com/sheltr/ingestion/interfaces/IngestionToRagEndToEndTest.java`
- Modify: `apps/backend/src/test/java/com/sheltr/ingestion/interfaces/IngestionControllerIntegrationTest.java`
- Modify: `apps/backend/src/test/java/com/sheltr/ai/interfaces/ConversationControllerIntegrationTest.java`
- Modify: `apps/backend/src/test/java/com/sheltr/ai/interfaces/RagLoopEndToEndTest.java`

**Interfaces:**
- Consumes: the two autoconfiguration class names confirmed in Task 2 Step 2.
- Produces: with no `SPRING_PROFILES_ACTIVE` set, the app boots against Ollama with no API key required. With `SPRING_PROFILES_ACTIVE=gemini`, it boots against Gemini exactly as before this plan. Task 4 and Task 5 both depend on `local` working; the four modified test classes continue exercising the Gemini path exactly as they do today, now pinned explicitly rather than relying on it being the unstated default.

- [ ] **Step 1: Trim `application.yml` to profile-agnostic keys and set the default active profile**

```yaml
spring:
  application:
    name: sheltr-backend
  profiles:
    active: local
  datasource:
    url: jdbc:postgresql://localhost:5432/sheltr
    username: sheltr
    password: sheltr
  flyway:
    enabled: true

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

- [ ] **Step 2: Create `application-gemini.yml` with today's Gemini config, unchanged, plus an autoconfigure exclude for Ollama**

```yaml
spring:
  ai:
    google:
      genai:
        api-key: ${GEMINI_API_KEY}
        chat:
          options:
            model: gemini-2.5-flash
        embedding:
          api-key: ${GEMINI_API_KEY}
          text:
            options:
              model: gemini-embedding-001
              dimensions: 768
  autoconfigure:
    exclude:
      - org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration
      - org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration

sheltr:
  search:
    relevance-threshold: 0.75
```

(If Task 2 Step 2 found different class names, use those here instead.)

- [ ] **Step 3: Create `application-local.yml` with Ollama config and an autoconfigure exclude for Google GenAI**

```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        model: qwen2.5:3b-instruct
      embedding:
        model: nomic-embed-text
  autoconfigure:
    exclude:
      - org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration
      - org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration
      - org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration

sheltr:
  search:
    relevance-threshold: 0.5
```

`0.5` here is a temporary placeholder that Task 4 replaces with an empirically derived value — it exists only so the app boots and `local`-profile tests in this task have a concrete number to check is present; Task 4 is what makes it correct.

- [ ] **Step 4: Boot the app under each profile and confirm no bean conflicts**

Run: `cd apps/backend && GEMINI_API_KEY=dummy mvn -q spring-boot:run -Dspring-boot.run.profiles=gemini &`, wait a few seconds, then `curl -s http://localhost:8080/actuator/health`, then stop it (`kill %1`).
Expected: `{"status":"UP"}` — confirms only one `ChatModel`/`EmbeddingModel` bean exists under `gemini` (a `NoUniqueBeanDefinitionException` at startup would mean an exclude class name from Step 2 is wrong).

Run: `cd apps/backend && mvn -q spring-boot:run &` (no profile flag — `local` is now default), wait a few seconds, then `curl -s http://localhost:8080/actuator/health`, then stop it (`kill %1`).
Expected: `{"status":"UP"}` with no `GEMINI_API_KEY` set at all — confirms `local` truly needs no cloud credentials.

- [ ] **Step 5: Pin the four existing Gemini-path `@SpringBootTest` classes to `@ActiveProfiles("gemini")`**

Without this, once `local` becomes the default, these tests would boot against Ollama instead of Gemini whenever `GEMINI_API_KEY` happens to be set but the test doesn't explicitly request the `gemini` profile — silently testing the wrong provider. Add the import and annotation to all four:

`apps/backend/src/test/java/com/sheltr/ingestion/interfaces/IngestionToRagEndToEndTest.java`:
```java
import org.springframework.test.context.ActiveProfiles;
// ...
@Testcontainers
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@ActiveProfiles("gemini")
@SpringBootTest(classes = SheltrBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestionToRagEndToEndTest {
```

Apply the same two-line change (import + `@ActiveProfiles("gemini")` directly above `@SpringBootTest`) to:
- `apps/backend/src/test/java/com/sheltr/ingestion/interfaces/IngestionControllerIntegrationTest.java`
- `apps/backend/src/test/java/com/sheltr/ai/interfaces/ConversationControllerIntegrationTest.java`
- `apps/backend/src/test/java/com/sheltr/ai/interfaces/RagLoopEndToEndTest.java`

- [ ] **Step 6: Run the full suite with `GEMINI_API_KEY` set to confirm the pinned Gemini tests still pass**

Run: `cd apps/backend && GEMINI_API_KEY=$GEMINI_API_KEY mvn -q test`
Expected: `BUILD SUCCESS`, same passing tests as before this plan (plus the Task 1 renames).

- [ ] **Step 7: Commit**

```bash
git add apps/backend/src/main/resources/application.yml \
        apps/backend/src/main/resources/application-gemini.yml \
        apps/backend/src/main/resources/application-local.yml \
        apps/backend/src/test/java/com/sheltr/ingestion/interfaces/IngestionToRagEndToEndTest.java \
        apps/backend/src/test/java/com/sheltr/ingestion/interfaces/IngestionControllerIntegrationTest.java \
        apps/backend/src/test/java/com/sheltr/ai/interfaces/ConversationControllerIntegrationTest.java \
        apps/backend/src/test/java/com/sheltr/ai/interfaces/RagLoopEndToEndTest.java
git commit -m "feat(config): split gemini/local Spring profiles, default to local

local (Ollama) needs no API key and no external network call at
request time. gemini remains fully supported via
SPRING_PROFILES_ACTIVE=gemini. Existing Gemini-path tests are pinned
to @ActiveProfiles(\"gemini\") so they keep exercising that path
regardless of the new default."
```

---

### Task 4: Empirically recalibrate the local-profile relevance threshold

**Files:**
- Create: `apps/backend/src/test/java/com/sheltr/search/infrastructure/RelevanceThresholdEvalTest.java`
- Modify: `apps/backend/src/main/resources/application-local.yml`

**Interfaces:**
- Consumes: `EmbeddingGenerator` (`com.sheltr.knowledge.domain`), `ChunkRepository`/`JooqChunkRepository` (`com.sheltr.knowledge.domain`/`infrastructure`), `ChunkEmbeddingBackfillService` (`com.sheltr.knowledge.application`, constructor `(ChunkRepository, EmbeddingGenerator)`, method `int backfillAll()`), `Retriever`/`SearchQuery`/`RankedResult` (`com.sheltr.search.domain`) — all reused as-is, no new production code.
- Produces: the real `sheltr.search.relevance-threshold` value for `application-local.yml`, replacing Task 3's `0.5` placeholder.

This test reuses the exact production retrieval path (`PgvectorSemanticSearchAdapter` via the `Retriever` bean, embeddings via the real `nomic-embed-text` model) against the 32 chunks already in the seed migration (`V5__seed_knowledge_documents.sql`), so the threshold it derives reflects real production behavior, not a synthetic approximation.

- [ ] **Step 1: Write the eval fixture and sweep harness**

```java
package com.sheltr.search.infrastructure;

import com.sheltr.SheltrBackendApplication;
import com.sheltr.knowledge.application.ChunkEmbeddingBackfillService;
import com.sheltr.knowledge.domain.ChunkRepository;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.knowledge.infrastructure.JooqChunkRepository;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "OLLAMA_AVAILABLE", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(classes = SheltrBackendApplication.class)
class RelevanceThresholdEvalTest {

    private record EvalCase(String question, boolean expectedRelevant) {
    }

    // Two "irrelevant" questions per disaster type: nothing in the 32-chunk seed corpus
    // (V5__seed_knowledge_documents.sql) answers them. Three "relevant" questions per type,
    // each paraphrasing (not quoting) one specific seed chunk, so a working embedding model
    // must generalize past exact wording to score them above threshold.
    private static final List<EvalCase> FIXTURE = List.of(
            new EvalCase("What should I do the moment the ground starts shaking?", true),
            new EvalCase("Is it safer to run outside or stay inside during an earthquake?", true),
            new EvalCase("I smell gas after an earthquake, what should I do?", true),
            new EvalCase("How do I renew my driver's license online?", false),
            new EvalCase("What's a good recipe for banana bread?", false),

            new EvalCase("Is it dangerous to drive through moving flood water?", true),
            new EvalCase("How long should I boil water to make it safe after a flood?", true),
            new EvalCase("I don't have a stove, can I use bleach to purify water?", true),
            new EvalCase("What's the best way to invest in index funds?", false),
            new EvalCase("How do I train my dog to stop barking?", false),

            new EvalCase("How should I prepare my house before hurricane season?", true),
            new EvalCase("A hurricane warning was just issued, what should I do?", true),
            new EvalCase("Is standing water after a hurricane safe to walk through?", true),
            new EvalCase("What's the tax deadline this year?", false),
            new EvalCase("How do I learn to play guitar?", false),

            new EvalCase("Where's the safest place in my house during a tornado?", true),
            new EvalCase("I'm driving and a tornado is coming, what do I do?", true),
            new EvalCase("What's the difference between a tornado watch and a warning?", true),
            new EvalCase("What's a good workout routine for beginners?", false),
            new EvalCase("How do I set up a home Wi-Fi network?", false),

            new EvalCase("I felt a strong earthquake near the beach, should I wait for an official warning?", true),
            new EvalCase("The ocean water suddenly pulled back and exposed the sea floor, what does that mean?", true),
            new EvalCase("Is it safe to go back to the beach right after a tsunami wave hits?", true),
            new EvalCase("How do I bake sourdough bread?", false),
            new EvalCase("What's the best programming language to learn first?", false),

            new EvalCase("What should I wear if I need to evacuate from a wildfire?", true),
            new EvalCase("How can I protect my home before wildfire season starts?", true),
            new EvalCase("I'm trapped by a wildfire, what should I do?", true),
            new EvalCase("How do I change a flat tire?", false),
            new EvalCase("What's a good beginner yoga routine?", false),

            new EvalCase("What should I drink during a heat wave?", true),
            new EvalCase("What are the warning signs of heat stroke?", true),
            new EvalCase("Is it okay to leave my dog in the car for a few minutes on a hot day?", true),
            new EvalCase("How do I fix a leaky faucet?", false),
            new EvalCase("What's the plot of a good mystery novel?", false),

            new EvalCase("Can I run my generator inside during a power outage?", true),
            new EvalCase("My power is out and I need to store insulin, what should I do?", true),
            new EvalCase("What does frostbite look like?", true),
            new EvalCase("How do I apply for a passport?", false),
            new EvalCase("What's a good stretching routine before running?", false)
    );

    private static final double[] CANDIDATE_THRESHOLDS =
            {0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80, 0.85, 0.90};

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
    DataSource dataSource;

    @Autowired
    EmbeddingGenerator embeddingGenerator;

    @Autowired
    Retriever retriever;

    @Test
    void sweepThresholdsAndReportTheSafestOne() {
        ChunkRepository chunkRepository = new JooqChunkRepository(dataSource);
        int backfilled = new ChunkEmbeddingBackfillService(chunkRepository, embeddingGenerator).backfillAll();
        assertThat(backfilled).isEqualTo(32);

        double[] topScores = new double[FIXTURE.size()];
        for (int i = 0; i < FIXTURE.size(); i++) {
            List<RankedResult> results = retriever.retrieve(new SearchQuery(FIXTURE.get(i).question()), 1);
            topScores[i] = results.isEmpty() ? 0.0 : results.get(0).score();
        }

        double chosenThreshold = -1;
        int chosenFalseAccepts = Integer.MAX_VALUE;
        int chosenFalseRejects = Integer.MAX_VALUE;
        for (double threshold : CANDIDATE_THRESHOLDS) {
            int falseAccepts = 0;
            int falseRejects = 0;
            for (int i = 0; i < FIXTURE.size(); i++) {
                boolean passes = topScores[i] >= threshold;
                if (passes && !FIXTURE.get(i).expectedRelevant()) falseAccepts++;
                if (!passes && FIXTURE.get(i).expectedRelevant()) falseRejects++;
            }
            System.out.printf("threshold=%.2f falseAccepts=%d falseRejects=%d%n", threshold, falseAccepts, falseRejects);
            boolean better = falseAccepts < chosenFalseAccepts
                    || (falseAccepts == chosenFalseAccepts && falseRejects < chosenFalseRejects);
            if (better) {
                chosenThreshold = threshold;
                chosenFalseAccepts = falseAccepts;
                chosenFalseRejects = falseRejects;
            }
        }

        System.out.printf("CHOSEN threshold=%.2f falseAccepts=%d falseRejects=%d%n",
                chosenThreshold, chosenFalseAccepts, chosenFalseRejects);
        assertThat(chosenFalseAccepts).isZero();
    }
}
```

- [ ] **Step 2: Run it against real Ollama to get the recommended threshold**

Run: `podman exec sheltr-ollama ollama list` first to confirm `nomic-embed-text` is present (from Task 2), then:

```bash
cd apps/backend && OLLAMA_AVAILABLE=true mvn -q test -Dtest=RelevanceThresholdEvalTest
```

Expected: `BUILD SUCCESS`, and stdout containing a `CHOSEN threshold=X.XX falseAccepts=0 falseRejects=N` line. Record that `X.XX` value — it's what Step 3 puts in `application-local.yml`. If every candidate threshold has `falseAccepts=0`, use the lowest such threshold from the printed table (fewest false-rejects among safe options); if none has `falseAccepts=0`, the test fails and the fixture or `qwen2.5`/`nomic-embed-text` choice needs revisiting before proceeding — do not hand-pick an unsafe threshold to force a pass.

- [ ] **Step 3: Replace the placeholder in `application-local.yml` with the recorded value**

```yaml
sheltr:
  search:
    relevance-threshold: <value printed in Step 2>
```

- [ ] **Step 4: Re-run the eval test to confirm the committed value is the one that was actually derived**

Run: `cd apps/backend && OLLAMA_AVAILABLE=true mvn -q test -Dtest=RelevanceThresholdEvalTest`
Expected: `BUILD SUCCESS`, same `CHOSEN threshold` value as Step 2 (confirms determinism — `nomic-embed-text` embeddings are not randomized).

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/test/java/com/sheltr/search/infrastructure/RelevanceThresholdEvalTest.java \
        apps/backend/src/main/resources/application-local.yml
git commit -m "test(search): empirically recalibrate local-profile relevance threshold

Sweeps candidate thresholds against a labeled fixture covering all 8
disaster types through the real nomic-embed-text embedding model and
picks the value with zero false-accepts, per ADR-0008's preference
for a wrong \"no answer\" over a wrong invented one."
```

---

### Task 5: Local-profile end-to-end Ingestion→RAG test

**Files:**
- Create: `apps/backend/src/test/java/com/sheltr/ingestion/interfaces/LocalIngestionToRagEndToEndTest.java`

**Interfaces:**
- Consumes: `IngestionRunDto`, `TriggerIngestionRequestDto` (`com.sheltr.ingestion.interfaces`, same package — package-private types already used by the existing `IngestionToRagEndToEndTest`), `Retriever`/`SearchQuery`/`RankedResult` (`com.sheltr.search.domain`).
- Produces: nothing consumed by later tasks — this is this subproject's own capstone proof, mirroring the one Gemini already has.

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "OLLAMA_AVAILABLE", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(classes = SheltrBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalIngestionToRagEndToEndTest {

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
    void ingestedDocumentIsRetrievableViaLocalSemanticSearch() {
        ResponseEntity<IngestionRunDto> response = restTemplate.postForEntity(
                "/api/v1/admin/ingestion/runs",
                new TriggerIngestionRequestDto(CAREGIVERS_GUIDE_URL, "PDF"),
                IngestionRunDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().status()).isEqualTo("SUCCEEDED");

        List<RankedResult> results = retriever.retrieve(
                new SearchQuery("How can caregivers prepare for a disaster?"), 5);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).content()).isNotBlank();
    }
}
```

Unlike the Gemini version, this test does not assert `results.get(0).score() > 0.5` — the local-profile threshold is whatever Task 4 derived (which may not be `0.5`), and this test's job is proving connectivity, not re-asserting the threshold value Task 4 already covers.

- [ ] **Step 2: Run it against real Ollama**

Run: `cd apps/backend && OLLAMA_AVAILABLE=true mvn -q test -Dtest=LocalIngestionToRagEndToEndTest`
Expected: `BUILD SUCCESS`, 1/1 passing. Like its Gemini counterpart, expect this to be one of the slower tests in the suite (real ~2.5MB download, real chunk-count-many embedding calls against a CPU-served model).

- [ ] **Step 3: Run the entire backend test suite one more time with both providers available, to confirm no regressions across the whole migration**

Run: `cd apps/backend && GEMINI_API_KEY=$GEMINI_API_KEY OLLAMA_AVAILABLE=true mvn -q test`
Expected: `BUILD SUCCESS`, every test passing — Gemini-path tests (pinned to `@ActiveProfiles("gemini")` in Task 3) and the new local-path tests (Task 4, Task 5) both green.

- [ ] **Step 4: Commit**

```bash
git add apps/backend/src/test/java/com/sheltr/ingestion/interfaces/LocalIngestionToRagEndToEndTest.java
git commit -m "test(ingestion): add local-profile Ingestion-to-RAG end-to-end test

Mirrors the existing Gemini-gated IngestionToRagEndToEndTest so the
local/Ollama path has the same real, end-to-end proof."
```

---

### Task 6: ADR updates

**Files:**
- Modify: `docs/adr/0003-gemini-via-spring-ai.md`
- Create: `docs/adr/0011-local-ollama-default-provider.md`

**Interfaces:**
- None — documentation only, no code dependency, but recorded last so it accurately reflects what Tasks 1–5 actually built.

- [ ] **Step 1: Amend ADR-0003**

```markdown
# ADR-0003: Google Gemini and Ollama, both accessed exclusively through Spring AI

## Status
Amended by ADR-0011 — see that ADR for the current default-provider decision. The core rule
below (no vendor SDK type outside `infrastructure`) is unchanged and now applies to both
providers.

## Context
Product requirement (original): Gemini is the only LLM provider (no OpenAI, no Anthropic, no
local LLM). At the same time, domain/application code must not hard-couple to a vendor SDK,
per ADR-0002.

This "Gemini only" requirement is superseded (see ADR-0011): as Sheltr moved toward an
open-source release, requiring every self-hoster to hold a Google API key and have internet
access just to run a disaster-response tool was recognized as an adoption barrier and a
single point of failure. The requirement now is that Gemini remains supported, but is no
longer the only option.

## Decision
All LLM/embedding access (embeddings, generation, and any future query rewriting, HyDE, or
summarization) goes through Spring AI's portable abstractions (`ChatModel`, `EmbeddingModel`,
etc.). No `com.google.*` Gemini SDK type or Ollama-specific type may appear outside an
`infrastructure` adapter package. This constraint made the ADR-0011 migration to a second
provider cheap: the existing `SpringAiEmbeddingGeneratorAdapter`/`SpringAiAnswerGeneratorAdapter`
classes needed no changes at all, only a Spring profile deciding which provider's
autoconfiguration supplies the beans they depend on.

## Consequences
- If Spring AI adds/changes a provider's starter, only configuration changes — adapter code
  is untouched, proven in practice by the ADR-0011 migration.
- Business logic (the AI context's `AnswerGenerator` port) is genuinely provider-agnostic now,
  not just in principle — it's exercised against two real providers.

## Alternatives Considered
- **Direct Google GenAI SDK usage**: rejected — couples domain/application code to a vendor
  SDK, violates ADR-0002, makes unit testing AI-dependent logic require mocking a concrete SDK
  class instead of a domain port.
```

- [ ] **Step 2: Write new ADR-0011**

```markdown
# ADR-0011: Local (Ollama) is the default provider; Gemini is opt-in

## Status
Accepted

## Context
Sheltr is moving toward an open-source release. ADR-0003 originally mandated Gemini as the
only provider — every self-hoster needed a Google API key and internet access just to run
the app. For an open-source disaster-response tool, that's both an adoption barrier (a
stranger cloning the repo can't try it without signing up for a cloud API first) and a
reliability concern (a tool meant to work during a disaster shouldn't have a hard dependency
on a remote API being reachable).

## Decision
Add a local, CPU-capable provider path via Ollama (`qwen2.5:3b-instruct` for generation,
`nomic-embed-text` for embedding — both Apache-2.0 licensed, both pulled from Ollama's
library, which mirrors the same weights published on Hugging Face). Make it the default
Spring profile (`local`). Gemini remains fully supported behind an explicit
`SPRING_PROFILES_ACTIVE=gemini` opt-in for users who want cloud-hosted quality/speed instead.

The relevance threshold gating citation-grounded generation (ADR-0008) is profile-scoped and
independently, empirically calibrated per provider — `gemini`'s embedding space and `local`'s
embedding space produce different similarity-score distributions, so a single shared
threshold would be wrong for at least one of them.

## Consequences
- A stranger cloning the repo gets a fully working, offline, zero-signup experience with
  `make up` — no API key, no signup, no outbound network call at answer-generation time.
- Self-hosters who want better answer quality or lower latency than a 3B CPU model provides
  can still opt into Gemini with one environment variable.
- Two providers means two paths to keep passing in CI going forward (tracked as an open-source
  release requirement, not part of this ADR).
- `qwen2.5:3b-instruct`'s answer quality is lower than Gemini's — accepted for the default,
  since ADR-0008's citation-grounding gate and empirically-tuned relevance threshold are the
  actual safety backstop, not raw model quality; a smaller model that says "I don't know" more
  often is safer than requiring a cloud dependency to say anything at all.

## Alternatives Considered
- **Keep Gemini as the only provider**: rejected — the adoption/reliability cost described in
  Context is exactly what this ADR exists to fix.
- **Drop Gemini, local-only**: rejected — some self-hosters have a Google API key and would
  prefer better answer quality; ADR-0002's port-based design already makes supporting both
  nearly free, so there's no real cost to keeping Gemini available.
- **llama.cpp or ONNX Runtime instead of Ollama**: rejected — no first-class Spring AI starter
  for either (unlike Ollama, which has one that mirrors the existing Google GenAI starter
  pattern exactly), meaning more adapter code to write and maintain for no clear benefit at
  this project's scale.
```

- [ ] **Step 3: Commit**

```bash
git add docs/adr/0003-gemini-via-spring-ai.md docs/adr/0011-local-ollama-default-provider.md
git commit -m "docs: amend ADR-0003 and add ADR-0011 for local/Ollama default provider"
```

---

## Final Verification

- [ ] **Run the full backend test suite with both providers available**

```bash
cd apps/backend && GEMINI_API_KEY=$GEMINI_API_KEY OLLAMA_AVAILABLE=true mvn -q test && cd ../..
```
Expected: `BUILD SUCCESS`.

- [ ] **Manual smoke test: clone-and-run with zero configuration**

```bash
make up
make migrate
cd apps/backend && mvn -q spring-boot:run &
sleep 10
curl -s -X POST http://localhost:8080/api/v1/admin/ingestion/runs \
  -H "Content-Type: application/json" \
  -d '{"sourceUri": "https://www.ready.gov/sites/default/files/2024-02/ready-gov_caregivers-preparedness-guide.pdf", "sourceType": "PDF"}' \
  | tee /tmp/run.json
kill %1
cd ../..
make down
```
Expected: a `202` response with `"status":"SUCCEEDED"` — with no `GEMINI_API_KEY` set anywhere in this sequence, proving the zero-signup claim in ADR-0011 is real, not aspirational.

- [ ] **Confirm git log shows all 6 tasks as separate commits**

```bash
git log --oneline -6
```
