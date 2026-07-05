package com.sheltr.knowledge.infrastructure;

import com.sheltr.knowledge.domain.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class JooqKnowledgeDocumentRepository implements KnowledgeDocumentRepository {

    private final DSLContext dsl;

    public JooqKnowledgeDocumentRepository(DataSource dataSource) {
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    @Override
    public Optional<KnowledgeDocument> findById(DocumentId id) {
        Record record = dsl.fetchOne(
                "SELECT id, title, source_agency, source_uri, disaster_types, published_date, version, "
                        + "checksum, supersedes_id, status FROM knowledge.documents WHERE id = ?",
                id.value());
        return Optional.ofNullable(record).map(JooqKnowledgeDocumentRepository::mapRow);
    }

    private static KnowledgeDocument mapRow(Record record) {
        String[] disasterTypeNames = record.get("disaster_types", String[].class);
        Set<DisasterType> disasterTypes = Arrays.stream(disasterTypeNames)
                .map(DisasterType::valueOf)
                .collect(Collectors.toSet());
        UUID supersedesId = record.get("supersedes_id", UUID.class);
        return new KnowledgeDocument(
                new DocumentId((UUID) record.get("id")),
                record.get("title", String.class),
                record.get("source_agency", String.class),
                record.get("source_uri", String.class),
                disasterTypes,
                record.get("published_date", LocalDate.class),
                new DocumentVersion(record.get("version", Integer.class)),
                new Checksum(record.get("checksum", String.class)),
                supersedesId == null ? null : new DocumentId(supersedesId),
                DocumentStatus.valueOf(record.get("status", String.class)));
    }
}
