package com.sheltr.ai.infrastructure;

import com.sheltr.ai.domain.*;
import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
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
}
