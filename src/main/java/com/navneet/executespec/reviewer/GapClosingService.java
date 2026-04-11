package com.navneet.executespec.reviewer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.model.TaskDefinition;
import com.navneet.executespec.compiler.CompilerAgent;
import com.navneet.executespec.harness.BackgroundValidationService;
import com.navneet.executespec.harness.RunCostBudgetService;
import com.navneet.executespec.harness.WorkerPool;
import com.navneet.executespec.reviewer.model.AccuracyReport;
import com.navneet.executespec.reviewer.model.ReviewIssue;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Post-review gap-closing loop. Fires during background validation when the
 * accuracy score is below TARGET_SCORE (90). Iteratively:
 *
 * <ol>
 *   <li>Extracts unimplemented requirements and missing endpoints from the AccuracyReport.</li>
 *   <li>Creates targeted AgentTask objects (one per gap, up to MAX_GAPS_PER_CYCLE).</li>
 *   <li>Runs them through the worker pool in parallel.</li>
 *   <li>Re-compiles and re-scores.</li>
 *   <li>Repeats up to MAX_CYCLES times or until score ≥ TARGET_SCORE.</li>
 * </ol>
 *
 * <p>Gap tasks use the "LOW" complexity tier and instruct the worker to ADD missing
 * functionality without rewriting existing working code. They are persisted to the
 * {@code agent_task} table and visible in the UI task timeline.
 *
 * <p>The loop is budget-aware: it stops immediately if {@link RunCostBudgetService#isOverBudget}
 * returns true, ensuring gap-closing never exceeds the user's approved cost cap.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GapClosingService {

    /** Maximum gap-closing iterations before giving up. */
    static final int MAX_CYCLES = 3;

    /** Accuracy score at which gap-closing considers the run "good enough". */
    static final int TARGET_SCORE = 90;

    /** Max number of gap tasks created per cycle — avoids flooding the worker pool. */
    static final int MAX_GAPS_PER_CYCLE = 5;

    private final WorkerPool workerPool;
    private final CompilerAgent compilerAgent;
    private final AccuracyAssessmentService accuracyAssessmentService;
    private final StateStore stateStore;
    private final SseBroadcaster broadcaster;
    private final RunCostBudgetService runCostBudgetService;
    private final BackgroundValidationService backgroundValidationService;
    private final ObjectMapper mapper;

    // ──────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Iteratively closes gaps identified in the accuracy report.
     * Runs inside the background validation virtual thread — safe to block.
     *
     * @param run           the pipeline run
     * @param initial       accuracy report produced by the review phase
     * @param originalGoal  the user's original specification text
     * @param existingIssues review issues from the reviewer (passed to re-score)
     * @param reviewModel   the PLANNING-tier model to use for re-scoring
     * @return the final AccuracyReport after all gap-closing cycles
     */
    public AccuracyReport closeGaps(PipelineRun run, AccuracyReport initial,
                                    String originalGoal, List<ReviewIssue> existingIssues,
                                    ResolvedModel reviewModel) {
        String runId = run.getRunId();
        AccuracyReport current = initial;
        // Track gap texts already attempted across cycles — each gap gets at most 1 attempt
        Set<String> attemptedGapTexts = new HashSet<>();

        for (int cycle = 1; cycle <= MAX_CYCLES; cycle++) {

            if (current.getSpecAdherenceScore() >= TARGET_SCORE) {
                log.info("[GapCloser][{}] Score {}/100 meets target — stopping", runId,
                        current.getSpecAdherenceScore());
                break;
            }

            if (runCostBudgetService.isOverBudget(runId)) {
                log.warn("[GapCloser][{}] Run is over budget — skipping remaining gap-closing cycles", runId);
                break;
            }

            List<GapItem> gaps = extractGaps(current).stream()
                    .filter(g -> !attemptedGapTexts.contains(g.text()))
                    .toList();
            if (gaps.isEmpty()) {
                log.info("[GapCloser][{}] No new actionable gaps found — stopping early", runId);
                break;
            }

            log.info("[GapCloser][{}] Cycle {}/{} — {} gap(s) identified (score: {}/100)",
                    runId, cycle, MAX_CYCLES, gaps.size(), current.getSpecAdherenceScore());
            backgroundValidationService.setPhase(runId, "GAP_CLOSING_CYCLE_" + cycle);
            broadcaster.emit(runId, RunEvent.log(runId, "gap-closer",
                    String.format("🔍 Gap-closing cycle %d/%d — %d gap(s) (score: %d/100 → target: %d)",
                            cycle, MAX_CYCLES, gaps.size(), current.getSpecAdherenceScore(), TARGET_SCORE)));

            // Mark gaps as attempted before running — prevents duplicate tasks on re-score
            gaps.forEach(g -> attemptedGapTexts.add(g.text()));

            // Build, persist, and execute gap tasks
            List<AgentTask> tasks = buildGapTasks(gaps, run, cycle);
            tasks.forEach(stateStore::saveTask);
            tasks.forEach(t -> broadcaster.emit(runId,
                    RunEvent.taskStatus(runId, t.getTaskId(), "PENDING")));

            workerPool.runAll(tasks, runId, run.getWorkerModel() != null
                    ? run.getWorkerModel() : "WORKHORSE");
            compilerAgent.runCompileAndFix(runId);

            // Re-score with updated codebase
            AccuracyReport rescored = accuracyAssessmentService.assess(
                    runId, run.getAiProvider(), reviewModel, originalGoal, existingIssues);

            log.info("[GapCloser][{}] Cycle {} complete — score: {}/100 ({}) [was: {}/100]",
                    runId, cycle, rescored.getSpecAdherenceScore(),
                    rescored.getOverallAccuracyGrade(), current.getSpecAdherenceScore());
            broadcaster.emit(runId, RunEvent.log(runId, "gap-closer",
                    String.format("✅ Cycle %d complete — score: %d/100 (%s)",
                            cycle, rescored.getSpecAdherenceScore(), rescored.getOverallAccuracyGrade())));

            current = rescored;
        }

        return current;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Gap extraction
    // ──────────────────────────────────────────────────────────────────────────

    private List<GapItem> extractGaps(AccuracyReport report) {
        List<GapItem> gaps = new ArrayList<>();

        // Priority 1: missing endpoints — spec explicitly named them; high confidence they belong
        if (report.getEndpointCoverage() != null
                && report.getEndpointCoverage().getMissing() != null) {
            report.getEndpointCoverage().getMissing().stream()
                    .limit(MAX_GAPS_PER_CYCLE)
                    .map(ep -> new GapItem(GapType.MISSING_ENDPOINT, ep))
                    .forEach(gaps::add);
        }

        // Priority 2: missing functional requirements (skip docs/README — they don't hurt much)
        if (gaps.size() < MAX_GAPS_PER_CYCLE && report.getMissingRequirements() != null) {
            report.getMissingRequirements().stream()
                    .filter(req -> !isDocGap(req))
                    .limit(MAX_GAPS_PER_CYCLE - gaps.size())
                    .map(req -> new GapItem(GapType.MISSING_REQUIREMENT, req))
                    .forEach(gaps::add);
        }

        // Priority 3: partial requirements (exists but incomplete)
        if (gaps.size() < MAX_GAPS_PER_CYCLE && report.getPartialRequirements() != null) {
            report.getPartialRequirements().stream()
                    .filter(req -> !isDocGap(req))
                    .limit(MAX_GAPS_PER_CYCLE - gaps.size())
                    .map(req -> new GapItem(GapType.PARTIAL_REQUIREMENT, req))
                    .forEach(gaps::add);
        }

        return gaps;
    }

    /** Returns true for documentation-only gaps that don't affect the functional score. */
    private boolean isDocGap(String req) {
        if (req == null) return false;
        String lower = req.toLowerCase();
        return lower.contains("readme") || lower.contains("documentation")
                || lower.contains("comment") || lower.contains("changelog")
                || lower.contains("javadoc") || lower.contains("docstring");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Task construction
    // ──────────────────────────────────────────────────────────────────────────

    private List<AgentTask> buildGapTasks(List<GapItem> gaps, PipelineRun run, int cycle) {
        List<AgentTask> tasks = new ArrayList<>();
        for (int i = 0; i < gaps.size(); i++) {
            GapItem gap = gaps.get(i);
            String taskId = run.getRunId() + "_gap_c" + cycle + "_" + i;
            String title = gap.type() == GapType.MISSING_ENDPOINT
                    ? "Implement endpoint: " + gap.text()
                    : "Close gap: " + truncate(gap.text(), 60);

            TaskDefinition def = buildTaskDef(taskId, title, gap, run);
            String taskJson;
            try {
                taskJson = mapper.writeValueAsString(def);
            } catch (Exception e) {
                log.warn("[GapCloser][{}] Failed to serialize gap task def for {}: {}",
                        run.getRunId(), taskId, e.getMessage());
                continue;
            }

            AgentTask task = AgentTask.builder()
                    .taskId(taskId)
                    .runId(run.getRunId())
                    .title(title)
                    .status("PENDING")
                    .complexity("MEDIUM")
                    .assignedModel(run.getWorkerModel())
                    .taskJson(taskJson)
                    .build();
            tasks.add(task);
        }
        return tasks;
    }

    private TaskDefinition buildTaskDef(String taskId, String title, GapItem gap, PipelineRun run) {
        String specContext = truncate(run.getUserRequest(), 1_000);
        String objective = switch (gap.type()) {
            case MISSING_ENDPOINT ->
                    "The following HTTP endpoint is required by the specification but is MISSING from the codebase. "
                    + "Implement it without modifying any existing working endpoints or routes.\n\n"
                    + "Required endpoint: " + gap.text() + "\n\n"
                    + "Original specification for context:\n" + specContext;
            case MISSING_REQUIREMENT ->
                    "The following requirement from the specification has NOT been implemented yet. "
                    + "Add it without breaking existing functionality.\n\n"
                    + "Missing requirement: " + gap.text() + "\n\n"
                    + "Original specification for context:\n" + specContext;
            case PARTIAL_REQUIREMENT ->
                    "The following requirement exists in the code but is INCOMPLETE. "
                    + "Complete it without rewriting what is already working correctly.\n\n"
                    + "Partial requirement: " + gap.text() + "\n\n"
                    + "Original specification for context:\n" + specContext;
        };

        String commitMsg = "fix(gap): " + truncate(gap.text(), 60)
                .replaceAll("[^a-zA-Z0-9 /_:.-]", "");

        return TaskDefinition.builder()
                .taskId(taskId)
                .title(title)
                .objective(objective)
                .boundaries("IMPORTANT: Do NOT rewrite or regenerate existing files from scratch. "
                        + "Only ADD the missing functionality. Read existing files first to understand "
                        + "the project structure and conventions, then make the minimal change needed. "
                        + "Commit with message: '" + commitMsg + "'")
                .complexity("MEDIUM")
                .dependsOn(List.of())
                .ownedFiles(List.of())
                .sharedFiles(List.of())
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal types
    // ──────────────────────────────────────────────────────────────────────────

    enum GapType {
        MISSING_ENDPOINT,
        MISSING_REQUIREMENT,
        PARTIAL_REQUIREMENT
    }

    record GapItem(GapType type, String text) {}
}
