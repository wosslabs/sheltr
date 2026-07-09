package com.sheltr.search.infrastructure;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.knowledge.domain.Embedding;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PgvectorSemanticSearchAdapter implements Retriever {

    private final DSLContext dsl;
    private final EmbeddingGenerator embeddingGenerator;

    public PgvectorSemanticSearchAdapter(DataSource dataSource, EmbeddingGenerator embeddingGenerator) {
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
        this.embeddingGenerator = embeddingGenerator;
    }

    @Override
    public List<RankedResult> retrieve(SearchQuery query, int topK) {
        Embedding queryEmbedding = embeddingGenerator.embed(query.text());
        String vectorLiteral = toVectorLiteral(queryEmbedding);

        var rows = dsl.fetch(
                "SELECT id, document_id, content, embedding <=> ?::vector AS distance "
                        + "FROM knowledge.chunks WHERE embedding IS NOT NULL "
                        + "ORDER BY embedding <=> ?::vector ASC LIMIT ?",
                vectorLiteral, vectorLiteral, topK);

        List<RankedResult> results = new ArrayList<>();
        for (Record row : rows) {
            double distance = row.get("distance", Double.class);
            double similarity = clampToUnitRange(1.0 - distance);
            results.add(new RankedResult(
                    new ChunkId((UUID) row.get("id")),
                    new DocumentId((UUID) row.get("document_id")),
                    row.get("content", String.class),
                    similarity));
        }
        return results;
    }

    private static double clampToUnitRange(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String toVectorLiteral(Embedding embedding) {
        float[] values = embedding.values();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        return sb.append(']').toString();
    }
}
