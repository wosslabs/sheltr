package com.sheltr.search.domain;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RankedResultTest {

    @Test
    void createsRankedResultWithScore() {
        RankedResult result = new RankedResult(
                new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()), "Boil water for one minute.", 0.87);

        assertThat(result.score()).isEqualTo(0.87);
    }

    @Test
    void rejectsScoreOutsideZeroToOne() {
        assertThatThrownBy(() -> new RankedResult(
                new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()), "text", 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
