package com.sheltr.knowledge.domain;

public interface EmbeddingGenerator {

    /** Embeds corpus text. */
    Embedding embed(String text);

    /** Embeds a user question. Some models want a different prefix for questions than for documents. */
    default Embedding embedQuery(String text) {
        return embed(text);
    }

    /** Name recorded on every chunk this generator embeds. Change it whenever vectors would differ. */
    String modelName();
}
