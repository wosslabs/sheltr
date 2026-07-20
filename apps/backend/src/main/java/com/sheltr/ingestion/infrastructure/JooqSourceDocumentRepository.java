package com.sheltr.ingestion.infrastructure;

import com.sheltr.ingestion.domain.IngestionStatus;
import com.sheltr.ingestion.domain.SourceDocument;
import com.sheltr.ingestion.domain.SourceDocumentRepository;
import com.sheltr.ingestion.domain.SourceType;
import com.sheltr.knowledge.domain.Checksum;
import com.sheltr.knowledge.domain.DocumentId;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;

public class JooqSourceDocumentRepository implements SourceDocumentRepository {

    private final DSLContext dsl;

    public JooqSourceDocumentRepository(DataSource dataSource) {
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    @Override
    public void save(SourceDocument document) {
        Checksum checksum = document.checksum().orElseThrow(() -> new IllegalStateException(
                "SourceDocument must have a checksum (status DOWNLOADED or later) before it can be saved"));
        dsl.execute(
                "INSERT INTO ingestion.source_documents (id, source_uri, source_type, checksum, status) "
                        + "VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET checksum = EXCLUDED.checksum, status = EXCLUDED.status",
                document.id().value(), document.sourceUri(), document.sourceType().name(),
                checksum.hex(), document.status().name());
    }

    @Override
    public Optional<SourceDocument> findById(DocumentId id) {
        Record record = dsl.fetchOne(
                "SELECT id, source_uri, source_type, checksum, status FROM ingestion.source_documents WHERE id = ?",
                id.value());
        return Optional.ofNullable(record).map(JooqSourceDocumentRepository::mapRow);
    }

    private static SourceDocument mapRow(Record record) {
        return new SourceDocument(
                new DocumentId((UUID) record.get("id")),
                record.get("source_uri", String.class),
                SourceType.valueOf(record.get("source_type", String.class)),
                new Checksum(record.get("checksum", String.class)),
                IngestionStatus.valueOf(record.get("status", String.class)));
    }
}
