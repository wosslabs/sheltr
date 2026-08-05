package com.sheltr.search.infrastructure;

import com.sheltr.SheltrBackendApplication;
import com.sheltr.knowledge.application.ChunkEmbeddingBackfillService;
import com.sheltr.knowledge.domain.ChunkRepository;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.knowledge.infrastructure.JooqChunkRepository;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "OLLAMA_AVAILABLE", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(classes = SheltrBackendApplication.class)
class RelevanceThresholdEvalTest {

    private record EvalCase(String question, boolean expectedRelevant) {
    }

    // Two "irrelevant" questions per disaster type: nothing in the 32-chunk seed corpus
    // (V5__seed_knowledge_documents.sql) answers them. Three "relevant" questions per type,
    // each paraphrasing (not quoting) one specific seed chunk, so a working embedding model
    // must generalize past exact wording to score them above threshold.
    private static final List<EvalCase> FIXTURE = List.of(
            new EvalCase("What should I do the moment the ground starts shaking?", true),
            new EvalCase("Is it safer to run outside or stay inside during an earthquake?", true),
            new EvalCase("I smell gas after an earthquake, what should I do?", true),
            new EvalCase("How do I renew my driver's license online?", false),
            new EvalCase("What's a good recipe for banana bread?", false),

            new EvalCase("Is it dangerous to drive through moving flood water?", true),
            new EvalCase("How long should I boil water to make it safe after a flood?", true),
            new EvalCase("I don't have a stove, can I use bleach to purify water?", true),
            new EvalCase("What's the best way to invest in index funds?", false),
            new EvalCase("How do I train my dog to stop barking?", false),

            new EvalCase("How should I prepare my house before hurricane season?", true),
            new EvalCase("A hurricane warning was just issued, what should I do?", true),
            new EvalCase("Is standing water after a hurricane safe to walk through?", true),
            new EvalCase("What's the tax deadline this year?", false),
            new EvalCase("How do I learn to play guitar?", false),

            new EvalCase("Where's the safest place in my house during a tornado?", true),
            new EvalCase("I'm driving and a tornado is coming, what do I do?", true),
            new EvalCase("What's the difference between a tornado watch and a warning?", true),
            new EvalCase("What's a good workout routine for beginners?", false),
            new EvalCase("How do I set up a home Wi-Fi network?", false),

            new EvalCase("I felt a strong earthquake near the beach, should I wait for an official warning?", true),
            new EvalCase("The ocean water suddenly pulled back and exposed the sea floor, what does that mean?", true),
            new EvalCase("Is it safe to go back to the beach right after a tsunami wave hits?", true),
            new EvalCase("How do I bake sourdough bread?", false),
            new EvalCase("What's the best programming language to learn first?", false),

            new EvalCase("What should I wear if I need to evacuate from a wildfire?", true),
            new EvalCase("How can I protect my home before wildfire season starts?", true),
            new EvalCase("I'm trapped by a wildfire, what should I do?", true),
            new EvalCase("How do I change a flat tire?", false),
            new EvalCase("What's a good beginner yoga routine?", false),

            new EvalCase("What should I drink during a heat wave?", true),
            new EvalCase("What are the warning signs of heat stroke?", true),
            new EvalCase("Is it okay to leave my dog in the car for a few minutes on a hot day?", true),
            new EvalCase("How do I fix a leaky faucet?", false),
            new EvalCase("What's the plot of a good mystery novel?", false),

            new EvalCase("Can I run my generator inside during a power outage?", true),
            new EvalCase("My power is out and I need to store insulin, what should I do?", true),
            new EvalCase("What does frostbite look like?", true),
            new EvalCase("How do I apply for a passport?", false),
            new EvalCase("What's a good stretching routine before running?", false)
    );

    private static final double[] CANDIDATE_THRESHOLDS =
            {0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80, 0.85, 0.90};

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    DataSource dataSource;

    @Autowired
    EmbeddingGenerator embeddingGenerator;

    @Autowired
    Retriever retriever;

    @Test
    void sweepThresholdsAndReportTheSafestOne() {
        ChunkRepository chunkRepository = new JooqChunkRepository(dataSource);
        int backfilled = new ChunkEmbeddingBackfillService(chunkRepository, embeddingGenerator).backfillAll();
        assertThat(backfilled).isEqualTo(32);

        double[] topScores = new double[FIXTURE.size()];
        for (int i = 0; i < FIXTURE.size(); i++) {
            List<RankedResult> results = retriever.retrieve(new SearchQuery(FIXTURE.get(i).question()), 1);
            topScores[i] = results.isEmpty() ? 0.0 : results.get(0).score();
        }

        double chosenThreshold = -1;
        int chosenFalseAccepts = Integer.MAX_VALUE;
        int chosenFalseRejects = Integer.MAX_VALUE;
        for (double threshold : CANDIDATE_THRESHOLDS) {
            int falseAccepts = 0;
            int falseRejects = 0;
            for (int i = 0; i < FIXTURE.size(); i++) {
                boolean passes = topScores[i] >= threshold;
                if (passes && !FIXTURE.get(i).expectedRelevant()) falseAccepts++;
                if (!passes && FIXTURE.get(i).expectedRelevant()) falseRejects++;
            }
            System.out.printf("threshold=%.2f falseAccepts=%d falseRejects=%d%n", threshold, falseAccepts, falseRejects);
            boolean better = falseAccepts < chosenFalseAccepts
                    || (falseAccepts == chosenFalseAccepts && falseRejects < chosenFalseRejects);
            if (better) {
                chosenThreshold = threshold;
                chosenFalseAccepts = falseAccepts;
                chosenFalseRejects = falseRejects;
            }
        }

        System.out.printf("CHOSEN threshold=%.2f falseAccepts=%d falseRejects=%d%n",
                chosenThreshold, chosenFalseAccepts, chosenFalseRejects);
        assertThat(chosenFalseAccepts).isZero();
    }
}
