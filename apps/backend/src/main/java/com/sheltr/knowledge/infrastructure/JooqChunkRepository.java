package com.sheltr.knowledge.infrastructure;

import com.sheltr.knowledge.domain.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class JooqChunkRepository implements ChunkRepository {

    private final DSLContext dsl;

    public JooqChunkRepository(DataSource dataSource) {
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    @Override
    public Optional<Chunk> findById(ChunkId id) {
        Record record = dsl.fetchOne(
                "SELECT id, document_id, content, page_number, chunk_index, section_heading, checksum "
                        + "FROM knowledge.chunks WHERE id = ?",
                id.value());
        return Optional.ofNullable(record).map(JooqChunkRepository::mapRow);
    }

    @Override
    public List<Chunk> findPendingEmbeddings() {
        var result = dsl.fetch(
                "SELECT id, document_id, content, page_number, chunk_index, section_heading, checksum "
                        + "FROM knowledge.chunks WHERE embedding IS NULL");
        List<Chunk> chunks = new ArrayList<>();
        for (Record record : result) {
            chunks.add(mapRow(record));
        }
        return chunks;
    }

    @Override
    public void save(Chunk chunk) {
        Embedding embedding = chunk.embedding().orElseThrow(
                () -> new IllegalStateException("save() only supports writing an embedding in this slice"));
        dsl.execute("UPDATE knowledge.chunks SET embedding = ?::vector WHERE id = ?",
                toVectorLiteral(embedding), chunk.id().value());
    }

    @Override
    public void insert(Chunk chunk) {
        Embedding embedding = chunk.embedding().orElseThrow(
                () -> new IllegalStateException("insert() requires the chunk to already have an embedding"));
        dsl.execute(
                "INSERT INTO knowledge.chunks (id, document_id, content, embedding, page_number, chunk_index, "
                        + "section_heading, checksum) VALUES (?, ?, ?, ?::vector, ?, ?, ?, ?)",
                chunk.id().value(), chunk.documentId().value(), chunk.content().value(),
                toVectorLiteral(embedding), chunk.pageNumber().map(PageNumber::value).orElse(null),
                chunk.chunkIndex(), chunk.metadata().sectionHeading(), chunk.checksum().hex());
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

    private static Chunk mapRow(Record record) {
        Integer pageNumberValue = record.get("page_number", Integer.class);
        String sectionHeading = record.get("section_heading", String.class);
        return new Chunk(
                new ChunkId((UUID) record.get("id")),
                new DocumentId((UUID) record.get("document_id")),
                new ChunkContent(record.get("content", String.class)),
                null,
                pageNumberValue == null ? null : new PageNumber(pageNumberValue),
                record.get("chunk_index", Integer.class),
                new ChunkMetadata(sectionHeading, Set.of()),
                new Checksum(record.get("checksum", String.class)));
    }
}
