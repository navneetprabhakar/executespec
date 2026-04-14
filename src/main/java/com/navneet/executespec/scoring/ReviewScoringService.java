package com.navneet.executespec.scoring;

import com.navneet.executespec.reviewer.model.AccuracyReport;
import com.navneet.executespec.reviewer.model.IssueCategory;
import com.navneet.executespec.reviewer.model.IssueSeverity;
import com.navneet.executespec.reviewer.model.ReviewIssue;
import com.navneet.executespec.reviewer.model.ReviewReportV2;
import com.navneet.executespec.state.entity.PipelineRun;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Pilot R6 — derives six review dimensions (code quality, test coverage,
 * architecture, security, performance, design compliance) from pipeline
 * artefacts we already collect. Deterministic heuristic, no extra LLM call —
 * keeps per-run cost flat while still giving the dashboard a trend to plot.
 *
 * <p>Design compliance is only emitted when the run was submitted with a
 * {@code figmaUrl} (Phase 5 adds that column to {@code pipeline_run}).
 */
@Service
@Slf4j
public class ReviewScoringService {

    public record Scores(
            Integer codeQuality,
            Integer testCoverage,
            Integer architecture,
            Integer security,
            Integer performance,
            Integer designCompliance) {}

    public Scores score(PipelineRun run, ReviewReportV2 review, AccuracyReport accuracy, String figmaUrl) {
        List<ReviewIssue> issues = review != null && review.getIssues() != null
                ? review.getIssues() : List.of();

        int codeQuality = 100;
        int architecture = 90;
        int security = 92;
        int performance = 90;
        int designIssues = 0;

        for (ReviewIssue issue : issues) {
            int penalty = severityPenalty(issue.getSeverity());
            codeQuality -= penalty;

            IssueCategory category = issue.getCategory();
            if (category == null) continue;
            switch (category) {
                case SECURITY -> security -= penalty * 3;
                case CROSS_TASK_MISMATCH, LOGIC_ERROR, ERROR_HANDLING -> architecture -= penalty * 2;
                case DEPLOYABILITY -> performance -= penalty * 2;
                case MISSING_ENDPOINT, MISSING_FEATURE, SPEC_MISMATCH -> architecture -= penalty;
                default -> { /* CODE_QUALITY / COMPLETENESS / README_INCOMPLETE already counted */ }
            }
            // Design-specific issues come in via description keywords.
            String desc = issue.getDescription() == null ? "" : issue.getDescription().toLowerCase();
            if (desc.contains("figma") || desc.contains("design") || desc.contains("ui ")) designIssues++;
        }

        int testCoverage = testCoverageScore(accuracy);

        Integer designCompliance = null;
        if (figmaUrl != null && !figmaUrl.isBlank()) {
            designCompliance = clamp(85 - designIssues * 8);
        }

        Scores scores = new Scores(
                clamp(codeQuality),
                testCoverage,
                clamp(architecture),
                clamp(security),
                clamp(performance),
                designCompliance);
        log.debug("Scored run={} scores={}", run != null ? run.getRunId() : "?", scores);
        return scores;
    }

    private static int severityPenalty(IssueSeverity severity) {
        if (severity == null) return 2;
        return switch (severity) {
            case CRITICAL -> 12;
            case MINOR -> 3;
        };
    }

    private static int testCoverageScore(AccuracyReport accuracy) {
        if (accuracy == null || accuracy.getTestCoverageLevel() == null) return 60;
        return switch (accuracy.getTestCoverageLevel().toUpperCase()) {
            case "HIGH", "COMPREHENSIVE" -> 95;
            case "MEDIUM", "ADEQUATE" -> 75;
            case "LOW", "MINIMAL" -> 45;
            case "NONE" -> 20;
            default -> 60;
        };
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }
}
