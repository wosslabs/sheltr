package com.sheltr.ai.interfaces;

import com.sheltr.SheltrBackendApplication;
import com.sheltr.knowledge.application.ChunkEmbeddingBackfillService;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.knowledge.infrastructure.JooqChunkRepository;
import org.junit.jupiter.api.BeforeEach;
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

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capstone integration test for the Sheltr Phase 2 RAG loop.
 *
 * <p>Backfills real Gemini embeddings for all seeded chunks (via {@link ChunkEmbeddingBackfillService}
 * against a real Testcontainers Postgres), then makes real HTTP calls to prove both halves of
 * ADR-0008: a genuine in-corpus question gets a citation-bearing answer, and a genuine
 * out-of-corpus question gets the "no reliable information" fallback (200, empty citations)
 * rather than an invented answer.
 *
 * <p>The embedding backfill runs from a guarded {@code @BeforeEach} (once per class, via a static
 * flag) rather than a static {@code @BeforeAll}, so it can use the Spring-managed
 * {@link EmbeddingGenerator} bean (from {@code EmbeddingGeneratorConfig}) via plain field
 * injection instead of hand-rolling Gemini plumbing (the class names guessed in early planning
 * docs, e.g. {@code GoogleGenAiEmbeddingModel} constructed directly, are unnecessary and were
 * superseded once the real config was verified). Note: {@code @TestInstance(PER_CLASS)} +
 * {@code @Testcontainers} + {@code @DynamicPropertySource} is a known ordering trap — with
 * PER_CLASS, Spring's context preparation can run before the Testcontainers {@code @BeforeAll}
 * callback starts the container, so we deliberately keep the default per-method test instance
 * lifecycle here.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@ActiveProfiles("gemini")
@SpringBootTest(classes = SheltrBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RagLoopEndToEndTest {

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
    TestRestTemplate restTemplate;

    @Autowired
    DataSource dataSource;

    @Autowired
    EmbeddingGenerator embeddingGenerator;

    @BeforeEach
    synchronized void backfillEmbeddingsForAllSeededChunksOnce() {
        if (embeddingsBackfilled) {
            return;
        }
        // Flyway (including the V5 seed migration) already ran via Spring Boot's
        // spring.flyway.enabled=true on application context startup, before this
        // runs. Backfilling all 32 real embeddings here is slow (network calls) but this
        // is the one test meant to prove the whole stack for real. The EmbeddingGenerator
        // used here is the real Spring-managed bean from EmbeddingGeneratorConfig (backed
        // by the actual Gemini embedding model configured in application-gemini.yml), not a
        // manually constructed one. Guarded by a static flag so the backfill runs exactly
        // once for the class even though this is a @BeforeEach (required because a static
        // @BeforeAll cannot use instance-injected @Autowired fields).
        int backfilled = new ChunkEmbeddingBackfillService(new JooqChunkRepository(dataSource), embeddingGenerator)
                .backfillAll();
        assertThat(backfilled).isEqualTo(32);
        embeddingsBackfilled = true;
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
