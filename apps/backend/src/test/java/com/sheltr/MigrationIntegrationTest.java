package com.sheltr;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class MigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void migratesAllExpectedTablesAcrossSchemas() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load();
        flyway.migrate();

        Set<String> tables = new HashSet<>();
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             ResultSet rs = conn.createStatement().executeQuery(
                     "SELECT table_schema, table_name FROM information_schema.tables " +
                     "WHERE table_schema IN ('knowledge', 'ingestion', 'ai')")) {
            while (rs.next()) {
                tables.add(rs.getString("table_schema") + "." + rs.getString("table_name"));
            }
        }

        assertThat(tables).containsExactlyInAnyOrder(
                "knowledge.documents",
                "knowledge.chunks",
                "knowledge.related_hazards",
                "ingestion.source_documents",
                "ingestion.ingestion_runs",
                "ai.conversations",
                "ai.messages",
                "ai.citations"
        );
    }
}
