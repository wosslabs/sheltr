package com.sheltr.knowledge.infrastructure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class CorpusFoundationMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void appliesCorpusFoundationChanges() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            assertThat(count(conn, "SELECT count(*) FROM knowledge.documents WHERE status = 'SEED'")).isEqualTo(8);
            assertThat(count(conn, "SELECT count(*) FROM knowledge.documents WHERE status = 'ACTIVE'")).isZero();
            assertThat(count(conn,
                    "SELECT count(*) FROM knowledge.documents WHERE 'HURRICANE' = ANY(disaster_types)")).isZero();
            assertThat(count(conn,
                    "SELECT count(*) FROM knowledge.documents WHERE 'TROPICAL_CYCLONE' = ANY(disaster_types)"))
                    .isEqualTo(1);
            assertThat(count(conn,
                    "SELECT count(*) FROM knowledge.chunks WHERE content_tsv @@ to_tsquery('english', 'boil')"))
                    .isGreaterThanOrEqualTo(1);
            assertThat(count(conn, "SELECT count(*) FROM knowledge.chunks WHERE embedding_model IS NOT NULL"))
                    .isZero();
            assertThat(count(conn, "SELECT count(*) FROM knowledge.documents WHERE license IS NOT NULL")).isZero();
            assertThat(count(conn, "SELECT count(*) FROM knowledge.related_hazards")).isZero();
        }
    }

    private static int count(Connection conn, String sql) throws Exception {
        try (ResultSet rs = conn.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
