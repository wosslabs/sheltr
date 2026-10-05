package com.sheltr.search.infrastructure;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.knowledge.domain.Embedding;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.search.domain.CountryBoost;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pure vector search. When the query has a country, the nearest {@code topK * POOL_MULTIPLIER}
 * chunks are fetched by the index, then re-sorted with the country boost and cut to topK.
 * Without a country the query and result are the same as plain nearest-neighbour search.
 */
public class PgvectorSemanticSearchAdapter implements Retriever {

    private static final int POOL_MULTIPLIER = 5;

    private final DSLContext dsl;
    private final EmbeddingGenerator embeddingGenerator;
    private final String statusesLiteral;
    private final CountryBoost countryBoost;

    public PgvectorSemanticSearchAdapter(DataSource dataSource, EmbeddingGenerator embeddingGenerator,
                                         Set<String> statuses) {
        this(dataSource, embeddingGenerator, statuses, CountryBoost.NONE);
    }

    public PgvectorSemanticSearchAdapter(DataSource dataSource, EmbeddingGenerator embeddingGenerator,
                                         Set<String> statuses, CountryBoost countryBoost) {
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
        this.embeddingGenerator = embeddingGenerator;
        this.statusesLiteral = "{" + String.join(",", statuses) + "}";
        this.countryBoost = countryBoost;
    }

    @Override
    public List<RankedResult> retrieve(SearchQuery query, int topK) {
        Embedding queryEmbedding = embeddingGenerator.embedQuery(query.text());
        String vectorLiteral = toVectorLiteral(queryEmbedding);
        String country = query.country();
        int pool = country == null ? topK : topK * POOL_MULTIPLIER;

        var rows = dsl.fetch(
                "SELECT c.id, c.document_id, c.content, d.country_code, "
                        + "c.embedding <=> ?::vector AS distance "
                        + "FROM knowledge.chunks c JOIN knowledge.documents d ON d.id = c.document_id "
                        + "WHERE c.embedding IS NOT NULL AND c.embedding_model = ? "
                        + "AND d.status = ANY(?::text[]) "
                        + "ORDER BY c.embedding <=> ?::vector ASC LIMIT ?",
                vectorLiteral, embeddingGenerator.modelName(), statusesLiteral, vectorLiteral, pool);

        List<Scored> scored = new ArrayList<>();
        for (Record row : rows) {
            double distance = row.get("distance", Double.class);
            double similarity = clampToUnitRange(1.0 - distance);
            double factor = countryBoost.factor(row.get("country_code", String.class), country);
            scored.add(new Scored(new RankedResult(
                    new ChunkId((UUID) row.get("id")),
                    new DocumentId((UUID) row.get("document_id")),
                    row.get("content", String.class),
                    similarity), similarity * factor));
        }

        if (country != null) {
            // Stable sort: equal boosted scores keep index order.
            scored.sort(Comparator.comparingDouble(Scored::boosted).reversed());
        }
        return scored.stream().limit(topK).map(Scored::result).toList();
    }

    private record Scored(RankedResult result, double boosted) {
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
