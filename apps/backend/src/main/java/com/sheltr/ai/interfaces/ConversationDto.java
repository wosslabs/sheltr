package com.sheltr.ai.interfaces;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationDto(UUID id, Instant createdAt, Instant lastActiveAt, List<MessageDto> messages) {
}
