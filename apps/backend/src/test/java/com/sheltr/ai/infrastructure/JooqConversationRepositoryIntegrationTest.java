package com.sheltr.ai.infrastructure;

import com.sheltr.ai.domain.*;
import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JooqConversationRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void savesAndReloadsConversationWithCitationsWithoutDuplicating() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        // Seed data already provides a real chunk/document pair to satisfy the FK.
        ChunkId chunkId = new ChunkId(UUID.fromString("b0000000-0000-0000-0000-000000000006"));
        DocumentId documentId = new DocumentId(UUID.fromString("a0000000-0000-0000-0000-000000000002"));

        JooqConversationRepository repository = new JooqConversationRepository(dataSource);

        Conversation conversation = new Conversation(new ConversationId(UUID.randomUUID()), null);
        Message userMessage = new Message(new MessageId(UUID.randomUUID()), MessageRole.USER,
                new MessageContent("Is flood water safe to drink?"), List.of(), null, List.of());
        conversation.addMessage(userMessage);
        Message assistantMessage = new Message(new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT,
                new MessageContent("Boil it first."),
                List.of(new Citation(documentId, chunkId, "Flood Safety", "https://example.org", null)),
                AnswerConfidence.fromSimilarity(0.88), List.of(chunkId));
        conversation.addMessage(assistantMessage);

        repository.save(conversation);
        repository.save(conversation); // idempotency check — must not duplicate citations

        Optional<Conversation> reloaded = repository.findById(conversation.id());

        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().messages()).hasSize(2);
        Message reloadedAssistantMessage = reloaded.get().messages().get(1);
        assertThat(reloadedAssistantMessage.citations()).hasSize(1);
        assertThat(reloadedAssistantMessage.citations().get(0).documentId()).isEqualTo(documentId);
        assertThat(reloadedAssistantMessage.confidence().level()).isEqualTo(ConfidenceLevel.MEDIUM);
    }

    /**
     * Documents the accepted behavior after wrapping save()'s per-message insert + citation
     * inserts in a transaction (see JooqConversationRepository.save()): a message row and its
     * citations now only ever commit together. This test simulates the *pre-fix* crash scenario
     * directly — a message row that exists in ai.messages with no matching citation rows,
     * as if the process had died mid-transaction before this fix — and asserts that calling
     * save() again with that same message id does NOT retroactively insert the citations,
     * because the message INSERT reports 0 rows affected (ON CONFLICT DO NOTHING) and the
     * citation inserts are gated on that count. With the transaction wrap in place, this
     * "orphaned message, no citations" state can no longer arise from save() itself; this test
     * only confirms save() does not attempt (and is not expected) to repair pre-existing gaps.
     */
    @Test
    void doesNotRetroactivelyInsertCitationsForAMessageThatAlreadyExistsWithoutThem() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        ChunkId chunkId = new ChunkId(UUID.fromString("b0000000-0000-0000-0000-000000000006"));
        DocumentId documentId = new DocumentId(UUID.fromString("a0000000-0000-0000-0000-000000000002"));

        JooqConversationRepository repository = new JooqConversationRepository(dataSource);

        ConversationId conversationId = new ConversationId(UUID.randomUUID());
        MessageId messageId = new MessageId(UUID.randomUUID());

        // Seed the conversation row and a "half-written" message row directly, bypassing
        // save(), to simulate the crash-before-citations-committed scenario the fix targets.
        DSLContext rawDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
        rawDsl.execute(
                "INSERT INTO ai.conversations (id, user_ref, created_at, last_active_at) VALUES (?, ?, ?, ?)",
                conversationId.value(), (Object) null, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        rawDsl.execute(
                "INSERT INTO ai.messages (id, conversation_id, role, content, confidence_score, "
                        + "confidence_level, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                messageId.value(), conversationId.value(), MessageRole.ASSISTANT.name(),
                "Boil it first.", 0.88, ConfidenceLevel.MEDIUM.name(), Timestamp.from(Instant.now()));

        Conversation conversation = new Conversation(conversationId, null);
        Message assistantMessage = new Message(messageId, MessageRole.ASSISTANT,
                new MessageContent("Boil it first."),
                List.of(new Citation(documentId, chunkId, "Flood Safety", "https://example.org", null)),
                AnswerConfidence.fromSimilarity(0.88), List.of(chunkId));
        conversation.addMessage(assistantMessage);

        repository.save(conversation);

        Optional<Conversation> reloaded = repository.findById(conversationId);

        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().messages()).hasSize(1);
        // Expected/accepted behavior: since the message row already existed, its insert
        // reported 0 rows affected, so the (never-run) citation inserts were skipped.
        assertThat(reloaded.get().messages().get(0).citations()).isEmpty();
    }
}
