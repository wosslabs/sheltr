package com.sheltr.ai.infrastructure;

import com.sheltr.ai.domain.*;
import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JooqConversationRepository implements ConversationRepository {

    private final DSLContext dsl;

    public JooqConversationRepository(DataSource dataSource) {
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    @Override
    public Optional<Conversation> findById(ConversationId id) {
        Record conversationRow = dsl.fetchOne(
                "SELECT id, user_ref, created_at, last_active_at FROM ai.conversations WHERE id = ?",
                id.value());
        if (conversationRow == null) {
            return Optional.empty();
        }

        List<Message> messages = new ArrayList<>();
        var messageRows = dsl.fetch(
                "SELECT id, role, content, confidence_score, confidence_level FROM ai.messages "
                        + "WHERE conversation_id = ? ORDER BY created_at ASC",
                id.value());
        for (Record messageRow : messageRows) {
            UUID messageId = (UUID) messageRow.get("id");
            List<Citation> citations = new ArrayList<>();
            var citationRows = dsl.fetch(
                    "SELECT c.chunk_id, ch.document_id, c.source_title, c.source_url, c.page_number "
                            + "FROM ai.citations c JOIN knowledge.chunks ch ON ch.id = c.chunk_id "
                            + "WHERE c.message_id = ?",
                    messageId);
            for (Record citationRow : citationRows) {
                citations.add(new Citation(
                        new DocumentId((UUID) citationRow.get("document_id")),
                        new ChunkId((UUID) citationRow.get("chunk_id")),
                        citationRow.get("source_title", String.class),
                        citationRow.get("source_url", String.class),
                        citationRow.get("page_number", Integer.class)));
            }
            Double confidenceScore = messageRow.get("confidence_score", Double.class);
            String confidenceLevel = messageRow.get("confidence_level", String.class);
            AnswerConfidence confidence = confidenceScore == null ? null
                    : new AnswerConfidence(confidenceScore, ConfidenceLevel.valueOf(confidenceLevel));
            messages.add(new Message(
                    new MessageId(messageId),
                    MessageRole.valueOf(messageRow.get("role", String.class)),
                    new MessageContent(messageRow.get("content", String.class)),
                    citations, confidence, List.of()));
        }

        String userRef = conversationRow.get("user_ref", String.class);
        Instant createdAt = conversationRow.get("created_at", Timestamp.class).toInstant();
        Instant lastActiveAt = conversationRow.get("last_active_at", Timestamp.class).toInstant();
        return Optional.of(new Conversation(
                new ConversationId((UUID) conversationRow.get("id")), userRef, messages, createdAt, lastActiveAt));
    }

    @Override
    public void save(Conversation conversation) {
        dsl.execute(
                "INSERT INTO ai.conversations (id, user_ref, created_at, last_active_at) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET last_active_at = EXCLUDED.last_active_at",
                conversation.id().value(), conversation.userRef().orElse(null),
                Timestamp.from(conversation.createdAt()), Timestamp.from(conversation.lastActiveAt()));

        for (Message message : conversation.messages()) {
            // Each message's INSERT and its citations' INSERTs must commit atomically together:
            // if a crash happens between the message insert and its citation inserts, the
            // ON CONFLICT (id) DO NOTHING on retry would otherwise report 0 rows inserted and
            // silently skip citations forever. Wrapping both in one jOOQ transaction ensures
            // either both commit or both roll back, preserving the idempotency invariant.
            dsl.transaction(configuration -> {
                DSLContext txDsl = DSL.using(configuration);

                Double confidenceScore = message.confidence() == null ? null : message.confidence().score();
                String confidenceLevel = message.confidence() == null ? null : message.confidence().level().name();
                int inserted = txDsl.execute(
                        "INSERT INTO ai.messages (id, conversation_id, role, content, confidence_score, "
                                + "confidence_level, created_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                        message.id().value(), conversation.id().value(), message.role().name(),
                        message.content().value(), confidenceScore, confidenceLevel, Timestamp.from(Instant.now()));

                if (inserted > 0) {
                    for (Citation citation : message.citations()) {
                        txDsl.execute(
                                "INSERT INTO ai.citations (id, message_id, chunk_id, source_title, source_url, page_number) "
                                        + "VALUES (?, ?, ?, ?, ?, ?)",
                                UUID.randomUUID(), message.id().value(), citation.chunkId().value(),
                                citation.sourceTitle(), citation.sourceUrl(), citation.pageNumber());
                    }
                }
            });
        }
    }

    @Override
    public long countUserMessagesSince(Instant since) {
        Long count = dsl.fetchOne(
                "SELECT count(*) FROM ai.messages WHERE role = ? AND created_at >= ?",
                MessageRole.USER.name(), Timestamp.from(since)).into(Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public List<Citation> findCitationsByDocumentId(DocumentId documentId) {
        var rows = dsl.fetch(
                "SELECT c.chunk_id, ch.document_id, c.source_title, c.source_url, c.page_number "
                        + "FROM ai.citations c JOIN knowledge.chunks ch ON ch.id = c.chunk_id "
                        + "WHERE ch.document_id = ?",
                documentId.value());
        List<Citation> citations = new ArrayList<>();
        for (Record row : rows) {
            citations.add(new Citation(
                    new DocumentId((UUID) row.get("document_id")),
                    new ChunkId((UUID) row.get("chunk_id")),
                    row.get("source_title", String.class),
                    row.get("source_url", String.class),
                    row.get("page_number", Integer.class)));
        }
        return citations;
    }
}
