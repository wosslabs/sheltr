package com.sheltr.ai.infrastructure;

import com.sheltr.ai.domain.ConversationId;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JooqConversationRepositoryCountIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void countsOnlyUserMessagesCreatedAtOrAfterTheGivenInstant() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        JooqConversationRepository repository = new JooqConversationRepository(dataSource);
        DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);

        ConversationId conversationId = new ConversationId(UUID.randomUUID());
        dsl.execute("INSERT INTO ai.conversations (id, user_ref, created_at, last_active_at) VALUES (?, ?, ?, ?)",
                conversationId.value(), (Object) null, ts("2026-10-01T09:00:00Z"), ts("2026-10-01T09:00:00Z"));

        insertMessage(dsl, conversationId, "USER", "2026-10-03T23:59:59Z");   // yesterday, excluded
        insertMessage(dsl, conversationId, "USER", "2026-10-04T00:00:00Z");   // start of today, included
        insertMessage(dsl, conversationId, "USER", "2026-10-04T08:30:00Z");   // today, included
        insertMessage(dsl, conversationId, "ASSISTANT", "2026-10-04T08:31:00Z"); // assistant, excluded

        assertThat(repository.countUserMessagesSince(Instant.parse("2026-10-04T00:00:00Z"))).isEqualTo(2);
    }

    private static Timestamp ts(String iso) {
        return Timestamp.from(Instant.parse(iso));
    }

    private static void insertMessage(DSLContext dsl, ConversationId conversationId, String role, String createdAt) {
        dsl.execute("INSERT INTO ai.messages (id, conversation_id, role, content, created_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), conversationId.value(), role, "text", ts(createdAt));
    }
}
