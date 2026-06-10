package com.navneet.executespec.integration;

import static com.navneet.executespec.integration.RunPipelineSupport.isTerminal;
import static com.navneet.executespec.integration.RunPipelineSupport.mapTaskStatusToV1;
import static com.navneet.executespec.integration.RunPipelineSupport.shortMsg;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.billing.cost.RunCostLog;
import com.navneet.executespec.billing.cost.RunCostLogRepository;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.TaskOutcome;
import com.navneet.executespec.reviewer.ReviewerResult;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * DB-write seam extracted from {@link RunOrchestrator} (god-class split, increment 2 — audit #43).
 * Thin persistence of run/task state to {@code pipeline_run} + {@code agent_task} + the cost-audit
 * log, plus the run-status SSE broadcast. No run-lifecycle decision logic — every method here is a
 * relocated, byte-identical write the orchestrator used to do inline. {@code persistEstimate} stays
 * in RunOrchestrator (it computes an estimate via {@code costHistoryService}/{@code costTracker}/
 * {@code apiKeyResolver} + the {@code clampHardCap} stayer — not a thin write).
 */
@Component
@RequiredArgsConstructor
@Slf4j
class RunPersistenceWriter {

  private final PipelineRunRepository runRepo;
  private final AgentTaskRepository taskRepo;
  private final RunCostLogRepository runCostLogRepo;
  private final SseBroadcaster broadcaster;
  private final ObjectMapper objectMapper;

  void persistPlan(String runId, Plan plan) {
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              try {
                r.setPlanJson(objectMapper.writeValueAsString(plan));
              } catch (Exception e) {
                log.warn("RunOrchestrator run={} failed to serialize plan JSON", runId, e);
              }
              // v2.5.11 (Validation v3 / Pillar 1) — persist the enumerated
              // requirement graph alongside the plan JSON so the reviewer (Pillar
              // 2) and the UI (Pillar 5) can render per-requirement evidence.
              // Null when plan.requirements is empty (legacy / flag off).
              try {
                if (plan.requirements() != null && !plan.requirements().isEmpty()) {
                  r.setPlanRequirementsJson(objectMapper.writeValueAsString(plan.requirements()));
                }
              } catch (Exception e) {
                log.warn(
                    "RunOrchestrator run={} failed to serialize plan_requirements_json", runId, e);
              }
              runRepo.save(r);
            });
  }

  void persistTasks(String runId, Plan plan) {
    String prefix = runId.substring(0, 8) + "_";
    for (PlannedTask task : plan.tasks()) {
      String taskId = prefix + task.id();
      AgentTask row =
          AgentTask.builder()
              .taskId(taskId)
              .runId(runId)
              .title(task.title())
              .complexity(task.complexity().name())
              .status("PENDING")
              // v2.6.2 slice 5d — record the planner-shape metadata so
              // StartupRunResumer can tell EXPANDED vs PENDING tasks
              // apart on resume + a future auto-replay pass can skip
              // tasks already filled in.
              .taskType(task.taskType() == null ? "LEAF" : task.taskType().name())
              // EXPANDED for full-bodied tasks (slices 1-2 + LEGACY's
              // recursive children) and post-expansion LARGE tasks.
              // Skeleton-only tasks pre-expansion would be PENDING here —
              // but persistTasks runs after the LARGE pipeline has
              // already stitched bodies in, so all rows reach this point
              // with full descriptions. Distinction matters for future
              // resume: a row written EXPANDED never needs a re-expand.
              .expansionStatus(task.isSkeleton() ? "PENDING" : "EXPANDED")
              .expandedAt(task.isSkeleton() ? null : java.time.Instant.now())
              .build();
      try {
        row.setTaskJson(objectMapper.writeValueAsString(task));
      } catch (Exception e) {
        log.warn("RunOrchestrator run={} failed to serialize task {}", runId, taskId, e);
      }
      // v2.5.11 (Validation v3 / Pillar 1) — persist per-task requirement
      // coverage so downstream consumers (reviewer evidence, UI table)
      // can trace implementation back to spec requirements.
      try {
        if (task.meetsRequirements() != null && !task.meetsRequirements().isEmpty()) {
          row.setMeetsRequirementsJson(objectMapper.writeValueAsString(task.meetsRequirements()));
        }
      } catch (Exception e) {
        log.warn(
            "RunOrchestrator run={} failed to serialize meets_requirements for {}",
            runId,
            taskId,
            e);
      }
      taskRepo.save(row);
    }
  }

  /**
   * Map each {@link TaskOutcome} back to the corresponding {@link AgentTask} row and update status
   * + timestamps. Task IDs are prefixed with the run's first 8 chars so we reconstruct the DB id
   * from {@code PlannedTask.id}.
   */
  void applyExecutionResultToTasks(String runId, ExecutionResult result) {
    String prefix = runId.substring(0, 8) + "_";
    for (TaskOutcome outcome : result.perTask()) {
      String taskId = prefix + outcome.taskId();
      taskRepo
          .findById(taskId)
          .ifPresent(
              t -> {
                t.setStatus(mapTaskStatusToV1(outcome.status()));
                t.setCompletedAt(Instant.now());
                if (outcome.totalLatencyMs() > 0) {
                  t.setDurationMs((long) outcome.totalLatencyMs());
                }
                taskRepo.save(t);
              });
    }
  }

  void persistReview(String runId, ReviewerResult review) {
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              try {
                // Minimal serialization — full ReviewerResult has WorkerResult refs that
                // aren't Jackson-friendly; extract the user-facing fields only.
                Map<String, Object> summary =
                    Map.of(
                        "overall",
                        review.overall().name(),
                        "finalBuildPassed",
                        review.finalBuildPassed() != null && review.finalBuildPassed(),
                        "finalBuildExitCode",
                        review.finalBuildExitCode() == null ? -1 : review.finalBuildExitCode(),
                        "generalPassSummary",
                        review.generalPassSummary() == null ? "" : review.generalPassSummary(),
                        "wiringPassSummary",
                        review.wiringPassSummary() == null ? "" : review.wiringPassSummary(),
                        "totalLatencyMs",
                        review.totalLatencyMs());
                r.setReviewJson(objectMapper.writeValueAsString(summary));
              } catch (Exception e) {
                log.warn("RunOrchestrator run={} failed to serialize review JSON", runId, e);
              }
              runRepo.save(r);
            });
  }

  void persistReviewFailure(String runId, Exception e) {
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              try {
                Map<String, Object> summary =
                    Map.of("overall", "REVIEW_FAILED", "error", shortMsg(e));
                r.setReviewJson(objectMapper.writeValueAsString(summary));
              } catch (Exception ignored) {
              }
              runRepo.save(r);
            });
  }

  void setWorkspacePath(String runId, String path) {
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              r.setWorkspacePath(path);
              runRepo.save(r);
            });
    log.debug("RunOrchestrator run={} workspace={}", runId, path);
  }

  void persistRunTitle(String runId, Plan plan) {
    if (plan.planTitle() == null || plan.planTitle().isBlank()) return;
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              r.setRunTitle(plan.planTitle());
              runRepo.save(r);
            });
  }

  ExecutionResult reconstructExecResultFromTasks(String runId) {
    var tasks = taskRepo.findByRunId(runId);
    int success = 0;
    int warn = 0;
    int failed = 0;
    int skipped = 0;
    for (var t : tasks) {
      String s = t.getStatus() == null ? "COMPLETE" : t.getStatus();
      switch (s) {
        case "COMPLETE" -> success++;
        case "PARTIAL" -> warn++;
        case "FAILED" -> failed++;
        case "BLOCKED", "SKIPPED" -> skipped++;
        default -> success++;
      }
    }
    ExecutionResult.Overall overall;
    if (failed > 0 && success == 0) overall = ExecutionResult.Overall.FAILED;
    else if (failed > 0) overall = ExecutionResult.Overall.PARTIAL_WITH_WARNINGS;
    else if (warn > 0) overall = ExecutionResult.Overall.PARTIAL_WITH_WARNINGS;
    else overall = ExecutionResult.Overall.ALL_SUCCESS;
    return ExecutionResult.builder()
        .overall(overall)
        .tasksExecuted(tasks.size() - skipped)
        .tasksSuccessful(success)
        .tasksWithWarning(warn)
        .tasksFailed(failed)
        .tasksSkipped(skipped)
        .perTask(java.util.List.of())
        .totalLatencyMs(0L)
        .finalTaskStatus(null)
        .anyIntermediateWarning(warn > 0)
        .build();
  }

  void updateStatus(String runId, String newStatus) {
    boolean[] changed = {false};
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              String current = r.getStatus();
              if (isTerminal(current) || "PAUSED".equals(current)) {
                log.info(
                    "RunOrchestrator run={} status update {} ignored; current status is {}",
                    runId,
                    newStatus,
                    current);
                return;
              }
              r.setStatus(newStatus);
              runRepo.save(r);
              changed[0] = true;
            });
    if (changed[0]) {
      broadcaster.emit(runId, RunEvent.runStatus(runId, newStatus));
    }
  }

  void writeRunCostLog(PipelineRun r, RunCostTracker.Snapshot cost) {
    try {
      double estimated = r.getCostEstimateHigh() != null ? r.getCostEstimateHigh() : 0.0;
      double hardCap = r.getHardCapUsd() != null ? r.getHardCapUsd() : 0.0;
      int credits = r.getCreditsCharged() != null ? r.getCreditsCharged() : 1;
      boolean budgetHit = hardCap > 0 && cost.costUsd() >= hardCap;
      RunCostLog row =
          RunCostLog.builder()
              .runId(r.getRunId())
              .userId(r.getCreatedByUserId() != null ? r.getCreatedByUserId() : "unknown")
              .orgId(r.getOrgId())
              .provider(r.getAiProvider() != null ? r.getAiProvider() : "unknown")
              .estimatedCost(estimated)
              .actualCost(cost.costUsd())
              .hardCap(hardCap)
              .creditsCharged(credits)
              .status(r.getStatus())
              .taskCount(0)
              .tasksCompleted(0)
              .budgetHit(budgetHit)
              .marginUsd(estimated - cost.costUsd())
              .executionMode("REALTIME")
              .batchDiscount(0.0)
              .createdAt(Instant.now())
              .build();
      runCostLogRepo.save(row);
    } catch (Exception e) {
      log.warn(
          "RunOrchestrator run={} failed to write RunCostLog: {}", r.getRunId(), e.getMessage());
    }
  }
}
