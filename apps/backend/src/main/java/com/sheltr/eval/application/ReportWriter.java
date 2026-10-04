package com.sheltr.eval.application;

import com.sheltr.eval.domain.ConfidenceRow;
import com.sheltr.eval.domain.EvalReport;
import com.sheltr.eval.domain.SplitScores;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class ReportWriter {

    /** Judge and maintainer agreement must reach this share before the judge is trusted (spec 4.2). */
    public static final double AGREEMENT_TARGET = 0.90;
    public static final String AGREEMENT_ROW_PREFIX = "| Judge and maintainer agreement |";

    private ReportWriter() {
    }

    /** One Markdown table row recording judge and maintainer agreement. */
    public static String agreementRow(double agreement) {
        return AGREEMENT_ROW_PREFIX + " " + number(agreement) + " | at least "
                + String.format(Locale.ROOT, "%.2f", AGREEMENT_TARGET) + " | "
                + (agreement >= AGREEMENT_TARGET ? "PASS" : "FAIL") + " |";
    }

    public static String markdown(EvalReport report) {
        StringBuilder out = new StringBuilder("# Eval report: " + report.config() + "\n\n");

        out.append("## Settings\n\n| Setting | Value |\n|---|---|\n");
        new TreeMap<>(report.settings()).forEach((key, value) ->
                out.append("| ").append(key).append(" | ").append(value).append(" |\n"));

        SplitScores heldout = report.splits().get("heldout");
        if (heldout != null) {
            out.append("\n## Targets (heldout)\n\n| Metric | Value | Target | Result |\n|---|---|---|---|\n");
            target(out, "False-accept rate", heldout.falseAcceptRate(), "0", heldout.falseAcceptRate() == 0.0);
            target(out, "False-refuse rate", heldout.falseRefuseRate(), "at most 0.10",
                    heldout.falseRefuseRate() <= 0.10);
            target(out, "Recall at 5", heldout.recallAt5(), "at least 0.85", heldout.recallAt5() >= 0.85);
            target(out, "Faithfulness", heldout.faithfulness(), "at least 0.95", heldout.faithfulness() >= 0.95);
        }

        out.append("\n## Scores by split\n\n")
                .append("| Split | Questions | Recall at 5 | MRR | False accept | False refuse | Faithfulness ")
                .append("| Key facts | Calibration error | Median ms | p95 ms | Errors |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, SplitScores> entry : report.splits().entrySet()) {
            SplitScores s = entry.getValue();
            out.append("| ").append(entry.getKey()).append(" | ").append(s.questions())
                    .append(" | ").append(number(s.recallAt5()))
                    .append(" | ").append(number(s.meanReciprocalRank()))
                    .append(" | ").append(number(s.falseAcceptRate()))
                    .append(" | ").append(number(s.falseRefuseRate()))
                    .append(" | ").append(number(s.faithfulness()))
                    .append(" | ").append(number(s.keyFactCoverage()))
                    .append(" | ").append(number(s.calibrationError()))
                    .append(" | ").append(s.medianLatencyMs())
                    .append(" | ").append(s.p95LatencyMs())
                    .append(" | ").append(s.errors()).append(" |\n");
        }

        out.append("\n## Confidence table (tune)\n\n")
                .append("Of the answers given with at least this confidence, the share that were correct.\n\n")
                .append("| Confidence at least | Answers | Accuracy |\n|---|---|---|\n");
        for (ConfidenceRow row : report.tuneConfidenceTable()) {
            out.append("| ").append(String.format(Locale.ROOT, "%.2f", row.threshold()))
                    .append(" | ").append(row.answers())
                    .append(" | ").append(number(row.accuracy())).append(" |\n");
        }

        out.append("\nJudge replies that could not be read: ").append(report.judgeErrors()).append("\n");
        return out.toString();
    }

    private static void target(StringBuilder out, String name, double value, String target, boolean pass) {
        out.append("| ").append(name).append(" | ").append(number(value)).append(" | ").append(target)
                .append(" | ").append(!Double.isNaN(value) && pass ? "PASS" : "FAIL").append(" |\n");
    }

    private static String number(double value) {
        return Double.isNaN(value) ? "n/a" : String.format(Locale.ROOT, "%.3f", value);
    }
}
