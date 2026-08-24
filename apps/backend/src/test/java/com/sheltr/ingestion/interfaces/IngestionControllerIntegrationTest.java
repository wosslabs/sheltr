package com.sheltr.ingestion.interfaces;

import com.sheltr.SheltrBackendApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@ActiveProfiles("gemini")
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
