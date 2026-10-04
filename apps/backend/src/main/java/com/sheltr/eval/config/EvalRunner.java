package com.sheltr.eval.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sheltr.ai.application.AnswerPipeline;
import com.sheltr.eval.application.AnswerJudge;
import com.sheltr.eval.application.EvalJson;
import com.sheltr.eval.application.EvalQuestionLoader;
import com.sheltr.eval.application.EvalRunService;
import com.sheltr.eval.application.EvalScoreService;
import com.sheltr.eval.application.ReportWriter;
import com.sheltr.eval.domain.EvalQuestion;
import com.sheltr.eval.domain.EvalResult;
import com.sheltr.eval.domain.EvalRun;
import com.sheltr.eval.domain.ExpectedPassage;
import com.sheltr.eval.domain.SpotcheckItem;
import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Command-line eval modes:
 * eval-validate, eval-run, eval-score, eval-agreement. See the Makefile targets.
 */
@Component
public class EvalRunner implements ApplicationRunner {

    private static final String SPOTCHECK_SUFFIX = ".spotcheck.jsonl";

    private static final List<String> RECORDED_SETTINGS = List.of(
            "sheltr.search.mode", "sheltr.search.reranker", "sheltr.search.hazard-boost",
            "sheltr.search.related-hazard-boost", "sheltr.search.relevance-threshold", "sheltr.judge.type",
            "sheltr.judge.min-probability", "sheltr.embedding.model-name", "sheltr.embedding.query-prefix",
            "sheltr.embedding.document-prefix");

    private final AnswerPipeline pipeline;
    private final KnowledgeDocumentRepository documents;
    private final ChatModel chatModel;
    private final DataSource dataSource;
    private final Environment environment;

    public EvalRunner(AnswerPipeline pipeline, KnowledgeDocumentRepository documents, ChatModel chatModel,
                      DataSource dataSource, Environment environment) {
        this.pipeline = pipeline;
        this.documents = documents;
        this.chatModel = chatModel;
        this.dataSource = dataSource;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> modes = args.getNonOptionArgs();
        if (modes.contains("eval-validate")) {
            System.exit(validate(Path.of(option(args, "questions"))));
        } else if (modes.contains("eval-run")) {
            runQuestions(option(args, "config"), Path.of(option(args, "questions")), Path.of(option(args, "out")));
            System.exit(0);
        } else if (modes.contains("eval-score")) {
            try {
                score(Path.of(option(args, "raw")), Path.of(option(args, "questions")));
            } catch (IllegalStateException | IllegalArgumentException e) {
                System.err.println("eval-score refused: " + e.getMessage());
                System.exit(1);
            }
            System.exit(0);
        } else if (modes.contains("eval-agreement")) {
            System.exit(agreement(Path.of(option(args, "spotcheck"))));
        }
    }

    /** Every expected passage phrase must exist in a chunk of the named active document. */
    private int validate(Path questionsPath) throws Exception {
        List<EvalQuestion> questions = EvalQuestionLoader.load(questionsPath);
        var dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
        int missing = 0;
        for (EvalQuestion question : questions) {
            for (ExpectedPassage expected : question.expectedPassages()) {
                int found = dsl.fetchOne(
                        "SELECT count(*) AS n FROM knowledge.chunks c "
                                + "JOIN knowledge.documents d ON d.id = c.document_id "
                                + "WHERE d.source_uri = ? AND d.status = 'ACTIVE' AND position("
                                + "lower(regexp_replace(?, '\\s+', ' ', 'g')) in "
                                + "lower(regexp_replace(c.content, '\\s+', ' ', 'g'))) > 0",
                        expected.sourceUri(), expected.contains()).get("n", Integer.class);
                if (found == 0) {
                    missing++;
                    System.out.println("MISSING " + question.id() + ": \"" + expected.contains()
                            + "\" not found in any chunk of " + expected.sourceUri());
                }
            }
        }
        System.out.println(questions.size() + " question(s) checked, " + missing + " expected passage(s) missing.");
        return missing == 0 ? 0 : 1;
    }

    private void runQuestions(String config, Path questionsPath, Path outDir) throws Exception {
        List<EvalQuestion> questions = EvalQuestionLoader.load(questionsPath);
        EvalRunService service = new EvalRunService(pipeline, documents);
        Instant started = Instant.now();
        List<EvalResult> results = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            results.add(service.run(questions.get(i)));
            System.out.println("[" + (i + 1) + "/" + questions.size() + "] " + questions.get(i).id());
        }
        Files.createDirectories(outDir);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(started);
        Path raw = outDir.resolve(stamp + "-" + config + ".raw.json");
        EvalJson.MAPPER.writeValue(raw.toFile(), new EvalRun(config, started.toString(), settings(), results));
        System.out.println("Wrote " + raw);
    }

    private void score(Path rawPath, Path questionsPath) throws Exception {
        EvalRun run = EvalJson.MAPPER.readValue(rawPath.toFile(), EvalRun.class);
        String recorded = run.settings() == null ? null : run.settings().get("spring.profiles.active");
        String conflict = judgeProfileConflict(recorded, String.join(",", environment.getActiveProfiles()));
        if (conflict != null) {
            throw new IllegalStateException(conflict);
        }
        List<EvalQuestion> questions = EvalQuestionLoader.load(questionsPath);
        EvalScoreService.Output output = new EvalScoreService(new AnswerJudge(chatModel::call))
                .score(questions, run);

        String base = rawPath.toString().replaceFirst("\\.raw\\.json$", "");
        Map<String, Object> reportWithJudge = new LinkedHashMap<>();
        reportWithJudge.put("report", output.report());
        reportWithJudge.put("judge_profiles", String.join(",", environment.getActiveProfiles()));
        EvalJson.MAPPER.writeValue(Path.of(base + ".report.json").toFile(), reportWithJudge);
        Files.writeString(Path.of(base + ".report.md"), ReportWriter.markdown(output.report())
                + "\nJudged under profile(s): " + String.join(",", environment.getActiveProfiles()) + "\n");
        StringBuilder lines = new StringBuilder();
        for (SpotcheckItem item : output.spotcheck()) {
            lines.append(EvalJson.LINE_WRITER.writeValueAsString(item)).append('\n');
        }
        Files.writeString(Path.of(base + ".spotcheck.jsonl"), lines);
        System.out.println(ReportWriter.markdown(output.report()));
        System.out.println("Wrote " + base + ".report.md, .report.json, .spotcheck.jsonl");
    }

    /**
     * Records agreement in the run's report.md and report.json, then returns the exit code:
     * 0 at or above the target, 1 below it.
     */
    private int agreement(Path spotcheckPath) throws Exception {
        String name = spotcheckPath.toString();
        if (!name.endsWith(SPOTCHECK_SUFFIX)) {
            throw new IllegalArgumentException("--spotcheck must be a " + SPOTCHECK_SUFFIX + " file: " + name);
        }
        String base = name.substring(0, name.length() - SPOTCHECK_SUFFIX.length());
        Path reportMd = Path.of(base + ".report.md");
        Path reportJson = Path.of(base + ".report.json");
        if (!Files.exists(reportMd) || !Files.exists(reportJson)) {
            throw new IllegalStateException("Run eval-score first: " + reportMd + " and " + reportJson
                    + " must exist");
        }

        List<SpotcheckItem> items = new ArrayList<>();
        for (String line : Files.readAllLines(spotcheckPath)) {
            if (!line.isBlank()) {
                JsonNode node = EvalJson.MAPPER.readTree(line);
                items.add(EvalJson.MAPPER.treeToValue(node, SpotcheckItem.class));
            }
        }
        double agreement = EvalScoreService.agreement(items);

        ObjectNode json = (ObjectNode) EvalJson.MAPPER.readTree(reportJson.toFile());
        json.put("judge_agreement", agreement);
        EvalJson.MAPPER.writeValue(reportJson.toFile(), json);

        // Replace an earlier agreement row, so running this again does not duplicate it.
        StringBuilder md = new StringBuilder();
        Files.readAllLines(reportMd).stream()
                .filter(line -> !line.startsWith(ReportWriter.AGREEMENT_ROW_PREFIX))
                .forEach(line -> md.append(line).append('\n'));
        md.append(ReportWriter.agreementRow(agreement)).append('\n');
        Files.writeString(reportMd, md);

        System.out.printf("Judge and maintainer agree on %.0f%% of %d answers (target: at least 90%%).%n",
                agreement * 100, items.size());
        if (agreement < ReportWriter.AGREEMENT_TARGET) {
            System.out.println("Below the target. Fix the judge prompt before trusting any result.");
            return 1;
        }
        return 0;
    }

    /**
     * Returns why eval-score must refuse, or null. The judge must not be the profile that produced the run
     * (spec 4.2). Profile lists are compared as sorted, trimmed, comma-separated names.
     */
    static String judgeProfileConflict(String recorded, String current) {
        String produced = normalizeProfiles(recorded);
        String judge = normalizeProfiles(current);
        if (produced.isEmpty() || !produced.equals(judge)) {
            return null;
        }
        String other = produced.equals("gemini") ? "local" : "gemini";
        return "The judge profile must differ from the profile that produced the run (both are " + produced
                + "). Run eval-score under another profile, for example SPRING_PROFILES_ACTIVE=" + other + ".";
    }

    private static String normalizeProfiles(String profiles) {
        if (profiles == null) {
            return "";
        }
        return Arrays.stream(profiles.split(","))
                .map(String::trim)
                .filter(profile -> !profile.isEmpty())
                .sorted()
                .collect(Collectors.joining(","));
    }

    private Map<String, String> settings() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("spring.profiles.active", String.join(",", environment.getActiveProfiles()));
        for (String key : RECORDED_SETTINGS) {
            settings.put(key, environment.getProperty(key, ""));
        }
        return settings;
    }

    private static String option(ApplicationArguments args, String name) {
        if (!args.containsOption(name) || args.getOptionValues(name).isEmpty()) {
            throw new IllegalArgumentException("Missing required option --" + name + "=<value>");
        }
        return args.getOptionValues(name).get(0);
    }
}
