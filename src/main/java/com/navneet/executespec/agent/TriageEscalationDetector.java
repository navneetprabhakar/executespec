package com.navneet.executespec.agent;

import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.compiler.CompileResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Post-execution checker for single-shot triage misclassification.
 *
 * <p>After a SINGLE_SHOT run completes, this detector examines execution metrics
 * and records whether the run exceeded complexity thresholds that suggest it should
 * have been classified as MULTI_TASK. Results are persisted to {@code triage_outcome}
 * for feedback loop analysis.
 *
 * <h3>Escalation thresholds</h3>
 * <ul>
 *   <li>{@code iterations > 20} — too many LLM calls for a "simple" request</li>
 *   <li>{@code files_written > 3} — touched too many files for single-shot</li>
 *   <li>{@code compile_errors > 2} — required too many compile iterations</li>
 *   <li>{@code duration > 8 minutes} — exceeded expected single-shot wall time</li>
 * </ul>
 *
 * <p>When thresholds are exceeded, an {@link EscalationResult#shouldEscalate() shouldEscalate}
 * signal is returned. The caller (PipelineHarness) uses this to log the outcome and
 * record it to the DB. Actual re-planning as MULTI_TASK is deferred to a future release.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TriageEscalationDetector {

    static final int ESCALATE_ITERATIONS    = 20;
    static final int ESCALATE_FILES         = 3;
    static final int ESCALATE_COMPILE_ERRORS = 2;
    static final Duration ESCALATE_DURATION = Duration.ofMinutes(8);

    private final JdbcTemplate jdbc;

    public record EscalationResult(
            boolean shouldEscalate,
            String reason,
            int iterations,
            int filesWritten,
            int compileErrors,
            int durationSeconds
    ) {}

    /**
     * Evaluate whether a completed single-shot run should have been MULTI_TASK.
     * Records the outcome to {@code triage_outcome} and returns the escalation signal.
     *
     * @param runId         the run to evaluate
     * @param reports       worker reports from the single-shot executor
     * @param compileResult the compile result (may be null if compile was skipped)
     * @param runStart      when execution started (used for duration)
     * @param outcomeStatus COMPLETE / PARTIAL / FAILED
     * @return escalation result (never null)
     */
    public EscalationResult evaluate(String runId, List<WorkerReport> reports,
                                     CompileResult compileResult,
                                     Instant runStart, String outcomeStatus) {
        int totalIterations = reports.stream()
                .mapToInt(WorkerReport::getIterationCount)
                .sum();

        Set<String> allFiles = new HashSet<>();
        for (WorkerReport r : reports) {
            if (r.getFilesCreated()  != null) allFiles.addAll(r.getFilesCreated());
            if (r.getFilesModified() != null) allFiles.addAll(r.getFilesModified());
        }
        int totalFilesWritten = allFiles.size();

        // compileResult.attempts() is total compile-and-fix cycles; attempts - 1 = error rounds
        int compileErrors = compileResult != null && compileResult.attempts() > 1
                ? compileResult.attempts() - 1 : 0;

        int durationSeconds = runStart != null
                ? (int) Duration.between(runStart, Instant.now()).toSeconds() : 0;

        List<String> reasons = new ArrayList<>();
        if (totalIterations > ESCALATE_ITERATIONS)
            reasons.add("iterations=" + totalIterations + " > " + ESCALATE_ITERATIONS);
        if (totalFilesWritten > ESCALATE_FILES)
            reasons.add("files=" + totalFilesWritten + " > " + ESCALATE_FILES);
        if (compileErrors > ESCALATE_COMPILE_ERRORS)
            reasons.add("compileErrors=" + compileErrors + " > " + ESCALATE_COMPILE_ERRORS);
        if (durationSeconds > ESCALATE_DURATION.toSeconds())
            reasons.add("duration=" + durationSeconds + "s > " + ESCALATE_DURATION.toSeconds() + "s");

        boolean escalate = !reasons.isEmpty();
        String reason = escalate ? String.join(", ", reasons) : null;

        if (escalate) {
            log.info("[TriageEscalation] Run {} should have been MULTI_TASK — {}",
                    runId, reason);
        }

        // Persist to triage_outcome for analytics
        recordOutcome(runId, "SINGLE_SHOT", escalate, reason,
                totalIterations, totalFilesWritten, compileErrors, durationSeconds, outcomeStatus);

        return new EscalationResult(escalate, reason,
                totalIterations, totalFilesWritten, compileErrors, durationSeconds);
    }

    private void recordOutcome(String runId, String classification, boolean escalated,
                               String reason, int iterations, int files, int compileErrors,
                               int durationSec, String status) {
        try {
            jdbc.update("""
                    INSERT INTO triage_outcome
                      (run_id, original_classification, escalated, escalation_reason,
                       iterations, files_written, compile_errors, duration_seconds, outcome_status)
                    VALUES (CAST(? AS UUID), ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """,
                    runId, classification, escalated, reason,
                    iterations, files, compileErrors, durationSec, status);
        } catch (Exception e) {
            log.warn("[TriageEscalation] Failed to record outcome for run {}: {}", runId, e.getMessage());
        }
    }
}
