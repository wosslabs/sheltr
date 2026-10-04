package com.sheltr.search.infrastructure;

import com.sheltr.knowledge.domain.DisasterType;
import com.sheltr.search.domain.RelatedHazards;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

public class JooqRelatedHazards implements RelatedHazards {

    private final DSLContext dsl;

    public JooqRelatedHazards(DataSource dataSource) {
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    @Override
    public Set<DisasterType> relatedTo(Set<DisasterType> hazards) {
        if (hazards.isEmpty()) {
            return Set.of();
        }
        String literal = "{" + hazards.stream().map(Enum::name).collect(Collectors.joining(",")) + "}";
        Set<DisasterType> related = EnumSet.noneOf(DisasterType.class);
        for (Record row : dsl.fetch(
                "SELECT DISTINCT related_hazard FROM knowledge.related_hazards WHERE hazard = ANY(?::text[])",
                literal)) {
            related.add(DisasterType.valueOf(row.get("related_hazard", String.class)));
        }
        related.removeAll(hazards);
        return related;
    }
}
