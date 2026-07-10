package com.sheltr.ai.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class Conversation {
    private final ConversationId id;
    private final String userRef;
    private final List<Message> messages = new ArrayList<>();
    private final Instant createdAt;
    private Instant lastActiveAt;

    public Conversation(ConversationId id, String userRef) {
        this.id = id;
        this.userRef = userRef;
        this.createdAt = Instant.now();
        this.lastActiveAt = this.createdAt;
    }

    public Conversation(ConversationId id, String userRef, List<Message> existingMessages,
                         Instant createdAt, Instant lastActiveAt) {
        this.id = id;
        this.userRef = userRef;
        this.messages.addAll(existingMessages);
        this.createdAt = createdAt;
        this.lastActiveAt = lastActiveAt;
    }

    public void addMessage(Message message) {
        messages.add(message);
        lastActiveAt = Instant.now();
    }

    public ConversationId id() { return id; }
    public Optional<String> userRef() { return Optional.ofNullable(userRef); }
    public List<Message> messages() { return List.copyOf(messages); }
    public Instant createdAt() { return createdAt; }
    public Instant lastActiveAt() { return lastActiveAt; }
}
