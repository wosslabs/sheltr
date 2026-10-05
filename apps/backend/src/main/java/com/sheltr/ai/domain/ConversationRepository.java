package com.sheltr.ai.domain;

import com.sheltr.knowledge.domain.DocumentId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ConversationRepository {
    Optional<Conversation> findById(ConversationId id);
    void save(Conversation conversation);
    List<Citation> findCitationsByDocumentId(DocumentId documentId);

    /** Number of USER messages created at or after {@code since}, counted by the database. */
    long countUserMessagesSince(Instant since);
}
