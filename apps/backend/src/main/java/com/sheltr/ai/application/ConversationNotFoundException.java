package com.sheltr.ai.application;

import com.sheltr.ai.domain.ConversationId;

public class ConversationNotFoundException extends RuntimeException {
    public ConversationNotFoundException(ConversationId id) {
        super("Conversation not found: " + id.value());
    }
}
