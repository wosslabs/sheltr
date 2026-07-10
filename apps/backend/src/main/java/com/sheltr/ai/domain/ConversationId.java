package com.sheltr.ai.domain;

import java.util.UUID;

public record ConversationId(UUID value) {
    public ConversationId {
        if (value == null) throw new IllegalArgumentException("ConversationId value must not be null");
    }
}
