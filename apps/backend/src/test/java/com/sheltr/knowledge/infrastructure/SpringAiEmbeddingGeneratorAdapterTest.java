package com.sheltr.knowledge.infrastructure;

import com.sheltr.knowledge.domain.Embedding;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class SpringAiEmbeddingGeneratorAdapterTest {

    @Test
    void embedsRealTextViaGemini() {
        GoogleGenAiEmbeddingConnectionDetails connectionDetails = GoogleGenAiEmbeddingConnectionDetails.builder()
                .apiKey(System.getenv("GEMINI_API_KEY"))
                .build();
        EmbeddingModel embeddingModel = new GoogleGenAiTextEmbeddingModel(connectionDetails,
                GoogleGenAiTextEmbeddingOptions.builder().model("gemini-embedding-001").dimensions(768).build());
        SpringAiEmbeddingGeneratorAdapter adapter = new SpringAiEmbeddingGeneratorAdapter(embeddingModel);

        Embedding embedding = adapter.embed("Boil water for one minute to make it safe to drink.");

        assertThat(embedding.values()).hasSize(768);
    }
}
