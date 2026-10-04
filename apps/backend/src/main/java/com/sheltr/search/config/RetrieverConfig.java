package com.sheltr.search.config;

import com.sheltr.knowledge.domain.ChunkRepository;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.infrastructure.GuardedRetriever;
import com.sheltr.search.infrastructure.PgvectorSemanticSearchAdapter;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Set;

@Configuration
public class RetrieverConfig {

    @Bean
    public Retriever retriever(DataSource dataSource, EmbeddingGenerator embeddingGenerator,
                               ChunkRepository chunkRepository,
                               @Value("${sheltr.search.statuses}") Set<String> statuses) {
        Retriever search = new PgvectorSemanticSearchAdapter(dataSource, embeddingGenerator, statuses);
        return new GuardedRetriever(search, chunkRepository, embeddingGenerator.modelName());
    }

    /** Says at startup what GuardedRetriever will refuse on, so the cause is in the log before a user asks. */
    @Bean
    public ApplicationRunner staleEmbeddingsWarning(ChunkRepository chunkRepository,
                                                    EmbeddingGenerator embeddingGenerator) {
        return args -> {
            int stale = chunkRepository.countEmbeddedWithOtherModel(embeddingGenerator.modelName());
            if (stale > 0) {
                LoggerFactory.getLogger(RetrieverConfig.class).error(
                        "{} chunk(s) were embedded by a model other than '{}'. Questions are refused "
                                + "until `make seed-embeddings` re-embeds them.",
                        stale, embeddingGenerator.modelName());
            }
        };
    }
}
