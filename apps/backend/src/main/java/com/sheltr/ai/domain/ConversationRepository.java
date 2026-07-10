package com.sheltr.ai.domain;

import java.util.Optional;

public interface ConversationRepository {
    Optional<Conversation> findById(ConversationId id);
    void save(Conversation conversation);
}
