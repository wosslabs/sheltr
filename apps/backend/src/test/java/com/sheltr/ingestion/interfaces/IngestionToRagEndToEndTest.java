package com.sheltr.ingestion.interfaces;

import com.sheltr.SheltrBackendApplication;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@ActiveProfiles("gemini")
@AutoConfigureTestRestTemplate
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
                new TriggerIngestionRequestDto(CAREGIVERS_GUIDE_URL, "PDF", "Disaster Preparedness Guide for Caregivers",
                "FEMA", java.util.List.of("FLOOD"), "2024-02-01", "test-fixture-licence", null),
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
