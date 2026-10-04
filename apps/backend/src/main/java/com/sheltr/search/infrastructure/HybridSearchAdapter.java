package com.sheltr.search.infrastructure;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DisasterType;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.search.domain.HybridRanker;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.RelatedHazards;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Vector search plus English keyword search, fused by rank and boosted for active hazards. */
public class HybridSearchAdapter implements Retriever {

    private static final String COLUMNS =
            "SELECT c.id, c.document_id, c.content, d.disaster_types, "
                    + "1 - (c.embedding <=> ?::vector) AS similarity ";
    private static final String FILTER =
            "WHERE c.embedding IS NOT NULL AND c.embedding_model = ? AND d.status = ANY(?::text[]) ";

    private final DSLContext dsl;
    private final EmbeddingGenerator embeddingGenerator;
    private final String statusesLiteral;
    private final RelatedHazards relatedHazards;
    private final HybridRanker ranker;
    private final int candidatesPerList;

    public HybridSearchAdapter(DataSource dataSource, EmbeddingGenerator embeddingGenerator,
                               Set<String> statuses, RelatedHazards relatedHazards, HybridRanker ranker,
                               int candidatesPerList) {
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
        this.embeddingGenerator = embeddingGenerator;
        this.statusesLiteral = "{" + String.join(",", statuses) + "}";
        this.relatedHazards = relatedHazards;
        this.ranker = ranker;
        this.candidatesPerList = candidatesPerList;
    }

    @Override
    public List<RankedResult> retrieve(SearchQuery query, int topK) {
        String vector = toVectorLiteral(embeddingGenerator.embedQuery(query.text()).values());
        String model = embeddingGenerator.modelName();

        List<HybridRanker.Candidate> vectorHits = candidates(dsl.fetch(
                COLUMNS + "FROM knowledge.chunks c JOIN knowledge.documents d ON d.id = c.document_id "
                        + FILTER + "ORDER BY c.embedding <=> ?::vector ASC LIMIT ?",
                vector, model, statusesLiteral, vector, candidatesPerList));

        // plainto_tsquery joins words with AND, which a natural question almost never satisfies.
        // Rewriting to OR lets ts_rank_cd order passages by how many question words they share.
        // A question of only stop words yields an empty query, which matches nothing.
        List<HybridRanker.Candidate> keywordHits = candidates(dsl.fetch(
                "WITH q AS (SELECT replace(plainto_tsquery('english', ?)::text, '&', '|')::tsquery AS tsq) "
                        + COLUMNS
                        + "FROM knowledge.chunks c JOIN knowledge.documents d ON d.id = c.document_id, q "
                        + FILTER + "AND c.content_tsv @@ q.tsq "
                        + "ORDER BY ts_rank_cd(c.content_tsv, q.tsq) DESC LIMIT ?",
                query.text(), vector, model, statusesLiteral, candidatesPerList));

        Set<DisasterType> active = query.activeHazards();
        return ranker.rank(vectorHits, keywordHits, active, relatedHazards.relatedTo(active), topK);
    }

    private static List<HybridRanker.Candidate> candidates(Iterable<? extends Record> rows) {
        List<HybridRanker.Candidate> candidates = new ArrayList<>();
        for (Record row : rows) {
            Set<DisasterType> hazards = Arrays.stream(row.get("disaster_types", String[].class))
                    .map(DisasterType::valueOf)
                    .collect(Collectors.toSet());
            candidates.add(new HybridRanker.Candidate(
                    new ChunkId((UUID) row.get("id")),
                    new DocumentId((UUID) row.get("document_id")),
                    row.get("content", String.class),
                    row.get("similarity", Double.class),
                    hazards));
        }
        return candidates;
    }

    private static String toVectorLiteral(float[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        return sb.append(']').toString();
    }
}
