package com.sheltr.ai.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationTest {

    @Test
    void addMessageAppendsAndUpdatesLastActiveAt() {
        Conversation conversation = new Conversation(new ConversationId(UUID.randomUUID()), null);
        var before = conversation.lastActiveAt();

        Message message = new Message(
                new MessageId(UUID.randomUUID()), MessageRole.USER,
                new MessageContent("Is it safe to drink flood water?"), List.of(), null, List.of());
        conversation.addMessage(message);

        assertThat(conversation.messages()).containsExactly(message);
        assertThat(conversation.lastActiveAt()).isAfterOrEqualTo(before);
    }
}
