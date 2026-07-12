package com.sheltr.ai.domain;

import com.sheltr.knowledge.domain.DocumentId;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository {
    Optional<Conversation> findById(ConversationId id);
    void save(Conversation conversation);
    List<Citation> findCitationsByDocumentId(DocumentId documentId);
}
