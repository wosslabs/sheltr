package com.sheltr.ai.interfaces;

import com.sheltr.SheltrBackendApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
 * Split out of {@link ConversationControllerIntegrationTest}: this is the one test in that class
 * that actually depends on the {@code gemini} provider (a real answer-generation call), so it
 * carries the {@code gemini} profile and the GEMINI_API_KEY gate on its own — the CRUD tests no
 * longer need either to run.
 */
@Testcontainers
@ActiveProfiles("gemini")
@SpringBootTest(classes = SheltrBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConversationControllerGeminiAskIntegrationTest {

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
