package com.navneet.executespec.execution.service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.codeintel.ProjectArchitectureService;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.execution.dto.CreateExecutionRequest;
import com.navneet.executespec.execution.dto.NewRepoSpec;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.execution.entity.ExecutionSolutionRepo;
import com.navneet.executespec.execution.event.ExecutionEventTypes;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * End-to-end orchestration for an {@link ExecutionRun} — setup → analysis → arch review → plan gen
 * → plan approval → execution → completion.
 *
 * <p>Each async phase is a {@code @Async} virtual-thread call that emits SSE milestones on the
 * execution-scoped channel {@code exec:{runId}} and mutates the status column so the frontend can
 * drive its phased stepper.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionOrchestrator {

  private final ExecutionRunRepository runRepo;
  private final ExecutionSolutionRepository solutionRepo;
  private final ProjectArchitectureService archService;
  private final ProjectRepoRepository projectRepoRepo;
  private final ProjectRepoService projectRepoService;
  private final AppUserRepository appUserRepo;
  private final SolutionDependencyResolver depResolver;
  private final SolutionExecutionService solutionExecService;
  private final ExecutionBranchService branchService;
  private final SseBroadcaster broadcaster;
  private final com.navneet.executespec.repo.GitHubRepoService gitHub;
  private final com.navneet.executespec.integration.RunOrchestrator v2Orchestrator;
  private final com.navneet.executespec.project.pr.PullRequestRecordRepository prRecordRepo;
  private final ExecutionRunCasUpdater runCas;
  private final com.navneet.executespec.state.repository.PipelineRunRepository pipelineRunRepo;
  private final com.navneet.executespec.harness.GateStore gateStore;
  private final ExecutionPlannerService plannerService;

  /**
   * V188 (2026-05-13) — toggle for the unified V2-dispatch path. When true (default), V3 launches
   * dispatch a single V2 run rather than fanning out per-{@link ExecutionSolution}. The legacy
   * Solution-dispatch path is still wired for existing executions and emergency rollback.
   */
  @org.springframework.beans.factory.annotation.Value("${execution.unified-dispatch.enabled:true}")
  private boolean unifiedDispatchEnabled;

  /**
   * V188 (2026-05-13) — V2 terminal status set. Mirrored from {@code
   * SolutionExecutionService.V2_TERMINAL}; kept private here so the unified dispatch path doesn't
   * cross-depend on the per-Solution executor we're retiring.
   */
  private static final java.util.Set<String> V2_TERMINAL =
      java.util.Set.of("COMPLETE", "PARTIAL", "FAILED", "CANCELLED", "ABORTED");

  private static final long UNIFIED_POLL_INTERVAL_MS = 5_000L;
  private static final long UNIFIED_MAX_WAIT_MS = 4L * 60L * 60L * 1000L; // 4 hours

  // ── Boot-time crash resume ─────────────────────────────────────────────

  /**
   * 2026-05-04 — boot-time recovery for ExecutionRuns interrupted mid-EXECUTING. Pairs with the V2
   * PipelineRun resume in {@code StartupRunResumer}: if a Launch's solutions had spawned V2
   * sub-runs that were in flight at the previous JVM's death, those V2 runs are resumed by V2's
   * boot listener. This listener handles the Launch-level orchestration on top:
   *
   * <ul>
   *   <li>Find every {@link ExecutionRun} in {@code EXECUTING} status (the only resumable
   *       intermediate state at the Launch level — earlier phases like SETUP / ANALYSING /
   *       ARCH_REVIEW / PLANNING are non-resumable today and are left to {@code
   *       AdminReconciliationService.onApplicationReady} to fail-clean).
   *   <li>For each: re-call {@link #executeApprovedPlan(String)} which is itself idempotent — it
   *       skips solutions that aren't {@code QUEUED} (so {@code DONE} / {@code FAILED} / {@code
   *       RUNNING} solutions are left alone) and re-dispatches the rest. {@code RUNNING} solutions
   *       whose underlying V2 PipelineRun was killed pre-crash will be picked up via V2 resume;
   *       their completion event flows back through {@code SolutionExecutionService} and advances
   *       the Launch.
   * </ul>
   *
   * <p>{@code @Async} so the boot path isn't blocked. Exceptions never propagate to startup.
   */
  @Async
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    try {
      List<ExecutionRun> resumable =
          runRepo.findStaleNonTerminal(java.time.Instant.now()).stream()
              .filter(r -> "EXECUTING".equals(r.getStatus()))
              .toList();
      if (resumable.isEmpty()) {
        log.info("ExecutionOrchestrator boot-resume: no EXECUTING ExecutionRuns to resume");
        return;
      }
      log.warn(
          "ExecutionOrchestrator boot-resume: re-dispatching {} EXECUTING ExecutionRun(s)",
          resumable.size());
      for (ExecutionRun r : resumable) {
        try {
          log.info(
              "ExecutionOrchestrator boot-resume: re-dispatching exec={} (status was EXECUTING"
                  + " at boot)",
              r.getId());
          executeApprovedPlan(r.getId());
        } catch (Exception e) {
          // Per-row safety net — one bad row never blocks the rest.
          log.error(
              "ExecutionOrchestrator boot-resume: failed to re-dispatch exec={}: {}",
              r.getId(),
              e.getMessage());
        }
      }
    } catch (Exception outer) {
      log.error("ExecutionOrchestrator boot-resume: scan failed", outer);
    }
  }

  // ── Creation ───────────────────────────────────────────────────────────

  public ExecutionRun createExecution(Project project, AppUser user, CreateExecutionRequest req) {
    // Pre-create any brand-new repos before we persist the ExecutionRun —
    // failures here abort the Launch synchronously so the user can retry.
    java.util.List<String> newRepoNotes = new java.util.ArrayList<>();
    java.util.List<String> effectiveRepoIds =
        req.repoIds() == null
            ? new java.util.ArrayList<>()
            : new java.util.ArrayList<>(req.repoIds());

    if (req.newRepos() != null) {
      String pat = appUserRepo.findById(user.getId()).map(u -> u.getGithubPat()).orElse(null);
      for (NewRepoSpec spec : req.newRepos()) {
        if (spec == null || spec.name() == null || spec.name().isBlank()) continue;
        // Owner fallback: explicit spec → project default → user default.
        String owner = spec.githubOwner();
        if (owner == null || owner.isBlank()) owner = project.getGithubOwner();
        if (owner == null || owner.isBlank()) owner = user.getGithubOwner();
        ProjectRepo created =
            projectRepoService.createAndLinkNewRepo(
                project.getId(),
                spec.name(),
                spec.description(),
                owner,
                spec.visibility(),
                pat,
                user.getId(),
                project.getOrgId());
        effectiveRepoIds.add(created.getId().toString());
        newRepoNotes.add(
            "- "
                + created.getGithubRepoFullName()
                + " ("
                + (spec.techHint() == null ? "unspecified" : spec.techHint())
                + ")"
                + (spec.description() == null ? "" : " — " + spec.description()));
      }
    }

    // Continue-on-PR: resolve branch from the PR record so the clone targets
    // the existing PR branch. The user's explicit branch field takes precedence.
    String branch = blankToNull(req.branch());
    if (branch == null && req.continuePrNumber() != null && req.continuePrRepoFullName() != null) {
      branch =
          prRecordRepo
              .findByRepoFullNameAndPrNumber(req.continuePrRepoFullName(), req.continuePrNumber())
              .map(pr -> pr.getHeadBranch())
              .orElse(null);
    }

    ExecutionRun run =
        ExecutionRun.builder()
            .projectId(project.getId())
            .orgId(project.getOrgId())
            .createdBy(user.getId())
            .status("SETUP")
            .requirement(
                composeRequirement(
                    req.requirement(), newRepoNotes, req.confluenceUrl(), req.figmaUrl()))
            .jiraEnabled(Boolean.TRUE.equals(req.jiraEnabled()))
            .confluenceUrl(blankToNull(req.confluenceUrl()))
            .figmaUrl(blankToNull(req.figmaUrl()))
            .aiProvider(resolveProvider(req.aiProvider(), project))
            .branch(branch)
            .sourceExecutionId(blankToNull(req.sourceExecutionId()))
            .continuePrNumber(req.continuePrNumber())
            .continuePrRepo(blankToNull(req.continuePrRepoFullName()))
            // R-324 — persist the launch-time repo selection (existing
            // links + any newly-minted repos from req.newRepos()) so
            // the planner can scope decomposition + the SSE/UI can
            // surface what's actually in scope. NULL when the user
            // didn't select anything (every linked repo is in scope).
            .selectedRepoIds(serializeRepoIds(effectiveRepoIds))
            .build();
    return runRepo.save(run);
  }

  /**
   * R-324 — serialise a list of repo IDs to a JSON string for storage in the {@code
   * execution_run.selected_repo_ids} JSONB column. Returns {@code null} when the list is null or
   * empty (which the column treats as "no explicit selection — every linked repo is in scope").
   *
   * <p>Plain string concatenation is safe here because every entry is a UUID-shaped string, which
   * has no characters needing JSON escaping.
   */
  private static String serializeRepoIds(java.util.List<String> ids) {
    if (ids == null || ids.isEmpty()) return null;
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < ids.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append('"').append(ids.get(i)).append('"');
    }
    return sb.append(']').toString();
  }

  private String composeRequirement(
      String requirement,
      java.util.List<String> newRepoNotes,
      String confluenceUrl,
      String figmaUrl) {
    StringBuilder sb = new StringBuilder(requirement == null ? "" : requirement);
    if (newRepoNotes != null && !newRepoNotes.isEmpty()) {
      sb.append("\n\nNEW REPOS IN THIS LAUNCH (must be scaffolded as first solution per repo):\n");
      for (String note : newRepoNotes) sb.append(note).append("\n");
    }
    String cleanConfluence = blankToNull(confluenceUrl);
    String cleanFigma = blankToNull(figmaUrl);
    if (cleanConfluence != null || cleanFigma != null) {
      sb.append("\n\nADDITIONAL CONTEXT:\n");
      if (cleanConfluence != null)
        sb.append("- Confluence spec: ").append(cleanConfluence).append("\n");
      if (cleanFigma != null) sb.append("- Figma design:    ").append(cleanFigma).append("\n");
    }
    return sb.toString();
  }

  private String blankToNull(String s) {
    return (s == null || s.isBlank()) ? null : s.trim();
  }

  /** Override wins; otherwise fall back to project's default. */
  private String resolveProvider(String override, Project project) {
    String clean = blankToNull(override);
    if (clean != null) return clean.toUpperCase();
    return project.getAiProvider() != null ? project.getAiProvider().name() : null;
  }

  // ── Phase 2: analysis ─────────────────────────────────────────────────

  @Async
  public void triggerAnalysis(String executionRunId) {
    // 2026-04-26 hotfix — extends the prior @Version retry pattern
    // (applyTerminalStatus / executeApprovedPlan, see hotfix branch
    // feature/fix-execution-orchestrator-stale-state-2026-04-25) to the
    // analysis path. Reproducible with 3 parallel /api/executions/{id}/
    // analyse calls in the project eval (2026-04-26 session): the
    // long-running archService.generate(...) call straddles concurrent
    // writes to the same execution_run row (SSE update, project-level
    // analyse callback). The original code held a stale `run` reference
    // for the entire generate call and saved it twice — both saves
    // OptimisticLockExceptions, leaving the run wedged at ANALYSING.
    // Fix: every save goes through ExecutionRunCasUpdater (shared bean,
    // promoted from this class on 2026-04-26), which refetches the row
    // + applies a mutator + retries up to 3 times on stale.
    ExecutionRun snapshot = runRepo.findById(executionRunId).orElse(null);
    if (snapshot == null) return;
    String projectId = snapshot.getProjectId();
    String createdBy = snapshot.getCreatedBy();

    boolean markedAnalysing = runCas.casUpdate(executionRunId, r -> r.setStatus("ANALYSING"));
    if (!markedAnalysing) {
      log.warn(
          "triggerAnalysis: failed to mark exec {} as ANALYSING (stale row); aborting",
          executionRunId);
      return;
    }
    emit(executionRunId, ExecutionEventTypes.EXEC_UNIFIED_ARCH_STARTED, null);

    try {
      // Delegate to the pilot v3.0 architecture service which already
      // handles per-repo codemap generation + unified doc synthesis.
      // Pass the creator's PAT so private repo trees resolve (system
      // GITHUB_PAT env isn't always scoped on user repos).
      String pat = appUserRepo.findById(createdBy).map(u -> u.getGithubPat()).orElse(null);
      ProjectArchitectureDoc doc = archService.generate(projectId, null, pat);
      String docContent = doc == null ? null : doc.getContent();
      boolean markedArchReview =
          runCas.casUpdate(
              executionRunId,
              r -> {
                r.setUnifiedArchDoc(docContent);
                r.setStatus("ARCH_REVIEW");
              });
      if (markedArchReview) {
        emit(executionRunId, ExecutionEventTypes.EXEC_ANALYSIS_COMPLETE, null);
      } else {
        log.warn(
            "triggerAnalysis: failed to mark exec {} as ARCH_REVIEW (stale row); arch doc not"
                + " persisted on exec row but project_architecture_doc was written by"
                + " archService.generate",
            executionRunId);
      }
    } catch (Exception e) {
      log.error("analysis failed for exec {}: {}", executionRunId, e.getMessage(), e);
      runCas.casUpdate(executionRunId, r -> r.setStatus("ABORTED"));
      emit(executionRunId, ExecutionEventTypes.EXEC_ABORTED, e.getMessage());
    }
  }

  // ── Phase 3: arch approval ────────────────────────────────────────────

  public void approveArchDoc(ExecutionRun run, boolean approved, String annotations) {
    if (!approved) return;
    run.setArchApprovedAt(Instant.now());
    if (annotations != null && !annotations.isBlank()) {
      String existing = run.getUnifiedArchDoc() == null ? "" : run.getUnifiedArchDoc();
      run.setUnifiedArchDoc(existing + "\n\n---\n## User annotations\n" + annotations);
    }
    runRepo.save(run);
  }

  // ── Phase 4b: kickoffPlanning router (V188 unified vs legacy) ──────────

  /**
   * V188 (2026-05-13) — entry point the {@code POST /api/executions/{id}/plan} controller calls.
   * When the unified-dispatch flag is on (default), dispatch a single V2 pipeline_run with the full
   * requirement + all linked repos; V2's hierarchical planner emits tasks, pauses at
   * AWAITING_APPROVAL, and the V3 plan-gate becomes a thin proxy over V2's plan-gate. When the flag
   * is off, fall through to the legacy LLM-split planner that emits per-Solution rows.
   *
   * <p>Routing here (not in the controller) so both paths can be observed in one place during the
   * deprecation window + so the unified path can poll/mirror without leaking V2 internals out of
   * the orchestrator.
   */
  public void kickoffPlanning(String executionRunId) {
    if (unifiedDispatchEnabled) {
      dispatchUnified(executionRunId);
    } else {
      plannerService.generatePlan(executionRunId);
    }
  }

  // ── V188 unified-dispatch — one V2 run per execution ──────────────────

  /**
   * V188 (2026-05-13) — dispatch a single V2 pipeline_run for this execution, then mirror its
   * status back onto the ExecutionRun row. V2 handles classification + hierarchical planning +
   * multi-repo workspace setup; we re-use the V2 plan-approval gate by passing {@code
   * skipApproval=false} so the user's V3 plan-approve click flows through {@code
   * gateStore.tryClaimPlanGate} to resume V2.
   *
   * <p>Phases (V3 status → V2 status):
   *
   * <ul>
   *   <li>PLANNING → V2 PLANNING (classifier + outline planner) → V2 AWAITING_APPROVAL (V3 stays
   *       PLANNING; frontend reads {@code executionPlan} mirror of {@code pipeline_run.planJson},
   *       shows Approve button).
   *   <li>(user clicks Approve at V3) → {@link #approvePlanAndExecute} claims V2's plan gate → V2
   *       WORKING → V3 EXECUTING.
   *   <li>V2 terminal (COMPLETE / PARTIAL / FAILED / ABORTED) → V3 mirrors via {@link
   *       #mirrorV2Terminal}.
   * </ul>
   */
  @Async
  public void dispatchUnified(String executionRunId) {
    ExecutionRun run = runRepo.findById(executionRunId).orElse(null);
    if (run == null) return;
    boolean markedPlanning = runCas.casUpdate(executionRunId, r -> r.setStatus("PLANNING"));
    if (!markedPlanning) {
      log.warn("dispatchUnified: failed to mark exec {} PLANNING (stale row)", executionRunId);
      return;
    }
    emit(executionRunId, ExecutionEventTypes.EXEC_PLANNING_STARTED, null);

    java.util.List<java.util.UUID> selectedRepoIds = parseSelectedRepoIds(run.getSelectedRepoIds());
    String githubPat =
        appUserRepo.findById(run.getCreatedBy()).map(AppUser::getGithubPat).orElse(null);

    com.navneet.executespec.state.entity.PipelineRun v2Run;
    try {
      v2Run =
          v2Orchestrator.submitRun(
              run.getRequirement(),
              "EXECUTION_VIEW",
              /* repoUrl */ null,
              /* repoMode */ "EXISTING",
              githubPat,
              /* githubOwner */ null,
              /* skipApproval */ false,
              /* autoMerge */ true,
              /* modelName */ null,
              run.getAiProvider(),
              run.getCreatedBy(),
              run.getOrgId(),
              run.getProjectId(),
              "PRODUCTION",
              selectedRepoIds,
              run.getFigmaUrl(),
              /* tddMode */ false,
              run.getBranch());
    } catch (Exception e) {
      log.error(
          "dispatchUnified: V2 submitRun failed for exec {}: {}",
          executionRunId,
          e.getMessage(),
          e);
      runCas.casUpdate(
          executionRunId,
          r -> {
            r.setStatus("FAILED");
          });
      emit(executionRunId, ExecutionEventTypes.EXEC_FAILED, null);
      return;
    }

    String pipelineRunId = v2Run.getRunId();
    runCas.casUpdate(executionRunId, r -> r.setPipelineRunId(pipelineRunId));
    log.info(
        "dispatchUnified: exec {} → V2 pipeline_run {} ({} linked repo(s))",
        executionRunId,
        pipelineRunId,
        selectedRepoIds.size());

    // Poll V2 until it reaches AWAITING_APPROVAL or terminates without one (e.g. PROTOTYPE
    // mode or an early failure). Mirror planJson onto ExecutionRun.executionPlan so the V3
    // frontend can render the plan-approval UI.
    long deadline = System.currentTimeMillis() + UNIFIED_MAX_WAIT_MS;
    while (System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(UNIFIED_POLL_INTERVAL_MS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return;
      }
      var freshV2 = pipelineRunRepo.findById(pipelineRunId).orElse(null);
      if (freshV2 == null) continue;
      // Mirror plan JSON the moment it lands — frontend polls on this.
      if (freshV2.getPlanJson() != null) {
        runCas.casUpdate(
            executionRunId,
            r -> {
              if (r.getExecutionPlan() == null) r.setExecutionPlan(freshV2.getPlanJson());
              if (r.getTotalEstimatedTokens() == null
                  && freshV2.getCostEstimateHigh() != null
                  && freshV2.getCostEstimateHigh() > 0) {
                // Rough token estimate proxy — frontend already renders cost separately.
                r.setTotalEstimatedTokens(0L);
              }
            });
      }
      String v2Status = freshV2.getStatus();
      if ("AWAITING_APPROVAL".equals(v2Status)) {
        // Planner done; V3 stays in PLANNING (frontend shows approve button). Return from this
        // async task; the next phase fires when the user clicks /plan/approve, which routes to
        // approvePlanAndExecute → claims the V2 plan gate.
        emit(executionRunId, ExecutionEventTypes.EXEC_PLAN_READY, pipelineRunId);
        return;
      }
      if (V2_TERMINAL.contains(v2Status)) {
        // V2 terminated without an approval gate (PROTOTYPE / early failure). Mirror status
        // immediately and stop polling.
        mirrorV2Terminal(executionRunId, freshV2);
        return;
      }
    }
    log.warn(
        "dispatchUnified: exec {} → V2 {} still not at AWAITING_APPROVAL after 4h; giving up poll",
        executionRunId,
        pipelineRunId);
  }

  /**
   * V188 (2026-05-13) — polls the V2 pipeline_run until terminal, mirroring its terminal state onto
   * the ExecutionRun. Called from {@link #approvePlanAndExecute} once the V2 plan gate has been
   * claimed.
   */
  @Async
  public void pollAndMirrorV2(String executionRunId, String pipelineRunId) {
    long deadline = System.currentTimeMillis() + UNIFIED_MAX_WAIT_MS;
    while (System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(UNIFIED_POLL_INTERVAL_MS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return;
      }
      var fresh = pipelineRunRepo.findById(pipelineRunId).orElse(null);
      if (fresh == null) continue;
      if (V2_TERMINAL.contains(fresh.getStatus())) {
        mirrorV2Terminal(executionRunId, fresh);
        return;
      }
    }
    log.warn(
        "pollAndMirrorV2: exec {} → V2 {} did not terminate within 4h; status stays as last seen",
        executionRunId,
        pipelineRunId);
  }

  /**
   * V188 (2026-05-13) — map V2 terminal status to V3 ExecutionRun status + bubble token / cost
   * totals up. PARTIAL collapses to COMPLETE on the V3 surface because the PR is still raised and
   * the customer can review it; the per-Solution skip/retry granularity that used to live at V3 is
   * moot in the unified architecture (the V2 task list + reviewer issues carry the same signal).
   */
  private void mirrorV2Terminal(
      String executionRunId, com.navneet.executespec.state.entity.PipelineRun v2Run) {
    String v3Status =
        switch (v2Run.getStatus()) {
          case "COMPLETE", "PARTIAL" -> "COMPLETE";
          case "CANCELLED", "ABORTED" -> "ABORTED";
          default -> "FAILED";
        };
    runCas.casUpdate(
        executionRunId,
        r -> {
          r.setStatus(v3Status);
          if (v2Run.getActualCostUsd() != null && v2Run.getActualCostUsd() > 0d) {
            r.setTotalCostUsd(java.math.BigDecimal.valueOf(v2Run.getActualCostUsd()));
          }
          long tok =
              (v2Run.getTotalInputTokens() == null ? 0L : v2Run.getTotalInputTokens().longValue())
                  + (v2Run.getTotalOutputTokens() == null
                      ? 0L
                      : v2Run.getTotalOutputTokens().longValue());
          if (tok > 0L) r.setTotalActualTokens(tok);
        });
    String eventType =
        "COMPLETE".equals(v3Status)
            ? ExecutionEventTypes.EXEC_COMPLETE
            : "FAILED".equals(v3Status)
                ? ExecutionEventTypes.EXEC_FAILED
                : ExecutionEventTypes.EXEC_ABORTED;
    emit(executionRunId, eventType, v2Run.getRunId());
    log.info(
        "mirrorV2Terminal: exec {} ← V2 {} terminal={} → V3 status={}",
        executionRunId,
        v2Run.getRunId(),
        v2Run.getStatus(),
        v3Status);
  }

  private static java.util.List<java.util.UUID> parseSelectedRepoIds(String json) {
    if (json == null || json.isBlank() || "[]".equals(json.trim())) return java.util.List.of();
    java.util.List<java.util.UUID> out = new java.util.ArrayList<>();
    com.fasterxml.jackson.databind.ObjectMapper mapper =
        new com.fasterxml.jackson.databind.ObjectMapper();
    try {
      java.util.List<String> ids =
          mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
      for (String s : ids) {
        try {
          out.add(java.util.UUID.fromString(s));
        } catch (IllegalArgumentException ignored) {
          // skip malformed ids; legacy rows may have non-UUID strings
        }
      }
    } catch (Exception e) {
      // unparseable → empty list; downstream will treat as "all linked repos"
    }
    return out;
  }

  // ── Phase 5: plan approval + execute ──────────────────────────────────

  @Async
  public void approvePlanAndExecute(String executionRunId) {
    // 2026-04-26 hotfix — covered by ExecutionRunCasUpdater. The find-then-
    // save window here is tight, but @Async runs on a fresh thread with
    // OSIV off; concurrent writers from the SSE update path or from a
    // sibling /api/executions/{id}/abort call can bump @Version between
    // findById and save, which OOMs the request because no
    // @RestControllerAdvice catches async exceptions.
    Instant approvedAt = Instant.now();
    boolean ok = runCas.casUpdate(executionRunId, r -> r.setPlanApprovedAt(approvedAt));
    if (!ok) {
      log.warn(
          "approvePlanAndExecute: failed to record plan approval for exec {} (stale row);"
              + " proceeding to execute regardless",
          executionRunId);
    }
    // V188 (2026-05-13) — when the unified-dispatch path is in use, the V3
    // plan-approve click maps to V2's plan gate. Claim the gate; V2 resumes
    // execution; we transition V3 to EXECUTING and poll V2 until terminal.
    ExecutionRun run = runRepo.findById(executionRunId).orElse(null);
    if (run != null && run.getPipelineRunId() != null) {
      String pipelineRunId = run.getPipelineRunId();
      boolean claimed = gateStore.tryClaimPlanGate(pipelineRunId, true);
      if (!claimed) {
        log.warn(
            "approvePlanAndExecute: V2 plan gate already resolved for exec {} (V2 run {});"
                + " polling anyway to mirror terminal state",
            executionRunId,
            pipelineRunId);
      }
      runCas.casUpdate(executionRunId, r -> r.setStatus("EXECUTING"));
      emit(executionRunId, ExecutionEventTypes.EXEC_STARTED, pipelineRunId);
      pollAndMirrorV2(executionRunId, pipelineRunId);
      return;
    }
    // Legacy path — per-Solution dispatch, retained for in-flight executions
    // that were created before V188 and emergency rollback when the unified
    // flag is off.
    executeApprovedPlan(executionRunId);
  }

  @Async
  public void executeApprovedPlan(String executionRunId) {
    ExecutionRun run = runRepo.findById(executionRunId).orElse(null);
    if (run == null) return;
    // 2026-04-26 hotfix — covered by ExecutionRunCasUpdater. The save below
    // races with the SSE update path + with abort/cancel callers; the
    // long solution-dispatch loop downstream then operates on a stale `run`
    // that already failed save here.
    boolean markedExecuting = runCas.casUpdate(executionRunId, r -> r.setStatus("EXECUTING"));
    if (!markedExecuting) {
      log.warn(
          "executeApprovedPlan: failed to mark exec {} as EXECUTING (stale row); aborting dispatch",
          executionRunId);
      return;
    }
    emit(run.getId(), ExecutionEventTypes.EXEC_STARTED, null);

    // Eager-fetch dependencies so SolutionDependencyResolver can walk the Set
    // without a LazyInitializationException — works regardless of whether
    // the caller path is proxied (avoids the @Transactional-self-invocation trap).
    List<ExecutionSolution> all =
        solutionRepo.findWithDependenciesByExecutionRun_IdOrderBySequenceNumberAsc(executionRunId);
    List<List<ExecutionSolution>> layers;
    try {
      layers = depResolver.resolveExecutionOrder(all);
    } catch (IllegalStateException e) {
      log.error("exec {} has cyclic deps: {}", executionRunId, e.getMessage());
      // 2026-04-26 hotfix — covered by ExecutionRunCasUpdater. The cyclic-
      // deps path is reachable AFTER the EXECUTING save, so the in-memory
      // `run` reference is stale by this point.
      runCas.casUpdate(executionRunId, r -> r.setStatus("ABORTED"));
      emit(run.getId(), ExecutionEventTypes.EXEC_ABORTED, e.getMessage());
      return;
    }

    for (List<ExecutionSolution> layer : layers) {
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (ExecutionSolution s : layer) {
        // Re-read to catch status changes (RETRY / SKIP decisions):
        ExecutionSolution fresh = solutionRepo.findById(s.getId()).orElse(s);
        if (!"QUEUED".equals(fresh.getStatus())) continue;
        futures.add(
            CompletableFuture.runAsync(() -> solutionExecService.executeSolution(fresh, run)));
      }
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

      // If something paused the run during this layer, bail out —
      // resumption happens via SolutionExecutionService.processDecision.
      ExecutionRun latest = runRepo.findById(executionRunId).orElse(run);
      if ("PAUSED".equals(latest.getStatus()) || "ABORTED".equals(latest.getStatus())) {
        return;
      }
    }

    // All layers complete → open final PRs per repo (skip for follow-up
    // runs that address an existing PR — V2 auto-push already pushed to the
    // same branch and GitHub auto-updates the PR).
    boolean isContinueOnPr = run.getContinuePrNumber() != null;
    if (!isContinueOnPr) {
      String githubPat =
          appUserRepo.findById(run.getCreatedBy()).map(AppUser::getGithubPat).orElse(null);
      // v2.5.12 — re-query solutions with repoMappings eager-fetched.
      // `all` came from findWithDependenciesBy... which only fetched the
      // dependencies Set. Walking s.getRepoMappings() on that list fires
      // LazyInitializationException ("no Session") because this method
      // runs on an @Async thread outside open-in-view. The exception
      // killed the terminal setStatus("COMPLETE") below, leaving runs
      // stuck at EXECUTING forever (observed on launch 9d5fb7d4).
      List<ExecutionSolution> solutionsWithRepos =
          solutionRepo.findWithRepoMappingsByExecutionRun_IdOrderBySequenceNumberAsc(
              executionRunId);
      Set<UUID> distinctRepos = new LinkedHashSet<>();
      for (ExecutionSolution s : solutionsWithRepos) {
        if (s.getRepoMappings() == null) continue;
        for (ExecutionSolutionRepo m : s.getRepoMappings()) {
          if (m.getRepoId() != null) distinctRepos.add(m.getRepoId());
        }
      }
      for (UUID repoId : distinctRepos) {
        projectRepoRepo
            .findById(repoId)
            .ifPresent(
                pr ->
                    branchService.createFinalPr(
                        pr.getGithubRepoFullName(),
                        pr.getId(),
                        executionRunId,
                        buildReportMarkdown(run, all),
                        githubPat));
        emit(run.getId(), ExecutionEventTypes.EXEC_FINAL_PR_CREATED, repoId.toString());
      }
    } else {
      log.info("Skipping PR creation for continue-on-PR execution {}", executionRunId);
    }

    // Backlog fix (extends the triggerAnalysis hotfix on
    // feature/fix-execution-orchestrator-stale-state-2026-04-25 to the
    // executeApprovedPlan path): the in-memory `run` reference here is
    // STALE — the long-running solution-dispatch loop above (potentially
    // many minutes) lets concurrent writes to this row bump @Version.
    // CAS-style re-fetch + retry so the @Async thread doesn't crash on
    // ObjectOptimisticLockingFailureException (no @RestControllerAdvice
    // catches async exceptions).
    boolean ok = applyTerminalStatus(executionRunId, "COMPLETE");
    if (!ok) {
      log.warn(
          "executeApprovedPlan: terminal COMPLETE for exec {} could not be applied",
          executionRunId);
      return;
    }
    emit(executionRunId, ExecutionEventTypes.EXEC_COMPLETE, null);
  }

  /**
   * Backlog — same retry shape as the triggerAnalysis hotfix. Up to 3 attempts with 50ms × attempt
   * back-off; refuses to trample a parallel terminal write (caller-driven cancel may have set
   * ABORTED).
   */
  /* package */ boolean applyTerminalStatus(String runId, String newStatus) {
    int attempts = 0;
    while (attempts < 3) {
      attempts++;
      try {
        ExecutionRun fresh = runRepo.findById(runId).orElse(null);
        if (fresh == null) return false;
        if ("ABORTED".equals(fresh.getStatus()) || "COMPLETE".equals(fresh.getStatus())) {
          log.info(
              "applyTerminalStatus: exec {} already terminal at {}; skipping {}",
              runId,
              fresh.getStatus(),
              newStatus);
          return true;
        }
        fresh.setStatus(newStatus);
        runRepo.save(fresh);
        return true;
      } catch (ObjectOptimisticLockingFailureException ole) {
        if (attempts >= 3) {
          log.error(
              "applyTerminalStatus: optimistic-lock retry exhausted for exec {} → {} after {}"
                  + " attempts",
              runId,
              newStatus,
              attempts);
          return false;
        }
        log.info(
            "applyTerminalStatus: optimistic-lock conflict on exec {} → {} attempt {}/3;"
                + " refetching",
            runId,
            newStatus,
            attempts);
        try {
          Thread.sleep(50L * attempts);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
    }
    return false;
  }

  /**
   * v2.5.2 (Phase 2.3) — abort + best-effort cleanup. Steps:
   *
   * <ol>
   *   <li>Flip status to ABORTED + emit EXEC_ABORTED so the UI can swap views.
   *   <li>For every solution with a {@code prUrl}, attempt to close the PR via GitHub. Each attempt
   *       emits an EXEC_COMPENSATION_UPDATE event so the cancel panel can render live progress.
   *   <li>Final EXEC_COMPENSATION_DONE summarises the run.
   * </ol>
   *
   * <p>Failures during cleanup do NOT roll back the abort — the user already asked for it to stop.
   * Failed compensations surface as {@code status:"FAILED"} payloads with a deep-link the user can
   * act on.
   */
  public void abortExecution(ExecutionRun run) {
    // 2026-04-26 hotfix — covered by ExecutionRunCasUpdater. The `run`
    // parameter is loaded by the controller (request thread, OSIV off);
    // by the time the abort reaches this save, a parallel @Async writer
    // (executeApprovedPlan, triggerAnalysis, accumulateExecutionRunTotals)
    // may have already bumped @Version. Without CAS the abort throws
    // OptimisticLockException, the row never flips to ABORTED, and the
    // user's cancel button silently fails.
    boolean ok = runCas.casUpdate(run.getId(), r -> r.setStatus("ABORTED"));
    if (!ok) {
      log.warn(
          "abortExecution: failed to mark exec {} as ABORTED (stale row); proceeding with cleanup",
          run.getId());
    }
    emit(run.getId(), ExecutionEventTypes.EXEC_ABORTED, null);
    cancelActiveV2Runs(run);
    runCompensations(run);
  }

  /**
   * Cancel every V2 PipelineRun still in flight for this ExecutionRun. Without this, the Abort
   * button flips ExecutionRun.status=ABORTED but each solution's downstream V2 run keeps executing
   * (the Launches tab then shows "RUNNING" even though the user already aborted).
   */
  private void cancelActiveV2Runs(ExecutionRun run) {
    Set<String> terminalSolStatus = Set.of("DONE", "SKIPPED", "FAILED");
    List<ExecutionSolution> solutions =
        solutionRepo.findByExecutionRun_IdOrderBySequenceNumberAsc(run.getId());
    for (ExecutionSolution s : solutions) {
      String solStatus = s.getStatus() == null ? "" : s.getStatus().toUpperCase();
      if (terminalSolStatus.contains(solStatus)) continue;
      String pipelineRunId = s.getPipelineRunId();
      if (pipelineRunId == null || pipelineRunId.isBlank()) continue;
      try {
        boolean cancelled = v2Orchestrator.cancel(pipelineRunId);
        log.info(
            "abort exec={} solution={} pipelineRun={} cancel={}",
            run.getId(),
            s.getId(),
            pipelineRunId,
            cancelled);
      } catch (Exception e) {
        log.warn(
            "abort exec={} solution={} pipelineRun={} cancel failed: {}",
            run.getId(),
            s.getId(),
            pipelineRunId,
            e.getMessage());
      }
    }
  }

  private void runCompensations(ExecutionRun run) {
    AppUser owner =
        run.getCreatedBy() == null ? null : appUserRepo.findById(run.getCreatedBy()).orElse(null);
    String pat = owner == null ? null : owner.getGithubPat();

    List<ExecutionSolution> solutions =
        solutionRepo.findByExecutionRun_IdOrderBySequenceNumberAsc(run.getId());
    int total = 0, done = 0, failed = 0;
    for (ExecutionSolution s : solutions) {
      if (s.getPrUrl() == null || s.getPrNumber() == null) continue;
      String repoFullName = extractRepoFullNameFromPrUrl(s.getPrUrl());
      if (repoFullName == null) continue;
      total++;
      emitCompensation(run.getId(), "PR_CLOSE", repoFullName, s.getPrNumber(), "RUNNING", null);
      boolean ok;
      try {
        ok = gitHub.closePullRequest(repoFullName, s.getPrNumber(), pat);
      } catch (Exception e) {
        ok = false;
        log.warn(
            "Compensation PR_CLOSE failed for {}#{}: {}",
            repoFullName,
            s.getPrNumber(),
            e.getMessage());
      }
      if (ok) {
        done++;
        emitCompensation(run.getId(), "PR_CLOSE", repoFullName, s.getPrNumber(), "COMPLETE", null);
      } else {
        failed++;
        emitCompensation(
            run.getId(),
            "PR_CLOSE",
            repoFullName,
            s.getPrNumber(),
            "FAILED",
            "Could not close PR — close manually on GitHub.");
      }
    }
    emit(
        run.getId(),
        ExecutionEventTypes.EXEC_COMPENSATION_DONE,
        "{\"total\":" + total + ",\"completed\":" + done + ",\"failed\":" + failed + "}");
  }

  private void emitCompensation(
      String runId, String type, String repo, Integer prNumber, String status, String message) {
    StringBuilder sb = new StringBuilder("{");
    sb.append("\"type\":\"").append(type).append("\",");
    sb.append("\"repo\":\"").append(repo).append("\",");
    if (prNumber != null) sb.append("\"prNumber\":").append(prNumber).append(",");
    sb.append("\"status\":\"").append(status).append("\"");
    if (message != null)
      sb.append(",\"message\":\"").append(message.replace("\"", "\\\"")).append("\"");
    sb.append("}");
    emit(runId, ExecutionEventTypes.EXEC_COMPENSATION_UPDATE, sb.toString());
  }

  private static String extractRepoFullNameFromPrUrl(String prUrl) {
    // Format: https://github.com/<owner>/<repo>/pull/<n>
    if (prUrl == null) return null;
    int host = prUrl.indexOf("github.com/");
    if (host < 0) return null;
    String tail = prUrl.substring(host + "github.com/".length());
    int pullIdx = tail.indexOf("/pull/");
    if (pullIdx < 0) return null;
    return tail.substring(0, pullIdx);
  }

  public Map<String, Object> buildReport(ExecutionRun run) {
    List<ExecutionSolution> solutions =
        solutionRepo.findByExecutionRun_IdOrderBySequenceNumberAsc(run.getId());
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("id", run.getId());
    report.put("status", run.getStatus());
    report.put("requirement", run.getRequirement());
    report.put("totalActualTokens", run.getTotalActualTokens());
    report.put("totalCostUsd", run.getTotalCostUsd());
    report.put("markdown", buildReportMarkdown(run, solutions));
    report.put("solutionCount", solutions.size());
    return report;
  }

  private String buildReportMarkdown(ExecutionRun run, List<ExecutionSolution> solutions) {
    StringBuilder sb = new StringBuilder();
    sb.append("# Execution ").append(run.getId().substring(0, 8)).append("\n\n");
    sb.append("**Status:** ").append(run.getStatus()).append("  \n");
    sb.append("**Project:** ").append(run.getProjectId()).append("  \n");
    if (run.isJiraEnabled()) sb.append("**JIRA sync:** enabled  \n");
    sb.append("\n**Requirement**\n\n> ")
        .append(run.getRequirement().replace("\n", "\n> "))
        .append("\n\n");

    long done = solutions.stream().filter(s -> "DONE".equals(s.getStatus())).count();
    long failed = solutions.stream().filter(s -> "FAILED".equals(s.getStatus())).count();
    long skipped = solutions.stream().filter(s -> "SKIPPED".equals(s.getStatus())).count();
    long totalTokens =
        solutions.stream()
            .mapToLong(s -> s.getActualTokens() == null ? 0L : s.getActualTokens())
            .sum();

    sb.append("**Summary:** ")
        .append(done)
        .append(" done · ")
        .append(failed)
        .append(" failed · ")
        .append(skipped)
        .append(" skipped · ~")
        .append(totalTokens)
        .append(" tokens\n\n");

    sb.append("## Solutions\n\n");
    sb.append("| # | Title | Status | Tokens | PR | JIRA |\n");
    sb.append("|---|-------|--------|--------|----|------|\n");
    for (ExecutionSolution s : solutions) {
      sb.append("| S")
          .append(s.getSequenceNumber())
          .append(" | ")
          .append(escape(s.getTitle()))
          .append(" | ")
          .append(s.getStatus())
          .append(" | ")
          .append(s.getActualTokens() == null ? "—" : s.getActualTokens())
          .append(" | ")
          .append(s.getPrUrl() == null ? "—" : "[PR](" + s.getPrUrl() + ")")
          .append(" | ")
          .append(s.getJiraTicketId() == null ? "—" : s.getJiraTicketId())
          .append(" |\n");
    }

    // Failed-solution call-outs so the PR reviewer doesn't have to chase the UI.
    boolean anyFailed = solutions.stream().anyMatch(s -> s.getFailureReason() != null);
    if (anyFailed) {
      sb.append("\n## Failures\n\n");
      for (ExecutionSolution s : solutions) {
        if (s.getFailureReason() == null) continue;
        sb.append("- **S")
            .append(s.getSequenceNumber())
            .append(" · ")
            .append(s.getTitle())
            .append("** (phase: ")
            .append(s.getFailurePhase())
            .append("): ")
            .append(s.getFailureReason())
            .append("\n");
      }
    }

    sb.append("\n_Generated by ExecuteSpec Execution View._\n");
    return sb.toString();
  }

  private String escape(String s) {
    if (s == null) return "";
    return s.replace("|", "\\|").replace("\n", " ");
  }

  private void emit(String executionRunId, String type, String payload) {
    broadcaster.emit(
        "exec:" + executionRunId, new RunEvent(type, executionRunId, payload, null, Instant.now()));
  }
}
