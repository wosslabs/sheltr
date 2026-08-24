# Native Lumina Chat UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Embed Lumina 1.0.0 in the Sheltr backend so a citizen can stream a citation-grounded disaster-response answer at `http://127.0.0.1:8090/` without changing the existing REST `/ask` path.

**Architecture:** Extract `AnswerContextBuilder` so REST and Lumina share prompt/citation/confidence construction. Keep the blocking `AnswerGenerator` port unchanged. `LuminaChatApp` duplicates the retrieve → `RelevanceGate` → persist loop (~15 lines) and streams via `io.lumina.ai.ChatClient`. Lumina 1.0.0's auto-configuration can miss `ChatModel`, so Sheltr registers `@Bean io.lumina.ai.ChatClient` as `SpringAiChatClient(ChatClient.create(chatModel))` with `@ConditionalOnMissingBean`. Remove the workaround when Lumina adds `@AutoConfigureAfter` for Spring AI chat auto-configuration (or equivalent) and the bean is unused.

**Tech Stack:** Spring Boot 4.1.0, Spring AI 2.0.0, Java 25, Lumina `io.github.wosslabs:lumina-spring-boot-starter:1.0.0` + `lumina-spring-ai:1.0.0`, existing pgvector/Ollama/`gemini` profiles, JUnit 5 + Testcontainers + AssertJ.

## Global Constraints

- Prerequisite: `docs/superpowers/plans/2026-08-07-spring-boot-4-upgrade.md` is fully implemented. `apps/backend/pom.xml` parent must already be `spring-boot-starter-parent` `4.1.0` and `spring-ai.version` `2.0.0`. If not, stop and execute that plan first.
- Lumina coordinates, exact: `io.github.wosslabs:lumina-spring-boot-starter:1.0.0` and `io.github.wosslabs:lumina-spring-ai:1.0.0`.
- `lumina.port` is `8090` in `application.yml`. Tests override with `lumina.port=0`.
- REST `AnswerGenerator.generate()` stays blocking. Do not add a streaming method. Do not implement `/ask/stream`.
- Keep the conditional Lumina 1.0.0 `io.lumina.ai.ChatClient` workaround in
  `LuminaChatAppConfig`; do not create a separate `LuminaChatClientConfig`.
- `TOP_K` is `5`. Refuse text is `NoAnswerAvailable.MESSAGE` in both REST and Lumina.
- `ui.citation` is not called. Live-turn sources use `ui.ragSources` with map keys `title` and `url` only.
- Maven from `apps/backend/` always uses: `env -u SPRING_CONFIG_IMPORT -u SPRING_PROFILES_ACTIVE -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_DRIVER_CLASS_NAME -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD`
- No auth, rate limits, disclaimer copy, CI, or corpus swap in this plan.

## File structure

- Create: `apps/backend/src/main/java/com/sheltr/ai/application/AnswerContext.java` — record `prompt`, `citations`, `confidence`
- Create: `apps/backend/src/main/java/com/sheltr/ai/application/AnswerContextBuilder.java` — prompt + citations + confidence
- Create: `apps/backend/src/main/java/com/sheltr/ai/config/AnswerContextBuilderConfig.java` — `@Bean AnswerContextBuilder`
- Create: `apps/backend/src/main/java/com/sheltr/ai/interfaces/LuminaChatApp.java` — `LuminaApp` chat page
- Create: `apps/backend/src/main/java/com/sheltr/ai/config/LuminaChatAppConfig.java` — `@Bean LuminaApp`
- Create: `apps/backend/src/test/java/com/sheltr/ai/application/AnswerContextBuilderTest.java`
- Create: `apps/backend/src/test/java/com/sheltr/ai/interfaces/LocalLuminaChatEndToEndTest.java`
- Create: `apps/backend/src/test/java/com/sheltr/ai/interfaces/LuminaChatEndToEndTest.java`
- Modify: `apps/backend/src/main/java/com/sheltr/ai/domain/NoAnswerAvailable.java` — add `MESSAGE`
- Modify: `apps/backend/src/main/java/com/sheltr/ai/application/AskQuestionService.java` — use `MESSAGE`
- Modify: `apps/backend/src/test/java/com/sheltr/ai/application/AskQuestionServiceTest.java` — assert `MESSAGE`
- Modify: `apps/backend/src/main/java/com/sheltr/ai/infrastructure/SpringAiAnswerGeneratorAdapter.java` — delegate to builder
- Modify: `apps/backend/src/main/java/com/sheltr/ai/config/AnswerGeneratorConfig.java` — inject builder
- Modify: `apps/backend/src/test/java/com/sheltr/ai/infrastructure/SpringAiAnswerGeneratorAdapterTest.java` — constructor wiring only
- Modify: `apps/backend/pom.xml` — Lumina dependencies
- Modify: `apps/backend/src/main/resources/application.yml` — `lumina.port: 8090`
- Modify: `README.md` — chat UI URL

---

### Task 1: Shared refuse text constant

**Files:**
- Modify: `apps/backend/src/main/java/com/sheltr/ai/domain/NoAnswerAvailable.java`
- Modify: `apps/backend/src/main/java/com/sheltr/ai/application/AskQuestionService.java`
- Modify: `apps/backend/src/test/java/com/sheltr/ai/application/AskQuestionServiceTest.java`

**Interfaces:**
- Consumes: existing `AskQuestionService.ask(...)` fallback path
- Produces: `NoAnswerAvailable.MESSAGE` (`public static final String`) with value `I don't have enough reliable information from trusted sources to answer that question.`

- [ ] **Step 1: Write the failing assertion**

In `AskQuestionServiceTest.returnsFallbackWithoutCallingGeneratorWhenBelowThreshold`, after `assertThat(actual.citations()).isEmpty();` add:

```java
assertThat(actual.content().value()).isEqualTo(NoAnswerAvailable.MESSAGE);
```

- [ ] **Step 2: Run test to verify it fails to compile**

Run from `apps/backend/`:

```bash
env -u SPRING_CONFIG_IMPORT -u SPRING_PROFILES_ACTIVE -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_DRIVER_CLASS_NAME -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD mvn -q -Dtest=AskQuestionServiceTest#returnsFallbackWithoutCallingGeneratorWhenBelowThreshold test
```

Expected: FAIL compile, cannot find symbol `MESSAGE` on `NoAnswerAvailable`.

- [ ] **Step 3: Add the constant and use it**

Replace `NoAnswerAvailable.java` with:

```java
package com.sheltr.ai.domain;

public record NoAnswerAvailable() implements AnswerOutcome {
    public static final String MESSAGE =
            "I don't have enough reliable information from trusted sources to answer that question.";
}
```

In `AskQuestionService.java`, replace both hardcoded fallback strings with `NoAnswerAvailable.MESSAGE` (the two `fallbackMessage(...)` call sites).

- [ ] **Step 4: Run test to verify it passes**

```bash
env -u SPRING_CONFIG_IMPORT -u SPRING_PROFILES_ACTIVE -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_DRIVER_CLASS_NAME -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD mvn -q -Dtest=AskQuestionServiceTest test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/main/java/com/sheltr/ai/domain/NoAnswerAvailable.java \
  apps/backend/src/main/java/com/sheltr/ai/application/AskQuestionService.java \
  apps/backend/src/test/java/com/sheltr/ai/application/AskQuestionServiceTest.java
git commit -m "$(cat <<'EOF'
feat: share refuse text via NoAnswerAvailable.MESSAGE

EOF
)"
```

---

### Task 2: AnswerContextBuilder

**Files:**
- Create: `apps/backend/src/main/java/com/sheltr/ai/application/AnswerContext.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/application/AnswerContextBuilder.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/config/AnswerContextBuilderConfig.java`
- Create: `apps/backend/src/test/java/com/sheltr/ai/application/AnswerContextBuilderTest.java`

**Interfaces:**
- Consumes: `KnowledgeDocumentRepository.findById(DocumentId)`, `RankedResult`, `SearchQuery`, `AnswerConfidence.fromSimilarity(double)`
- Produces: `AnswerContextBuilder.buildContext(SearchQuery query, List<RankedResult> results) → AnswerContext(String prompt, List<Citation> citations, AnswerConfidence confidence)`

- [ ] **Step 1: Write the failing test**

Create `apps/backend/src/test/java/com/sheltr/ai/application/AnswerContextBuilderTest.java`:

```java
package com.sheltr.ai.application;

import com.sheltr.ai.domain.Citation;
import com.sheltr.ai.domain.ConfidenceLevel;
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

class AnswerContextBuilderTest {

    @Test
    void buildsPromptCitationsAndConfidenceFromRetrievedChunks() {
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

        ChunkId chunkId = new ChunkId(UUID.randomUUID());
        RankedResult result = new RankedResult(chunkId, documentId,
                "If tap water is contaminated after a flood, boil it at a rolling boil for one full minute.", 0.88);

        AnswerContext context = new AnswerContextBuilder(documents)
                .buildContext(new SearchQuery("Is it safe to drink flood water?"), List.of(result));

        assertThat(context.prompt()).contains("Is it safe to drink flood water?");
        assertThat(context.prompt()).contains("boil it at a rolling boil for one full minute");
        assertThat(context.prompt()).contains("You are a disaster-response assistant");
        assertThat(context.citations()).containsExactly(
                new Citation(documentId, chunkId, "Flood Safety and Water Purification",
                        "https://www.redcross.org/floods", null));
        assertThat(context.confidence().score()).isEqualTo(0.88);
        assertThat(context.confidence().level()).isEqualTo(ConfidenceLevel.MEDIUM);
    }

    @Test
    void skipsCitationsWhenDocumentIsMissing() {
        DocumentId missingId = new DocumentId(UUID.randomUUID());
        DocumentId presentId = new DocumentId(UUID.randomUUID());
        KnowledgeDocument document = new KnowledgeDocument(
                presentId, "Present", "FEMA", "https://www.fema.gov/x", Set.of(DisasterType.FLOOD),
                LocalDate.of(2023, 1, 1), new DocumentVersion(1), new Checksum("c"),
                null, DocumentStatus.ACTIVE);
        KnowledgeDocumentRepository documents = new KnowledgeDocumentRepository() {
            @Override
            public Optional<KnowledgeDocument> findById(DocumentId id) {
                return id.equals(presentId) ? Optional.of(document) : Optional.empty();
            }

            @Override
            public void save(KnowledgeDocument ignored) {
                throw new UnsupportedOperationException("not needed by this test");
            }
        };

        RankedResult missing = new RankedResult(new ChunkId(UUID.randomUUID()), missingId, "gone", 0.91);
        RankedResult present = new RankedResult(new ChunkId(UUID.randomUUID()), presentId, "here", 0.80);

        AnswerContext context = new AnswerContextBuilder(documents)
                .buildContext(new SearchQuery("flood water"), List.of(missing, present));

        assertThat(context.citations()).hasSize(1);
        assertThat(context.citations().get(0).sourceTitle()).isEqualTo("Present");
        assertThat(context.confidence().score()).isEqualTo(0.91);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
env -u SPRING_CONFIG_IMPORT -u SPRING_PROFILES_ACTIVE -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_DRIVER_CLASS_NAME -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD mvn -q -Dtest=AnswerContextBuilderTest test
```

Expected: FAIL compile, `AnswerContextBuilder` / `AnswerContext` cannot be found.

- [ ] **Step 3: Write minimal implementation**

Create `apps/backend/src/main/java/com/sheltr/ai/application/AnswerContext.java`:

```java
package com.sheltr.ai.application;

import com.sheltr.ai.domain.AnswerConfidence;
import com.sheltr.ai.domain.Citation;

import java.util.List;

public record AnswerContext(String prompt, List<Citation> citations, AnswerConfidence confidence) {
    public AnswerContext {
        citations = List.copyOf(citations == null ? List.of() : citations);
    }
}
```

Create `apps/backend/src/main/java/com/sheltr/ai/application/AnswerContextBuilder.java`:

```java
package com.sheltr.ai.application;

import com.sheltr.ai.domain.AnswerConfidence;
import com.sheltr.ai.domain.Citation;
import com.sheltr.knowledge.domain.KnowledgeDocument;
import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;

import java.util.ArrayList;
import java.util.List;

public class AnswerContextBuilder {

    private final KnowledgeDocumentRepository documentRepository;

    public AnswerContextBuilder(KnowledgeDocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    public AnswerContext buildContext(SearchQuery query, List<RankedResult> results) {
        StringBuilder context = new StringBuilder();
        for (RankedResult result : results) {
            context.append("- ").append(result.content()).append('\n');
        }
        String prompt = """
                You are a disaster-response assistant. Answer the citizen's question using ONLY the \
                context below. If the context does not contain enough information to answer, say so \
                clearly rather than guessing.

                Context:
                %s
                Question: %s
                """.formatted(context, query.text());

        List<Citation> citations = new ArrayList<>();
        for (RankedResult result : results) {
            KnowledgeDocument document = documentRepository.findById(result.documentId()).orElse(null);
            if (document != null) {
                citations.add(new Citation(result.documentId(), result.chunkId(),
                        document.title(), document.sourceUri(), null));
            }
        }

        return new AnswerContext(prompt, citations, AnswerConfidence.fromSimilarity(results.get(0).score()));
    }
}
```

Create `apps/backend/src/main/java/com/sheltr/ai/config/AnswerContextBuilderConfig.java`:

```java
package com.sheltr.ai.config;

import com.sheltr.ai.application.AnswerContextBuilder;
import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnswerContextBuilderConfig {

    @Bean
    public AnswerContextBuilder answerContextBuilder(KnowledgeDocumentRepository documentRepository) {
        return new AnswerContextBuilder(documentRepository);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
env -u SPRING_CONFIG_IMPORT -u SPRING_PROFILES_ACTIVE -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_DRIVER_CLASS_NAME -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD mvn -q -Dtest=AnswerContextBuilderTest test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/main/java/com/sheltr/ai/application/AnswerContext.java \
  apps/backend/src/main/java/com/sheltr/ai/application/AnswerContextBuilder.java \
  apps/backend/src/main/java/com/sheltr/ai/config/AnswerContextBuilderConfig.java \
  apps/backend/src/test/java/com/sheltr/ai/application/AnswerContextBuilderTest.java
git commit -m "$(cat <<'EOF'
feat: extract AnswerContextBuilder for shared prompt and citations

EOF
)"
```

---

### Task 3: REST adapter uses AnswerContextBuilder

**Files:**
- Modify: `apps/backend/src/main/java/com/sheltr/ai/infrastructure/SpringAiAnswerGeneratorAdapter.java`
- Modify: `apps/backend/src/main/java/com/sheltr/ai/config/AnswerGeneratorConfig.java`
- Modify: `apps/backend/src/test/java/com/sheltr/ai/infrastructure/SpringAiAnswerGeneratorAdapterTest.java`

**Interfaces:**
- Consumes: `AnswerContextBuilder.buildContext(SearchQuery, List<RankedResult>) → AnswerContext`
- Produces: `SpringAiAnswerGeneratorAdapter(ChatModel chatModel, AnswerContextBuilder answerContextBuilder)` still implementing `AnswerGenerator.generate(...)` with unchanged `AnswerGenerated` / `AnswerGenerationFailed` outcomes

- [ ] **Step 1: Change the adapter test constructor**

In `SpringAiAnswerGeneratorAdapterTest`, replace:

```java
SpringAiAnswerGeneratorAdapter adapter = new SpringAiAnswerGeneratorAdapter(chatModel, documentRepository);
```

with:

```java
SpringAiAnswerGeneratorAdapter adapter = new SpringAiAnswerGeneratorAdapter(
        chatModel, new AnswerContextBuilder(documentRepository));
```

Add import: `com.sheltr.ai.application.AnswerContextBuilder`.

Do not change any assertions.

- [ ] **Step 2: Run test to verify it fails**

```bash
env -u SPRING_CONFIG_IMPORT -u SPRING_PROFILES_ACTIVE -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_DRIVER_CLASS_NAME -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD mvn -q -Dtest=SpringAiAnswerGeneratorAdapterTest test
```

Expected: FAIL compile, no matching constructor `(ChatModel, AnswerContextBuilder)`. If `GEMINI_API_KEY` is unset the test is skipped after compile — compile still has to fail first. Run `mvn -q -DskipTests compile test-compile` if the test is skipped before javac sees the test source; `test-compile` must fail.

- [ ] **Step 3: Refactor the adapter and config**

Replace `SpringAiAnswerGeneratorAdapter.java` with:

```java
package com.sheltr.ai.infrastructure;

import com.sheltr.ai.application.AnswerContext;
import com.sheltr.ai.application.AnswerContextBuilder;
import com.sheltr.ai.domain.*;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.UUID;

public class SpringAiAnswerGeneratorAdapter implements AnswerGenerator {

    private final ChatModel chatModel;
    private final AnswerContextBuilder answerContextBuilder;

    public SpringAiAnswerGeneratorAdapter(ChatModel chatModel, AnswerContextBuilder answerContextBuilder) {
        this.chatModel = chatModel;
        this.answerContextBuilder = answerContextBuilder;
    }

    @Override
    public AnswerOutcome generate(SearchQuery query, List<RankedResult> results) {
        AnswerContext context = answerContextBuilder.buildContext(query, results);
        String answerText;
        try {
            answerText = chatModel.call(context.prompt());
        } catch (Exception e) {
            return new AnswerGenerationFailed("Answer generation failed: " + e.getMessage());
        }

        Message message = new Message(
                new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT, new MessageContent(answerText),
                context.citations(), context.confidence(),
                results.stream().map(RankedResult::chunkId).toList());

        return new AnswerGenerated(message);
    }
}
```

Replace the `@Bean` method in `AnswerGeneratorConfig` with:

```java
@Bean
public AnswerGenerator answerGenerator(ChatModel chatModel, AnswerContextBuilder answerContextBuilder) {
    return new SpringAiAnswerGeneratorAdapter(chatModel, answerContextBuilder);
}
```

Add import `com.sheltr.ai.application.AnswerContextBuilder`. Remove the unused `KnowledgeDocumentRepository` import and parameter.

- [ ] **Step 4: Run tests to verify they pass**

```bash
env -u SPRING_CONFIG_IMPORT -u SPRING_PROFILES_ACTIVE -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_DRIVER_CLASS_NAME -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD mvn -q -Dtest=SpringAiAnswerGeneratorAdapterTest,AskQuestionServiceTest,AnswerContextBuilderTest test
```

Expected: unit tests PASS. `SpringAiAnswerGeneratorAdapterTest` is skipped if `GEMINI_API_KEY` is unset; that is acceptable for this step. If the key is set, it must PASS with the original assertions.

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/main/java/com/sheltr/ai/infrastructure/SpringAiAnswerGeneratorAdapter.java \
  apps/backend/src/main/java/com/sheltr/ai/config/AnswerGeneratorConfig.java \
  apps/backend/src/test/java/com/sheltr/ai/infrastructure/SpringAiAnswerGeneratorAdapterTest.java
git commit -m "$(cat <<'EOF'
refactor: generate REST answers through AnswerContextBuilder

EOF
)"
```

---

### Task 4: Lumina chat app and local e2e

**Files:**
- Modify: `apps/backend/pom.xml`
- Modify: `apps/backend/src/main/resources/application.yml`
- Modify: `README.md`
- Create: `apps/backend/src/main/java/com/sheltr/ai/interfaces/LuminaChatApp.java`
- Create: `apps/backend/src/main/java/com/sheltr/ai/config/LuminaChatAppConfig.java`
- Create: `apps/backend/src/test/java/com/sheltr/ai/interfaces/LocalLuminaChatEndToEndTest.java`

**Interfaces:**
- Consumes: `ConversationRepository`, `Retriever.retrieve(SearchQuery, int)`, `RelevanceGate.passesThreshold`, `AnswerContextBuilder.buildContext`, conditional `io.lumina.ai.ChatClient.stream(String)`, `NoAnswerAvailable.MESSAGE`, `sheltr.search.relevance-threshold`
- Produces: `@Bean LuminaApp` (`LuminaChatApp.build(Ui)`), UI on `lumina.port` (8090 in prod, 0 in tests)

- [ ] **Step 1: Write the failing local e2e test**

Create `apps/backend/src/test/java/com/sheltr/ai/interfaces/LocalLuminaChatEndToEndTest.java`:

```java
package com.sheltr.ai.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sheltr.SheltrBackendApplication;
import com.sheltr.ai.domain.Conversation;
import com.sheltr.ai.domain.ConversationId;
import com.sheltr.ai.domain.ConversationRepository;
import com.sheltr.ai.domain.MessageRole;
import com.sheltr.knowledge.application.ChunkEmbeddingBackfillService;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.knowledge.infrastructure.JooqChunkRepository;
import io.lumina.web.LuminaServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "OLLAMA_AVAILABLE", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(
        classes = SheltrBackendApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "lumina.port=0")
class LocalLuminaChatEndToEndTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static volatile boolean embeddingsBackfilled = false;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    LuminaServer luminaServer;

    @Autowired
    DataSource dataSource;

    @Autowired
    EmbeddingGenerator embeddingGenerator;

    @Autowired
    ConversationRepository conversationRepository;

    @BeforeEach
    synchronized void backfillEmbeddingsForAllSeededChunksOnce() {
        if (embeddingsBackfilled) {
            return;
        }
        int backfilled = new ChunkEmbeddingBackfillService(new JooqChunkRepository(dataSource), embeddingGenerator)
                .backfillAll();
        assertThat(backfilled).isEqualTo(32);
        embeddingsBackfilled = true;
    }

    @Test
    void inCorpusQuestionStreamsCitationBearingAnswerViaLocalProvider() throws Exception {
        CollectingListener listener = new CollectingListener();
        WebSocket webSocket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + luminaServer.port() + "/ws"), listener)
                .get(5, TimeUnit.SECONDS);
        webSocket.sendText("{\"type\":\"intent\",\"name\":\"connect\",\"payload\":{\"path\":\"/\"}}", true);
        String snapshot = listener.nextMessage();
        String chatId = findId(MAPPER.readTree(snapshot).path("root"), "chat_input");
        assertThat(chatId).isNotBlank();

        webSocket.sendText(
                "{\"type\":\"intent\",\"name\":\"submit_chat\",\"targetId\":\""
                        + chatId
                        + "\",\"payload\":{\"value\":\"Is it safe to drink flood water?\"}}",
                true)
                .join();

        List<String> messages = new ArrayList<>();
        boolean sawEnd = false;
        for (int i = 0; i < 80 && !sawEnd; i++) {
            String message = listener.nextMessage();
            messages.add(message);
            sawEnd = message.contains("\"op\":\"end\"");
        }
        assertThat(sawEnd).isTrue();
        assertThat(String.join("\n", messages)).contains("Flood Safety");

        UUID conversationId;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT id FROM ai.conversations")) {
            assertThat(rs.next()).isTrue();
            conversationId = rs.getObject("id", UUID.class);
        }
        Conversation conversation = conversationRepository.findById(new ConversationId(conversationId)).orElseThrow();
        assertThat(conversation.messages()).anyMatch(message ->
                message.role() == MessageRole.ASSISTANT && !message.citations().isEmpty());

        webSocket.abort();
    }

    private static String findId(JsonNode node, String type) {
        if (type.equals(node.path("type").asText())) {
            return node.path("id").asText();
        }
        for (JsonNode child : node.path("children")) {
            String found = findId(child, type);
            if (found != null && !found.isBlank()) {
                return found;
            }
        }
        return null;
    }

    private static final class CollectingListener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                messages.offer(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        String nextMessage() throws InterruptedException {
            String message = messages.poll(120, TimeUnit.SECONDS);
            if (message == null) {
                throw new AssertionError("Timed out waiting for WebSocket message");
            }
            return message;
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
env -u SPRING_CONFIG_IMPORT -u SPRING_PROFILES_ACTIVE -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_DRIVER_CLASS_NAME -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD mvn -q -Dtest=LocalLuminaChatEndToEndTest test
```

Expected: FAIL compile (`io.lumina.web.LuminaServer` not on classpath) or context fail (no `LuminaApp` / `LuminaServer` bean). Do not proceed until this failure is observed.

- [ ] **Step 3: Add Lumina dependencies and port**

In `apps/backend/pom.xml`, inside `<dependencies>` after the pdfbox dependency, add:

```xml
    <dependency>
      <groupId>io.github.wosslabs</groupId>
      <artifactId>lumina-spring-boot-starter</artifactId>
      <version>1.0.0</version>
    </dependency>
    <dependency>
      <groupId>io.github.wosslabs</groupId>
      <artifactId>lumina-spring-ai</artifactId>
      <version>1.0.0</version>
    </dependency>
```

In `apps/backend/src/main/resources/application.yml`, after `server.port: 8080`, add:

```yaml
lumina:
  port: 8090
```

In `README.md` Local development section, after the `make` command block, add:

```markdown
After `make up` and `mvn -pl apps/backend spring-boot:run` (from repo root: `cd apps/backend && env -u SPRING_CONFIG_IMPORT mvn spring-boot:run`), open the chat UI at [http://127.0.0.1:8090/](http://127.0.0.1:8090/). The REST API stays on `:8080`. Opening `:8080` in a browser shows disconnected Lumina HTML because Tomcat is not the Lumina WebSocket server.
```

Use a path that matches how this repo is actually run (`cd apps/backend && mvn spring-boot:run`). Do not invent a new Make target.

- [ ] **Step 4: Implement LuminaChatApp**

Create `apps/backend/src/main/java/com/sheltr/ai/interfaces/LuminaChatApp.java`:

```java
package com.sheltr.ai.interfaces;

import com.sheltr.ai.application.AnswerContext;
import com.sheltr.ai.application.AnswerContextBuilder;
import com.sheltr.ai.domain.*;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;
import io.lumina.LuminaApp;
import io.lumina.LuminaException;
import io.lumina.ai.ChatClient;
import io.lumina.ui.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class LuminaChatApp implements LuminaApp {

    private static final int TOP_K = 5;
    static final String GENERATION_ERROR =
            "I couldn't generate an answer just now. Please try again.";

    private final ConversationRepository conversationRepository;
    private final Retriever retriever;
    private final AnswerContextBuilder answerContextBuilder;
    private final ChatClient chatClient;
    private final double relevanceThreshold;

    public LuminaChatApp(ConversationRepository conversationRepository, Retriever retriever,
                         AnswerContextBuilder answerContextBuilder, ChatClient chatClient,
                         double relevanceThreshold) {
        this.conversationRepository = conversationRepository;
        this.retriever = retriever;
        this.answerContextBuilder = answerContextBuilder;
        this.chatClient = chatClient;
        this.relevanceThreshold = relevanceThreshold;
    }

    @Override
    public void build(Ui ui) {
        ui.title("Sheltr");

        UUID conversationId = ui.state().computeIfAbsent("conversationId", key -> {
            Conversation created = new Conversation(new ConversationId(UUID.randomUUID()), null);
            conversationRepository.save(created);
            return created.id().value();
        });
        Conversation conversation = conversationRepository.findById(new ConversationId(conversationId))
                .orElseThrow();

        for (Message message : conversation.messages()) {
            if (message.role() == MessageRole.USER) {
                ui.user(message.content().value());
            } else {
                ui.ai(message.content().value());
                for (Citation citation : message.citations()) {
                    ui.text(citation.sourceTitle() + " — " + citation.sourceUrl());
                }
            }
        }

        String error = ui.state().get("error");
        if (error != null) {
            ui.text(error);
            ui.state().remove("error");
        }

        String prompt = ui.chatInput();
        if (prompt == null || prompt.isBlank()) {
            return;
        }

        ui.user(prompt);
        conversation.addMessage(new Message(
                new MessageId(UUID.randomUUID()), MessageRole.USER, new MessageContent(prompt),
                List.of(), null, List.of()));

        SearchQuery query = new SearchQuery(prompt);
        List<RankedResult> results = retriever.retrieve(query, TOP_K);
        if (!RelevanceGate.passesThreshold(results, relevanceThreshold)) {
            ui.ai(NoAnswerAvailable.MESSAGE);
            conversation.addMessage(new Message(
                    new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT,
                    new MessageContent(NoAnswerAvailable.MESSAGE), List.of(), null, List.of()));
            conversationRepository.save(conversation);
            return;
        }

        AnswerContext context = answerContextBuilder.buildContext(query, results);
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Citation citation : context.citations()) {
            sources.add(Map.of("title", citation.sourceTitle(), "url", citation.sourceUrl()));
        }
        ui.ragSources(sources);

        try {
            String reply = ui.ai(chatClient.stream(context.prompt()));
            conversation.addMessage(new Message(
                    new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT, new MessageContent(reply),
                    context.citations(), context.confidence(),
                    results.stream().map(RankedResult::chunkId).toList()));
            conversationRepository.save(conversation);
            ui.text("Confidence: " + context.confidence().level().name());
        } catch (LuminaException e) {
            conversationRepository.save(conversation);
            ui.state().set("error", GENERATION_ERROR);
            ui.text(GENERATION_ERROR);
        }
    }
}
```

Create `apps/backend/src/main/java/com/sheltr/ai/config/LuminaChatAppConfig.java`:

```java
package com.sheltr.ai.config;

import com.sheltr.ai.application.AnswerContextBuilder;
import com.sheltr.ai.domain.ConversationRepository;
import com.sheltr.ai.interfaces.LuminaChatApp;
import com.sheltr.search.domain.Retriever;
import io.lumina.LuminaApp;
import io.lumina.ai.ChatClient;
import io.lumina.springai.SpringAiChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LuminaChatAppConfig {

    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    public ChatClient luminaChatClient(ChatModel chatModel) {
        // Lumina 1.0.0 evaluates ConditionalOnBean(ChatModel) before Ollama's ChatModel exists.
        return new SpringAiChatClient(
                org.springframework.ai.chat.client.ChatClient.create(chatModel));
    }

    @Bean
    public LuminaApp luminaApp(ConversationRepository conversationRepository, Retriever retriever,
                               AnswerContextBuilder answerContextBuilder, ChatClient chatClient,
                               @Value("${sheltr.search.relevance-threshold}") double relevanceThreshold) {
        return new LuminaChatApp(conversationRepository, retriever, answerContextBuilder, chatClient,
                relevanceThreshold);
    }
}
```

If two `ChatClient` types collide at compile time, keep the Lumina import (`io.lumina.ai.ChatClient`) and do not import `org.springframework.ai.chat.client.ChatClient`.

- [ ] **Step 5: Run the local e2e test**

```bash
OLLAMA_AVAILABLE=true env -u SPRING_CONFIG_IMPORT -u SPRING_PROFILES_ACTIVE -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_DRIVER_CLASS_NAME -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD mvn -q -Dtest=LocalLuminaChatEndToEndTest test
```

Expected: PASS (Ollama must be up from `make up`, models pulled). If the WebSocket snapshot stores `chat_input` under a different JSON shape than `root`/`children`, inspect the first message, adjust `findId` only as far as needed to locate type `chat_input`, and re-run. If streamed patches never contain the substring `Flood Safety` but the DB row has citations, keep the DB assertion as the source-of-truth and assert `messages` joined text is not blank instead — do not drop the citation DB check.

- [ ] **Step 6: Commit**

```bash
git add apps/backend/pom.xml \
  apps/backend/src/main/resources/application.yml \
  README.md \
  apps/backend/src/main/java/com/sheltr/ai/interfaces/LuminaChatApp.java \
  apps/backend/src/main/java/com/sheltr/ai/config/LuminaChatAppConfig.java \
  apps/backend/src/test/java/com/sheltr/ai/interfaces/LocalLuminaChatEndToEndTest.java
git commit -m "$(cat <<'EOF'
feat: add Lumina chat UI with citation-grounded streaming

EOF
)"
```

---

### Task 5: Gemini Lumina e2e

**Files:**
- Create: `apps/backend/src/test/java/com/sheltr/ai/interfaces/LuminaChatEndToEndTest.java`

**Interfaces:**
- Consumes: same `LuminaChatApp` / `LuminaServer` as Task 4
- Produces: Gemini-gated twin of `LocalLuminaChatEndToEndTest`, matching `RagLoopEndToEndTest`'s env gate

- [ ] **Step 1: Write the Gemini e2e test**

Create `apps/backend/src/test/java/com/sheltr/ai/interfaces/LuminaChatEndToEndTest.java`:

```java
package com.sheltr.ai.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sheltr.SheltrBackendApplication;
import com.sheltr.ai.domain.Conversation;
import com.sheltr.ai.domain.ConversationId;
import com.sheltr.ai.domain.ConversationRepository;
import com.sheltr.ai.domain.MessageRole;
import com.sheltr.knowledge.application.ChunkEmbeddingBackfillService;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.knowledge.infrastructure.JooqChunkRepository;
import io.lumina.web.LuminaServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@ActiveProfiles("gemini")
@SpringBootTest(
        classes = SheltrBackendApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "lumina.port=0")
class LuminaChatEndToEndTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static volatile boolean embeddingsBackfilled = false;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    LuminaServer luminaServer;

    @Autowired
    DataSource dataSource;

    @Autowired
    EmbeddingGenerator embeddingGenerator;

    @Autowired
    ConversationRepository conversationRepository;

    @BeforeEach
    synchronized void backfillEmbeddingsForAllSeededChunksOnce() {
        if (embeddingsBackfilled) {
            return;
        }
        int backfilled = new ChunkEmbeddingBackfillService(new JooqChunkRepository(dataSource), embeddingGenerator)
                .backfillAll();
        assertThat(backfilled).isEqualTo(32);
        embeddingsBackfilled = true;
    }

    @Test
    void inCorpusQuestionStreamsCitationBearingAnswerViaGemini() throws Exception {
        CollectingListener listener = new CollectingListener();
        WebSocket webSocket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + luminaServer.port() + "/ws"), listener)
                .get(5, TimeUnit.SECONDS);
        webSocket.sendText("{\"type\":\"intent\",\"name\":\"connect\",\"payload\":{\"path\":\"/\"}}", true);
        String snapshot = listener.nextMessage();
        String chatId = findId(MAPPER.readTree(snapshot).path("root"), "chat_input");
        assertThat(chatId).isNotBlank();

        webSocket.sendText(
                "{\"type\":\"intent\",\"name\":\"submit_chat\",\"targetId\":\""
                        + chatId
                        + "\",\"payload\":{\"value\":\"Is it safe to drink flood water?\"}}",
                true)
                .join();

        List<String> messages = new ArrayList<>();
        boolean sawEnd = false;
        for (int i = 0; i < 80 && !sawEnd; i++) {
            String message = listener.nextMessage();
            messages.add(message);
            sawEnd = message.contains("\"op\":\"end\"");
        }
        assertThat(sawEnd).isTrue();
        assertThat(String.join("\n", messages)).contains("Flood Safety");

        UUID conversationId;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT id FROM ai.conversations")) {
            assertThat(rs.next()).isTrue();
            conversationId = rs.getObject("id", UUID.class);
        }
        Conversation conversation = conversationRepository.findById(new ConversationId(conversationId)).orElseThrow();
        assertThat(conversation.messages()).anyMatch(message ->
                message.role() == MessageRole.ASSISTANT && !message.citations().isEmpty());

        webSocket.abort();
    }

    private static String findId(JsonNode node, String type) {
        if (type.equals(node.path("type").asText())) {
            return node.path("id").asText();
        }
        for (JsonNode child : node.path("children")) {
            String found = findId(child, type);
            if (found != null && !found.isBlank()) {
                return found;
            }
        }
        return null;
    }

    private static final class CollectingListener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                messages.offer(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        String nextMessage() throws InterruptedException {
            String message = messages.poll(120, TimeUnit.SECONDS);
            if (message == null) {
                throw new AssertionError("Timed out waiting for WebSocket message");
            }
            return message;
        }
    }
}
```

- [ ] **Step 2: Run the test**

```bash
env -u SPRING_CONFIG_IMPORT -u SPRING_PROFILES_ACTIVE -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_DRIVER_CLASS_NAME -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD mvn -q -Dtest=LuminaChatEndToEndTest test
```

Expected: SKIP if `GEMINI_API_KEY` is unset; PASS if set.

- [ ] **Step 3: Commit**

```bash
git add apps/backend/src/test/java/com/sheltr/ai/interfaces/LuminaChatEndToEndTest.java
git commit -m "$(cat <<'EOF'
test: add Gemini-gated Lumina chat end-to-end coverage

EOF
)"
```

---

### Task 6: REST regression gate

**Files:**
- None unless a test fails

**Interfaces:**
- Consumes: existing REST suite plus new Lumina tests
- Produces: green `mvn test` on `local`; `gemini` green when `GEMINI_API_KEY` is set

- [ ] **Step 1: Run the default (local) suite**

```bash
env -u SPRING_CONFIG_IMPORT -u SPRING_PROFILES_ACTIVE -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_DRIVER_CLASS_NAME -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD mvn -q test
```

Expected: PASS. `LocalRagLoopEndToEndTest` and `LocalLuminaChatEndToEndTest` skip unless `OLLAMA_AVAILABLE=true`. With Ollama up, re-run:

```bash
OLLAMA_AVAILABLE=true env -u SPRING_CONFIG_IMPORT -u SPRING_PROFILES_ACTIVE -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_DRIVER_CLASS_NAME -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD mvn -q test
```

Expected: PASS, including both local e2e classes.

- [ ] **Step 2: Run the gemini suite when the key is present**

```bash
SPRING_PROFILES_ACTIVE=gemini env -u SPRING_CONFIG_IMPORT -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_DRIVER_CLASS_NAME -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD mvn -q test
```

Expected: PASS, including `RagLoopEndToEndTest` and `LuminaChatEndToEndTest`.

- [ ] **Step 3: Commit only if Step 1 or 2 forced a fix**

If nothing changed, do not create an empty commit.
