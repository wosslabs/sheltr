package com.sheltr.ai.domain;

public record MessageContent(String value) {
    public MessageContent {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MessageContent value must not be blank");
        }
    }
}
