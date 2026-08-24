package com.sheltr.ai.interfaces;

import com.sheltr.SheltrBackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers conversation CRUD only — no AI provider call, so this runs under {@code local} (the
 * default profile) and needs neither a GEMINI_API_KEY nor a reachable Ollama. The provider-specific
 * {@code /ask} behavior is covered separately: {@link ConversationControllerGeminiAskIntegrationTest}
 * (Gemini, gated on GEMINI_API_KEY) and {@link LocalRagLoopEndToEndTest} (local, gated on
 * OLLAMA_AVAILABLE).
 */
@Testcontainers
@ActiveProfiles("local")
@AutoConfigureTestRestTemplate
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
}
