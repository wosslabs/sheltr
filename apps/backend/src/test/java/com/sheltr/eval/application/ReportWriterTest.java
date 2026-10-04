package com.sheltr.eval.application;

import com.sheltr.eval.domain.ConfidenceRow;
import com.sheltr.eval.domain.EvalReport;
import com.sheltr.eval.domain.SplitScores;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportWriterTest {

    @Test
    void marksEachHeldoutTargetAsPassOrFail() {
        SplitScores heldout = new SplitScores(50, 0.90, 0.80, 0.02, 0.05, 0.97, 0.90, 0.08, 800, 2000, 0);
        EvalReport report = new EvalReport("hybrid", Map.of("sheltr.search.mode", "hybrid"),
                Map.of("heldout", heldout), List.of(new ConfidenceRow(0.5, 10, 0.9)), 0);

        String markdown = ReportWriter.markdown(report);

        assertThat(markdown).contains("# Eval report: hybrid");
        assertThat(markdown).contains("sheltr.search.mode");
        assertThat(markdown).contains("| False-accept rate | 0.020 | 0 | FAIL |");
        assertThat(markdown).contains("| False-refuse rate | 0.050 | at most 0.10 | PASS |");
        assertThat(markdown).contains("| Recall at 5 | 0.900 | at least 0.85 | PASS |");
        assertThat(markdown).contains("| Faithfulness | 0.970 | at least 0.95 | PASS |");
    }

    @Test
    void showsNotANumberAsNotApplicable() {
        SplitScores tune = new SplitScores(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, 0, 0, 0);

        assertThat(ReportWriter.markdown(new EvalReport("x", Map.of(), Map.of("tune", tune), List.of(), 0)))
                .contains("n/a").doesNotContain("NaN");
    }

    @Test
    void agreementRowPassesAtOrAboveTheTarget() {
        assertThat(ReportWriter.agreementRow(0.92))
                .isEqualTo("| Judge and maintainer agreement | 0.920 | at least 0.90 | PASS |");
        assertThat(ReportWriter.agreementRow(0.90))
                .isEqualTo("| Judge and maintainer agreement | 0.900 | at least 0.90 | PASS |");
    }

    @Test
    void agreementRowFailsBelowTheTarget() {
        assertThat(ReportWriter.agreementRow(0.85))
                .isEqualTo("| Judge and maintainer agreement | 0.850 | at least 0.90 | FAIL |");
    }
}
