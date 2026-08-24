package com.sheltr.ai.interfaces;

import com.sheltr.SheltrBackendApplication;
import com.sheltr.knowledge.application.ChunkEmbeddingBackfillService;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.knowledge.infrastructure.JooqChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
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
 * Local-profile counterpart to {@link RagLoopEndToEndTest}, proving the default (Ollama-backed)
 * provider path works end to end — not just retrieval (already covered by
 * {@code RelevanceThresholdEvalTest} and {@code LocalIngestionToRagEndToEndTest}), but generation
 * too, via a real HTTP call to {@code /ask} that reaches a real {@code ChatModel} backed by Ollama.
 *
 * <p>Before this test, nothing in the suite ever exercised {@code SpringAiAnswerGeneratorAdapter}
 * against real Ollama: since Spring AI's Ollama {@code pull-model-strategy} defaults to
 * {@code never}, a typo'd or unpulled model name would silently 500 every {@code /ask} call under
 * the new default profile without any test catching it.
 *
 * <p>Deliberately does not assert a specific {@link com.sheltr.ai.domain.ConfidenceLevel}
 * (HIGH/MEDIUM/LOW) or exact threshold value — the local profile's embedding-score distribution
 * differs from Gemini's, and {@code AnswerConfidence}'s bands are not yet profile-scoped (see
 * ADR-0011's Consequences section). That's a separate, documented limitation, not something this
 * test should re-litigate; it only asserts that a real answer with real citations comes back.
 *
 * <p>See {@link RagLoopEndToEndTest}'s class Javadoc for the {@code @TestInstance(PER_CLASS)} +
 * {@code @Testcontainers} + {@code @DynamicPropertySource} ordering trap this class also avoids
 * by keeping the default per-method test instance lifecycle.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "OLLAMA_AVAILABLE", matches = "true")
@ActiveProfiles("local")
@AutoConfigureTestRestTemplate
@SpringBootTest(classes = SheltrBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalRagLoopEndToEndTest {

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
        // spring.flyway.enabled=true on application context startup, before this runs.
        // The EmbeddingGenerator used here is the real Spring-managed bean from
        // EmbeddingGeneratorConfig, backed by the actual Ollama embedding model
        // (nomic-embed-text) configured in application-local.yml, not a manually
        // constructed one. Guarded by a static flag so the backfill runs exactly once
        // for the class even though this is a @BeforeEach (required because a static
        // @BeforeAll cannot use instance-injected @Autowired fields).
        int backfilled = new ChunkEmbeddingBackfillService(new JooqChunkRepository(dataSource), embeddingGenerator)
                .backfillAll();
        assertThat(backfilled).isEqualTo(32);
        embeddingsBackfilled = true;
    }

    @Test
    void inCorpusQuestionReturnsCitationBearingAnswerViaLocalProvider() {
        UUID conversationId = createConversation();

        ResponseEntity<MessageDto> response = restTemplate.postForEntity(
                "/api/v1/conversations/" + conversationId + "/ask",
                new AskRequestDto("Is it safe to drink flood water?"),
                MessageDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().role()).isEqualTo("ASSISTANT");
        assertThat(response.getBody().content()).isNotBlank();
        assertThat(response.getBody().citations()).isNotEmpty();
    }

    private UUID createConversation() {
        ResponseEntity<ConversationDto> created =
                restTemplate.postForEntity("/api/v1/conversations", null, ConversationDto.class);
        return created.getBody().id();
    }
}
