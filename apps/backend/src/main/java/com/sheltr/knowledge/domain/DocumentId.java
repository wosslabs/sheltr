package com.sheltr.knowledge.domain;

import java.util.UUID;

public record DocumentId(UUID value) {
    public DocumentId {
        if (value == null) throw new IllegalArgumentException("DocumentId value must not be null");
    }
}
