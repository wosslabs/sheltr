package com.sheltr.knowledge.domain;

public class StaleEmbeddingsException extends RuntimeException {
    public StaleEmbeddingsException(int staleCount, String activeModel) {
        super(staleCount + " chunk(s) were embedded by a model other than '" + activeModel
                + "'. Run `make seed-embeddings` to re-embed them before serving questions.");
    }
}
