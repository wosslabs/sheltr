package com.sheltr.knowledge.interfaces;

import com.sheltr.ai.domain.ConversationRepository;
import com.sheltr.interfaces.ApiError;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.knowledge.domain.KnowledgeDocument;
import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import com.sheltr.ai.interfaces.CitationDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final KnowledgeDocumentRepository documentRepository;
    private final ConversationRepository conversationRepository;

    public DocumentController(KnowledgeDocumentRepository documentRepository, ConversationRepository conversationRepository) {
        this.documentRepository = documentRepository;
        this.conversationRepository = conversationRepository;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable UUID id) {
        Optional<KnowledgeDocument> document = documentRepository.findById(new DocumentId(id));
        if (document.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiError.of("NOT_FOUND", "Document not found."));
        }
        return ResponseEntity.ok(toDto(document.get()));
    }

    @GetMapping("/{id}/citations")
    public ResponseEntity<List<CitationDto>> citations(@PathVariable UUID id) {
        List<CitationDto> citations = conversationRepository.findCitationsByDocumentId(new DocumentId(id)).stream()
                .map(c -> new CitationDto(c.documentId().value(), c.chunkId().value(), c.sourceTitle(), c.sourceUrl(), c.pageNumber()))
                .toList();
        return ResponseEntity.ok(citations);
    }

    private static DocumentMetadataDto toDto(KnowledgeDocument document) {
        List<String> disasterTypes = document.disasterTypes().stream().map(Enum::name).toList();
        return new DocumentMetadataDto(document.id().value(), document.title(), document.sourceAgency(),
                document.sourceUri(), disasterTypes, document.publishedDate(), document.version().value(),
                document.status().name());
    }
}
