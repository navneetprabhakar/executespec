package com.navneet.agentteam.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.agentteam.agent.model.ReviewReport;
import com.navneet.agentteam.agent.model.TaskDefinition;
import com.navneet.agentteam.agent.model.TaskPlan;
import com.navneet.agentteam.agent.model.WorkerReport;
import com.navneet.agentteam.state.entity.*;
import com.navneet.agentteam.state.repository.*;
import lombok.RequiredArgsConstructor;
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
        run.setPlanJson(toJson(plan));
        run.setStatus("AWAITING_APPROVAL");
        run.setCurrentGate("PLAN_APPROVAL");
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
    }

    public void markApproved(String runId) {
        PipelineRun run = runRepo.findById(runId).orElseThrow();
        run.setStatus("WORKING");
        run.setCurrentGate(null);
        runRepo.save(run);
    }

    public void markRejected(String runId, String reason) {
        finaliseRun(runRepo.findById(runId).orElseThrow(), "FAILED", "Rejected by user: " + reason);
    }

    public void markReviewing(String runId) {
        updateRunStatus(runId, "REVIEWING");
    }

    public void saveReview(String runId, ReviewReport review) {
        PipelineRun run = runRepo.findById(runId).orElseThrow();
        run.setReviewJson(toJson(review));
        run.setStatus("AWAITING_MERGE");
        run.setCurrentGate("MERGE_APPROVAL");
        runRepo.save(run);
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
        task.setCompletedAt(Instant.now());
        task.setDurationMs(task.getStartedAt() != null
            ? Duration.between(task.getStartedAt(), Instant.now()).toMillis() : 0);
        taskRepo.save(task);

        // Accumulate totals on run
        runRepo.findById(task.getRunId()).ifPresent(run -> {
            run.setTotalInputTokens(run.getTotalInputTokens() + report.getInputTokens());
            run.setTotalOutputTokens(run.getTotalOutputTokens() + report.getOutputTokens());
            runRepo.save(run);
        });
    }

    /** Adds raw input/output token counts to the run totals (used by Planner + Reviewer). */
    public void addTokensToRun(String runId, int inputTok, int outputTok) {
        runRepo.findById(runId).ifPresent(run -> {
            run.setTotalInputTokens(run.getTotalInputTokens() + inputTok);
            run.setTotalOutputTokens(run.getTotalOutputTokens() + outputTok);
            runRepo.save(run);
        });
    }

    /** Saves the cache-aware actual cost computed post-run by CostEstimator. */
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
        task.setLastCheckpointPath(checkpointPath);
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
        Specification<PipelineRun> combined = visSpec != null
                ? Specification.where(visSpec)
                : Specification.<PipelineRun>where(null);
        if (status != null && !status.isBlank()) {
            final String s = status;
            combined = combined.and((root, q, cb) -> cb.equal(root.get("status"), s));
        }
        if (projectId != null && !projectId.isBlank()) {
            final String pid = projectId;
            combined = combined.and((root, q, cb) -> cb.equal(root.get("projectId"), pid));
        }
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "startedAt"));
        return runRepo.findAll(combined, sorted);
    }

    public Optional<PipelineRun> findRun(String runId) {
        return runRepo.findById(runId);
    }

    public boolean cancelRun(String runId) {
        return runRepo.findById(runId).map(run -> {
            if (List.of("COMPLETE", "FAILED").contains(run.getStatus())) return false;
            finaliseRun(run, "FAILED", "Cancelled by user");
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

    public record MetricsData(long totalRuns, long completedRuns, long failedRuns,
                               long activeRuns, long queuedRuns,
                               long totalInputTokens, long totalOutputTokens) {}

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
        Instant since = Instant.now().minus(Duration.ofDays(days));
        List<AgentTask> allTasks = taskRepo.findAll().stream()
            .filter(t -> t.getStartedAt() != null && t.getStartedAt().isAfter(since))
            .filter(t -> t.getInputTokens() != null)
            .toList();

        Map<String, List<AgentTask>> grouped = new LinkedHashMap<>();
        for (AgentTask t : allTasks) {
            String key = (t.getProvider() != null ? t.getProvider() : "anthropic") + "/"
                       + (t.getModel() != null ? t.getModel() : "claude-sonnet-4-6");
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

    public List<AgentTask> getWorkingTasks() {
        return taskRepo.findByStatus("WORKING");
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

    private void updateRunStatus(String runId, String status) {
        PipelineRun run = runRepo.findById(runId).orElseThrow();
        run.setStatus(status);
        runRepo.save(run);
    }

    private String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
