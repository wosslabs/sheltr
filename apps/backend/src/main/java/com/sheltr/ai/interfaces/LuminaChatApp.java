package com.sheltr.ai.interfaces;

import com.sheltr.ai.application.AnswerContext;
import com.sheltr.ai.application.AnswerPipeline;
import com.sheltr.ai.application.AnswerContextBuilder;
import com.sheltr.ai.application.DailyAskCap;
import com.sheltr.ai.domain.*;
import com.sheltr.knowledge.domain.StaleEmbeddingsException;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;
import io.lumina.LuminaApp;
import io.lumina.ai.ChatClient;
import io.lumina.ai.TokenStream;
import io.lumina.ui.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class LuminaChatApp implements LuminaApp {

    static final String GENERATION_ERROR =
            "I couldn't generate an answer just now. Please try again.";

    private final ConversationRepository conversationRepository;
    private final AnswerPipeline pipeline;
    private final AnswerContextBuilder answerContextBuilder;
    private final ChatClient chatClient;
    private final DailyAskCap dailyAskCap;

    public LuminaChatApp(ConversationRepository conversationRepository, AnswerPipeline pipeline,
                         AnswerContextBuilder answerContextBuilder, ChatClient chatClient,
                         DailyAskCap dailyAskCap) {
        this.conversationRepository = conversationRepository;
        this.pipeline = pipeline;
        this.answerContextBuilder = answerContextBuilder;
        this.chatClient = chatClient;
        this.dailyAskCap = dailyAskCap;
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

        // Same daily cap as REST, checked before the question is persisted, retrieved or generated.
        if (!dailyAskCap.allows()) {
            ui.text(DailyAskCap.LIMIT_MESSAGE);
            return;
        }

        ui.user(prompt);
        conversation.addMessage(new Message(
                new MessageId(UUID.randomUUID()), MessageRole.USER, new MessageContent(prompt),
                List.of(), null, List.of()));

        SearchQuery query = new SearchQuery(prompt);
        AnswerPipeline.JudgedPassages judged;
        try {
            judged = pipeline.retrieve(query);
        } catch (StaleEmbeddingsException e) {
            conversationRepository.save(conversation);
            ui.state().set("error", GENERATION_ERROR);
            ui.text(GENERATION_ERROR);
            return;
        }
        List<RankedResult> results = judged.passages();
        if (!judged.verdict().answerable()) {
            ui.ai(NoAnswerAvailable.MESSAGE);
            conversation.addMessage(new Message(
                    new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT,
                    new MessageContent(NoAnswerAvailable.MESSAGE), List.of(), null, List.of()));
            conversationRepository.save(conversation);
            return;
        }

        AnswerContext context = answerContextBuilder.buildContext(query, results, judged.verdict());

        try {
            // Buffer the whole reply before showing it: an uncited answer must be refused, never displayed (ADR-0008).
            StringBuilder buffered = new StringBuilder();
            for (String chunk : chatClient.stream(context.prompt())) {
                buffered.append(chunk);
            }
            String reply = buffered.toString();
            List<Citation> used = CitationSelector.select(reply, results, context.citations());
            // Verified text is shown as one completed stream so the client gets the stream end frame.
            if (used.isEmpty()) {
                ui.ragSources(List.of());
                ui.ai(TokenStream.of(NoAnswerAvailable.MESSAGE));
                conversation.addMessage(new Message(
                        new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT,
                        new MessageContent(NoAnswerAvailable.MESSAGE), List.of(), null, List.of()));
                conversationRepository.save(conversation);
                return;
            }
            // Only the passages the answer cites are shown as sources (spec 5.4).
            ui.ragSources(sourcesOf(used));
            ui.ai(TokenStream.of(reply));
            conversation.addMessage(new Message(
                    new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT, new MessageContent(reply),
                    used, context.confidence(),
                    results.stream().map(RankedResult::chunkId).toList()));
            conversationRepository.save(conversation);
            ui.text("Confidence: " + context.confidence().level().name());
        } catch (Exception e) {
            conversationRepository.save(conversation);
            ui.state().set("error", GENERATION_ERROR);
            ui.text(GENERATION_ERROR);
        }
    }

    private static List<Map<String, Object>> sourcesOf(List<Citation> citations) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Citation citation : citations) {
            sources.add(Map.of("title", citation.sourceTitle(), "url", citation.sourceUrl()));
        }
        return sources;
    }
}
