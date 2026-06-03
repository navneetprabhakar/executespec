package com.navneet.executespec.integration;

import static com.navneet.executespec.integration.RunPipelineSupport.coerceToLeafFallback;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Classification;
import com.navneet.executespec.agent.ClassificationResult;
import com.navneet.executespec.agent.CoherencePlannerAgent;
import com.navneet.executespec.agent.OutlinePlannerAgent;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlanStrategy;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.PlannerRequest;
import com.navneet.executespec.agent.SmartClassifierAgent;
import com.navneet.executespec.agent.TaskExpanderAgent;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.memory.ProjectMemoryProvider;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.tool.ExecutionContext;

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
 * <p>inc-4b also moved the LARGE-pipeline driver ({@code runLargePipeline}) + EPIC recursion
 * ({@code expandEpic}) here: both fan out task expansion across the now-shared {@link
 * PipelineExecutorPool} bean (lifted out of RunOrchestrator in the same increment), so they belong
 * with the other planning phases. Behaviour byte-identical to the originals.
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
  private final TaskExpanderAgent taskExpander;
  private final CoherencePlannerAgent coherencePlanner;
  private final PipelineExecutorPool pool;

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

  /**
   * v2.6.2 slice 3 — LARGE pipeline. Drives:
   *
   * <ol>
   *   <li>Phase 1: outline planner in SKELETON_ONLY mode (id/title/complexity/ repos/dependsOn/type
   *       only).
   *   <li>Phase 2: parallel expansion of every LEAF task via {@link TaskExpanderAgent}. Bounded
   *       concurrency via the existing pipeline pool. EPIC tasks are NOT recursed yet — slice 4
   *       brings depth-2 expansion; for slice 3 they're treated as LEAFs.
   *   <li>Phase 3: coherence pass via {@link CoherencePlannerAgent} + apply any patches.
   * </ol>
   *
   * <p>Per-phase cost is diff'd from {@link RunCostTracker#snapshot} and persisted to the matching
   * {@code pipeline_run.{outline,expansion,coherence}_*} columns. Run total auto-aggregates via the
   * tracker's accumulator.
   */
  Plan runLargePipeline(
      String runId, PlannerRequest req, ExecutionContext rootCtx, boolean enableEpicRecursion) {
    Plan skeleton = runOutlinePhase(runId, req, OutlinePlannerAgent.Mode.SKELETON_ONLY);
    if (skeleton.tasks() == null || skeleton.tasks().isEmpty()) {
      throw new IllegalStateException("LARGE outline returned 0 skeleton tasks");
    }

    // For LEAF tasks: a single TaskExpanderAgent call fills the body.
    // For EPIC tasks under LEGACY recursion (slice 4): recurse via
    // OutlinePlannerAgent (skeleton-mode) to get child skeletons, then
    // expand each child as a LEAF in parallel. Children inherit the
    // parent's id as a prefix (T3 → T3.1, T3.2, …). Without LEGACY
    // recursion, EPIC tasks are treated identically to LEAF tasks (slice 3
    // behaviour) — the worker either handles the breadth or the planner
    // declared EPIC mistakenly.
    RunCostTracker.Snapshot beforeExpand = costTracker.snapshot(runId);
    long expansionStart = System.currentTimeMillis();
    List<PlannedTask> tasksToExpand = skeleton.tasks();
    // Per-skeleton-id → list of resulting LEAF tasks (1 for LEAF, N for
    // recursively-expanded EPIC).
    java.util.concurrent.ConcurrentMap<String, List<PlannedTask>> expanded =
        new java.util.concurrent.ConcurrentHashMap<>();
    java.util.concurrent.ConcurrentMap<String, Throwable> expansionFailures =
        new java.util.concurrent.ConcurrentHashMap<>();

    List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
    for (PlannedTask t : tasksToExpand) {
      futures.add(
          java.util.concurrent.CompletableFuture.runAsync(
              () -> {
                try {
                  if (enableEpicRecursion && t.taskType() == PlannedTask.TaskType.EPIC) {
                    List<PlannedTask> children = expandEpic(runId, t, skeleton, req, rootCtx);
                    expanded.put(t.id(), children);
                  } else {
                    ExecutionContext taskCtx =
                        new ExecutionContext(runId, "_expand_" + t.id(), rootCtx.workspaceRoot());
                    PlannedTask full = taskExpander.expand(t, skeleton, req, taskCtx);
                    expanded.put(t.id(), List.of(full));
                  }
                } catch (Throwable ex) {
                  log.warn(
                      "V2 run={} task expansion failed for {}: {}", runId, t.id(), ex.getMessage());
                  expansionFailures.put(t.id(), ex);
                  // Fall back to a LEAF carrying just the title-derived
                  // description so the pipeline can still progress.
                  expanded.put(
                      t.id(),
                      List.of(
                          PlannedTask.builder()
                              .id(t.id())
                              .title(t.title())
                              .description(
                                  "Implement: "
                                      + t.title()
                                      + " (expansion failed; worker will figure it out)")
                              .complexity(t.complexity())
                              .touchesFiles(t.touchesFiles())
                              .dependsOn(t.dependsOn())
                              .hardConstraints(List.of())
                              .meetsRequirements(List.of())
                              .taskType(PlannedTask.TaskType.LEAF)
                              .build()));
                }
              },
              pool.executor()));
    }
    try {
      java.util.concurrent.CompletableFuture.allOf(
              futures.toArray(new java.util.concurrent.CompletableFuture[0]))
          .get(20, java.util.concurrent.TimeUnit.MINUTES);
    } catch (Exception expandWaitEx) {
      throw new IllegalStateException(
          "LARGE expansion phase exceeded 20-minute timeout: " + expandWaitEx.getMessage(),
          expandWaitEx);
    }

    // Stitch expanded bodies back onto the plan in original order. EPIC
    // entries are replaced by their N child LEAFs in-place.
    List<PlannedTask> assembledTasks = new ArrayList<>(tasksToExpand.size());
    for (PlannedTask sk : tasksToExpand) {
      List<PlannedTask> result = expanded.get(sk.id());
      if (result == null || result.isEmpty()) {
        assembledTasks.add(sk);
      } else {
        assembledTasks.addAll(result);
      }
    }
    Plan assembled =
        Plan.builder()
            .planTitle(skeleton.planTitle())
            .planSummary(skeleton.planSummary())
            .projectType(skeleton.projectType())
            .buildCommand(skeleton.buildCommand())
            .smokeTestStrategy(skeleton.smokeTestStrategy())
            .runtimeSurface(skeleton.runtimeSurface())
            .tasks(assembledTasks)
            .classification(skeleton.classification())
            .requirements(skeleton.requirements())
            .build();

    RunCostTracker.Snapshot afterExpand = costTracker.snapshot(runId);
    long expandIn = Math.max(0, afterExpand.inputTokens() - beforeExpand.inputTokens());
    long expandOut = Math.max(0, afterExpand.outputTokens() - beforeExpand.outputTokens());
    double expandCost = Math.max(0.0, afterExpand.costUsd() - beforeExpand.costUsd());
    log.info(
        "V2 run={} expansion: {} tasks, {} failures, wall={}ms, in/out={}/{}, ${}",
        runId,
        assembledTasks.size(),
        expansionFailures.size(),
        System.currentTimeMillis() - expansionStart,
        expandIn,
        expandOut,
        String.format("%.6f", expandCost));

    RunCostTracker.Snapshot beforeCoherence = costTracker.snapshot(runId);
    ExecutionContext coherenceCtx =
        new ExecutionContext(runId, "_coherence_", rootCtx.workspaceRoot());
    CoherencePlannerAgent.PatchResult patchResult;
    try {
      patchResult = coherencePlanner.patchIfNeeded(assembled, req, coherenceCtx);
    } catch (Exception coherenceEx) {
      log.warn(
          "V2 run={} coherence pass failed (non-fatal — using unpatched plan): {}",
          runId,
          coherenceEx.getMessage());
      patchResult = new CoherencePlannerAgent.PatchResult(assembled, 0, List.of());
    }
    RunCostTracker.Snapshot afterCoherence = costTracker.snapshot(runId);
    long cohIn = Math.max(0, afterCoherence.inputTokens() - beforeCoherence.inputTokens());
    long cohOut = Math.max(0, afterCoherence.outputTokens() - beforeCoherence.outputTokens());
    double cohCost = Math.max(0.0, afterCoherence.costUsd() - beforeCoherence.costUsd());

    log.info(
        "V2 run={} coherence: {} patches applied, in/out={}/{}, ${}",
        runId,
        patchResult.patchesApplied(),
        cohIn,
        cohOut,
        String.format("%.6f", cohCost));

    // Persist per-phase costs + final plan
    try {
      PipelineRun fresh = runRepo.findById(runId).orElse(null);
      if (fresh != null) {
        fresh.setExpansionInputTokens(expandIn);
        fresh.setExpansionOutputTokens(expandOut);
        fresh.setExpansionCostUsd(expandCost);
        fresh.setCoherenceInputTokens(cohIn);
        fresh.setCoherenceOutputTokens(cohOut);
        fresh.setCoherenceCostUsd(cohCost);
        try {
          fresh.setPlanOutlineJson(objectMapper.writeValueAsString(patchResult.patchedPlan()));
        } catch (Exception serEx) {
          log.warn(
              "V2 run={} plan_outline_json (post-expand) serialisation failed: {}",
              runId,
              serEx.getMessage());
        }
        runRepo.save(fresh);
      }
    } catch (Exception persistEx) {
      log.warn(
          "V2 run={} expansion/coherence persist failed (non-fatal): {}",
          runId,
          persistEx.getMessage());
    }

    return patchResult.patchedPlan();
  }

  /**
   * v2.6.2 slice 4 — depth-2 recursive expansion for an EPIC skeleton task. The orchestrator
   * detects EPICs after the top-level skeleton outline, makes a focused sub-outline call ({@link
   * OutlinePlannerAgent.Mode#SKELETON_ONLY}) for just this epic, then expands each sub-skeleton as
   * a LEAF in parallel. Sub-task IDs are prefixed with the parent's id (e.g. T3 → T3.1, T3.2, …) so
   * the assembled plan keeps stable references for downstream {@code dependsOn} resolution.
   *
   * <p>Recursion stops at depth 2: if a child of an EPIC also comes back marked EPIC, it's coerced
   * to LEAF and expanded normally. We prevent runaway planning trees from a hallucinating outline
   * planner.
   */
  private List<PlannedTask> expandEpic(
      String runId,
      PlannedTask epic,
      Plan parentPlan,
      PlannerRequest parentReq,
      ExecutionContext rootCtx) {
    log.info("V2 run={} expandEpic {} → recursing", runId, epic.id());

    String subSpec = "Sub-task: " + epic.title();
    if (epic.description() != null && !epic.description().isBlank()) {
      subSpec += "\n\nFurther context: " + epic.description();
    }
    // Add parent-plan summary so the sub-planner knows the broader scope.
    if (parentPlan.planSummary() != null && !parentPlan.planSummary().isBlank()) {
      subSpec += "\n\nParent plan: " + parentPlan.planSummary();
    }

    PlannerRequest subReq =
        PlannerRequest.builder()
            .spec(subSpec)
            // MEDIUM caps sub-tasks at 4 (per Classification.maxTasks); a
            // sub-outline that overflows is almost always a sign the
            // planner mistakenly marked something EPIC that should have
            // been LEAF. Better to truncate than blow the budget.
            .classification(Classification.MEDIUM)
            .provider(parentReq.provider())
            .model(parentReq.model())
            .context(parentReq.context())
            .workspaceSummary(parentReq.workspaceSummary())
            .projectMemory(parentReq.projectMemory())
            .projectBrief(parentReq.projectBrief())
            .referenceDocs(parentReq.referenceDocs())
            .contracts(parentReq.contracts())
            .projectAdrs(parentReq.projectAdrs())
            .tddMode(parentReq.tddMode())
            .build();

    Plan subSkeleton;
    try {
      subSkeleton = outlinePlanner.plan(subReq, OutlinePlannerAgent.Mode.SKELETON_ONLY);
    } catch (Exception subOutlineEx) {
      log.warn(
          "V2 run={} sub-outline for EPIC {} failed — falling back to single LEAF expansion: {}",
          runId,
          epic.id(),
          subOutlineEx.getMessage());
      return List.of(coerceToLeafFallback(epic));
    }

    List<PlannedTask> children = subSkeleton.tasks();
    if (children == null || children.isEmpty()) {
      log.warn(
          "V2 run={} sub-outline for EPIC {} returned 0 children — coercing to single LEAF",
          runId,
          epic.id());
      return List.of(coerceToLeafFallback(epic));
    }

    // Re-id with parent prefix and force LEAF (depth cap).
    List<PlannedTask> reIdChildren = new ArrayList<>(children.size());
    for (int i = 0; i < children.size(); i++) {
      PlannedTask c = children.get(i);
      String childId = epic.id() + "." + (i + 1);
      reIdChildren.add(
          PlannedTask.builder()
              .id(childId)
              .title(c.title())
              .description(c.description())
              .complexity(c.complexity() == null ? PlannedTask.Complexity.MEDIUM : c.complexity())
              .touchesFiles(c.touchesFiles())
              .dependsOn(c.dependsOn())
              .hardConstraints(c.hardConstraints())
              .meetsRequirements(c.meetsRequirements())
              .taskType(PlannedTask.TaskType.LEAF) // depth cap: no further recursion
              .build());
    }

    // Build a sub-plan view for the expander prompt so siblings cross-reference correctly.
    Plan subPlanForContext =
        Plan.builder()
            .planTitle(parentPlan.planTitle())
            .planSummary(parentPlan.planSummary())
            .projectType(parentPlan.projectType())
            .buildCommand(parentPlan.buildCommand())
            .smokeTestStrategy(parentPlan.smokeTestStrategy())
            .runtimeSurface(parentPlan.runtimeSurface())
            .tasks(reIdChildren)
            .classification(parentPlan.classification())
            .requirements(parentPlan.requirements())
            .build();

    // Expand each child in parallel. Reuses the same pipeline pool — bounded
    // concurrency means we won't thrash the LLM provider even on big EPICs.
    java.util.concurrent.ConcurrentMap<String, PlannedTask> bodied =
        new java.util.concurrent.ConcurrentHashMap<>();
    List<java.util.concurrent.CompletableFuture<Void>> subFutures = new ArrayList<>();
    for (PlannedTask child : reIdChildren) {
      subFutures.add(
          java.util.concurrent.CompletableFuture.runAsync(
              () -> {
                try {
                  ExecutionContext childCtx =
                      new ExecutionContext(runId, "_expand_" + child.id(), rootCtx.workspaceRoot());
                  bodied.put(
                      child.id(),
                      taskExpander.expand(child, subPlanForContext, parentReq, childCtx));
                } catch (Throwable ex) {
                  log.warn(
                      "V2 run={} sub-task expansion failed for {}: {}",
                      runId,
                      child.id(),
                      ex.getMessage());
                  bodied.put(
                      child.id(),
                      PlannedTask.builder()
                          .id(child.id())
                          .title(child.title())
                          .description(
                              "Implement: "
                                  + child.title()
                                  + " (sub-expansion failed; worker will figure it out)")
                          .complexity(child.complexity())
                          .touchesFiles(child.touchesFiles())
                          .dependsOn(child.dependsOn())
                          .hardConstraints(List.of())
                          .meetsRequirements(List.of())
                          .taskType(PlannedTask.TaskType.LEAF)
                          .build());
                }
              },
              pool.executor()));
    }
    try {
      java.util.concurrent.CompletableFuture.allOf(
              subFutures.toArray(new java.util.concurrent.CompletableFuture[0]))
          .get(10, java.util.concurrent.TimeUnit.MINUTES);
    } catch (Exception subExpandEx) {
      log.warn(
          "V2 run={} sub-expansion for EPIC {} hit timeout/error — using whatever completed: {}",
          runId,
          epic.id(),
          subExpandEx.getMessage());
    }

    // Stitch sub-children in input order.
    List<PlannedTask> result = new ArrayList<>(reIdChildren.size());
    for (PlannedTask c : reIdChildren) {
      result.add(bodied.getOrDefault(c.id(), c));
    }
    log.info("V2 run={} expandEpic {} → {} LEAF children", runId, epic.id(), result.size());
    return result;
  }
}
