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
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SeedDataMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void seedsChunksAcrossAllEightDisasterTypes() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {

            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT count(*) FROM knowledge.chunks")) {
                rs.next();
                assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(30);
            }

            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT count(*) FROM knowledge.chunks WHERE embedding IS NULL")) {
                rs.next();
                assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(30);
            }

            Set<String> disasterTypesCovered = new HashSet<>();
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT DISTINCT unnest(disaster_types) FROM knowledge.documents")) {
                while (rs.next()) {
                    disasterTypesCovered.add(rs.getString(1));
                }
            }
            assertThat(disasterTypesCovered).containsExactlyInAnyOrder(
                    "EARTHQUAKE", "FLOOD", "HURRICANE", "TORNADO", "TSUNAMI", "WILDFIRE",
                    "EXTREME_HEAT", "WINTER_STORM");
        }
    }
}
