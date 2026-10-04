package com.sheltr.config;

import com.sheltr.SheltrBackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An authenticated user without role ADMIN gets 403 on admin routes. Production has exactly one
 * (admin) user, so this test replaces the {@link UserDetailsService} bean with one non-admin user.
 */
@Testcontainers
@ActiveProfiles("local")
@AutoConfigureTestRestTemplate
@SpringBootTest(classes = SheltrBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminSecurityForbiddenIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @TestBean(methodName = "viewerOnly")
    UserDetailsService adminUserDetailsService;

    static UserDetailsService viewerOnly() {
        return new InMemoryUserDetailsManager(
                User.withUsername("viewer").password("{noop}viewer-password").roles("USER").build());
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void authenticatedNonAdminIsForbidden() {
        ResponseEntity<String> response = restTemplate.withBasicAuth("viewer", "viewer-password")
                .getForEntity("/api/v1/admin/ingestion/runs/" + UUID.randomUUID(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
