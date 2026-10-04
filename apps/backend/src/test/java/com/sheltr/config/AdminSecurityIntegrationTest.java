package com.sheltr.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sheltr.SheltrBackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * Admin routes need HTTP Basic with role ADMIN; citizen routes and actuator health stay public.
 * The admin password comes from {@code src/test/resources/application.properties}. Request bodies
 * are deliberately invalid so an authenticated call stops at validation (400) and never fetches.
 */
@Testcontainers
@ActiveProfiles("local")
@AutoConfigureTestRestTemplate
@SpringBootTest(classes = SheltrBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminSecurityIntegrationTest {

    static final String ADMIN_USER = "admin";
    static final String ADMIN_PASSWORD = "test-only-password";
    static final String RUNS = "/api/v1/admin/ingestion/runs";

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
    ConfigurableApplicationContext context;

    /** Asserts the body is exactly the {@code ApiError} shape with the given code and message. */
    static void assertApiError(ResponseEntity<String> response, String error, String message) throws Exception {
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        JsonNode body = new ObjectMapper().readTree(response.getBody());
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("error", "message", "timestamp");
        assertThat(body.get("error").asText()).isEqualTo(error);
        assertThat(body.get("message").asText()).isEqualTo(message);
        assertThat(java.time.Instant.parse(body.get("timestamp").asText())).isNotNull();
    }

    static HttpEntity<String> emptyJson() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>("{}", headers);
    }

    @Test
    void adminRouteWithoutCredentialsIsUnauthorized() {
        ResponseEntity<String> response = restTemplate.postForEntity(RUNS, emptyJson(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Basic realm=");
    }

    @Test
    void unauthorizedBodyIsApiError() throws Exception {
        ResponseEntity<String> response = restTemplate.postForEntity(RUNS, emptyJson(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertApiError(response, "UNAUTHORIZED", "Authentication is required.");
    }

    @Test
    void adminGetWithoutCredentialsIsUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity(RUNS + "/" + UUID.randomUUID(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminRouteWithWrongPasswordIsUnauthorized() throws Exception {
        ResponseEntity<String> response = restTemplate.withBasicAuth(ADMIN_USER, "wrong-password")
                .postForEntity(RUNS, emptyJson(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Basic realm=");
        assertApiError(response, "UNAUTHORIZED", "Authentication is required.");
    }

    @Test
    void adminRouteWithAdminCredentialsPassesSecurity() {
        ResponseEntity<String> response = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASSWORD)
                .postForEntity(RUNS, emptyJson(), String.class);

        assertThat(response.getStatusCode()).isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void adminGetWithAdminCredentialsReachesController() {
        ResponseEntity<String> response = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASSWORD)
                .getForEntity(RUNS + "/" + UUID.randomUUID(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void citizenRoutesStayPublicWithoutCsrfToken() {
        ResponseEntity<String> created = restTemplate.postForEntity("/api/v1/conversations", null, String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> missing = restTemplate.exchange(
                "/api/v1/conversations/" + UUID.randomUUID(), HttpMethod.GET, null, String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void actuatorHealthStaysPublic() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void actuatorInfoStaysPublic() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/info", String.class);

        assertThat(response.getStatusCode()).isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void otherActuatorEndpointsAreNotReachableAnonymously() {
        for (String path : new String[] {"/actuator/env", "/actuator/configprops", "/actuator/beans"}) {
            ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);

            assertThat(response.getStatusCode()).as(path).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void noBeanHoldsThePlaintextAdminPassword() {
        assertThat(context.getBeanNamesForType(SecurityProperties.class)).isEmpty();
        assertThat(context.getBeanNamesForType(AdminCredentials.class)).isEmpty();
        assertThat(BeanSecrets.beansExposing(context.getBeanFactory(), ADMIN_PASSWORD)).isEmpty();
    }

    @Test
    void sessionIsNotCreated() {
        ResponseEntity<String> response = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASSWORD)
                .getForEntity(RUNS + "/" + UUID.randomUUID(), String.class);

        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
    }
}
