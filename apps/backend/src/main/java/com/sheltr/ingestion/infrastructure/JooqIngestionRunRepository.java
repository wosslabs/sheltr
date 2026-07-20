package com.sheltr.ingestion.infrastructure;

import com.sheltr.ingestion.domain.IngestionRun;
import com.sheltr.ingestion.domain.IngestionRunId;
import com.sheltr.ingestion.domain.IngestionRunRepository;
import com.sheltr.ingestion.domain.RunStatus;
import com.sheltr.knowledge.domain.DocumentId;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

public class JooqIngestionRunRepository implements IngestionRunRepository {

    private final DSLContext dsl;

    public JooqIngestionRunRepository(DataSource dataSource) {
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    @Override
    public void save(IngestionRun run) {
        Timestamp finishedAt = run.finishedAt().map(Timestamp::from).orElse(null);
        String errorMessage = run.errorMessage().orElse(null);
        dsl.execute(
                "INSERT INTO ingestion.ingestion_runs (id, source_document_id, started_at, finished_at, status, "
                        + "error_message) VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET finished_at = EXCLUDED.finished_at, "
                        + "status = EXCLUDED.status, error_message = EXCLUDED.error_message",
                run.id().value(), run.sourceDocumentId().value(), Timestamp.from(run.startedAt()),
                finishedAt, run.status().name(), errorMessage);
    }

    @Override
    public Optional<IngestionRun> findById(IngestionRunId id) {
        Record record = dsl.fetchOne(
                "SELECT id, source_document_id, started_at, finished_at, status, error_message "
                        + "FROM ingestion.ingestion_runs WHERE id = ?",
                id.value());
        return Optional.ofNullable(record).map(JooqIngestionRunRepository::mapRow);
    }

    private static IngestionRun mapRow(Record record) {
        Timestamp finishedAtTs = record.get("finished_at", Timestamp.class);
        return new IngestionRun(
                new IngestionRunId((UUID) record.get("id")),
                new DocumentId((UUID) record.get("source_document_id")),
                record.get("started_at", Timestamp.class).toInstant(),
                RunStatus.valueOf(record.get("status", String.class)),
                finishedAtTs == null ? null : finishedAtTs.toInstant(),
                record.get("error_message", String.class));
    }
}
