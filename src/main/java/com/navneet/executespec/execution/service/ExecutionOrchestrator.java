package com.navneet.executespec.execution.service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

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
import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
  private final ExecutionPipelineSubmissionService pipelineSubmissionService;
  private final com.navneet.executespec.integration.RunLifecycleService runLifecycleService;
  private final com.navneet.executespec.project.pr.PullRequestRecordRepository prRecordRepo;
  private final ExecutionRunCasUpdater runCas;
  private final com.navneet.executespec.state.repository.PipelineRunRepository pipelineRunRepo;
  private final ExecutionPlannerService plannerService;
  private final ExecutionCostAggregator costAggregator;

  /**
   * RR-343 — self reference through the Spring proxy.
   *
   * <p>{@link #kickoffPlanning} used to call {@link #dispatchUnified} directly on {@code this},
   * which never traverses the {@code @Async} proxy. The callee then polled every five seconds for
   * up to four hours <em>on the servlet thread</em>, so the controller's claimed HTTP 202 could not
   * be returned until planning finished: each plan request pinned a request thread and connection
   * for minutes to hours, clients timed out and retried, and a handful of launches could exhaust
   * the web tier. Routing through the proxy restores the asynchronous contract the endpoint
   * advertises.
   *
   * <p>{@code @Lazy} breaks the self-injection cycle at construction time.
   */
  private final org.springframework.beans.factory.ObjectProvider<ExecutionOrchestrator> self;

  /** RR-527 — used for the transaction-scoped advisory lock in {@link #admitAndCreateExecution}. */
  @PersistenceContext private EntityManager em;

  /** RR-348 — bounded, context-propagating executor for per-layer solution dispatch. */
  private final com.navneet.executespec.integration.PipelineExecutorPool executorPool;

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

  /** RR-349 / RR-350 / RR-351 — launch states from which no further work may start. */
  // RR-345 — PARTIAL is a first-class terminal launch status. A V2 PARTIAL carries a concrete
  // failure reason (FINAL_BUILD_FAILED, TESTS_FAILED:N_OF_M, REVIEWER_UNRESOLVED, ...) and must be
  // terminal here so it is neither re-executed/resumed nor overwritten by applyTerminalStatus.
  private static final java.util.Set<String> LAUNCH_TERMINAL =
      java.util.Set.of("COMPLETE", "PARTIAL", "FAILED", "CANCELLED", "ABORTED");

  /**
   * RR-905 — a launch whose row was written more recently than this is presumed live and is not a
   * boot-recovery candidate.
   */
  private static final java.time.Duration RECOVERY_QUIET_PERIOD = java.time.Duration.ofMinutes(5);

  /** RR-905 — takeover timeout: another instance may claim a lease older than this. */
  private static final java.time.Duration RECOVERY_LEASE_TIMEOUT = java.time.Duration.ofMinutes(30);

  /** RR-905 — hard cap on boot recoveries, so a poisoned launch is not recovered forever. */
  private static final int MAX_BOOT_RECOVERY_ATTEMPTS = 3;

  /** Stable-per-JVM identity recorded on the recovery lease. */
  private static String recoveryInstanceId() {
    return RECOVERY_INSTANCE_ID;
  }

  private static final String RECOVERY_INSTANCE_ID =
      (System.getenv("HOSTNAME") == null ? "local" : System.getenv("HOSTNAME"))
          + ":"
          + java.util.UUID.randomUUID().toString().substring(0, 8);

  private static final long UNIFIED_POLL_INTERVAL_MS = 5_000L;
  private static final long UNIFIED_MAX_WAIT_MS = 4L * 60L * 60L * 1000L; // 4 hours

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
      Instant now = Instant.now();
      // RR-905 — only launches whose row has actually gone quiet are recovery candidates. The old
      // call passed Instant.now(), which made the `updatedAt < :cutoff` predicate vacuously true
      // and selected EVERY executing row on every instance's boot.
      Instant quietBefore = now.minus(RECOVERY_QUIET_PERIOD);
      Instant staleBefore = now.minus(RECOVERY_LEASE_TIMEOUT);
      List<ExecutionRun> resumable =
          runRepo.findStaleNonTerminal(quietBefore).stream()
              .filter(r -> "EXECUTING".equals(r.getStatus()))
              .toList();
      if (resumable.isEmpty()) {
        log.info("ExecutionOrchestrator boot-resume: no EXECUTING ExecutionRuns to resume");
        return;
      }
      log.warn(
          "ExecutionOrchestrator boot-resume: {} EXECUTING ExecutionRun(s) are recovery candidates",
          resumable.size());
      for (ExecutionRun r : resumable) {
        try {
          // RR-905 — take the durable fenced lease BEFORE any side effect. Exactly one instance in
          // a rolling deploy / scaled-out fleet can win this compare-and-set; the losers skip the
          // launch entirely rather than re-running the same LLM, Git, PR and cost side effects.
          int observed = r.getRecoveryAttempts() == null ? 0 : r.getRecoveryAttempts();
          if (observed >= MAX_BOOT_RECOVERY_ATTEMPTS) {
            log.warn(
                "ExecutionOrchestrator boot-resume: exec={} exhausted recovery attempts ({}/{});"
                    + " leaving for operator reconciliation",
                r.getId(),
                observed,
                MAX_BOOT_RECOVERY_ATTEMPTS);
            continue;
          }
          int claimed =
              runRepo.claimBootRecovery(
                  r.getId(), observed, observed + 1, now, staleBefore, recoveryInstanceId());
          if (claimed != 1) {
            log.info(
                "ExecutionOrchestrator boot-resume: exec={} claimed by another instance or no"
                    + " longer eligible; skipping",
                r.getId());
            continue;
          }
          if (r.getPipelineRunId() != null && !r.getPipelineRunId().isBlank()) {
            // V188 (2026-05-13) unified path: the @Async pollAndMirrorV2 thread died with the
            // previous JVM; rearm it so the V3 terminal-status mirror still fires. The underlying
            // V2 PipelineRun is resumed independently by StartupRunResumer.
            log.info(
                "ExecutionOrchestrator boot-resume: re-polling unified exec={} V2={}",
                r.getId(),
                r.getPipelineRunId());
            // Through the proxy: these are 4-hour poll loops and must not run serially on the boot
            // listener thread.
            self.getObject().pollAndMirrorV2(r.getId(), r.getPipelineRunId());
          } else {
            log.info(
                "ExecutionOrchestrator boot-resume: re-dispatching legacy exec={} (status was"
                    + " EXECUTING at boot)",
                r.getId());
            self.getObject().executeApprovedPlan(r.getId());
          }
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

  // B5 (audit 2026-05-29) — event-driven Launch mirror. When a V2 run reaches terminal, mirror its
  // linked V3 ExecutionRun immediately via the PipelineEvent bus instead of waiting on the
  // per-launch poll. pollAndMirrorV2 is retained as the fallback (boot-resume + cross-JVM / any
  // missed event); the mirror is idempotent (skipped when the Launch is already terminal), so the
  // listener and the poll coexist safely. @Async so the run-completion publish thread isn't
  // blocked.
  @Async
  @EventListener
  public void onV2RunCompleted(PipelineEvent.RunCompleted e) {
    mirrorLaunchOnV2Terminal(e.runId());
  }

  @Async
  @EventListener
  public void onV2RunFailed(PipelineEvent.RunFailed e) {
    mirrorLaunchOnV2Terminal(e.runId());
  }

  private void mirrorLaunchOnV2Terminal(String pipelineRunId) {
    if (pipelineRunId == null || pipelineRunId.isBlank()) return;
    runRepo
        .findFirstByPipelineRunIdOrderByCreatedAtDesc(pipelineRunId)
        .filter(er -> !java.util.Set.of("COMPLETE", "FAILED", "ABORTED").contains(er.getStatus()))
        .ifPresent(
            er ->
                pipelineRunRepo
                    .findById(pipelineRunId)
                    .filter(v2 -> V2_TERMINAL.contains(v2.getStatus()))
                    .ifPresent(v2 -> mirrorV2Terminal(er.getId(), v2)));
  }

  /**
   * RR-527 — atomic single-active-execution admission. The controller previously read {@code
   * findActiveByProjectId} and then, in a separate step, called {@link #createExecution}, so two
   * concurrent requests (a double-click or parallel clients) could both observe no active row and
   * both create an execution for the same project — each then analysing, spending, opening gates
   * and mutating overlapping repos, defeating the explicit single-active-run contract.
   *
   * <p>Serialize admission per project with a transaction-scoped PostgreSQL advisory lock (the same
   * idiom {@code ActivityAuditService} uses): the lock releases at commit, the re-check runs while
   * it is held, and the create + persist happen in the same transaction, so a concurrent admission
   * blocks until the winner commits and then observes the winner's active row and is rejected with
   * a 409.
   */
  @Transactional
  public ExecutionRun admitAndCreateExecution(
      Project project, AppUser user, CreateExecutionRequest req) {
    em.createNativeQuery(
            "SELECT pg_advisory_xact_lock(hashtext('executespec')," + " hashtext(:key))")
        .setParameter("key", "exec_admission:" + project.getId())
        .getSingleResult();

    var activeRuns = runRepo.findActiveByProjectId(project.getId());
    if (!activeRuns.isEmpty()) {
      ExecutionRun active = activeRuns.get(0);
      String status = active.getStatus() == null ? "" : active.getStatus().toUpperCase();
      String reason =
          switch (status) {
            case "PAUSED" ->
                "Previous execution is paused awaiting a user decision on a failed solution. "
                    + "Resolve it (retry / skip / fix manually) or abort it to start a new one.";
            case "AWAITING_APPROVAL", "ARCH_REVIEW" ->
                "Previous execution is waiting for your approval ("
                    + status
                    + "). Approve it or abort it to start a new one.";
            case "EXECUTING", "PLANNING", "ANALYSING", "SETUP" ->
                "Previous execution is still running (status="
                    + status
                    + "). Wait for it to finish or abort it to start a new one.";
            default ->
                "Previous execution has not finished or been cancelled (status="
                    + status
                    + "). Finish it or abort it to start a new one.";
          };
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, reason + " [execution id: " + active.getId() + "]");
    }
    return createExecution(project, user, req);
  }

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
            .selectedRepoIds(serializeRepoIds(effectiveRepoIds, project.getId()))
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
  private String serializeRepoIds(java.util.List<String> ids) {
    return serializeRepoIds(ids, null);
  }

  /**
   * RR-344 — serialise + VALIDATE the launch repository selection before it is persisted.
   *
   * <p>Every entry must be a syntactically valid UUID, and (when {@code projectId} is supplied)
   * must name a repository actually linked to this project. Rejecting at the write boundary means
   * the read boundary can no longer face a value it must guess about; the two halves together are
   * what prevent a malformed selection from being reinterpreted as "all repositories".
   *
   * <p>Returns {@code null} for a genuinely empty selection, which remains the explicit "no subset
   * chosen — every linked repo is in scope" encoding.
   */
  private String serializeRepoIds(java.util.List<String> ids, String projectId) {
    if (ids == null || ids.isEmpty()) return null;
    List<UUID> parsed = new ArrayList<>();
    for (String raw : ids) {
      if (raw == null || raw.isBlank()) {
        throw new IllegalArgumentException("Repository selection contains a blank id");
      }
      try {
        parsed.add(UUID.fromString(raw.trim()));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Repository selection contains a non-UUID id: " + raw);
      }
    }
    if (projectId != null) {
      // Fail-closed membership + capability check against the durable project links.
      projectRepoService.resolveForRunStrict(projectId, parsed);
    }
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < parsed.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append('"').append(parsed.get(i)).append('"');
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

  public void approveArchDoc(ExecutionRun run, boolean approved, String annotations) {
    if (!approved) return;
    run.setArchApprovedAt(Instant.now());
    if (annotations != null && !annotations.isBlank()) {
      String existing = run.getUnifiedArchDoc() == null ? "" : run.getUnifiedArchDoc();
      run.setUnifiedArchDoc(existing + "\n\n---\n## User annotations\n" + annotations);
    }
    runRepo.save(run);
  }

  // Phase 4b: kickoffPlanning router (V188 unified vs legacy)

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
      // RR-343 — MUST go through the proxy, otherwise @Async is bypassed and the 4-hour poll loop
      // below runs on the caller's (servlet) thread. See the `self` field javadoc.
      self.getObject().dispatchUnified(executionRunId);
    } else {
      plannerService.generatePlan(executionRunId);
    }
  }

  // V188 unified-dispatch — one V2 run per execution

  /**
   * V188 (2026-05-13) — dispatch a single V2 pipeline_run for this execution, then mirror its
   * status back onto the ExecutionRun row. V2 handles classification + hierarchical planning +
   * multi-repo workspace setup; we re-use the V2 plan-approval gate by passing {@code
   * skipApproval=false} so the user's V3 plan-approve click flows through the restart-safe run
   * lifecycle authority to resume V2.
   *
   * <p>Phases (V3 status → V2 status):
   *
   * <ul>
   *   <li>PLANNING → V2 PLANNING (classifier + outline planner) → V2 AWAITING_APPROVAL (V3 stays
   *       PLANNING; frontend reads {@code executionPlan} mirror of {@code pipeline_run.planJson},
   *       shows Approve button).
   *   <li>(user clicks Approve at V3) → the shared approval service resolves V2's plan gate, then
   *       {@link #projectApprovedPlan} projects V2 WORKING → V3 EXECUTING.
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

    com.navneet.executespec.state.entity.PipelineRun v2Run;
    try {
      v2Run = pipelineSubmissionService.submitUnified(run, selectedRepoIds);
    } catch (Exception e) {
      log.error(
          "dispatchUnified: V2 submitRun failed for exec {}: {}",
          executionRunId,
          e.getMessage(),
          e);
      // PR-I (2026-05-13) — preserve the exception message on the row so the dashboard can show
      // "Planner crashed: <reason>" instead of a bare FAILED badge. Truncate to 4000 chars so a
      // stack-trace-wrapped error can't blow the TEXT column out unexpectedly. (V184 set the
      // analogous cap on agent_task.last_llm_failure for the same reason.)
      String reason = "V2 submitRun failed: " + truncate(safeMessage(e), 3900);
      runCas.casUpdate(
          executionRunId,
          r -> {
            r.setStatus("FAILED");
            r.setFailureReason(reason);
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
        // the shared approval service claims the V2 plan gate before projectApprovedPlan runs.
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
    // PR-I (2026-05-13) — transition to FAILED + record reason. Pre-PR-I this method returned
    // silently and left the ExecutionRun pinned in PLANNING forever — the frontend's plan-
    // approval spinner never gave up, and operators couldn't tell a stuck planner from a
    // healthy slow one. Now we fail loudly so the dashboard chip flips and the support flow
    // has something to query.
    runCas.casUpdate(
        executionRunId,
        r -> {
          // Only flip to FAILED if we're still in PLANNING — a concurrent terminal transition
          // (e.g. V2 finishing right at the deadline boundary) wins.
          if ("PLANNING".equals(r.getStatus())) {
            r.setStatus("FAILED");
            r.setFailureReason(
                "Planner timed out after "
                    + (UNIFIED_MAX_WAIT_MS / 60_000)
                    + " minutes waiting for AWAITING_APPROVAL on V2 run "
                    + pipelineRunId);
          }
        });
    emit(executionRunId, ExecutionEventTypes.EXEC_FAILED, null);
  }

  /** PR-I (2026-05-13) — null-safe Throwable.getMessage() with a sensible fallback. */
  private static String safeMessage(Throwable t) {
    if (t == null) return "unknown error";
    if (t.getMessage() != null && !t.getMessage().isBlank()) return t.getMessage();
    return t.getClass().getSimpleName();
  }

  /** PR-I (2026-05-13) — truncate to a max-length budget to keep TEXT columns bounded. */
  private static String truncate(String s, int max) {
    if (s == null) return null;
    return s.length() <= max ? s : s.substring(0, max);
  }

  /**
   * V188 (2026-05-13) — polls the V2 pipeline_run until terminal, mirroring its terminal state onto
   * the ExecutionRun. Called from {@link #projectApprovedPlan} once the V2 plan gate has been
   * durably resolved and its continuation has been queued.
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
   * totals up. RR-345 — PARTIAL is preserved as a first-class terminal status (it is NOT collapsed
   * to COMPLETE) and its V2 status/failure reason is always propagated, so failed tests / red final
   * builds / unresolved reviewer issues stay visible on the execution dashboard instead of reading
   * as a clean pass.
   *
   * <p>Package-private for unit-test access (RR-345 mirror behaviour).
   */
  void mirrorV2Terminal(
      String executionRunId, com.navneet.executespec.state.entity.PipelineRun v2Run) {
    String v3Status =
        switch (v2Run.getStatus()) {
          case "COMPLETE" -> "COMPLETE";
            // RR-345 — a V2 PARTIAL is NOT a clean COMPLETE. Collapsing it presented failed tests,
            // red final builds and unresolved reviewer issues as a passing product result and,
            // because
            // the derived status then read "clean", it also suppressed the V2 status/failure
            // reason.
            // Preserve PARTIAL as a first-class terminal status and always surface its reason
            // (below).
            // PR availability is carried separately by the solution/PR rows and is never inferred
            // as
            // implementation quality.
          case "PARTIAL" -> "PARTIAL";
          case "CANCELLED", "ABORTED" -> "ABORTED";
          default -> "FAILED";
        };
    // PR-I (2026-05-13) — propagate the V2 status reason / failure reason into the
    // ExecutionRun.failureReason so the V3 dashboard surfaces a non-empty diagnostic on
    // non-clean terminals. Prefer the explicit statusReason (PARTIAL with FINAL_BUILD_FAILED,
    // TESTS_FAILED:2_OF_7, etc.); fall back to failureReason on hard FAILED rows.
    boolean nonClean = !"COMPLETE".equals(v3Status);
    String reason = null;
    if (nonClean) {
      reason =
          v2Run.getStatusReason() != null && !v2Run.getStatusReason().isBlank()
              ? v2Run.getStatusReason()
              : v2Run.getFailureReason();
      reason = truncate(reason, 3900);
    }
    final String reasonFinal = reason;
    runCas.casUpdate(
        executionRunId,
        r -> {
          r.setStatus(v3Status);
          if (reasonFinal != null) r.setFailureReason(reasonFinal);
        });
    // PR-A (2026-05-13) — recompute totals from the source-of-truth pipeline_run rows rather
    // than reading the in-memory v2Run snapshot (which may lag if V2's cost flush + status flip
    // raced our poll). The aggregator handles both unified (single linked pipeline_run) and any
    // legacy attempt history on this execution row.
    costAggregator.recomputeAndPersist(executionRunId);
    // RR-345 — PARTIAL is terminal-with-a-PR: it reaches the same completion event as COMPLETE so
    // the frontend stops polling, but the ExecutionRun.status (PARTIAL) + failureReason carry the
    // honest nuance rather than the event masking it.
    String eventType =
        ("COMPLETE".equals(v3Status) || "PARTIAL".equals(v3Status))
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

  /**
   * RR-344 — parse the persisted launch repository selection, fail-closed.
   *
   * <p>Downstream treats an EMPTY list as "every linked repository is in scope". The previous
   * implementation silently dropped malformed entries and returned an empty list for unparseable
   * JSON, so a typo, a stale frontend ID, or a crafted request could <em>widen</em> a launch from
   * the subset the user picked to the whole project — reading and modifying repositories the launch
   * never selected. An unreadable selection means the scope is unknown, which must be an error.
   */
  private static java.util.List<java.util.UUID> parseSelectedRepoIds(String json) {
    if (json == null || json.isBlank() || "[]".equals(json.trim())) return java.util.List.of();
    java.util.List<java.util.UUID> out = new java.util.ArrayList<>();
    com.fasterxml.jackson.databind.ObjectMapper mapper =
        new com.fasterxml.jackson.databind.ObjectMapper();
    java.util.List<String> ids;
    try {
      ids = mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException(
          "Launch repository selection is unparseable; refusing to widen scope to every linked"
              + " repository",
          e);
    }
    if (ids == null) return java.util.List.of();
    for (String s : ids) {
      try {
        out.add(java.util.UUID.fromString(s));
      } catch (IllegalArgumentException e) {
        throw new IllegalStateException(
            "Launch repository selection contains a non-UUID entry; refusing to widen scope to"
                + " every linked repository");
      }
    }
    if (out.isEmpty()) {
      throw new IllegalStateException(
          "Launch repository selection parsed to an empty set from a non-empty stored value");
    }
    return out;
  }

  // Phase 5: plan approval + execute

  /**
   * RR-346 — the launch-level plan approval, now fail-CLOSED.
   *
   * <p>Previously this method logged-and-continued when the approval record could not be persisted,
   * logged-and-continued when the V2 plan-gate claim returned {@code false}, and then
   * unconditionally flipped the launch to {@code EXECUTING}. That meant a launch could advertise
   * and enter execution with no durable record of human approval; a missing gate could leave V3
   * EXECUTING while V2 sat forever in AWAITING_APPROVAL; and a gate the user had already
   * <em>rejected</em> was briefly reported as started.
   *
   * <p>Now: the controller synchronously completes canonical admission and gate resolution before
   * calling this projection. This method records the V3 approval only after that authority exists,
   * refuses to overwrite an abort/terminal state, and fences the linked V2 run when projection lost
   * a concurrent abort race.
   */
  @Async
  public void projectApprovedPlan(String executionRunId, String approvedPipelineRunId) {
    // 2026-04-26 hotfix — covered by ExecutionRunCasUpdater. The find-then-
    // save window here is tight, but @Async runs on a fresh thread with
    // OSIV off; concurrent writers from the SSE update path or from a
    // sibling /api/executions/{id}/abort call can bump @Version between
    // findById and save, which OOMs the request because no
    // @RestControllerAdvice catches async exceptions.
    Instant approvedAt = Instant.now();
    java.util.concurrent.atomic.AtomicBoolean transitioned =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    java.util.concurrent.atomic.AtomicBoolean alreadyProjected =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    java.util.concurrent.atomic.AtomicBoolean linkMatches =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    boolean ok =
        runCas.casUpdate(
            executionRunId,
            r -> {
              // The mutator may run again after an optimistic-lock retry. Its decision must reflect
              // the latest refetched row, not the status observed by an earlier failed attempt.
              transitioned.set(false);
              alreadyProjected.set(false);
              linkMatches.set(
                  java.util.Objects.equals(approvedPipelineRunId, r.getPipelineRunId()));
              if (!linkMatches.get()) return;
              if ("EXECUTING".equals(r.getStatus()) && r.getPlanApprovedAt() != null) {
                alreadyProjected.set(true);
                return;
              }
              if (!"PLANNING".equals(r.getStatus())
                  && !"AWAITING_PLAN_APPROVAL".equals(r.getStatus())) return;
              r.setPlanApprovedAt(approvedAt);
              r.setStatus("EXECUTING");
              transitioned.set(true);
            });
    if (!ok) {
      // Canonical approval already exists, but the V3 projection could not be recorded. Fence the
      // linked run first; then mark FAILED only if the projection is still in an approval state.
      // Never overwrite a concurrent ABORTED/terminal decision with a generic failure update.
      log.error(
          "projectApprovedPlan: could not durably record plan approval for exec {}; refusing to"
              + " execute",
          executionRunId);
      runLifecycleService.cancel(approvedPipelineRunId);
      java.util.concurrent.atomic.AtomicBoolean markedFailed =
          new java.util.concurrent.atomic.AtomicBoolean(false);
      runCas.casUpdate(
          executionRunId,
          r -> {
            markedFailed.set(false);
            if ("PLANNING".equals(r.getStatus())
                || "AWAITING_PLAN_APPROVAL".equals(r.getStatus())) {
              r.setStatus("FAILED");
              r.setFailureReason(
                  "Plan approval could not be durably projected; execution was fenced.");
              markedFailed.set(true);
            }
          });
      if (markedFailed.get()) emit(executionRunId, ExecutionEventTypes.EXEC_FAILED, null);
      return;
    }
    if (alreadyProjected.get()) return;
    if (!linkMatches.get() || !transitioned.get()) {
      runLifecycleService.cancel(approvedPipelineRunId);
      log.warn(
          "projectApprovedPlan: exec {} no longer permits the exact approved pipeline {}"
              + " projection; fenced it",
          executionRunId,
          approvedPipelineRunId);
      return;
    }
    emit(executionRunId, ExecutionEventTypes.EXEC_STARTED, approvedPipelineRunId);
    self.getObject().pollAndMirrorV2(executionRunId, approvedPipelineRunId);
  }

  /** RR-346 — terminal failure for a launch that must not proceed without proven authority. */
  private void failLaunch(String executionRunId, String reason) {
    String bounded = truncate(reason, 3900);
    runCas.casUpdate(
        executionRunId,
        r -> {
          r.setStatus("FAILED");
          r.setFailureReason(bounded);
        });
    emit(executionRunId, ExecutionEventTypes.EXEC_FAILED, null);
  }

  /**
   * RR-351 — the legacy per-solution executor, now gated on proven plan approval.
   *
   * <p>The public {@code POST /api/executions/{id}/resume} action calls straight into this method.
   * Before this change it required neither {@code planApprovedAt} nor a resolved V2 plan gate and
   * immediately set {@code EXECUTING}, so ANY writable launch — including one still in SETUP /
   * ANALYSING / PLANNING, or one whose plan the user had explicitly rejected — could skip
   * architecture and plan approval and enter solution execution (and therefore provider spend and
   * PR publication).
   *
   * <p>The durable {@code planApprovedAt} stamp written by {@link #projectApprovedPlan} is now the
   * required authority, and the launch must be in a state from which execution is legal. Boot
   * recovery re-enters through the same door, so recovery cannot launder an unapproved plan either.
   */
  @Async
  public void executeApprovedPlan(String executionRunId) {
    ExecutionRun run = runRepo.findById(executionRunId).orElse(null);
    if (run == null) return;
    if (run.getPlanApprovedAt() == null) {
      log.warn(
          "executeApprovedPlan: exec {} has no durable plan approval; refusing to execute"
              + " (status={})",
          executionRunId,
          run.getStatus());
      return;
    }
    if (LAUNCH_TERMINAL.contains(run.getStatus())) {
      log.warn(
          "executeApprovedPlan: exec {} is already terminal at {}; refusing to (re)execute",
          executionRunId,
          run.getStatus());
      return;
    }
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

    // RR-348 — dispatch each dependency layer on the bounded pipeline executor (per-org/run limits,
    // no starvation of the JVM common pool by wide layers) with traceId/tenant context propagated
    // across the thread boundary, and always settle the launch terminally. Previously a child
    // exception propagated out of the un-caught allOf().join(), so an @Async method with no advice
    // to catch it simply died — leaving the launch pinned in EXECUTING forever with no PR/terminal
    // handling. Catch it here and mark the launch FAILED.
    var contextSnapshot =
        io.micrometer.context.ContextSnapshotFactory.builder().build().captureAll();
    try {
      for (List<ExecutionSolution> layer : layers) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (ExecutionSolution s : layer) {
          // Re-read to catch status changes (RETRY / SKIP decisions):
          ExecutionSolution fresh = solutionRepo.findById(s.getId()).orElse(s);
          if (!"QUEUED".equals(fresh.getStatus())) continue;
          futures.add(
              CompletableFuture.runAsync(
                  contextSnapshot.wrap(() -> solutionExecService.executeSolution(fresh, run)),
                  executorPool.executor()));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // If something paused the run during this layer, bail out —
        // resumption happens via SolutionExecutionService.processDecision.
        ExecutionRun latest = runRepo.findById(executionRunId).orElse(run);
        if ("PAUSED".equals(latest.getStatus()) || "ABORTED".equals(latest.getStatus())) {
          return;
        }
      }
    } catch (RuntimeException layerError) {
      log.error(
          "executeApprovedPlan: layer dispatch for exec {} failed unexpectedly; settling FAILED",
          executionRunId,
          layerError);
      runCas.casUpdate(
          executionRunId,
          r -> {
            if (!LAUNCH_TERMINAL.contains(r.getStatus())) {
              r.setStatus("FAILED");
              r.setFailureReason(
                  "Execution dispatch failed: " + truncate(safeMessage(layerError), 3900));
            }
          });
      emit(executionRunId, ExecutionEventTypes.EXEC_FAILED, null);
      return;
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
    // RR-349 — derive the terminal state from the immutable per-solution outcomes instead of
    // unconditionally applying COMPLETE. Before this, an execution whose solutions had FAILED (or
    // whose concurrent writers had already recorded a FAILED launch) still ended as COMPLETE,
    // producing false reports and downstream publication eligibility.
    String derived = deriveTerminalStatus(executionRunId);
    boolean ok = applyTerminalStatus(executionRunId, derived);
    if (!ok) {
      log.warn(
          "executeApprovedPlan: terminal {} for exec {} could not be applied",
          derived,
          executionRunId);
      return;
    }
    emit(
        executionRunId,
        "COMPLETE".equals(derived)
            ? ExecutionEventTypes.EXEC_COMPLETE
            : ExecutionEventTypes.EXEC_FAILED,
        null);
  }

  /**
   * RR-349 — terminal state as a function of the persisted solution outcomes.
   *
   * <p>Any FAILED solution, or any solution that never reached a terminal state, means the launch
   * did not complete. Only a launch whose every solution is DONE or explicitly SKIPPED is COMPLETE.
   */
  private String deriveTerminalStatus(String executionRunId) {
    List<ExecutionSolution> solutions =
        solutionRepo.findByExecutionRun_IdOrderBySequenceNumberAsc(executionRunId);
    if (solutions.isEmpty()) return "COMPLETE"; // nothing to execute; unchanged legacy behaviour
    for (ExecutionSolution s : solutions) {
      String status = s.getStatus() == null ? "" : s.getStatus().trim().toUpperCase(Locale.ROOT);
      if ("DONE".equals(status) || "SKIPPED".equals(status)) continue;
      log.warn(
          "deriveTerminalStatus: exec {} solution {} is {} — launch is not COMPLETE",
          executionRunId,
          s.getId(),
          status.isEmpty() ? "(unknown)" : status);
      return "FAILED";
    }
    return "COMPLETE";
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
        // RR-349 — the protected terminal set now includes FAILED and CANCELLED. Previously only
        // ABORTED and COMPLETE were protected, so an existing FAILED terminal state (written by a
        // concurrent writer that had real failure evidence) was silently overwritten by whatever
        // status the caller requested — including COMPLETE.
        if (LAUNCH_TERMINAL.contains(fresh.getStatus())) {
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
  /**
   * RR-350 — cancellation is now an atomic claim from an explicitly cancellable state, and the
   * destructive compensations run only for the caller that WON that claim.
   *
   * <p>Previously the endpoint had no lifecycle precondition and this method attempted an
   * unconditional mutation to ABORTED, then proceeded to cancel descendants and close every
   * solution's pull request <em>even when that mutation failed</em>, operating on the stale
   * controller-loaded row. A late or retried cancel could therefore rewrite an already-COMPLETE
   * launch as ABORTED or — worse — lose the database transition yet still close pull requests that
   * had already been delivered to the customer.
   *
   * <p>Now: a terminal launch is a no-op, and a lost CAS means another writer owns the lifecycle,
   * so this call performs no side effects at all.
   */
  public void abortExecution(ExecutionRun run) {
    // Re-read: the `run` parameter was loaded by the controller (request thread, OSIV off) and may
    // be arbitrarily stale by now. Never make an irreversible decision from it.
    ExecutionRun fresh = runRepo.findById(run.getId()).orElse(null);
    if (fresh == null) return;
    if (LAUNCH_TERMINAL.contains(fresh.getStatus())) {
      log.info(
          "abortExecution: exec {} is already terminal at {}; no cancellation performed",
          run.getId(),
          fresh.getStatus());
      return;
    }
    // 2026-04-26 hotfix — covered by ExecutionRunCasUpdater. The CAS refetches + retries, so a
    // concurrent @Async writer bumping @Version does not silently drop the user's cancel. The
    // guard inside the mutator makes the claim conditional on the row still being cancellable at
    // the moment of the write.
    boolean[] claimed = {false};
    boolean ok =
        runCas.casUpdate(
            run.getId(),
            r -> {
              if (LAUNCH_TERMINAL.contains(r.getStatus())) {
                claimed[0] = false;
                return;
              }
              r.setStatus("ABORTED");
              claimed[0] = true;
            });
    if (!ok || !claimed[0]) {
      // RR-350 — losing the claim (or racing a terminal transition) means we are NOT the owner of
      // this cancellation. Running compensations here is what closed already-delivered PRs.
      log.warn(
          "abortExecution: did not win the cancellation claim for exec {} (casApplied={},"
              + " claimed={}); skipping all compensations",
          run.getId(),
          ok,
          claimed[0]);
      return;
    }
    emit(run.getId(), ExecutionEventTypes.EXEC_ABORTED, null);
    ExecutionRun owned = runRepo.findById(run.getId()).orElse(fresh);
    cancelActiveV2Runs(owned);
    runCompensations(owned);
  }

  /**
   * Cancel every V2 PipelineRun still in flight for this ExecutionRun. Without this, the Abort
   * button flips ExecutionRun.status=ABORTED but each solution's downstream V2 run keeps executing
   * (the Launches tab then shows "RUNNING" even though the user already aborted).
   *
   * <p>V188 (2026-05-13) — also cancels the unified-dispatch V2 run linked via {@code
   * ExecutionRun.pipelineRunId}. Per-Solution cancellation still fires below for any legacy
   * in-flight executions during the deprecation window.
   */
  private void cancelActiveV2Runs(ExecutionRun run) {
    // V188 — unified path: one V2 run for the whole execution. Cancel it.
    if (run.getPipelineRunId() != null && !run.getPipelineRunId().isBlank()) {
      try {
        boolean cancelled = runLifecycleService.cancel(run.getPipelineRunId());
        log.info(
            "abort exec={} (unified) pipelineRun={} cancel={}",
            run.getId(),
            run.getPipelineRunId(),
            cancelled);
      } catch (Exception e) {
        log.warn(
            "abort exec={} (unified) pipelineRun={} cancel failed: {}",
            run.getId(),
            run.getPipelineRunId(),
            e.getMessage());
      }
    }

    Set<String> terminalSolStatus = Set.of("DONE", "SKIPPED", "FAILED");
    List<ExecutionSolution> solutions =
        solutionRepo.findByExecutionRun_IdOrderBySequenceNumberAsc(run.getId());
    for (ExecutionSolution s : solutions) {
      String solStatus = s.getStatus() == null ? "" : s.getStatus().toUpperCase();
      if (terminalSolStatus.contains(solStatus)) continue;
      String pipelineRunId = s.getPipelineRunId();
      if (pipelineRunId == null || pipelineRunId.isBlank()) continue;
      try {
        boolean cancelled = runLifecycleService.cancel(pipelineRunId);
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
