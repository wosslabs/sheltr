package com.sheltr.ai.interfaces;

import com.sheltr.ai.application.AskQuestionService;
import com.sheltr.ai.domain.*;
import com.sheltr.knowledge.domain.DisasterType;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationRepository conversationRepository;
    private final AskQuestionService askQuestionService;

    public ConversationController(ConversationRepository conversationRepository, AskQuestionService askQuestionService) {
        this.conversationRepository = conversationRepository;
        this.askQuestionService = askQuestionService;
    }

    @PostMapping
    public ResponseEntity<ConversationDto> create() {
        Conversation conversation = new Conversation(new ConversationId(UUID.randomUUID()), null);
        conversationRepository.save(conversation);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(conversation));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConversationDto> get(@PathVariable UUID id) {
        return conversationRepository.findById(new ConversationId(id))
                .map(conversation -> ResponseEntity.ok(toDto(conversation)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/ask")
    public ResponseEntity<MessageDto> ask(@PathVariable UUID id, @Valid @RequestBody AskRequestDto request) {
        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Set<DisasterType> activeHazards;
        try {
            activeHazards = request.activeHazards() == null ? Set.of()
                    : request.activeHazards().stream().map(DisasterType::valueOf).collect(Collectors.toSet());
        } catch (IllegalArgumentException | NullPointerException e) {
            return ResponseEntity.badRequest().build();
        }
        Message message = askQuestionService.ask(new ConversationId(id), request.question(), activeHazards);
        return ResponseEntity.ok(toDto(message));
    }

    private static ConversationDto toDto(Conversation conversation) {
        List<MessageDto> messages = conversation.messages().stream().map(ConversationController::toDto).toList();
        return new ConversationDto(conversation.id().value(), conversation.createdAt(), conversation.lastActiveAt(), messages);
    }

    private static MessageDto toDto(Message message) {
        List<CitationDto> citations = message.citations().stream()
                .map(c -> new CitationDto(c.documentId().value(), c.chunkId().value(), c.sourceTitle(), c.sourceUrl(), c.pageNumber()))
                .toList();
        Double confidenceScore = message.confidence() == null ? null : message.confidence().score();
        String confidenceLevel = message.confidence() == null ? null : message.confidence().level().name();
        return new MessageDto(message.id().value(), message.role().name(), message.content().value(),
                confidenceScore, confidenceLevel, citations);
    }
}
