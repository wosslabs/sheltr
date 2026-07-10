package com.sheltr.ai.domain;

import java.util.UUID;

public record MessageId(UUID value) {
    public MessageId {
        if (value == null) throw new IllegalArgumentException("MessageId value must not be null");
    }
}
