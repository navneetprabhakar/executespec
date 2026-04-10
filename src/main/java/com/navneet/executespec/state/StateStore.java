package com.navneet.executespec.state;
import com.navneet.executespec.router.ModelRegistry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.model.ReviewReport;
import com.navneet.executespec.reviewer.model.ReviewReportV2;
import com.navneet.executespec.agent.model.TaskDefinition;
import com.navneet.executespec.agent.model.TaskPlan;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.state.entity.*;
import com.navneet.executespec.state.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class StateStore {

    private final PipelineRunRepository runRepo;
    private final AgentTaskRepository taskRepo;
    private final AgentMessageRepository messageRepo;
    private final GateEventRepository gateEventRepo;
    private final CompactionEventRepository compactionEventRepo;
    private final ObjectMapper mapper;

    // ── Tier 5 repositories ───────────────────────────────────
    private final RunRecoveryRepository recoveryRepo;
    private final PromptAuditRepository promptAuditRepo;
    private final AppUserRepository appUserRepo;
    private final PlanVersionRepository planVersionRepo;
    private final ModelRegistry modelRegistry;

    // ── Run lifecycle ─────────────────────────────────────────

    /** CLI path: creates run with no repo info. */
    public PipelineRun createRun(String request, String plannerModel,
                                  String workerModel, String reviewerModel) {
        return createRun(request, "MANUAL", null, null, plannerModel, workerModel, reviewerModel);
    }

    /** Full path used by REST API. */
    public PipelineRun createRun(String request, String source, String repoUrl, String repoMode,
                                  String plannerModel, String workerModel, String reviewerModel) {
        return runRepo.save(PipelineRun.builder()
            .runId(UUID.randomUUID().toString())
            .userRequest(request)
            .status("PLANNING")
            .plannerModel(plannerModel)
            .workerModel(workerModel)
            .reviewerModel(reviewerModel)
            .sourceType(source != null ? source : "MANUAL")
            .repoUrl(repoUrl)
            .repoMode(repoMode)
            .queuePosition(0)
            .startedAt(Instant.now())
            .totalInputTokens(0)
            .totalOutputTokens(0)
            .build());
    }

    public PipelineRun save(PipelineRun run) {
        return runRepo.save(run);
    }

    public void savePlan(String runId, TaskPlan plan) {
        PipelineRun run = runRepo.findById(runId).orElseThrow();

        // ── Make task IDs globally unique ────────────────────────────────────
        // agent_task PK is task_id alone (not composite with run_id).
        // Planner generates predictable IDs like "project_setup", "readme" — if
        // two runs use the same IDs, a stale pipeline thread from run A can
        // overwrite run B's tasks.  Prefixing with the run hash prevents this.
        String prefix = runId.substring(0, 8) + "_";

        // Build old→new ID mapping so we can rewrite depends_on references
        Map<String, String> idMap = new java.util.HashMap<>();
        plan.getTasks().forEach(t -> idMap.put(t.getTaskId(), prefix + t.getTaskId()));

        // Rewrite task IDs and dependency references in-place
        plan.getTasks().forEach(t -> {
            if (t.getDependsOn() != null) {
                t.setDependsOn(t.getDependsOn().stream()
                    .map(dep -> idMap.getOrDefault(dep, prefix + dep))
                    .toList());
            }
            t.setTaskId(idMap.get(t.getTaskId()));
        });

        run.setPlanJson(toJson(plan));
        if (plan.getPlanTitle() != null)   run.setRunTitle(plan.getPlanTitle());
        if (plan.getPlanSummary() != null) run.setRunSummary(plan.getPlanSummary());
        run.setStatus("AWAITING_APPROVAL");
        run.setCurrentGate("PLAN_APPROVAL");
        run.setTechStack(detectTechStack(plan, run.getUserRequest()));
        runRepo.save(run);

        plan.getTasks().forEach(t -> taskRepo.save(AgentTask.builder()
            .taskId(t.getTaskId())
            .runId(runId)
            .title(t.getTitle())
            .complexity(t.getComplexity())
            .assignedModel(t.getAssignedModel())   // planner routes: Sonnet for HIGH/MEDIUM, Haiku for LOW
            .status("PENDING")
            .taskJson(toJson(t))
            .compactionCount(0)
            .build()));

        savePlanVersion(runId, "PLANNER", null);
    }

    public void markApproved(String runId) {
        // Use direct update to avoid overwriting approvedExecutionMode set concurrently
        // by the controller thread (race condition: findById may return stale entity)
        runRepo.updateStatusAndClearGate(runId, "WORKING");
    }

    // ── Plan versioning ──────────────────────────────────────

    public void savePlanVersion(String runId, String source, String createdBy) {
        PipelineRun run = findRun(runId).orElseThrow();
        if (run.getPlanJson() == null) return;
        planVersionRepo.save(com.navneet.executespec.state.entity.PlanVersion.builder()
            .id(java.util.UUID.randomUUID().toString())
            .runId(runId)
            .version(run.getPlanVersion())
            .planJson(run.getPlanJson())
            .source(source)
            .createdBy(createdBy)
            .createdAt(java.time.Instant.now())
            .build());
    }

    public void incrementPlanVersion(String runId) {
        PipelineRun run = findRun(runId).orElseThrow();
        run.setPlanVersion(run.getPlanVersion() + 1);
        save(run);
    }

    /**
     * Deletes all PENDING tasks for a run, then saves a new plan and creates
     * fresh tasks from it. Used when the user edits the plan at the approval gate.
     */
    public void recreateTasksFromPlan(String runId, TaskPlan plan) {
        List<AgentTask> existingTasks = getTasksForRun(runId);
        for (AgentTask task : existingTasks) {
            if ("PENDING".equals(task.getStatus())) {
                taskRepo.deleteById(task.getTaskId());
            }
        }
        savePlan(runId, plan);
    }

    public void markRejected(String runId, String reason) {
        finaliseRun(runRepo.findById(runId).orElseThrow(), "FAILED", "Rejected by user: " + reason);
    }

    public void markReviewing(String runId) {
        PipelineRun run = runRepo.findById(runId).orElseThrow();
        String current = run.getStatus();
        // Guard: never regress a terminal state back to REVIEWING
        if ("COMPLETE".equals(current) || "FAILED".equals(current) || "PARTIAL".equals(current)) {
            log.warn("[StateStore] Refusing to regress run {} from {} to REVIEWING", runId, current);
            return;
        }
        run.setStatus("REVIEWING");
        runRepo.save(run);
    }

    public void saveReview(String runId, ReviewReport review) {
        PipelineRun run = runRepo.findById(runId).orElseThrow();
        run.setReviewJson(toJson(review));
        // Status stays REVIEWING — merge is automatic (no gate).
        // PipelineHarness.markComplete() advances the status after report generation.
        runRepo.save(run);
    }

    /** Save V2 review report (used after migration to ReviewerAgentV2). */
    public void saveReviewV2(String runId, ReviewReportV2 review) {
        PipelineRun run = runRepo.findById(runId).orElseThrow();
        run.setReviewJson(toJson(review));
        // Status stays REVIEWING — merge is automatic (no gate).
        // PipelineHarness.markComplete() advances the status after report generation.
        runRepo.save(run);
    }

    /** Deserialize V2 review report from run's reviewJson field. */
    public ReviewReportV2 getReviewReportV2(String runId) {
        return runRepo.findById(runId)
            .filter(r -> r.getReviewJson() != null)
            .map(r -> {
                try {
                    return mapper.readValue(r.getReviewJson(), ReviewReportV2.class);
                } catch (JsonProcessingException e) {
                    log.warn("Failed to deserialize ReviewReportV2 for run {}: {}", runId, e.getMessage());
                    return null;
                }
            })
            .orElse(null);
    }

    /**
     * Marks the run complete. If any tasks are BLOCKED or PARTIAL, the run is marked
     * PARTIAL (merged but incomplete) rather than COMPLETE so the UI reflects reality.
     */
    public void markComplete(String runId) {
        PipelineRun run = runRepo.findById(runId).orElseThrow();
        boolean hasIncomplete = taskRepo.findByRunId(runId).stream()
            .anyMatch(t -> "BLOCKED".equals(t.getStatus()) || "PARTIAL".equals(t.getStatus()));
        finaliseRun(run,
            hasIncomplete ? "PARTIAL" : "COMPLETE",
            hasIncomplete ? "Merged with incomplete tasks (BLOCKED/PARTIAL)" : null);
    }

    public void markFailed(String runId, String reason) {
        finaliseRun(runRepo.findById(runId).orElseThrow(), "FAILED", reason);
        // Fail all non-terminal tasks so UI does not show stale WORKING/LOCKED state
        taskRepo.findByRunId(runId).forEach(task -> {
            String s = task.getStatus();
            if (!"COMPLETE".equals(s) && !"FAILED".equals(s) && !"PARTIAL".equals(s)) {
                task.setStatus("FAILED");
                taskRepo.save(task);
            }
        });
    }

    /**
     * Marks a run as PARTIAL — workers ran but some tasks are incomplete.
     * Unlike {@link #markFailed}, PARTIAL means the pipeline executed but could not
     * finish all tasks. The UI uses this to offer per-task retry buttons.
     */
    public void markPartial(String runId, String reason) {
        finaliseRun(runRepo.findById(runId).orElseThrow(), "PARTIAL", reason);
    }

    // ── Tier 8: API spec + preview markers ───────────────────────────────────

    /** Marks the run as having a generated OpenAPI spec (set after ApiTestSuiteAgent writes openapi.yaml). */
    public void markApiSpecReady(String runId) {
        runRepo.findById(runId).ifPresent(r -> {
            r.setHasApiSpec(true);
            runRepo.save(r);
        });
    }

    /** Marks the run as having a built frontend preview available at the given serve directory. */
    public void markPreviewReady(String runId, String serveDir) {
        runRepo.findById(runId).ifPresent(r -> {
            r.setHasFrontend(true);
            r.setPreviewServeDir(serveDir != null ? serveDir : "");
            r.setPreviewStatus("READY");
            runRepo.save(r);
        });
    }

    /**
     * Resets a PARTIAL/BLOCKED task for force-retry and puts the run back to WORKING.
     * Clears completion state so the worker starts fresh.
     */
    public void markRetrying(String runId, String taskId) {
        markRetryingWithFeedback(runId, taskId, null);
    }

    /**
     * Resets a task for auto-remediation after a REWORK verdict.
     * Injects {@code reworkFeedback} into the task's TaskDefinition JSON so the
     * worker's next attempt knows exactly what the reviewer flagged.
     * Pass {@code null} feedback to behave identically to {@link #markRetrying}.
     */
    public void markRetryingWithFeedback(String runId, String taskId, String reworkFeedback) {
        taskRepo.findById(taskId).ifPresent(task -> {
            // Subtract old token counts from run totals before resetting (prevents double-counting)
            int oldIn = task.getInputTokens() != null ? task.getInputTokens() : 0;
            int oldOut = task.getOutputTokens() != null ? task.getOutputTokens() : 0;
            if (oldIn > 0 || oldOut > 0) {
                runRepo.findById(runId).ifPresent(run -> {
                    run.setTotalInputTokens(Math.max(0, run.getTotalInputTokens() - oldIn));
                    run.setTotalOutputTokens(Math.max(0, run.getTotalOutputTokens() - oldOut));
                    runRepo.save(run);
                });
            }
            task.setInputTokens(0);
            task.setOutputTokens(0);
            task.setCacheReadTokens(0);
            task.setCacheWriteTokens(0);

            // Inject reviewer feedback into the stored TaskDefinition JSON so
            // WorkerAgent.buildInitialUserMessage() can surface it on the next attempt.
            if (reworkFeedback != null && !reworkFeedback.isBlank() && task.getTaskJson() != null) {
                try {
                    TaskDefinition taskDef = mapper.readValue(task.getTaskJson(), TaskDefinition.class);
                    taskDef.setReworkFeedback(reworkFeedback);
                    task.setTaskJson(mapper.writeValueAsString(taskDef));
                } catch (JsonProcessingException ex) {
                    // Non-fatal — worker will still run without explicit feedback
                    org.slf4j.LoggerFactory.getLogger(StateStore.class)
                        .warn("[StateStore] Could not inject rework feedback into task {}: {}", taskId, ex.getMessage());
                }
            }
            task.setStatus("PENDING");
            task.setWorkerReportJson(null);
            task.setCompletedAt(null);
            task.setStuckDetectedAt(null);
            task.setLastActivityAt(Instant.now());
            task.setRecoveryAttempt((task.getRecoveryAttempt() != null ? task.getRecoveryAttempt() : 0) + 1);
            taskRepo.save(task);
        });
        runRepo.findById(runId).ifPresent(run -> {
            run.setStatus("WORKING");
            run.setCompletedAt(null);
            run.setFailureReason(null);
            run.setCurrentGate(null);
            runRepo.save(run);
        });
    }

    // ── Task lifecycle ────────────────────────────────────────

    public boolean lockTask(String taskId, String model, String branchName) {
        return taskRepo.lockAndStart(taskId, model, branchName, Instant.now()) > 0;
    }

    public void completeTask(String taskId, WorkerReport report) {
        AgentTask task = taskRepo.findById(taskId).orElseThrow();
        task.setStatus(report.getStatus().equals("complete") ? "COMPLETE" : report.getStatus().toUpperCase());
        task.setWorkerReportJson(toJson(report));
        task.setInputTokens(report.getInputTokens());
        task.setOutputTokens(report.getOutputTokens());
        task.setCacheReadTokens(report.getCacheReadTokens());
        task.setCacheWriteTokens(report.getCacheWriteTokens());
        // Persist actual provider/model used (may differ from assignedModel due to failover/escalation)
        if (report.getProvider() != null) task.setProvider(report.getProvider());
        if (report.getModel() != null) task.setModel(report.getModel());
        if (report.getSummary() != null) task.setCompletionNotes(report.getSummary());
        task.setCompletedAt(Instant.now());
        task.setDurationMs(task.getStartedAt() != null
            ? Duration.between(task.getStartedAt(), Instant.now()).toMillis() : 0);
        taskRepo.save(task);

        // Accumulate totals on run (atomic SQL to avoid read-modify-write race)
        runRepo.addTokens(task.getRunId(), report.getInputTokens(), report.getOutputTokens());
    }

    /** Adds raw input/output token counts to the run totals (used by Planner + Reviewer). */
    @Transactional
    public void addTokensToRun(String runId, int inputTok, int outputTok) {
        runRepo.addTokens(runId, inputTok, outputTok);
    }

    /** Stores per-agent token breakdown + actual provider/model (planner, reviewer, compiler). */
    public void setAgentTokens(String runId, String agentRole,
                               int inputTok, int outputTok,
                               String provider, String model) {
        runRepo.findById(runId).ifPresent(run -> {
            switch (agentRole) {
                case "planner" -> {
                    run.setPlannerInputTokens((run.getPlannerInputTokens() != null ? run.getPlannerInputTokens() : 0) + inputTok);
                    run.setPlannerOutputTokens((run.getPlannerOutputTokens() != null ? run.getPlannerOutputTokens() : 0) + outputTok);
                    run.setPlannerProvider(provider);
                    run.setPlannerActualModel(model);
                }
                case "reviewer" -> {
                    run.setReviewerInputTokens((run.getReviewerInputTokens() != null ? run.getReviewerInputTokens() : 0) + inputTok);
                    run.setReviewerOutputTokens((run.getReviewerOutputTokens() != null ? run.getReviewerOutputTokens() : 0) + outputTok);
                    run.setReviewerProvider(provider);
                    run.setReviewerActualModel(model);
                }
                case "compiler" -> {
                    run.setCompilerInputTokens((run.getCompilerInputTokens() != null ? run.getCompilerInputTokens() : 0) + inputTok);
                    run.setCompilerOutputTokens((run.getCompilerOutputTokens() != null ? run.getCompilerOutputTokens() : 0) + outputTok);
                    run.setCompilerProvider(provider);
                    run.setCompilerActualModel(model);
                }
                default -> log.warn("Unknown agent role for token tracking: {}", agentRole);
            }
            runRepo.save(run);
        });
    }

    /** Saves the cache-aware actual cost computed post-run by CostEstimator. */
    public void setCostEstimate(String runId, double lowUsd, double highUsd) {
        runRepo.findById(runId).ifPresent(run -> {
            run.setCostEstimateLow(lowUsd);
            run.setCostEstimateHigh(highUsd);
            runRepo.save(run);
        });
    }

    public void setUiStyle(String runId, String uiStyleJson) {
        runRepo.findById(runId).ifPresent(run -> {
            run.setUiStyle(uiStyleJson);
            runRepo.save(run);
        });
    }

    /** Persist the number of run credits charged for this run. */
    public void setCreditsCharged(String runId, int credits) {
        runRepo.findById(runId).ifPresent(run -> {
            run.setCreditsCharged(credits);
            runRepo.save(run);
        });
    }

    /** Persist the hard-cap USD ceiling for this run. */
    public void setHardCapUsd(String runId, double hardCap) {
        runRepo.findById(runId).ifPresent(run -> {
            run.setHardCapUsd(hardCap);
            runRepo.save(run);
        });
    }

    /** Persist batch cost fields computed at plan time. */
    public void setBatchCostFields(String runId, Integer batchCredits, boolean batchAvailable, int creditSavings) {
        runRepo.findById(runId).ifPresent(run -> {
            run.setBatchCredits(batchCredits);
            run.setBatchAvailable(batchAvailable);
            run.setCreditSavings(creditSavings);
            runRepo.save(run);
        });
    }

    // ── Batch execution state ──────────────────────────────────────────────

    public void setBatchInfo(String runId, String batchId, String batchProvider) {
        runRepo.findById(runId).ifPresent(run -> {
            run.setBatchId(batchId);
            run.setBatchProvider(batchProvider);
            run.setBatchSubmittedAt(java.time.Instant.now());
            runRepo.save(run);
        });
    }

    public void setApprovedExecutionMode(String runId, String mode) {
        // Atomic JPQL update — avoids read-modify-write race with clearGateSync/markApproved
        runRepo.updateApprovedExecutionMode(runId, mode);
        log.info("[Run {}] Execution mode set to {} (atomic)", runId, mode);
    }

    public void clearCurrentGate(String runId) {
        runRepo.clearCurrentGate(runId);
    }

    public void updateBatchProgress(String runId, int completed, int total) {
        runRepo.findById(runId).ifPresent(run -> {
            run.setBatchProgressCompleted(completed);
            run.setBatchProgressTotal(total);
            runRepo.save(run);
        });
    }

    public java.util.List<PipelineRun> findRunsByStatusIn(java.util.List<String> statuses) {
        return runRepo.findByStatusIn(statuses);
    }

    // ── Batch task helpers ──────────────────────────────────────────────────

    /** Mark a task as COMPLETE (batch mode — no WorkerReport). */
    public void markTaskComplete(String taskId) {
        taskRepo.findById(taskId).ifPresent(task -> {
            task.setStatus("COMPLETE");
            task.setCompletedAt(Instant.now());
            if (task.getStartedAt() != null) {
                task.setDurationMs(Duration.between(task.getStartedAt(), Instant.now()).toMillis());
            }
            taskRepo.save(task);
        });
    }

    /** Set model and provider on a task (used by batch resume to attribute token usage). */
    public void setTaskModelProvider(String taskId, String model, String provider) {
        taskRepo.findById(taskId).ifPresent(task -> {
            if (model != null) task.setModel(model);
            if (provider != null) task.setProvider(provider);
            taskRepo.save(task);
        });
    }

    /** Mark a task as FAILED with a reason (batch mode). */
    public void markTaskFailed(String taskId, String reason) {
        taskRepo.findById(taskId).ifPresent(task -> {
            task.setStatus("FAILED");
            task.setLastLlmFailure(reason);
            task.setCompletedAt(Instant.now());
            taskRepo.save(task);
        });
    }

    /** Reset a failed task for real-time retry (batch fallback). */
    public void resetTaskForRetry(String taskId) {
        taskRepo.findById(taskId).ifPresent(task -> {
            task.setStatus("PENDING");
            task.setLastLlmFailure(null);
            task.setCompletedAt(null);
            task.setWorkerReportJson(null);
            taskRepo.save(task);
        });
    }

    /** Update task token counts (batch mode — no WorkerReport). */
    public void updateTaskTokens(String taskId, int inputTokens, int outputTokens) {
        taskRepo.findById(taskId).ifPresent(task -> {
            task.setInputTokens(inputTokens);
            task.setOutputTokens(outputTokens);
            taskRepo.save(task);
        });
    }

    public void setActualCost(String runId, double costUsd) {
        runRepo.findById(runId).ifPresent(run -> {
            run.setActualCostUsd(costUsd);
            runRepo.save(run);
        });
    }

    public void updateTaskReadmeStatus(String taskId, boolean readmeUpdated) {
        taskRepo.findById(taskId).ifPresent(task -> {
            task.setReadmeUpdated(readmeUpdated);
            taskRepo.save(task);
        });
    }

    public void recordCompaction(String taskId, String checkpointPath) {
        AgentTask task = taskRepo.findById(taskId).orElseThrow();
        task.setCompactionCount(task.getCompactionCount() + 1);
        taskRepo.save(task);

        // Record compaction event for Tier 4 timeline visualization
        compactionEventRepo.save(CompactionEvent.builder()
            .id(UUID.randomUUID().toString())
            .runId(task.getRunId())
            .taskId(taskId)
            .occurredAt(Instant.now())
            .build());
    }

    public List<AgentTask> getPendingTasks(String runId) {
        return taskRepo.findByRunIdAndStatusIn(runId, List.of("PENDING"));
    }

    public List<AgentTask> getIncompleteTasks(String runId) {
        return taskRepo.findByRunIdAndStatusIn(runId,
            List.of("PENDING", "LOCKED", "WORKING", "PARTIAL"));
    }

    public List<AgentTask> getAllTasks(String runId) {
        return taskRepo.findByRunId(runId);
    }

    // ── Gate events (Tier 4 timeline) ─────────────────────────

    public String recordGateOpened(String runId, String gateType) {
        String id = UUID.randomUUID().toString();
        gateEventRepo.save(GateEvent.builder()
            .id(id).runId(runId).gateType(gateType).openedAt(Instant.now())
            .build());
        return id;
    }

    public void recordGateResolved(String gateEventId, boolean approved) {
        gateEventRepo.findById(gateEventId).ifPresent(ge -> {
            ge.setResolvedAt(Instant.now());
            ge.setApproved(approved);
            gateEventRepo.save(ge);
        });
    }

    // ── Resume ────────────────────────────────────────────────

    public Optional<PipelineRun> findResumableRun() {
        return runRepo.findTopByStatusInOrderByStartedAtDesc(
            List.of("PLANNING", "AWAITING_APPROVAL", "WORKING", "STUCK", "REVIEWING", "AWAITING_MERGE"));
    }

    // ── Tier 3: REST API queries ──────────────────────────────

    public Page<PipelineRun> listRuns(String status, Pageable pageable) {
        if (status != null && !status.isBlank()) {
            return runRepo.findByStatusOrderByStartedAtDesc(status, pageable);
        }
        return runRepo.findAllByOrderByStartedAtDesc(pageable);
    }

    /** Visibility-filtered variant — applies a per-user Specification on top of the status filter. */
    public Page<PipelineRun> listRuns(String status, Pageable pageable,
                                      Specification<PipelineRun> visSpec) {
        return listRuns(status, null, pageable, visSpec);
    }

    /**
     * Visibility-filtered + project-filtered variant.
     * Stacks status, projectId, and visibility predicates.
     */
    public Page<PipelineRun> listRuns(String status, String projectId, Pageable pageable,
                                      Specification<PipelineRun> visSpec) {
        return listRuns(status, projectId, false, pageable, visSpec);
    }

    /**
     * Visibility-filtered + project-filtered + archive-filtered variant.
     * When {@code archived} is true, returns only archived runs; when false, excludes them.
     */
    public Page<PipelineRun> listRuns(String status, String projectId, boolean archived,
                                      Pageable pageable, Specification<PipelineRun> visSpec) {
        Specification<PipelineRun> combined = visSpec != null
                ? Specification.where(visSpec)
                : Specification.<PipelineRun>where(null);

        // Archive filter: archived=true → only archived, false → only non-archived
        if (archived) {
            combined = combined.and((root, q, cb) -> cb.isNotNull(root.get("archivedAt")));
        } else {
            combined = combined.and((root, q, cb) -> cb.isNull(root.get("archivedAt")));
        }

        if (status != null && !status.isBlank()) {
            final String s = status;
            combined = combined.and((root, q, cb) -> cb.equal(root.get("status"), s));
        }
        if ("__none__".equals(projectId)) {
            combined = combined.and((root, q, cb) -> cb.isNull(root.get("projectId")));
        } else if (projectId != null && !projectId.isBlank()) {
            final String pid = projectId;
            combined = combined.and((root, q, cb) -> cb.equal(root.get("projectId"), pid));
        }
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "startedAt"));
        return runRepo.findAll(combined, sorted);
    }

    /**
     * Archive a run. Only terminal runs (COMPLETE, FAILED, CANCELLED, PARTIAL) can be archived.
     * This is irreversible.
     */
    public PipelineRun archiveRun(String runId) {
        PipelineRun run = runRepo.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        if (run.isArchived()) {
            throw new IllegalStateException("Run is already archived");
        }
        if (!List.of("COMPLETE", "FAILED", "CANCELLED", "PARTIAL").contains(run.getStatus())) {
            throw new IllegalStateException("Only terminal runs can be archived. Current status: " + run.getStatus());
        }
        run.markArchived();
        return runRepo.save(run);
    }

    public Optional<PipelineRun> findRun(String runId) {
        return runRepo.findById(runId);
    }

    public PipelineRun saveRun(PipelineRun run) {
        return runRepo.save(run);
    }

    public boolean cancelRun(String runId) {
        return runRepo.findById(runId).map(run -> {
            if (List.of("COMPLETE", "FAILED", "CANCELLED", "PAUSED").contains(run.getStatus())) return false;
            finaliseRun(run, "CANCELLED", "Cancelled by user");
            taskRepo.findByRunId(runId).forEach(task -> {
                String s = task.getStatus();
                if (!"COMPLETE".equals(s) && !"FAILED".equals(s) && !"PARTIAL".equals(s)) {
                    task.setStatus("CANCELLED");
                    taskRepo.save(task);
                }
            });
            return true;
        }).orElse(false);
    }

    /**
     * Pauses a run — user intentionally interrupted to edit the goal.
     * Unlike cancelRun(), sets status PAUSED (not FAILED) so the UI can
     * offer "Edit Goal and Restart" instead of generic retry.
     */
    public boolean pauseRun(String runId) {
        return runRepo.findById(runId).map(run -> {
            if (List.of("COMPLETE", "FAILED", "CANCELLED", "PAUSED").contains(run.getStatus())) return false;
            finaliseRun(run, "PAUSED", "Paused by user for goal editing");
            taskRepo.findByRunId(runId).forEach(task -> {
                String s = task.getStatus();
                if (!"COMPLETE".equals(s) && !"FAILED".equals(s) && !"PARTIAL".equals(s)) {
                    task.setStatus("FAILED");
                    taskRepo.save(task);
                }
            });
            return true;
        }).orElse(false);
    }

    /**
     * Resumes a PAUSED run — resets incomplete tasks to PENDING and sets
     * run status to WORKING so the pipeline re-enters at the worker phase.
     * Already-COMPLETE tasks are preserved (their merged work stays on main).
     */
    public boolean resumeRun(String runId) {
        return runRepo.findById(runId).map(run -> {
            if (!"PAUSED".equals(run.getStatus())) return false;

            run.setStatus("WORKING");
            run.setCompletedAt(null);
            run.setFailureReason(null);
            run.setCurrentGate(null);
            runRepo.save(run);

            taskRepo.findByRunId(runId).forEach(task -> {
                String s = task.getStatus();
                if (!"COMPLETE".equals(s)) {
                    task.setStatus("PENDING");
                    task.setWorkerReportJson(null);
                    task.setBranchName(null);
                    task.setStartedAt(null);
                    task.setCompletedAt(null);
                    task.setAssignedModel(null);
                    task.setInputTokens(0);
                    task.setOutputTokens(0);
                    task.setCacheReadTokens(0);
                    task.setCacheWriteTokens(0);
                    task.setLastLlmFailure(null);
                    task.setStuckDetectedAt(null);
                    task.setLastActivityAt(Instant.now());
                    taskRepo.save(task);
                }
            });
            return true;
        }).orElse(false);
    }

    /** Computes metrics across ALL runs (for SUPER_ADMIN). */
    public MetricsData computeMetrics() {
        long total     = runRepo.count();
        long completed = runRepo.countByStatus("COMPLETE");
        long failed    = runRepo.countByStatus("FAILED");
        long active    = runRepo.countByStatus("WORKING")
                       + runRepo.countByStatus("STUCK")
                       + runRepo.countByStatus("PLANNING")
                       + runRepo.countByStatus("REVIEWING")
                       + runRepo.countByStatus("AWAITING_APPROVAL")
                       + runRepo.countByStatus("AWAITING_MERGE");
        long queued    = runRepo.countByStatus("QUEUED");
        Long sumIn  = runRepo.sumInputTokens();
        Long sumOut = runRepo.sumOutputTokens();
        return new MetricsData(total, completed, failed, active, queued,
                               sumIn != null ? sumIn : 0, sumOut != null ? sumOut : 0);
    }

    /** Computes metrics filtered by orgId (for ORG_ADMIN). */
    public MetricsData computeMetricsForOrg(String orgId) {
        long total     = runRepo.countByOrgId(orgId);
        long completed = runRepo.countByOrgIdAndStatus(orgId, "COMPLETE");
        long failed    = runRepo.countByOrgIdAndStatus(orgId, "FAILED");
        long active    = runRepo.countByOrgIdAndStatus(orgId, "WORKING")
                       + runRepo.countByOrgIdAndStatus(orgId, "STUCK")
                       + runRepo.countByOrgIdAndStatus(orgId, "PLANNING")
                       + runRepo.countByOrgIdAndStatus(orgId, "REVIEWING")
                       + runRepo.countByOrgIdAndStatus(orgId, "AWAITING_APPROVAL")
                       + runRepo.countByOrgIdAndStatus(orgId, "AWAITING_MERGE");
        long queued    = runRepo.countByOrgIdAndStatus(orgId, "QUEUED");
        Long sumIn  = runRepo.sumInputTokensByOrgId(orgId);
        Long sumOut = runRepo.sumOutputTokensByOrgId(orgId);
        return new MetricsData(total, completed, failed, active, queued,
                               sumIn != null ? sumIn : 0, sumOut != null ? sumOut : 0);
    }

    public record MetricsData(long totalRuns, long completedRuns, long failedRuns,
                               long activeRuns, long queuedRuns,
                               long totalInputTokens, long totalOutputTokens) {}

    /** Date-filtered global metrics. If days <= 0, delegates to all-time variant. */
    public MetricsData computeMetrics(int days) {
        if (days <= 0) return computeMetrics();
        Instant since = Instant.now().minus(Duration.ofDays(days));
        long total     = runRepo.countByStartedAtGreaterThanEqual(since);
        long completed = runRepo.countByStatusAndStartedAtGreaterThanEqual("COMPLETE", since);
        long failed    = runRepo.countByStatusAndStartedAtGreaterThanEqual("FAILED", since);
        long active    = runRepo.countByStatusAndStartedAtGreaterThanEqual("WORKING", since)
                       + runRepo.countByStatusAndStartedAtGreaterThanEqual("STUCK", since)
                       + runRepo.countByStatusAndStartedAtGreaterThanEqual("PLANNING", since)
                       + runRepo.countByStatusAndStartedAtGreaterThanEqual("REVIEWING", since)
                       + runRepo.countByStatusAndStartedAtGreaterThanEqual("AWAITING_APPROVAL", since)
                       + runRepo.countByStatusAndStartedAtGreaterThanEqual("AWAITING_MERGE", since);
        long queued    = runRepo.countByStatusAndStartedAtGreaterThanEqual("QUEUED", since);
        Long sumIn  = runRepo.sumInputTokensByStartedAtAfter(since);
        Long sumOut = runRepo.sumOutputTokensByStartedAtAfter(since);
        return new MetricsData(total, completed, failed, active, queued,
                               sumIn != null ? sumIn : 0, sumOut != null ? sumOut : 0);
    }

    /** Date-filtered org metrics. If days <= 0, delegates to all-time variant. */
    public MetricsData computeMetricsForOrg(String orgId, int days) {
        if (days <= 0) return computeMetricsForOrg(orgId);
        Instant since = Instant.now().minus(Duration.ofDays(days));
        long total     = runRepo.countByOrgIdAndStartedAtGreaterThanEqual(orgId, since);
        long completed = runRepo.countByOrgIdAndStatusAndStartedAtGreaterThanEqual(orgId, "COMPLETE", since);
        long failed    = runRepo.countByOrgIdAndStatusAndStartedAtGreaterThanEqual(orgId, "FAILED", since);
        long active    = runRepo.countByOrgIdAndStatusAndStartedAtGreaterThanEqual(orgId, "WORKING", since)
                       + runRepo.countByOrgIdAndStatusAndStartedAtGreaterThanEqual(orgId, "STUCK", since)
                       + runRepo.countByOrgIdAndStatusAndStartedAtGreaterThanEqual(orgId, "PLANNING", since)
                       + runRepo.countByOrgIdAndStatusAndStartedAtGreaterThanEqual(orgId, "REVIEWING", since)
                       + runRepo.countByOrgIdAndStatusAndStartedAtGreaterThanEqual(orgId, "AWAITING_APPROVAL", since)
                       + runRepo.countByOrgIdAndStatusAndStartedAtGreaterThanEqual(orgId, "AWAITING_MERGE", since);
        long queued    = runRepo.countByOrgIdAndStatusAndStartedAtGreaterThanEqual(orgId, "QUEUED", since);
        Long sumIn  = runRepo.sumInputTokensByOrgIdAndStartedAtAfter(orgId, since);
        Long sumOut = runRepo.sumOutputTokensByOrgIdAndStartedAtAfter(orgId, since);
        return new MetricsData(total, completed, failed, active, queued,
                               sumIn != null ? sumIn : 0, sumOut != null ? sumOut : 0);
    }

    // ── Tier 4: Timeline and model comparison ─────────────────

    public TimelineData buildTimeline(String runId) {
        PipelineRun run = runRepo.findById(runId).orElseThrow();
        List<AgentTask> tasks = taskRepo.findByRunIdOrderByStartedAtAsc(runId);
        List<GateEvent> gates = gateEventRepo.findByRunIdOrderByOpenedAtAsc(runId);
        List<CompactionEvent> compactions = compactionEventRepo.findByRunIdOrderByOccurredAtAsc(runId);
        List<AgentMessage> messages = messageRepo.findByRunIdOrderBySentAt(runId);
        return new TimelineData(run, tasks, gates, compactions, messages);
    }

    public ModelComparisonData computeModelComparison(int days) {
        Instant since = days > 0 ? Instant.now().minus(Duration.ofDays(days)) : null;
        List<AgentTask> allTasks = taskRepo.findAll().stream()
            .filter(t -> since == null || (t.getStartedAt() != null && t.getStartedAt().isAfter(since)))
            .filter(t -> t.getInputTokens() != null)
            .toList();

        Map<String, List<AgentTask>> grouped = new LinkedHashMap<>();
        for (AgentTask t : allTasks) {
            String key = (t.getProvider() != null ? t.getProvider() : "anthropic") + "/"
                       + (t.getModel() != null ? t.getModel() : modelRegistry.workhouseModelFor("anthropic"));
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }

        List<ModelStatData> stats = grouped.entrySet().stream().map(entry -> {
            String[] parts = entry.getKey().split("/", 2);
            String provider = parts[0];
            String model = parts.length > 1 ? parts[1] : "unknown";
            List<AgentTask> tl = entry.getValue();
            long totalIn  = tl.stream().mapToLong(t -> t.getInputTokens()  != null ? t.getInputTokens()  : 0).sum();
            long totalOut = tl.stream().mapToLong(t -> t.getOutputTokens() != null ? t.getOutputTokens() : 0).sum();
            int count = tl.size();
            int compactions = tl.stream().mapToInt(t -> t.getCompactionCount() != null ? t.getCompactionCount() : 0).sum();
            return new ModelStatData(provider, model, count, totalIn, totalOut,
                count > 0 ? (double) totalIn / count : 0,
                count > 0 ? (double) totalOut / count : 0, compactions);
        }).toList();

        return new ModelComparisonData(stats);
    }

    /**
     * Org-scoped model comparison: only tasks belonging to runs in the given org.
     */
    public ModelComparisonData computeModelComparisonForOrg(int days, String orgId) {
        Instant since = days > 0 ? Instant.now().minus(Duration.ofDays(days)) : null;
        // Get run IDs for this org
        List<String> orgRunIds = runRepo.findAll().stream()
            .filter(r -> orgId.equals(r.getOrgId()))
            .map(PipelineRun::getRunId)
            .toList();
        if (orgRunIds.isEmpty()) return new ModelComparisonData(List.of());

        List<AgentTask> allTasks = taskRepo.findAll().stream()
            .filter(t -> orgRunIds.contains(t.getRunId()))
            .filter(t -> since == null || (t.getStartedAt() != null && t.getStartedAt().isAfter(since)))
            .filter(t -> t.getInputTokens() != null)
            .toList();

        Map<String, List<AgentTask>> grouped = new LinkedHashMap<>();
        for (AgentTask t : allTasks) {
            String key = (t.getProvider() != null ? t.getProvider() : "anthropic") + "/"
                       + (t.getModel() != null ? t.getModel() : modelRegistry.workhouseModelFor("anthropic"));
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }

        List<ModelStatData> stats = grouped.entrySet().stream().map(entry -> {
            String[] parts = entry.getKey().split("/", 2);
            String provider = parts[0];
            String model = parts.length > 1 ? parts[1] : "unknown";
            List<AgentTask> tl = entry.getValue();
            long totalIn  = tl.stream().mapToLong(t -> t.getInputTokens()  != null ? t.getInputTokens()  : 0).sum();
            long totalOut = tl.stream().mapToLong(t -> t.getOutputTokens() != null ? t.getOutputTokens() : 0).sum();
            int count = tl.size();
            int compactions = tl.stream().mapToInt(t -> t.getCompactionCount() != null ? t.getCompactionCount() : 0).sum();
            return new ModelStatData(provider, model, count, totalIn, totalOut,
                count > 0 ? (double) totalIn / count : 0,
                count > 0 ? (double) totalOut / count : 0, compactions);
        }).toList();

        return new ModelComparisonData(stats);
    }

    public record TimelineData(PipelineRun run, List<AgentTask> tasks,
                                List<GateEvent> gates, List<CompactionEvent> compactions,
                                List<AgentMessage> messages) {}

    public record ModelComparisonData(List<ModelStatData> models) {}

    public record ModelStatData(String provider, String model, int taskCount,
                                 long totalInputTokens, long totalOutputTokens,
                                 double avgInputTokensPerTask, double avgOutputTokensPerTask,
                                 int compactionCount) {}

    // ── Per-user run statistics ───────────────────────────────

    public record UserRunsSummaryData(List<UserRunStat> users) {}

    public record UserRunStat(String userId, String displayName,
                              int runCount, int completedCount, int failedCount,
                              long totalInputTokens, long totalOutputTokens) {}

    /** Computes per-user run statistics for the given org. */
    public UserRunsSummaryData computeUserRunsSummary(String orgId, int days) {
        Instant since = days > 0 ? Instant.now().minus(Duration.ofDays(days)) : null;
        List<PipelineRun> runs = runRepo.findAll().stream()
            .filter(r -> orgId.equals(r.getOrgId()))
            .filter(r -> since == null || (r.getStartedAt() != null && r.getStartedAt().isAfter(since)))
            .toList();
        Map<String, AppUser> userMap = appUserRepo.findActiveByOrgId(orgId).stream()
            .collect(Collectors.toMap(AppUser::getId, u -> u, (a, b) -> a));
        return buildUserStats(runs, userMap);
    }

    /** Global (SUPER_ADMIN) variant: no org filter. */
    public UserRunsSummaryData computeUserRunsSummaryGlobal(int days) {
        Instant since = days > 0 ? Instant.now().minus(Duration.ofDays(days)) : null;
        List<PipelineRun> runs = runRepo.findAll().stream()
            .filter(r -> since == null || (r.getStartedAt() != null && r.getStartedAt().isAfter(since)))
            .toList();
        Map<String, AppUser> userMap = appUserRepo.findAllActive().stream()
            .collect(Collectors.toMap(AppUser::getId, u -> u, (a, b) -> a));
        return buildUserStats(runs, userMap);
    }

    private UserRunsSummaryData buildUserStats(List<PipelineRun> runs, Map<String, AppUser> userMap) {
        Map<String, List<PipelineRun>> grouped = runs.stream()
            .filter(r -> r.getCreatedByUserId() != null)
            .collect(Collectors.groupingBy(PipelineRun::getCreatedByUserId));

        List<UserRunStat> stats = grouped.entrySet().stream().map(entry -> {
            String uid = entry.getKey();
            List<PipelineRun> userRuns = entry.getValue();
            AppUser user = userMap.get(uid);
            String displayName = user != null
                ? (user.getFirstName() + " " + user.getLastName()).trim()
                : uid.substring(0, Math.min(8, uid.length())) + "…";
            int runCount  = userRuns.size();
            int completed = (int) userRuns.stream().filter(r -> "COMPLETE".equals(r.getStatus())).count();
            int failed    = (int) userRuns.stream().filter(r -> "FAILED".equals(r.getStatus())).count();
            long totalIn  = userRuns.stream().mapToLong(r -> r.getTotalInputTokens() != null ? r.getTotalInputTokens() : 0).sum();
            long totalOut = userRuns.stream().mapToLong(r -> r.getTotalOutputTokens() != null ? r.getTotalOutputTokens() : 0).sum();
            return new UserRunStat(uid, displayName, runCount, completed, failed, totalIn, totalOut);
        }).sorted(Comparator.comparingInt(UserRunStat::runCount).reversed())
          .toList();

        return new UserRunsSummaryData(stats);
    }

    // ── Logging ───────────────────────────────────────────────

    public void logMessage(String runId, String from, String to,
                            String type, String summary, int tokenCost) {
        messageRepo.save(AgentMessage.builder()
            .messageId(UUID.randomUUID().toString())
            .runId(runId).fromAgent(from).toAgent(to)
            .messageType(type).contentSummary(summary)
            .sentAt(Instant.now()).tokenCost(tokenCost)
            .build());
    }

    // ── Tier 5: Stuck detection ───────────────────────────────

    /** Find runs in any of the given statuses (used by OrphanedRunWatchdog). */
    public List<PipelineRun> findRunsByStatuses(List<String> statuses) {
        return runRepo.findByStatusIn(statuses);
    }

    /** Get tasks in WORKING or LOCKED status for a specific run (used by OrphanedRunWatchdog). */
    public List<AgentTask> getActiveTasksForRun(String runId) {
        return taskRepo.findByRunIdAndStatusIn(runId, List.of("WORKING", "LOCKED"));
    }

    public List<AgentTask> getWorkingTasks() {
        return taskRepo.findByStatus("WORKING");
    }

    /** Returns QUEUED runs that were stalled due to a provider outage (for automatic retry). */
    public List<PipelineRun> getOutageQueuedRuns() {
        return runRepo.findOutageQueuedRuns();
    }

    public void updateLastActivity(String taskId) {
        taskRepo.findById(taskId).ifPresent(task -> {
            task.setLastActivityAt(Instant.now());
            taskRepo.save(task);
        });
    }

    public void markStuckDetected(String taskId) {
        taskRepo.findById(taskId).ifPresent(task -> {
            task.setStuckDetectedAt(Instant.now());
            taskRepo.save(task);
        });
    }

    /**
     * Marks the run as STUCK when one of its tasks has exceeded the act-threshold with no
     * activity. The run stays STUCK until recovery re-queues the task (markTaskForRetry /
     * escalateTask both restore the run to WORKING automatically).
     */
    public void markRunStuck(String runId) {
        runRepo.findById(runId).ifPresent(run -> {
            if ("WORKING".equals(run.getStatus())) {
                run.setStatus("STUCK");
                runRepo.save(run);
            }
        });
    }

    public void markTaskForRetry(String taskId) {
        taskRepo.findById(taskId).ifPresent(task -> {
            task.setStatus("PENDING");
            task.setRecoveryAttempt((task.getRecoveryAttempt() != null ? task.getRecoveryAttempt() : 0) + 1);
            task.setStuckDetectedAt(null);
            task.setLastActivityAt(Instant.now());
            taskRepo.save(task);
            // Restore run to WORKING if it was moved to STUCK by the monitor
            runRepo.findById(task.getRunId()).ifPresent(run -> {
                if ("STUCK".equals(run.getStatus())) {
                    run.setStatus("WORKING");
                    runRepo.save(run);
                }
            });
        });
    }

    public void blockTask(String taskId, String reason) {
        taskRepo.findById(taskId).ifPresent(task -> {
            task.setStatus("BLOCKED");
            task.setLastLlmFailure(reason);
            taskRepo.save(task);
        });
    }

    // ── Tier 5: Task log ──────────────────────────────────────

    public List<String> getTaskEventLog(String taskId, int limit) {
        return messageRepo.findTop20ByTaskIdOrderBySentAtDesc(taskId)
            .stream()
            .limit(limit)
            .map(AgentMessage::getContentSummary)
            .collect(Collectors.toList());
    }

    public void appendTaskLog(String taskId, String event) {
        taskRepo.findById(taskId).ifPresent(task -> {
            messageRepo.save(AgentMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .runId(task.getRunId())
                .taskId(taskId)
                .fromAgent("worker-" + taskId)
                .toAgent("state")
                .messageType("SYSTEM")
                .contentSummary(event)
                .sentAt(Instant.now())
                .tokenCost(0)
                .build());
        });
    }

    // ── Tier 5: Escalation ────────────────────────────────────

    public AgentTask getTask(String taskId) {
        return taskRepo.findById(taskId).orElseThrow();
    }

    /**
     * Marks a single task as FAILED with a descriptive reason.
     * Used by PipelineHarness when a task branch has a merge conflict so the
     * user can retry the task via the TaskCard retry button.
     */
    public void failTask(String taskId, String reason) {
        taskRepo.findById(taskId).ifPresent(task -> {
            task.setStatus("FAILED");
            task.setLastLlmFailure(reason);
            taskRepo.save(task);
        });
    }

    // ── Subtask decomposition ─────────────────────────────────────────────────

    /**
     * Creates subtask DB rows for a decomposed multi-file task.
     * Each subtask gets its own AgentTask entry linked to the parent via parentTaskId.
     */
    public List<AgentTask> createSubtasks(String parentTaskId, String runId,
                                          List<TaskDefinition> subtaskDefs, String tenantId) {
        int total = subtaskDefs.size();
        List<AgentTask> rows = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            TaskDefinition def = subtaskDefs.get(i);
            AgentTask st = AgentTask.builder()
                    .taskId(def.getTaskId())
                    .runId(runId)
                    .title(def.getTitle())
                    .complexity(def.getComplexity())
                    .status("PENDING")
                    .taskJson(toJson(def))
                    .parentTaskId(parentTaskId)
                    .subtaskIndex(i + 1)
                    .subtaskTotal(total)
                    .compactionCount(0)
                    .tenantId(tenantId != null ? tenantId : "default")
                    .build();
            rows.add(taskRepo.save(st));
        }
        log.info("Created {} subtasks for parent task {}", total, parentTaskId);
        return rows;
    }

    /**
     * Returns subtasks for a parent task, ordered by subtask index.
     */
    public List<AgentTask> getSubtasks(String parentTaskId) {
        return taskRepo.findByParentTaskIdOrderBySubtaskIndexAsc(parentTaskId);
    }

    /**
     * Aggregates subtask results into the parent task.
     * Sums tokens, merges file lists, picks worst status, sets duration.
     */
    public void completeParentFromSubtasks(String parentTaskId) {
        AgentTask parent = taskRepo.findById(parentTaskId).orElseThrow();
        List<AgentTask> subtasks = getSubtasks(parentTaskId);

        int totalIn = 0, totalOut = 0, totalCacheRead = 0, totalCacheWrite = 0;
        List<String> allCreated = new ArrayList<>();
        List<String> allModified = new ArrayList<>();
        boolean anyFailed = false;
        String provider = null, model = null;

        for (AgentTask st : subtasks) {
            totalIn += st.getInputTokens() != null ? st.getInputTokens() : 0;
            totalOut += st.getOutputTokens() != null ? st.getOutputTokens() : 0;
            totalCacheRead += st.getCacheReadTokens() != null ? st.getCacheReadTokens() : 0;
            totalCacheWrite += st.getCacheWriteTokens() != null ? st.getCacheWriteTokens() : 0;

            if ("FAILED".equals(st.getStatus()) || "BLOCKED".equals(st.getStatus())) {
                anyFailed = true;
            }
            if (st.getProvider() != null) provider = st.getProvider();
            if (st.getModel() != null) model = st.getModel();

            // Parse worker report to collect file lists
            if (st.getWorkerReportJson() != null) {
                try {
                    WorkerReport wr = mapper.readValue(st.getWorkerReportJson(), WorkerReport.class);
                    if (wr.getFilesCreated() != null) allCreated.addAll(wr.getFilesCreated());
                    if (wr.getFilesModified() != null) allModified.addAll(wr.getFilesModified());
                } catch (Exception e) {
                    log.warn("Failed to parse subtask {} worker report: {}", st.getTaskId(), e.getMessage());
                }
            }
        }

        // Build composite report
        WorkerReport compositeReport = WorkerReport.builder()
                .taskId(parentTaskId)
                .status(anyFailed ? "partial" : "complete")
                .filesCreated(allCreated)
                .filesModified(allModified)
                .filesRead(List.of())
                .inputTokens(totalIn)
                .outputTokens(totalOut)
                .cacheReadTokens(totalCacheRead)
                .cacheWriteTokens(totalCacheWrite)
                .provider(provider)
                .model(model)
                .selfReview("Decomposed into " + subtasks.size() + " subtasks: " +
                        subtasks.stream().filter(s -> "COMPLETE".equals(s.getStatus())).count() + " complete")
                .build();

        parent.setStatus(anyFailed ? "PARTIAL" : "COMPLETE");
        parent.setWorkerReportJson(toJson(compositeReport));
        parent.setInputTokens(totalIn);
        parent.setOutputTokens(totalOut);
        parent.setCacheReadTokens(totalCacheRead);
        parent.setCacheWriteTokens(totalCacheWrite);
        if (provider != null) parent.setProvider(provider);
        if (model != null) parent.setModel(model);
        parent.setCompletedAt(Instant.now());
        parent.setDurationMs(parent.getStartedAt() != null
                ? Duration.between(parent.getStartedAt(), Instant.now()).toMillis() : 0);
        parent.setCompletionNotes(compositeReport.getSelfReview());
        taskRepo.save(parent);

        // Accumulate totals on run
        runRepo.addTokens(parent.getRunId(), totalIn, totalOut);

        log.info("Parent task {} completed from {} subtasks — status={}, tokens={}/{}",
                parentTaskId, subtasks.size(), parent.getStatus(), totalIn, totalOut);
    }

    public List<AgentTask> getTasksForRun(String runId) {
        return taskRepo.findByRunId(runId);
    }

    public void escalateTask(String taskId, String provider, String model) {
        taskRepo.findById(taskId).ifPresent(task -> {
            task.setCurrentProvider(provider);
            task.setCurrentModel(model);
            task.setRecoveryAttempt((task.getRecoveryAttempt() != null ? task.getRecoveryAttempt() : 0) + 1);
            task.setStatus("PENDING");
            task.setStuckDetectedAt(null);
            task.setLastActivityAt(Instant.now());
            taskRepo.save(task);
            // Restore run to WORKING if it was moved to STUCK by the monitor
            runRepo.findById(task.getRunId()).ifPresent(run -> {
                if ("STUCK".equals(run.getStatus())) {
                    run.setStatus("WORKING");
                    runRepo.save(run);
                }
            });
        });
    }

    public void setEscalationLevel(String taskId, int level) {
        taskRepo.findById(taskId).ifPresent(task -> {
            task.setRecoveryAttempt(level);
            taskRepo.save(task);
        });
    }

    public void forceCompactionFlag(String taskId) {
        taskRepo.findById(taskId).ifPresent(task -> {
            task.setStatus("PENDING");
            task.setLastLlmFailure("CONTEXT_TOO_LONG");
            taskRepo.save(task);
        });
    }

    public void recordLlmFailure(String taskId, Object failure) {
        taskRepo.findById(taskId).ifPresent(task -> {
            task.setLastLlmFailure(failure.getClass().getSimpleName());
            taskRepo.save(task);
        });
    }

    // ── Tier 5: Prompt Optimiser support ─────────────────────

    public List<PipelineRun> getRecentCompletedRuns(int limit) {
        return runRepo.findByStatusOrderByStartedAtDesc("COMPLETE",
            PageRequest.of(0, limit)).getContent();
    }

    public long countCompletedRuns() {
        return runRepo.countByStatus("COMPLETE");
    }

    public ReviewReport getReviewReport(String runId) {
        return runRepo.findById(runId)
            .filter(r -> r.getReviewJson() != null)
            .map(r -> {
                try {
                    return mapper.readValue(r.getReviewJson(), ReviewReport.class);
                } catch (JsonProcessingException e) {
                    return null;
                }
            })
            .orElse(null);
    }

    // ── Post-run conversation ─────────────────────────────────

    /**
     * Appends a single message to the run's chat history JSON and increments the
     * chat message counter.
     *
     * @param runId   the pipeline run ID
     * @param role    "user" or "assistant"
     * @param content message text
     */
    public void saveChatMessage(String runId, String role, String content) {
        runRepo.findById(runId).ifPresent(run -> {
            List<Map<String, String>> history = parseChatHistory(run.getChatHistoryJson());
            Map<String, String> message = new LinkedHashMap<>();
            message.put("role", role);
            message.put("content", content);
            history.add(message);
            run.setChatHistoryJson(toJson(history));
            run.setChatMessageCount(run.getChatMessageCount() + 1);
            runRepo.save(run);
        });
    }

    /**
     * Returns the conversation history for a run as an ordered list of
     * {@code {role, content}} maps. Returns an empty list if none exists.
     *
     * @param runId the pipeline run ID
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, String>> getChatHistory(String runId) {
        return runRepo.findById(runId)
            .map(run -> parseChatHistory(run.getChatHistoryJson()))
            .orElse(List.of());
    }

    /**
     * Creates a new pipeline run that is an iteration of an existing completed run.
     * Sets {@code sourceRunId} so the harness will copy the workspace instead of
     * initialising a fresh one.
     *
     * @param sourceRun  the completed run to iterate from
     * @param newRequest the user's new / refined request
     * @return the new {@link PipelineRun} entity (status=PLANNING, not yet saved to DB)
     */
    public PipelineRun createIterationRun(PipelineRun sourceRun, String newRequest) {
        PipelineRun newRun = PipelineRun.builder()
            .runId(UUID.randomUUID().toString())
            .userRequest(newRequest)
            .status("PLANNING")
            .plannerModel(sourceRun.getPlannerModel())
            .workerModel(sourceRun.getWorkerModel())
            .reviewerModel(sourceRun.getReviewerModel())
            .sourceType("MANUAL")
            .repoUrl(null)        // new run gets its own repo if user has a PAT
            .repoMode(null)
            .queuePosition(0)
            .startedAt(Instant.now())
            .totalInputTokens(0)
            .totalOutputTokens(0)
            .githubPat(sourceRun.getGithubPat())
            .githubOwner(sourceRun.getGithubOwner())
            .createdByUserId(sourceRun.getCreatedByUserId())
            .orgId(sourceRun.getOrgId())
            .projectId(sourceRun.getProjectId())
            .sourceRunId(sourceRun.getRunId())     // ← links back to the source
            .build();
        return runRepo.save(newRun);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseChatHistory(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return mapper.readValue(json, List.class);
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    // ── Apply suggestion tasks ──────────────────────────────────

    /**
     * Appends suggestion tasks to an existing run's plan.
     * Task IDs are prefixed to indicate their source (copilot/review) and apply index.
     *
     * @param runId      the run to append tasks to
     * @param miniPlan   the generated mini-plan (≤6 tasks)
     * @param applyIndex the 1-based apply iteration number
     * @param source     COPILOT or REVIEW — determines the task ID prefix
     * @return the list of newly created AgentTask entities
     */
    public List<AgentTask> appendSuggestionTasks(String runId, TaskPlan miniPlan,
                                                  int applyIndex, ApplySource source) {
        PipelineRun run = runRepo.findById(runId).orElseThrow();

        // Build prefix: e.g. "adb68707_cop1_" or "adb68707_rev2_"
        String prefix = runId.substring(0, 8) + "_" + source.prefix() + applyIndex + "_";

        // Build old→new ID mapping for rewriting depends_on references
        Map<String, String> idMap = new java.util.HashMap<>();
        miniPlan.getTasks().forEach(t -> idMap.put(t.getTaskId(), prefix + t.getTaskId()));

        // Rewrite task IDs and dependency references
        miniPlan.getTasks().forEach(t -> {
            if (t.getDependsOn() != null) {
                t.setDependsOn(t.getDependsOn().stream()
                    .map(dep -> idMap.getOrDefault(dep, prefix + dep))
                    .toList());
            }
            t.setTaskId(idMap.get(t.getTaskId()));
        });

        // Merge into existing plan_json
        try {
            TaskPlan existingPlan = run.getPlanJson() != null
                    ? mapper.readValue(run.getPlanJson(), TaskPlan.class)
                    : TaskPlan.builder().planSummary("").tasks(new ArrayList<>()).build();

            List<TaskDefinition> mergedTasks = new ArrayList<>(existingPlan.getTasks());
            mergedTasks.addAll(miniPlan.getTasks());
            existingPlan.setTasks(mergedTasks);

            // Append to plan summary
            String suffix = "\n\n[" + source.name() + " Apply #" + applyIndex + "] " +
                    (miniPlan.getPlanSummary() != null ? miniPlan.getPlanSummary() : "Applied suggestion");
            existingPlan.setPlanSummary(
                    (existingPlan.getPlanSummary() != null ? existingPlan.getPlanSummary() : "") + suffix);

            run.setPlanJson(toJson(existingPlan));
            runRepo.save(run);
        } catch (JsonProcessingException e) {
            log.error("[StateStore] Failed to merge suggestion plan for run {}: {}", runId, e.getMessage());
            throw new RuntimeException("Failed to merge suggestion plan", e);
        }

        // Create AgentTask rows for each new task
        List<AgentTask> created = new ArrayList<>();
        for (TaskDefinition t : miniPlan.getTasks()) {
            AgentTask task = taskRepo.save(AgentTask.builder()
                .taskId(t.getTaskId())
                .runId(runId)
                .title(t.getTitle())
                .complexity(t.getComplexity())
                .assignedModel(t.getAssignedModel())
                .status("PENDING")
                .taskJson(toJson(t))
                .compactionCount(0)
                .build());
            created.add(task);
        }

        log.info("[StateStore] Appended {} {} tasks for run {} (apply #{})",
                created.size(), source.name(), runId, applyIndex);
        return created;
    }

    /**
     * Atomically increments the apply_suggestion_count for the given run.
     */
    public void incrementApplySuggestionCount(String runId) {
        runRepo.findById(runId).ifPresent(run -> {
            run.setApplySuggestionCount(run.getApplySuggestionCount() + 1);
            runRepo.save(run);
        });
    }

    // ── GCS workspace archiving ───────────────────────────────

    /**
     * Returns runs eligible for GCS archiving: terminal state, completed more than
     * {@code cutoff} hours ago, not yet archived. Processed in batches of 50 to
     * avoid loading too many records into memory.
     */
    public List<PipelineRun> findRunsToArchive(Instant cutoff) {
        return runRepo.findRunsToArchive(cutoff, PageRequest.of(0, 50));
    }

    /**
     * Marks a run as archived to GCS: sets {@code gcsWorkspaceKey} and {@code gcsArchivedAt}.
     *
     * @param runId  the run that was archived
     * @param gcsKey the GCS object key (e.g. {@code workspaces/{runId}.zip})
     */
    public void markGcsArchived(String runId, String gcsKey) {
        runRepo.findById(runId).ifPresent(run -> {
            run.setGcsWorkspaceKey(gcsKey);
            run.setGcsArchivedAt(Instant.now());
            runRepo.save(run);
        });
    }

    /**
     * Returns terminal runs whose local workspace needs cleanup (already uploaded
     * to GCS or not). Used by the modified WorkspaceArchiveJob.
     */
    public List<PipelineRun> findRunsToCleanup(Instant cutoff) {
        return runRepo.findRunsToCleanup(cutoff, PageRequest.of(0, 50));
    }

    /**
     * Returns terminal runs without a GCS workspace key. Used by the 5-minute
     * fallback sweep in WorkspaceGcsUploadListener.
     */
    public List<PipelineRun> findTerminalRunsWithoutGcsKey(Instant cutoff) {
        return runRepo.findTerminalRunsWithoutGcsKey(cutoff, PageRequest.of(0, 50));
    }

    // ── Tech stack detection ─────────────────────────────────

    /**
     * Detects the technology stack from the plan and user request.
     * Returns a comma-separated string of tech IDs (e.g. "react,typescript,nodejs")
     * matching the frontend's TECH_DEFS in techDetect.ts.
     *
     * <p>The plan text (task titles, objectives, boundaries) is the primary source
     * because it reflects what the planner actually decided to build — not just
     * what the user mentioned in their request.</p>
     */
    static String detectTechStack(TaskPlan plan, String userRequest) {
        // Build a combined text from the plan + user request
        StringBuilder sb = new StringBuilder();
        if (plan.getPlanSummary() != null) sb.append(plan.getPlanSummary()).append(' ');
        if (plan.getTasks() != null) {
            for (TaskDefinition t : plan.getTasks()) {
                if (t.getTitle() != null) sb.append(t.getTitle()).append(' ');
                if (t.getObjective() != null) sb.append(t.getObjective()).append(' ');
                if (t.getBoundaries() != null) sb.append(t.getBoundaries()).append(' ');
                if (t.getAcceptanceCriteria() != null) sb.append(t.getAcceptanceCriteria()).append(' ');
                if (t.getOutputFormat() != null) sb.append(t.getOutputFormat()).append(' ');
            }
        }
        if (userRequest != null) sb.append(userRequest);
        String text = sb.toString().toLowerCase();

        // Same keyword→ID mapping as frontend's TECH_DEFS (techDetect.ts)
        // Order: priority ascending (frontend frameworks → languages → backends → databases → infra)
        record TechEntry(String id, int priority, String[] keywords, boolean wordBoundary) {}
        TechEntry[] defs = {
            new TechEntry("html",       15, new String[]{"html", "css", "vanilla js", "vanilla javascript", "plain html", "static html", "index.html"}, false),
            new TechEntry("react",      10, new String[]{"react", "reactjs", "react.js", "create-react-app"}, false),
            new TechEntry("nextjs",     11, new String[]{"next.js", "nextjs", "next js"}, false),
            new TechEntry("vue",        12, new String[]{"vuejs", "vue.js", "nuxt"}, false),
            new TechEntry("angular",    13, new String[]{"angular"}, false),
            new TechEntry("svelte",     14, new String[]{"svelte", "sveltekit"}, false),
            new TechEntry("typescript", 20, new String[]{"typescript", "tsx", "tsconfig"}, false),
            new TechEntry("nodejs",     30, new String[]{"nodejs", "node.js", "express", "fastify", "koa", "hapi", "nestjs"}, false),
            new TechEntry("java",       31, new String[]{"\\bjava\\b", "\\bjvm\\b", "maven", "gradle", "pom.xml"}, true),
            new TechEntry("spring",     32, new String[]{"spring boot", "springboot", "spring-boot", "spring framework"}, false),
            new TechEntry("python",     33, new String[]{"python", "django", "fastapi", "flask", "pandas", "numpy"}, false),
            new TechEntry("go",         34, new String[]{"golang", "go lang", "gin framework", "fiber"}, false),
            new TechEntry("rust",       35, new String[]{"\\brust\\b", "cargo", "tokio", "actix", "axum"}, true),
            new TechEntry("dotnet",     38, new String[]{".net", "dotnet", "csharp", "c#", "asp.net", "blazor"}, false),
            new TechEntry("postgres",   50, new String[]{"postgresql", "postgres", "psql"}, false),
            new TechEntry("mongodb",    51, new String[]{"mongodb", "mongoose"}, false),
            new TechEntry("mysql",      52, new String[]{"mysql", "mariadb"}, false),
            new TechEntry("redis",      53, new String[]{"redis"}, false),
            new TechEntry("sqlite",     54, new String[]{"sqlite"}, false),
            new TechEntry("docker",     60, new String[]{"docker", "dockerfile", "container", "docker-compose"}, false),
        };

        List<String> found = new ArrayList<>();
        for (TechEntry def : defs) {
            for (String kw : def.keywords) {
                boolean matched;
                if (def.wordBoundary || kw.contains("\\b")) {
                    matched = java.util.regex.Pattern.compile(kw).matcher(text).find();
                } else {
                    matched = text.contains(kw);
                }
                if (matched) {
                    found.add(def.id);
                    break;
                }
            }
        }

        // Dedup: Spring Boot ⊃ Java, Next.js ⊃ React, HTML ⊃ suppress frameworks
        if (found.contains("spring")) found.remove("java");
        if (found.contains("nextjs")) found.remove("react");
        if (found.contains("html") && !found.contains("react") && !found.contains("vue") && !found.contains("angular")) {
            // Pure HTML/CSS/JS project — suppress Node.js if only detected from generic "node" keyword
            found.remove("nodejs");
        }

        // Cap at 5 badges (matches frontend MAX_BADGES)
        if (found.size() > 5) found = new ArrayList<>(found.subList(0, 5));

        return found.isEmpty() ? null : String.join(",", found);
    }

    // ── Helpers ───────────────────────────────────────────────

    /**
     * Common terminal-state mutation: sets status, clears the approval gate, stamps
     * completedAt, and optionally sets a failure/partial reason. Used by markFailed,
     * markComplete, markPartial, markRejected, and cancelRun to avoid boilerplate duplication.
     */
    private void finaliseRun(PipelineRun run, String status, String reason) {
        run.setStatus(status);
        run.setCurrentGate(null);
        run.setCompletedAt(Instant.now());
        if (reason != null) run.setFailureReason(reason);
        runRepo.save(run);
    }

    /** Update run status without changing completedAt or failureReason. Used for batch status transitions. */
    public void updateStatus(String runId, String status) {
        PipelineRun run = runRepo.findById(runId).orElseThrow();
        run.setStatus(status);
        runRepo.save(run);
    }

    private void updateRunStatus(String runId, String status) {
        PipelineRun run = runRepo.findById(runId).orElseThrow();
        run.setStatus(status);
        runRepo.save(run);
    }

    /**
     * Updates the instance heartbeat for a run. Called every 30s by PipelineHarness.
     * Uses a lightweight JPA update to avoid loading the full entity.
     */
    public void updateHeartbeat(String runId, String instanceId, java.time.Instant heartbeat) {
        runRepo.findById(runId).ifPresent(run -> {
            run.setExecutingInstance(instanceId);
            run.setExecutionHeartbeat(heartbeat);
            runRepo.save(run);
        });
    }

    private String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
