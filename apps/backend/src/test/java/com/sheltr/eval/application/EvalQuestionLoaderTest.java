package com.sheltr.eval.application;

import com.sheltr.eval.domain.EvalKind;
import com.sheltr.eval.domain.EvalQuestion;
import com.sheltr.knowledge.domain.DisasterType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvalQuestionLoaderTest {

    private static final String ANSWERABLE = """
            {"id":"flood-001","question":"How long should I boil water?","kind":"ANSWERABLE","active_hazards":["FLOOD"],"expected_passages":[{"source_uri":"https://example.org/flood.pdf","contains":"rolling boil"}],"key_facts":["one minute"],"split":"tune"}""";
    private static final String OFF_TOPIC = """
            {"id":"off-001","question":"How do I bake bread?","kind":"OFF_TOPIC","split":"heldout"}""";

    @TempDir
    Path dir;

    @Test
    void loadsQuestionsAndSkipsBlankLines() throws IOException {
        List<EvalQuestion> questions = EvalQuestionLoader.load(file(ANSWERABLE + "\n\n" + OFF_TOPIC + "\n"));

        assertThat(questions).hasSize(2);
        EvalQuestion first = questions.get(0);
        assertThat(first.id()).isEqualTo("flood-001");
        assertThat(first.kind()).isEqualTo(EvalKind.ANSWERABLE);
        assertThat(first.activeHazards()).containsExactly(DisasterType.FLOOD);
        assertThat(first.expectedPassages().get(0).contains()).isEqualTo("rolling boil");
        assertThat(first.keyFacts()).containsExactly("one minute");
        assertThat(questions.get(1).expectedPassages()).isEmpty();
    }

    @Test
    void rejectsADuplicateId() {
        assertThatThrownBy(() -> EvalQuestionLoader.load(file(OFF_TOPIC + "\n" + OFF_TOPIC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line 2")
                .hasMessageContaining("off-001");
    }

    @Test
    void rejectsAnAnswerableQuestionWithoutAnExpectedPassageOrKeyFacts() {
        String noPassage = ANSWERABLE.replace(
                "[{\"source_uri\":\"https://example.org/flood.pdf\",\"contains\":\"rolling boil\"}]", "[]");

        assertThatThrownBy(() -> EvalQuestionLoader.load(file(noPassage)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected_passages");
        assertThatThrownBy(() -> EvalQuestionLoader.load(file(ANSWERABLE.replace("[\"one minute\"]", "[]"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key_facts");
    }

    @Test
    void rejectsAMustRefuseQuestionThatNamesAnExpectedPassage() {
        String wrong = ANSWERABLE.replace("\"ANSWERABLE\"", "\"UNCOVERED\"");

        assertThatThrownBy(() -> EvalQuestionLoader.load(file(wrong)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not have expected_passages");
    }

    @Test
    void rejectsAnUnknownSplitAndUnreadableJson() {
        assertThatThrownBy(() -> EvalQuestionLoader.load(file(OFF_TOPIC.replace("heldout", "test"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("split");
        assertThatThrownBy(() -> EvalQuestionLoader.load(file("{not json")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line 1");
    }

    @Test
    void rejectsAnUnknownFieldNamingTheLine() {
        String typo = OFF_TOPIC.replace("\"split\":\"heldout\"", "\"split\":\"heldout\",\"active_hazard\":[\"FLOOD\"]");

        assertThatThrownBy(() -> EvalQuestionLoader.load(file(OFF_TOPIC + "\n" + typo)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line 2")
                .hasMessageContaining("active_hazard");
    }

    @Test
    void rejectsAMissingId() {
        assertThatThrownBy(() -> EvalQuestionLoader.load(file(OFF_TOPIC.replace("\"id\":\"off-001\",", ""))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id is required");
    }

    @Test
    void rejectsAMissingQuestion() {
        assertThatThrownBy(() -> EvalQuestionLoader.load(
                file(OFF_TOPIC.replace("\"question\":\"How do I bake bread?\",", ""))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("question is required");
    }

    @Test
    void rejectsAMissingKind() {
        assertThatThrownBy(() -> EvalQuestionLoader.load(file(OFF_TOPIC.replace("\"kind\":\"OFF_TOPIC\",", ""))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind is required");
    }

    @Test
    void rejectsAnExpectedPassageMissingSourceUriOrContains() {
        String noUri = ANSWERABLE.replace("\"source_uri\":\"https://example.org/flood.pdf\",", "");
        String noContains = ANSWERABLE.replace(",\"contains\":\"rolling boil\"", "");

        assertThatThrownBy(() -> EvalQuestionLoader.load(file(noUri)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flood-001")
                .hasMessageContaining("expected_passages[0] needs source_uri and contains");
        assertThatThrownBy(() -> EvalQuestionLoader.load(file(noContains)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected_passages[0] needs source_uri and contains");
    }

    @Test
    void aLiteralNullLineIsReportedWithItsLineNumber() {
        assertThatThrownBy(() -> EvalQuestionLoader.load(file(OFF_TOPIC + "\nnull\n")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line 2");
    }

    private Path file(String content) throws IOException {
        Path path = dir.resolve("questions.jsonl");
        Files.writeString(path, content);
        return path;
    }
}
