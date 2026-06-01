package com.navneet.executespec.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.ClassificationResult;
import com.navneet.executespec.agent.OutlinePlannerAgent;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlanStrategy;
import com.navneet.executespec.agent.PlannerRequest;
import com.navneet.executespec.agent.SmartClassifierAgent;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.memory.ProjectMemoryProvider;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Hierarchical-planner phase helpers extracted from {@link RunOrchestrator} (god-class split,
 * increment 4 — audit #43). The two **pool-free** planning phases: the smart-classifier shadow run
 * (phase 0) and the outline-planner call (phase 1), each of which snapshots per-run cost before +
 * after the agent call and persists the verdict + per-phase token attribution. Byte-identical to
 * the originals.
 *
 * <p>The LARGE-pipeline driver (`runLargePipeline`) + EPIC recursion (`expandEpic`) intentionally
 * STAY in RunOrchestrator: both fan out task expansion across the run-orchestration {@code pool}
 * (an internally-owned {@code ExecutorService}, not a bean), so they belong with the pool owner. A
 * future pass can lift the pool to a shared bean and move them then.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class RunPlanningCoordinator {

  private final PipelineRunRepository runRepo;
  private final ObjectMapper objectMapper;
  private final RunCostTracker costTracker;
  private final SmartClassifierAgent smartClassifier;
  private final OutlinePlannerAgent outlinePlanner;
  private final ProjectRepoService projectRepoService;
  private final ProjectMemoryProvider projectMemoryProvider;

  PlanStrategy runClassifierShadow(
      String runId,
      PipelineRun runRow,
      String userRequest,
      String projectId,
      List<UUID> selectedRepoIds,
      String provider) {
    try {
      // Repo full names — best effort. Skip lookup if not multi-repo.
      List<String> repoNames = new ArrayList<>();
      if (projectId != null && selectedRepoIds != null && !selectedRepoIds.isEmpty()) {
        try {
          List<ProjectRepo> repos = projectRepoService.resolveForRun(projectId, selectedRepoIds);
          for (ProjectRepo r : repos) {
            if (r.getGithubRepoFullName() != null) repoNames.add(r.getGithubRepoFullName());
          }
        } catch (Exception ignored) {
          /* fall through with empty list */
        }
      }

      // Project type — Project entity doesn't carry one yet (slice 1
      // skips this signal). Future slices will plumb it through from
      // detected_stack on linked repos.
      String projectType = null;

      // Project memory snippet — short tail of last 3 ledger entries.
      String projectMemorySnippet = null;
      if (projectId != null) {
        try {
          String full = projectMemoryProvider.buildPlannerContext(projectId);
          if (full != null && !full.isBlank()) {
            // Cap at ~600 chars so the classifier prompt stays compact.
            projectMemorySnippet = full.length() > 600 ? full.substring(full.length() - 600) : full;
          }
        } catch (Exception ignored) {
          /* leave null */
        }
      }

      com.navneet.executespec.router.LlmProvider providerEnum;
      try {
        providerEnum =
            com.navneet.executespec.router.LlmProvider.valueOf(
                provider == null ? "OPENAI" : provider.toUpperCase());
      } catch (Exception e) {
        providerEnum = com.navneet.executespec.router.LlmProvider.OPENAI;
      }

      RunCostTracker.Snapshot before = costTracker.snapshot(runId);
      ClassificationResult result =
          smartClassifier.classify(
              userRequest, repoNames, projectId, projectType, providerEnum, projectMemorySnippet);
      RunCostTracker.Snapshot after = costTracker.snapshot(runId);

      long classifierIn = Math.max(0, after.inputTokens() - before.inputTokens());
      long classifierOut = Math.max(0, after.outputTokens() - before.outputTokens());
      double classifierCost = Math.max(0.0, after.costUsd() - before.costUsd());

      log.info(
          "V2 run={} smart classifier → {} (source={}, est tasks={}, repos={}, in/out={}/{}, ${})",
          runId,
          result.strategy(),
          result.source(),
          result.estimatedTaskCount(),
          result.estimatedRepoTouchCount(),
          classifierIn,
          classifierOut,
          String.format("%.6f", classifierCost));

      // Persist verdict + per-phase cost on the run row. Use a fresh load
      // so we don't overwrite columns that other writers may have touched
      // (initialisation log, codemap summary, etc.).
      try {
        PipelineRun fresh = runRepo.findById(runId).orElse(null);
        if (fresh != null) {
          fresh.setPlanStrategy(result.strategy().name());
          fresh.setClassifierReasoning(result.reasoning());
          fresh.setClassifierInputTokens(classifierIn);
          fresh.setClassifierOutputTokens(classifierOut);
          fresh.setClassifierCostUsd(classifierCost);
          runRepo.save(fresh);
        }
      } catch (Exception persistEx) {
        log.warn(
            "V2 run={} smart classifier persist failed (non-fatal): {}",
            runId,
            persistEx.getMessage());
      }
      return result.strategy();
    } catch (Exception e) {
      log.warn(
          "V2 run={} smart classifier shadow run failed (non-fatal): {}", runId, e.getMessage());
      return null;
    }
  }

  /**
   * v2.6.2 — hierarchical-planner phase 1. Snapshots per-run cost before + after the {@link
   * OutlinePlannerAgent} call, attributes the diff to {@code pipeline_run.outline_*} columns +
   * persists the plan JSON to {@code plan_outline_json}. Failures bubble up so the orchestrator can
   * fall back to the legacy planner.
   */
  Plan runOutlinePhase(String runId, PlannerRequest req, OutlinePlannerAgent.Mode mode) {
    RunCostTracker.Snapshot before = costTracker.snapshot(runId);
    try {
      Plan plan = outlinePlanner.plan(req, mode);
      RunCostTracker.Snapshot after = costTracker.snapshot(runId);

      long inTok = Math.max(0, after.inputTokens() - before.inputTokens());
      long outTok = Math.max(0, after.outputTokens() - before.outputTokens());
      double cost = Math.max(0.0, after.costUsd() - before.costUsd());

      log.info(
          "V2 run={} outline planner [{}] → {} tasks, in/out={}/{}, ${}",
          runId,
          mode,
          plan.tasks().size(),
          inTok,
          outTok,
          String.format("%.6f", cost));

      try {
        PipelineRun fresh = runRepo.findById(runId).orElse(null);
        if (fresh != null) {
          try {
            fresh.setPlanOutlineJson(objectMapper.writeValueAsString(plan));
          } catch (Exception serEx) {
            log.warn(
                "V2 run={} plan_outline_json serialisation failed: {}", runId, serEx.getMessage());
          }
          fresh.setOutlineInputTokens(inTok);
          fresh.setOutlineOutputTokens(outTok);
          fresh.setOutlineCostUsd(cost);
          runRepo.save(fresh);
        }
      } catch (Exception persistEx) {
        log.warn("V2 run={} outline persist failed (non-fatal): {}", runId, persistEx.getMessage());
      }
      return plan;
    } catch (Exception e) {
      log.warn(
          "V2 run={} outline planner failed: {} — bubbling up for fallback", runId, e.getMessage());
      throw e;
    }
  }
}
