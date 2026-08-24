package com.sheltr.ai.interfaces;

import com.sheltr.ai.application.AnswerContext;
import com.sheltr.ai.application.AnswerContextBuilder;
import com.sheltr.ai.domain.*;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;
import io.lumina.LuminaApp;
import io.lumina.LuminaException;
import io.lumina.ai.ChatClient;
import io.lumina.ui.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class LuminaChatApp implements LuminaApp {

    private static final int TOP_K = 5;
    static final String GENERATION_ERROR =
            "I couldn't generate an answer just now. Please try again.";

    private final ConversationRepository conversationRepository;
    private final Retriever retriever;
    private final AnswerContextBuilder answerContextBuilder;
    private final ChatClient chatClient;
    private final double relevanceThreshold;

    public LuminaChatApp(ConversationRepository conversationRepository, Retriever retriever,
                         AnswerContextBuilder answerContextBuilder, ChatClient chatClient,
                         double relevanceThreshold) {
        this.conversationRepository = conversationRepository;
        this.retriever = retriever;
        this.answerContextBuilder = answerContextBuilder;
        this.chatClient = chatClient;
        this.relevanceThreshold = relevanceThreshold;
    }

    @Override
    public void build(Ui ui) {
        ui.title("Sheltr");

        UUID conversationId = ui.state().computeIfAbsent("conversationId", key -> {
            Conversation created = new Conversation(new ConversationId(UUID.randomUUID()), null);
            conversationRepository.save(created);
            return created.id().value();
        });
        Conversation conversation = conversationRepository.findById(new ConversationId(conversationId))
                .orElseThrow();

        for (Message message : conversation.messages()) {
            if (message.role() == MessageRole.USER) {
                ui.user(message.content().value());
            } else {
                ui.ai(message.content().value());
                for (Citation citation : message.citations()) {
                    ui.text(citation.sourceTitle() + " — " + citation.sourceUrl());
                }
            }
        }

        String error = ui.state().get("error");
        if (error != null) {
            ui.text(error);
            ui.state().remove("error");
        }

        String prompt = ui.chatInput();
        if (prompt == null || prompt.isBlank()) {
            return;
        }

        ui.user(prompt);
        conversation.addMessage(new Message(
                new MessageId(UUID.randomUUID()), MessageRole.USER, new MessageContent(prompt),
                List.of(), null, List.of()));

        SearchQuery query = new SearchQuery(prompt);
        List<RankedResult> results = retriever.retrieve(query, TOP_K);
        if (!RelevanceGate.passesThreshold(results, relevanceThreshold)) {
            ui.ai(NoAnswerAvailable.MESSAGE);
            conversation.addMessage(new Message(
                    new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT,
                    new MessageContent(NoAnswerAvailable.MESSAGE), List.of(), null, List.of()));
            conversationRepository.save(conversation);
            return;
        }

        AnswerContext context = answerContextBuilder.buildContext(query, results);
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Citation citation : context.citations()) {
            sources.add(Map.of("title", citation.sourceTitle(), "url", citation.sourceUrl()));
        }
        ui.ragSources(sources);

        try {
            String reply = ui.ai(chatClient.stream(context.prompt()));
            conversation.addMessage(new Message(
                    new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT, new MessageContent(reply),
                    context.citations(), context.confidence(),
                    results.stream().map(RankedResult::chunkId).toList()));
            conversationRepository.save(conversation);
            ui.text("Confidence: " + context.confidence().level().name());
        } catch (LuminaException e) {
            conversationRepository.save(conversation);
            ui.state().set("error", GENERATION_ERROR);
            ui.text(GENERATION_ERROR);
        }
    }
}
