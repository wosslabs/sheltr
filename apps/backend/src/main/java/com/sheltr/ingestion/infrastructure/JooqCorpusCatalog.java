package com.sheltr.ingestion.infrastructure;

import com.sheltr.ingestion.domain.CorpusCatalog;
import com.sheltr.ingestion.domain.StoredDocument;
import com.sheltr.knowledge.domain.DisasterType;
import com.sheltr.knowledge.domain.DocumentId;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;

public class JooqCorpusCatalog implements CorpusCatalog {

    private final DSLContext dsl;

    public JooqCorpusCatalog(DataSource dataSource) {
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    @Override
    public Optional<StoredDocument> findActiveDocument(String sourceUri) {
        Record record = dsl.fetchOne(
                "SELECT id, checksum FROM knowledge.documents WHERE source_uri = ? AND status = 'ACTIVE' LIMIT 1",
                sourceUri);
        return Optional.ofNullable(record)
                .map(r -> new StoredDocument(new DocumentId((UUID) r.get("id")), (String) r.get("checksum")));
    }

    @Override
    public void upsertRelatedHazard(DisasterType hazard, DisasterType relatedHazard, DocumentId source,
                                    String note) {
        dsl.execute(
                "INSERT INTO knowledge.related_hazards (hazard, related_hazard, source_document_id, note) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT (hazard, related_hazard) DO UPDATE "
                        + "SET source_document_id = EXCLUDED.source_document_id, note = EXCLUDED.note",
                hazard.name(), relatedHazard.name(), source.value(), note);
    }
}
