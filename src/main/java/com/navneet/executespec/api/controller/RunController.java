package com.navneet.executespec.api.controller;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import com.navneet.executespec.api.RunMapper;
import com.navneet.executespec.api.dto.ApprovalRequest;
import com.navneet.executespec.api.dto.RunDetail;
import com.navneet.executespec.api.dto.RunRequest;
import com.navneet.executespec.api.dto.RunSummary;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.JwtAuthenticationFilter;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.auth.security.RunVisibilitySpec;
import com.navneet.executespec.auth.service.AiProviderPreferenceService;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.billing.service.UsageTrackingService;
import com.navneet.executespec.deployment.GcsWorkspaceStore;
import com.navneet.executespec.deployment.WorkspaceExportService;
import com.navneet.executespec.deployment.WorkspaceFileService;
import com.navneet.executespec.integration.scm.ScmPushService;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentMessage;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.entity.RunRecovery;
import com.navneet.executespec.state.repository.AgentMessageRepository;
import com.navneet.executespec.state.repository.RunRecoveryRepository;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
@Slf4j
public class RunController {

  private final com.navneet.executespec.integration.RunOrchestrator v2Orchestrator;
  private final com.navneet.executespec.integration.RunLifecycleService runLifecycleService;
  private final ScmPushService scmPushService;
  private final StateStore stateStore;
  private final RunMapper mapper;
  private final RunRecoveryRepository recoveryRepository;
  private final WorkspaceExportService workspaceExportService;
  private final WorkspaceFileService workspaceFileService;
  private final GcsWorkspaceStore gcsWorkspaceStore;
  private final AgentMessageRepository agentMessageRepository;
  private final TierEnforcementService tierEnforcementService;
  private final ModelAccessService modelAccessService;
  private final UsageTrackingService usageTrackingService;
  private final RunVisibilitySpec runVisibilitySpec;
  private final RunAccessGuard guard;
  private final com.navneet.executespec.project.security.ProjectAccessGuard projectAccessGuard;
  private final com.navneet.executespec.agent.ApiKeyResolver apiKeyResolver;
  private final com.navneet.executespec.billing.service.ManagedAiUsageService managedAiUsageService;

  /** M-032 + M-033 (Wave 3) — plan-approval governance guards. */
  private final com.navneet.executespec.governance.GovernanceGuard governanceGuard;

  private final com.navneet.executespec.run.sync.RunSyncService runSyncService;
  private final com.navneet.executespec.state.repository.PlanVersionRepository planVersionRepo;
  private final com.navneet.executespec.auth.service.ActivityAuditService activityAuditService;
  private final com.navneet.executespec.sse.SseBroadcaster broadcaster;
  private final com.navneet.executespec.agent.CostEstimator costEstimator;
  private final com.navneet.executespec.cost.RunCostTracker v2CostTracker;
  private final com.navneet.executespec.harness.GateStore gateStore;
  private final com.navneet.executespec.config.featureflag.FeatureFlagService featureFlagService;
  private final com.navneet.executespec.security.InputSanitizer inputSanitizer;

  /** Audit r5 (#5, 2026-05-06) — staged-PR-before-push mode. */
  private final com.navneet.executespec.run.RunSkipPushFlag runSkipPushFlag;

  /** P3 local-hands (RL-2026-06-014) — per-run LOCAL-execution marker. */
  private final com.navneet.executespec.run.RunLocalExecutionFlag runLocalExecutionFlag;

  /** RL-2026-06-005 — server-side dedupe of retried submits carrying an {@code Idempotency-Key}. */
  private final com.navneet.executespec.run.RunIdempotencyService runIdempotencyService;

  private final AiProviderPreferenceService aiProviderPreferenceService;

  /**
   * Wave 2.1 — activation funnel (SPEC_SUBMITTED fired here on run submit, PLAN_APPROVED here on
   * approval).
   */
  private final com.navneet.executespec.analytics.EventTrackingService eventTracking;

  /**
   * Submit a new run. Returns immediately with the run summary (status may be PLANNING or QUEUED).
   */
  @PostMapping
  public ResponseEntity<RunSummary> submit(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @Valid @RequestBody RunRequest req,
      HttpServletRequest httpRequest,
      @org.springframework.web.bind.annotation.RequestHeader(
              value = "Idempotency-Key",
              required = false)
          String idempotencyKey) {
    guard.requireAuthenticated(userDetails);
    log.info("POST /api/runs — goal: {}", req.userRequest());

    // Content-level security scan (prompt injection, shell embedding, abuse patterns)
    inputSanitizer.scan(req.userRequest());

    // Check if submissions are paused (maintenance mode)
    if (featureFlagService.isEnabled("submissions.paused")) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Run submissions are temporarily paused for maintenance. Please try again later.");
    }

    boolean localExecutionRequested = Boolean.TRUE.equals(req.localExecution());
    if (localExecutionRequested && !featureFlagService.isEnabled("worker.local-hands.enabled")) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Local-hands execution is not enabled on this backend. Use remote run submit or ask an"
              + " operator to enable worker.local-hands.enabled.");
    }
    if (localExecutionRequested && !isPatAuthenticated(httpRequest)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Local-hands execution requires a CLI or VS Code Personal Access Token session; web UI"
              + " runs execute remotely.");
    }

    // Idempotent submit (RL-2026-06-005): a retried POST /api/runs carrying the same
    // Idempotency-Key returns the ORIGINAL run instead of creating a duplicate. Checked BEFORE the
    // quota/usage increments below, so a client retry (after a transient 5xx whose response was
    // lost) never double-charges the user's monthly quota. Scoped + ownership-checked so a key can
    // only ever resolve to the caller's own run; fail-open if Redis is down.
    final String userId = userDetails.getUser().getId();
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      java.util.Optional<String> priorRunId = runIdempotencyService.lookup(userId, idempotencyKey);
      if (priorRunId.isPresent()) {
        java.util.Optional<PipelineRun> prior = stateStore.findRun(priorRunId.get());
        if (prior.isPresent() && userId.equals(prior.get().getCreatedByUserId())) {
          log.info(
              "Idempotent submit — returning existing run {} for a repeated Idempotency-Key",
              prior.get().getRunId());
          return ResponseEntity.status(HttpStatus.ACCEPTED).body(mapper.toSummary(prior.get()));
        }
      }
    }

    // Tier gate: a free-tier caller can't run a premium model (the client shows the whole catalog
    // and lets them pick, but a gated choice is rejected here). Before the quota increment so a
    // rejected model never burns the user's monthly quota.
    modelAccessService.assertModelAllowed(userId, req.modelName());

    // Run-count retirement (2026-06-22): spend is gated by managed ES AI credits, not a monthly run
    // cap. assertCanStartRun throws 402 only when a managed tier (FREE/PRO) is EXHAUSTED and the
    // caller is not pure BYOK (own-key spend is cap-exempt). The run counter is still bumped for
    // analytics — no enforcement, no refund needed. Per-call debit/step-down in LlmRouter remains
    // the mid-run source of truth.
    managedAiUsageService.assertCanStartRun(userDetails, apiKeyResolver.isOwnKeysMode(userId));
    tierEnforcementService.incrementRunCountForAnalytics(userId);
    // Pre-flight: in OWN_KEYS mode, ensure at least one enabled provider has a key
    apiKeyResolver.validateKeyAvailability(userDetails.getUser().getId());

    // Extract per-user GitHub credentials to pass into the run
    String githubPat = userDetails.getUser().getGithubPat();
    String githubOwner = userDetails.getUser().getGithubOwner();
    String effectiveAiProvider =
        req.aiProvider() != null && !req.aiProvider().isBlank()
            ? aiProviderPreferenceService.updatePreferredProvider(userDetails, req.aiProvider())
            : aiProviderPreferenceService.preferredProvider(userDetails);

    // Pass ALL pre-async fields INTO submitRun so they're set on the entity
    // BEFORE the async pipeline thread starts (avoids race condition where the
    // pipeline thread re-loads and re-saves the run without ownership fields).
    String executionMode = req.executionMode() != null ? req.executionMode() : "PRODUCTION";

    // V2 is the only harness; HarnessVersionResolver retired with deprecation P3.
    PipelineRun run =
        v2Orchestrator.submitRun(
            req.userRequest(),
            req.source() != null ? req.source() : "REST",
            req.repoUrl(),
            req.repoMode(),
            githubPat,
            githubOwner,
            Boolean.TRUE.equals(req.skipApproval()),
            Boolean.TRUE.equals(req.autoMerge()),
            req.modelName(),
            effectiveAiProvider,
            userDetails.getUser().getId(),
            userDetails.getUser().getOrgId(),
            req.projectId(),
            executionMode,
            req.selectedRepoIds(),
            req.figmaUrl(),
            Boolean.TRUE.equals(req.tddMode()),
            req.branch(),
            req.prReviewComments(),
            req.requireConfirmationBeforeExpensiveContinuation(),
            req.requireBenchmarkPriorForAutomaticContinuation(),
            req.lowComplexityWorkerStartTier());

    // Record (user, key) → runId so a retried submit with the same Idempotency-Key returns THIS run
    // instead of creating a duplicate (RL-2026-06-005). Recorded immediately after creation so it's
    // present when the client's backoff retry arrives; best-effort (no-op if Redis is down).
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      runIdempotencyService.record(userId, idempotencyKey, run.getRunId());
    }

    // Run count incremented atomically inside `assertAndIncrementRunCount` above. The
    // usageTrackingService increment below tracks the BILLING surface (not the quota counter),
    // which is independent and used by `assertWithinLimits` for the plan-tier billing limits.
    usageTrackingService.increment(userDetails.getUser().getId(), "RUNS", 1);

    // GA-readiness P3.2 — stamp the customer-supplied cost cap onto the new run row so
    // RunOrchestrator.persistEstimate picks it up when the planner returns a plan and clamps
    // hard_cap_usd to min(platformCap, userCostCap). Validation (DTO @DecimalMin/@DecimalMax)
    // already rejected unbounded or negative values before reaching here.
    if (req.userCostCap() != null) {
      stateStore.setUserCostCap(run.getRunId(), req.userCostCap());
    }

    // Audit r5 (#5, 2026-05-06) — staged-PR-before-push. The flag rides
    // a per-run Redis key (24h TTL); RunOrchestrator's auto-push gate
    // consumes it once on terminal-success. JVM crash during the run
    // → flag dies with the Redis key's TTL or with the next consume()
    // call, default behaviour (auto-push) resumes naturally.
    if (Boolean.TRUE.equals(req.skipAutoPush())) {
      runSkipPushFlag.mark(run.getRunId());
    }

    // P3 local-hands (RL-2026-06-014) — mark LOCAL execution the same transient way;
    // RunOrchestrator
    // consumes it once at pipeline start (gated by worker.local-hands.enabled, default OFF).
    if (localExecutionRequested) {
      runLocalExecutionFlag.mark(run.getRunId());
    }

    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(mapper.toSummary(run, localExecutionRequested, localExecutionRequested));
  }

  /**
   * List runs, optionally filtered by status, projectId, and scope. scope=mine restricts to the
   * current user's runs (within org visibility).
   */
  @GetMapping
  public ResponseEntity<Map<String, Object>> list(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false) String scope,
      @RequestParam(defaultValue = "false") boolean archived,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    guard.requireAuthenticated(userDetails);
    page = Math.max(0, page);
    size = Math.max(1, Math.min(100, size));
    var visSpec = runVisibilitySpec.forUser(userDetails.getUser(), userDetails.getOrgMember());
    boolean realProjectFilter =
        projectId != null && !projectId.isBlank() && !"__none__".equals(projectId);
    if (realProjectFilter) {
      projectAccessGuard.assertCanRead(projectId, userDetails);
      visSpec = (root, query, cb) -> cb.conjunction();
    }

    // scope=mine  → only the current user's runs
    // scope=org   → only runs in the user's org (useful for SUPER_ADMIN to see org-only view)
    // scope=all   → all runs (SUPER_ADMIN only — default for super admin when no scope)
    if ("mine".equals(scope)) {
      Specification<PipelineRun> mineSpec =
          (root, query, cb) -> cb.equal(root.get("createdByUserId"), userDetails.getUser().getId());
      visSpec = visSpec.and(mineSpec);
    } else if ("org".equals(scope) && userDetails.getUser().getOrgId() != null) {
      String orgId = userDetails.getUser().getOrgId();
      Specification<PipelineRun> orgSpec = (root, query, cb) -> cb.equal(root.get("orgId"), orgId);
      visSpec = visSpec.and(orgSpec);
    }

    Page<PipelineRun> runs =
        stateStore.listRuns(status, projectId, archived, PageRequest.of(page, size), visSpec);
    // RL-2026-06-019 — batch variant hydrates each row's verifiedScore (one query per page) so
    // the runs list can lead with the score instead of provider internals.
    List<RunSummary> content = mapper.toSummaries(runs.getContent());
    return ResponseEntity.ok(
        Map.of(
            "content", content,
            "page", runs.getNumber(),
            "size", runs.getSize(),
            "totalElements", runs.getTotalElements(),
            "totalPages", runs.getTotalPages(),
            "hasNext", runs.hasNext(),
            "hasPrevious", runs.hasPrevious()));
  }

  /** Get a single run with task details. */
  @GetMapping("/{runId}")
  public ResponseEntity<RunDetail> get(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    PipelineRun run = guard.requireRunAccess(runId, userDetails);
    return ResponseEntity.ok(mapper.toDetail(run, stateStore.getAllTasks(runId)));
  }

  /**
   * Live cost cache: runId → [costUsd, computedAtMillis]. Completed runs cached forever,
   * in-progress cached 15s.
   */
  private final java.util.concurrent.ConcurrentHashMap<String, double[]> costCache =
      new java.util.concurrent.ConcurrentHashMap<>();

  private static final long COST_CACHE_TTL_MS = 30_000;
  private static final java.util.Set<String> TERMINAL_STATUSES =
      java.util.Set.of("COMPLETE", "FAILED", "CANCELLED", "PARTIAL");

  /** Compute the live cost for a run. Cached: terminal runs forever, in-progress runs for 15s. */
  @GetMapping("/{runId}/cost")
  public ResponseEntity<java.util.Map<String, Object>> getLiveCost(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    var run = guard.requireRunAccess(runId, userDetails);

    // Terminal run with stored cost → return immediately with DB totals.
    // The stored total_input_tokens / total_output_tokens are authoritative
    // once the run is done (V2 orchestrator writes them at phase boundaries
    // and at terminate()).
    if (TERMINAL_STATUSES.contains(run.getStatus()) && run.getActualCostUsd() != null) {
      Map<String, Object> body = baseCostBody(runId, run.getActualCostUsd());
      body.put("inputTokens", safeTok(run.getTotalInputTokens()));
      body.put("outputTokens", safeTok(run.getTotalOutputTokens()));
      body.put("cacheReadTokens", null);
      body.put("cacheWriteTokens", null);
      body.put("reasoningTokens", null);
      body.put("calls", null);
      body.put("source", "stored_total");
      return ResponseEntity.ok(body);
    }

    // V2 in-progress: live cost AND live tokens come from RunCostTracker.
    // V2 only flushes DB token columns at phase boundaries (planner done →
    // worker done → reviewer done), so `total_input_tokens` lags the real
    // usage mid-run. Reading the in-memory accumulator gives the UI an
    // honest live view: cost and tokens stay in lockstep instead of showing
    // "Total tokens 3.4K · Live cost $3.31" (planner-only tokens vs full
    // cost accumulator).
    if ("v2".equals(run.getHarnessVersion())) {
      var snap = v2CostTracker.snapshot(runId);
      int liveIn = Math.max(snap.inputTokens(), safeTok(run.getTotalInputTokens()));
      int liveOut = Math.max(snap.outputTokens(), safeTok(run.getTotalOutputTokens()));
      Map<String, Object> body = baseCostBody(runId, snap.costUsd());
      body.put("inputTokens", liveIn);
      body.put("outputTokens", liveOut);
      body.put("cacheReadTokens", snap.cacheReadTokens());
      body.put("cacheWriteTokens", snap.cacheWriteTokens());
      body.put("reasoningTokens", snap.reasoningTokens());
      body.put("calls", snap.calls());
      body.put("source", "live_tracker");
      return ResponseEntity.ok(body);
    }

    double[] cached =
        costCache.compute(
            runId,
            (k, existing) -> {
              if (existing != null) {
                boolean isTerminal = TERMINAL_STATUSES.contains(run.getStatus());
                if (isTerminal || (System.currentTimeMillis() - existing[1]) < COST_CACHE_TTL_MS) {
                  return existing;
                }
              }
              double cost = costEstimator.computeCostForRun(runId);
              return new double[] {cost, System.currentTimeMillis()};
            });
    Map<String, Object> body = baseCostBody(runId, cached[0]);
    body.put("inputTokens", safeTok(run.getTotalInputTokens()));
    body.put("outputTokens", safeTok(run.getTotalOutputTokens()));
    body.put("cacheReadTokens", null);
    body.put("cacheWriteTokens", null);
    body.put("reasoningTokens", null);
    body.put("calls", null);
    body.put("source", "legacy_estimator");
    return ResponseEntity.ok(body);
  }

  private static Map<String, Object> baseCostBody(String runId, double costUsd) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("runId", runId);
    body.put("costUsd", costUsd);
    body.put("schemaVersion", 1);
    return body;
  }

  private static int safeTok(Integer v) {
    return v == null ? 0 : v;
  }

  static boolean isPatAuthenticated(HttpServletRequest request) {
    return JwtAuthenticationFilter.AUTH_SOURCE_PAT.equals(
        request.getAttribute(JwtAuthenticationFilter.AUTH_SOURCE_ATTRIBUTE));
  }

  /** Approve or reject the plan-approval gate. */
  @PostMapping("/{runId}/approve")
  public ResponseEntity<Void> approvePlan(
      @PathVariable String runId,
      @RequestBody ApprovalRequest req,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    PipelineRun approveRun = guard.requireRunWriteAccess(runId, userDetails);
    log.info(
        "POST /api/runs/{}/approve — approved={}, creditsConfirmed={}, executionMode={},"
            + " harness={}",
        runId,
        req.approved(),
        req.creditsConfirmed(),
        req.executionMode(),
        approveRun.getHarnessVersion());

    // Validate creditsConfirmed matches the current estimate (prevents stale approvals)
    if (req.approved() && req.creditsConfirmed() != null) {
      stateStore
          .findRun(runId)
          .ifPresent(
              run -> {
                int expected;
                if ("BATCH".equalsIgnoreCase(req.executionMode())
                    && run.getBatchCredits() != null) {
                  expected = run.getBatchCredits();
                } else {
                  expected = run.getCreditsCharged() != null ? run.getCreditsCharged() : 1;
                }
                if (!req.creditsConfirmed().equals(expected)) {
                  throw new ResponseStatusException(
                      HttpStatus.CONFLICT,
                      "Credits mismatch: expected "
                          + expected
                          + " but confirmed "
                          + req.creditsConfirmed()
                          + ". Cost estimate may have changed — please review.");
                }
              });
    }

    // M-032 + M-033 (Wave 3) — governance gates run before we ever
    // signal the plan gate. Skip on rejection (req.approved == false).
    if (req.approved()) {
      double estUsd =
          approveRun.getCostEstimateHigh() != null
              ? approveRun.getCostEstimateHigh()
              : (approveRun.getHardCapUsd() != null ? approveRun.getHardCapUsd() : 0.0);
      governanceGuard.assertCanApproveCost(approveRun.getOrgId(), estUsd, userDetails);
      governanceGuard.assertProjectSpendCap(approveRun.getProjectId(), estUsd);
    }

    // Validate and persist execution mode choice
    if (req.approved() && "BATCH".equalsIgnoreCase(req.executionMode())) {
      stateStore
          .findRun(runId)
          .ifPresent(
              run -> {
                if (!Boolean.TRUE.equals(run.getBatchAvailable())) {
                  throw new ResponseStatusException(
                      HttpStatus.BAD_REQUEST, "Batch execution is not available for this run.");
                }
              });
      stateStore.setApprovedExecutionMode(runId, "BATCH");
    }

    // V2 orchestrator blocks on GateStore.awaitPlanGate() which completes
    // instantly on the in-memory future. If no future exists (server
    // restarted while run was AWAITING_APPROVAL), the pipeline thread is
    // gone — signalling the gate would write Redis/DB without anything
    // reading them, leaving the run silently stuck. Detect that case and
    // fail the run cleanly so the user gets an actionable message.
    if (!gateStore.isPlanGateWaiting(runId)) {
      log.warn(
          "POST /api/runs/{}/approve — no pipeline thread waiting; "
              + "run was interrupted during AWAITING_APPROVAL. Failing cleanly.",
          runId);
      // Still persist the gate decision (DB+Redis) — the audit record is
      // useful — then mark the run FAILED with a retry-able reason.
      gateStore.setPlanGate(runId, req.approved());
      stateStore
          .findRun(runId)
          .ifPresent(
              r -> {
                r.setStatus("FAILED");
                r.setFailureReason(
                    "Approval received after server restart — pipeline "
                        + "thread was lost. Please submit a new run.");
                r.setCompletedAt(Instant.now());
                stateStore.saveRun(r);
              });
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Approval received but the pipeline was interrupted by a restart. "
              + "Please submit a new run.");
    }
    // Atomic claim — if another concurrent approve call already resolved the gate,
    // return 409 Conflict to this caller (adversarial-test-report Finding 4).
    boolean claimed = gateStore.tryClaimPlanGate(runId, req.approved());
    if (!claimed) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Plan gate has already been resolved by another caller.");
    }

    // Wave 2.1 — activation funnel step 4 (fires once per user, only on
    // positive approval — rejections don't count as "crossed the credit bar").
    if (req.approved() && approveRun.getCreatedByUserId() != null) {
      eventTracking.track(
          approveRun.getCreatedByUserId(),
          com.navneet.executespec.analytics.EventName.PLAN_APPROVED,
          java.util.Map.of(
              "executionMode", req.executionMode() == null ? "REALTIME" : req.executionMode(),
              "creditsConfirmed", req.creditsConfirmed() == null ? 0 : req.creditsConfirmed()));
    }
    return ResponseEntity.ok().build();
  }

  // V2 has no clarification gate, merge gate, reopen-merge, pause/resume, or
  // in-place rework — those were V1-specific concepts. Endpoints that no
  // longer apply are returned 410 Gone so existing frontend callers get a
  // clear signal rather than 404/500.

  public record ClarificationRequest(String response) {}

  public record EditAndRestartRequest(String userRequest) {}

  @PostMapping("/{runId}/clarify")
  public ResponseEntity<Void> clarifyContext(
      @PathVariable String runId,
      @RequestBody ClarificationRequest req,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    throw new ResponseStatusException(
        HttpStatus.GONE,
        "Clarification gate removed in V2 — this endpoint is no longer supported.");
  }

  @PostMapping("/{runId}/merge")
  public ResponseEntity<Void> approveMerge(
      @PathVariable String runId,
      @RequestBody ApprovalRequest req,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    throw new ResponseStatusException(
        HttpStatus.GONE, "V2 auto-merges; explicit merge gate removed.");
  }

  @PostMapping("/{runId}/retry")
  public ResponseEntity<Void> retryRun(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    throw new ResponseStatusException(
        HttpStatus.GONE, "Run retry not yet supported in V2 — submit a fresh run instead.");
  }

  /**
   * Phase 1.3 #35 — fork-a-teammate's-run. Creates a new run with the source run's spec / repo /
   * provider settings copied; new run gets a fresh runId, its own quota slot, and is owned by the
   * caller (not the source's user).
   *
   * <p>Visibility: any user with read access to the source run can fork it. Within an org, this
   * means any org member sees + can fork their teammates' runs. Outside an org, only the owner can
   * fork their own.
   *
   * <p>The fork inherits the source's tone (executionMode, tddMode, model choices) so the user gets
   * exactly the same setup — they can override via the optional body before submitting.
   */
  @PostMapping("/{runId}/fork")
  public ResponseEntity<RunSummary> forkRun(
      @PathVariable String runId,
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestBody(required = false) ForkOverrides overrides) {
    guard.requireRunAccess(runId, userDetails);
    PipelineRun source =
        stateStore
            .findRun(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));

    String forkerId = userDetails.getUser().getId();

    // Fork creates a fresh run, so apply the same managed-AI start gate to the forker.
    // Run-count quota is analytics-only here.
    managedAiUsageService.assertCanStartRun(userDetails, apiKeyResolver.isOwnKeysMode(forkerId));
    tierEnforcementService.incrementRunCountForAnalytics(forkerId);
    apiKeyResolver.validateKeyAvailability(userDetails.getUser().getId());
    modelAccessService.assertModelAllowed(forkerId, source.getPlannerModel());
    if (source.getAiProvider() != null && !source.getAiProvider().isBlank()) {
      aiProviderPreferenceService.normalizeProvider(source.getAiProvider());
    }

    String userRequest =
        overrides != null && overrides.userRequest() != null && !overrides.userRequest().isBlank()
            ? overrides.userRequest()
            : source.getUserRequest();
    // Re-scan in case the user edited the spec in the fork dialog.
    inputSanitizer.scan(userRequest);
    String execMode =
        overrides != null && overrides.executionMode() != null
            ? overrides.executionMode()
            : (source.getApprovedExecutionMode() != null
                ? source.getApprovedExecutionMode()
                : "REALTIME");

    log.info("Forking run {} as new run for user {}", runId, forkerId);
    PipelineRun forked =
        v2Orchestrator.submitRun(
            userRequest,
            "FORK",
            source.getRepoUrl(),
            source.getRepoUrl() != null ? "EXISTING" : null,
            userDetails.getUser().getGithubPat(),
            userDetails.getUser().getGithubOwner(),
            false, // skipApproval — caller approves their fork explicitly
            false, // autoMerge — same
            source.getPlannerModel(),
            source.getAiProvider(),
            forkerId,
            userDetails.getUser().getOrgId(),
            source.getProjectId(),
            execMode,
            null, // selectedRepoIds — source's projectId carries the repo set
            source.getFigmaUrl(),
            false // tddMode — opt-in per fork
            );
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(mapper.toSummary(forked));
  }

  /** Optional overrides for {@code POST /api/runs/{id}/fork}. All fields nullable. */
  public record ForkOverrides(String userRequest, String executionMode) {}

  /**
   * Pause a running V2 run. The pause request is observed at the next task / iteration boundary
   * (NOT mid-LLM-call); the in-flight LLM round-trip completes before pause takes effect to avoid
   * orphaning provider tokens. Valid only from {@code WORKING} or {@code REVIEWING}; the controller
   * returns 409 from any other status (planning is short-bounded; awaiting-approval is already
   * paused via the plan gate; queued runs should use cancel; terminal states are no-ops).
   *
   * <p>Gated behind the {@code v2.pause.enabled} feature flag (V168) so ops can flip it on after
   * smoke-verifying on dev. When OFF the endpoint returns 503 with the flag name in the message —
   * matches the pre-V168 behaviour where pause was 410 Gone but with a clearer "feature is off, not
   * gone" framing.
   */
  @PostMapping("/{runId}/pause")
  public ResponseEntity<Void> pauseRun(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    if (!featureFlagService.isEnabled("v2.pause.enabled")) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Pause/resume is feature-flagged off (v2.pause.enabled). Ask an admin to enable it.");
    }
    boolean paused = runLifecycleService.pause(runId);
    if (!paused) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Pause not valid from this status — only WORKING and REVIEWING runs can be paused.");
    }
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/{runId}/edit-and-restart")
  public ResponseEntity<Void> editAndRestart(
      @PathVariable String runId,
      @RequestBody EditAndRestartRequest body,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    throw new ResponseStatusException(
        HttpStatus.GONE, "Edit-and-restart not supported in V2 — submit a fresh run.");
  }

  /**
   * Resume a paused V2 run. Valid only from {@code PAUSED}; 409 from anywhere else. Implementation
   * is same-JVM only today — the workspace lives on local disk and cross-JVM resume requires GCS
   * shipping (deferred chunk #3 of the pause/resume design). When the workspace is gone the
   * orchestrator returns false and the controller returns 409 with a recovery hint pointing at
   * {@code POST /runs/{id}/retry} for cross-JVM cases.
   */
  @PostMapping("/{runId}/resume")
  public ResponseEntity<Void> resumeRun(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    if (!featureFlagService.isEnabled("v2.pause.enabled")) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Pause/resume is feature-flagged off (v2.pause.enabled). Ask an admin to enable it.");
    }
    com.navneet.executespec.integration.RunOrchestrator.ResumeOutcome outcome =
        runLifecycleService.resume(runId);
    return switch (outcome) {
      case OK -> ResponseEntity.accepted().build();
      case NOT_PAUSED ->
          throw new ResponseStatusException(
              HttpStatus.CONFLICT, "Resume valid only for PAUSED runs.");
      case WORKSPACE_GONE ->
          throw new ResponseStatusException(
              HttpStatus.CONFLICT,
              "Workspace is no longer on this node. Use POST /api/runs/{id}/retry to start a fresh"
                  + " run with the same spec.");
      case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found.");
    };
  }

  /**
   * Push an existing COMPLETE/PARTIAL run's workspace to GitHub. If repoMode=NEW and no repoUrl,
   * creates a new repo first. This avoids re-running the pipeline just to push generated code.
   *
   * @deprecated Use {@code POST /api/runs/{runId}/push-scm} instead, which routes to the correct
   *     SCM provider (GitHub or Bitbucket) based on user credentials.
   */
  @Deprecated
  @PostMapping("/{runId}/push-github")
  public ResponseEntity<Map<String, String>> pushToGitHub(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @PathVariable String runId,
      @RequestParam(required = false) String repoUrl,
      @RequestParam(required = false, defaultValue = "NEW") String repoMode) {
    PipelineRun run = guard.requireRunWriteAccess(runId, userDetails);
    log.info("POST /api/runs/{}/push-github — repoMode={} repoUrl={}", runId, repoMode, repoUrl);
    if (!List.of("COMPLETE", "PARTIAL").contains(run.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Only COMPLETE or PARTIAL runs can be pushed to GitHub (current: "
              + run.getStatus()
              + ")");
    }
    // Override run's repo config with request params
    if (repoUrl != null && !repoUrl.isBlank()) run.setRepoUrl(repoUrl);
    run.setRepoMode(repoMode);
    // Ensure PAT is available (from user or run or system)
    if (run.getGithubPat() == null || run.getGithubPat().isBlank()) {
      run.setGithubPat(userDetails.getUser().getGithubPat());
      run.setGithubOwner(userDetails.getUser().getGithubOwner());
    }
    stateStore.save(run);
    scmPushService.push(run);
    // SDET 2026-05-16 (BUG-018) — surface push outcome honestly. The previous response was a
    // hard-coded `{status: "pushed"}` whether or not a PR actually got created. When the user has
    // no GitHub installation, `scmPushService.push` short-circuits silently — leaving the user
    // confused because the button reports success but no PR appears anywhere. Reload the run to
    // see whether a PR URL got populated by the push, then derive `status` + `prUrl` from that.
    PipelineRun updated = stateStore.findRun(runId).orElse(run);
    String prUrl =
        updated.getGithubPrUrl() != null
            ? updated.getGithubPrUrl()
            : (updated.getScmPrUrl() != null ? updated.getScmPrUrl() : "");
    String status = prUrl.isBlank() ? "no_install" : "pushed";
    String message =
        prUrl.isBlank()
            ? "No GitHub installation found for this user. Connect GitHub under Settings →"
                + " Integrations and retry."
            : "Pushed and PR opened.";
    return ResponseEntity.ok(
        Map.of(
            "status",
            status,
            "repoUrl",
            updated.getRepoUrl() != null ? updated.getRepoUrl() : "",
            "prUrl",
            prUrl,
            "message",
            message));
  }

  /**
   * Push an existing COMPLETE/PARTIAL run's workspace to the appropriate SCM provider. Routes to
   * GitHub or Bitbucket based on user credentials: - If user has Bitbucket credentials → pushes to
   * Bitbucket, creates PR - Else if user has GitHub PAT → pushes to GitHub, creates PR - If neither
   * → returns 400
   */
  /**
   * Refresh the run's workspace from a branch on origin (typically used after a PR is merged
   * externally so the user can re-run review/tests against the post-merge code without restarting
   * the whole pipeline). Multi-repo runs sync every {@code repos/<name>/} subdirectory to the same
   * branch; missing or unknown branch names fall back to that repo's own default branch.
   *
   * <p>Hard-resets the workspace — uncommitted local edits are wiped. Only allowed on terminal
   * runs.
   */
  @PostMapping("/{runId}/sync")
  public ResponseEntity<Map<String, Object>> syncRun(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @PathVariable String runId,
      @RequestParam(value = "branch", required = false) String branch) {
    PipelineRun run = guard.requireRunWriteAccess(runId, userDetails);
    if (!List.of("COMPLETE", "PARTIAL", "FAILED", "CANCELLED").contains(run.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Run sync requires a terminal status (current: " + run.getStatus() + ")");
    }
    var outcome = runSyncService.sync(runId, branch);
    // SDET 2026-05-16 (BUG-019) — distinguish "everything synced" from "nothing synced (all
    // failed)" / "partial sync". The previous response said `status: "synced"` whether or not any
    // repo actually moved; with `reposSynced=0 + details=[FAILED ...]` the UI happily displayed
    // a green check even though every clone failed.
    boolean anyFailed = outcome.details().stream().anyMatch(d -> d.contains("FAILED"));
    String syncStatus;
    if (outcome.reposSynced() == 0 && anyFailed) syncStatus = "failed";
    else if (anyFailed) syncStatus = "partial";
    else syncStatus = "synced";
    return ResponseEntity.ok(
        Map.of(
            "status", syncStatus,
            "reposSynced", outcome.reposSynced(),
            "details", outcome.details()));
  }

  /**
   * v2.5.2 — reset every cloned repo in the workspace back to the exact commit SHA captured at run
   * start (from {@code run_repo_snapshot}). Distinct from {@code POST /sync} which resets to
   * current origin HEAD of the chosen branch.
   *
   * <p>Returns 409 when the run predates v2.5.2 and has no snapshot rows.
   */
  @PostMapping("/{runId}/sync/original")
  public ResponseEntity<Map<String, Object>> syncRunToOriginal(
      @AuthenticationPrincipal AppUserDetails userDetails, @PathVariable String runId) {
    PipelineRun run = guard.requireRunWriteAccess(runId, userDetails);
    if (!List.of("COMPLETE", "PARTIAL", "FAILED", "CANCELLED").contains(run.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Run sync requires a terminal status (current: " + run.getStatus() + ")");
    }
    var outcome = runSyncService.syncToOriginalCommit(runId);
    // SDET 2026-05-16 (BUG-019) — same outcome aggregation as /sync above so the UI doesn't
    // surface a misleading success when every repo failed to reset.
    boolean anyFailed = outcome.details().stream().anyMatch(d -> d.contains("FAILED"));
    String syncStatus;
    if (outcome.reposSynced() == 0 && anyFailed) syncStatus = "failed";
    else if (anyFailed) syncStatus = "partial";
    else syncStatus = "synced";
    return ResponseEntity.ok(
        Map.of(
            "status", syncStatus,
            "reposSynced", outcome.reposSynced(),
            "details", outcome.details()));
  }

  @PostMapping("/{runId}/push-scm")
  public ResponseEntity<Map<String, String>> pushToScm(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @PathVariable String runId,
      @RequestParam(required = false) String repoUrl,
      @RequestParam(required = false, defaultValue = "EXISTING") String repoMode) {
    PipelineRun run = guard.requireRunWriteAccess(runId, userDetails);
    log.info("POST /api/runs/{}/push-scm — repoMode={} repoUrl={}", runId, repoMode, repoUrl);
    if (!List.of("COMPLETE", "PARTIAL").contains(run.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Only COMPLETE or PARTIAL runs can be pushed to SCM (current: " + run.getStatus() + ")");
    }
    // Override run's repo config with request params
    if (repoUrl != null && !repoUrl.isBlank()) run.setRepoUrl(repoUrl);
    run.setRepoMode(repoMode);

    var user = userDetails.getUser();

    // Populate Bitbucket credentials from user if not on run
    if (run.getBitbucketUsername() == null && user.getBitbucketUsername() != null) {
      run.setBitbucketUsername(user.getBitbucketUsername());
      run.setBitbucketAppPassword(user.getBitbucketAppPassword());
      run.setBitbucketWorkspace(user.getBitbucketWorkspace());
      run.setBitbucketRepoSlug(user.getBitbucketRepoSlug());
    }
    // Populate GitHub credentials from user if not on run
    if ((run.getGithubPat() == null || run.getGithubPat().isBlank())
        && user.getGithubPat() != null) {
      run.setGithubPat(user.getGithubPat());
      run.setGithubOwner(user.getGithubOwner());
    }

    stateStore.save(run);
    scmPushService.push(run);

    // Reload to pick up PR URL set during push
    PipelineRun updated = stateStore.findRun(runId).orElse(run);
    String prUrl =
        updated.getScmPrUrl() != null
            ? updated.getScmPrUrl()
            : (updated.getGithubPrUrl() != null ? updated.getGithubPrUrl() : "");
    String provider = updated.getScmProvider() != null ? updated.getScmProvider() : "";

    return ResponseEntity.ok(
        Map.of(
            "status",
            "pushed",
            "repoUrl",
            updated.getRepoUrl() != null ? updated.getRepoUrl() : "",
            "prUrl",
            prUrl,
            "scmProvider",
            provider));
  }

  /**
   * Update the project association of an existing run. Send null or empty projectId to un-assign.
   */
  @PatchMapping("/{runId}/project")
  public ResponseEntity<RunSummary> updateRunProject(
      @PathVariable String runId,
      @RequestBody Map<String, String> body,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    PipelineRun run = guard.requireRunWriteAccess(runId, userDetails);
    String newProjectId = body.get("projectId");
    if (newProjectId != null && !newProjectId.isBlank()) {
      projectAccessGuard.assertCanWrite(newProjectId, userDetails);
    }
    run.setProjectId(newProjectId != null && !newProjectId.isBlank() ? newProjectId : null);
    stateStore.save(run);
    log.info("PATCH /api/runs/{}/project — projectId={}", runId, run.getProjectId());
    return ResponseEntity.ok(mapper.toSummary(run));
  }

  /** Cancel a run. */
  @DeleteMapping("/{runId}")
  public ResponseEntity<Void> cancel(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    log.info("DELETE /api/runs/{}", runId);
    boolean cancelled = runLifecycleService.cancel(runId);
    return cancelled ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
  }

  /**
   * Archive a run. Only terminal runs (COMPLETE, FAILED, CANCELLED, PARTIAL) can be archived.
   *
   * <p>V191 (PR-B-part-2, 2026-05-13) — archive is now reversible via {@link #unarchiveRun}; the
   * cleanup sweeper (PR-H) hard-deletes archived rows after their retention window expires.
   * Idempotent: re-archiving returns 200 OK without writing a duplicate audit row.
   */
  @PostMapping("/{runId}/archive")
  public ResponseEntity<Map<String, String>> archiveRun(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    log.info("POST /api/runs/{}/archive (actor={})", runId, userDetails.getUser().getId());
    try {
      PipelineRun run = stateStore.archiveRun(runId);
      activityAuditService.record(
          com.navneet.executespec.auth.entity.ActivityAudit.builder()
              .eventType("RUN_ARCHIVED")
              .entityType("RUN")
              .entityId(runId)
              .actorId(userDetails.getUser().getId())
              .orgId(userDetails.getUser().getOrgId())
              .oldValue(run.getStatus())
              .build());
      return ResponseEntity.ok(Map.of("message", "Run archived successfully"));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
  }

  /**
   * V191 (PR-B-part-2) — reverse of {@link #archiveRun}. Idempotent: unarchiving an already-active
   * run returns 200 OK with no audit row. Refuses with 409 if the run is soft-deleted; the caller
   * must restore first.
   */
  @PostMapping("/{runId}/unarchive")
  public ResponseEntity<Map<String, String>> unarchiveRun(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    log.info("POST /api/runs/{}/unarchive (actor={})", runId, userDetails.getUser().getId());
    try {
      stateStore.unarchiveRun(runId);
      activityAuditService.record(
          com.navneet.executespec.auth.entity.ActivityAudit.builder()
              .eventType("RUN_UNARCHIVED")
              .entityType("RUN")
              .entityId(runId)
              .actorId(userDetails.getUser().getId())
              .orgId(userDetails.getUser().getOrgId())
              .build());
      return ResponseEntity.ok(Map.of("message", "Run unarchived successfully"));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
  }

  /**
   * V191 (PR-B-part-2) — soft-delete a run with a 30-day undo window. Distinct from {@link
   * #cancel}: cancel aborts an in-flight run; soft-delete tombstones a terminal run so it
   * disappears from listings but can be restored within 30 days via {@link #restoreRun}. After the
   * window expires the cleanup sweeper (PR-H) hard-deletes the row + workspace.
   *
   * <p>Uses POST verb (not DELETE) because {@code DELETE /api/runs/{id}} is already the
   * cancel-in-flight endpoint. Idempotent — re-deleting does not extend the 30-day window.
   */
  @PostMapping("/{runId}/soft-delete")
  public ResponseEntity<Map<String, String>> softDeleteRun(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    log.info("POST /api/runs/{}/soft-delete (actor={})", runId, userDetails.getUser().getId());
    try {
      PipelineRun run = stateStore.softDeleteRun(runId);
      activityAuditService.record(
          com.navneet.executespec.auth.entity.ActivityAudit.builder()
              .eventType("RUN_DELETED")
              .entityType("RUN")
              .entityId(runId)
              .actorId(userDetails.getUser().getId())
              .orgId(userDetails.getUser().getOrgId())
              .oldValue(run.getStatus())
              .newValue("{\"deletedAt\":\"" + run.getDeletedAt() + "\"}")
              .build());
      return ResponseEntity.ok(
          Map.of("message", "Run deleted — restorable for 30 days via POST /restore"));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
  }

  /**
   * V191 (PR-B-part-2) — undo a soft-delete within the 30-day window. 404 if the run is not
   * currently soft-deleted (so the frontend can disambiguate "no such run" from "already
   * restored"). Archive state is preserved.
   */
  @PostMapping("/{runId}/restore")
  public ResponseEntity<Map<String, String>> restoreRun(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    // requireRunWriteAccess() pulls the run by id including soft-deleted rows (visibility guards
    // operate on org + project membership, not on the deletedAt tombstone). Confirmed by
    // reading PipelineRunRepository.findById which is the standard JPA finder.
    guard.requireRunWriteAccess(runId, userDetails);
    log.info("POST /api/runs/{}/restore (actor={})", runId, userDetails.getUser().getId());
    try {
      stateStore.restoreRun(runId);
      activityAuditService.record(
          com.navneet.executespec.auth.entity.ActivityAudit.builder()
              .eventType("RUN_RESTORED")
              .entityType("RUN")
              .entityId(runId)
              .actorId(userDetails.getUser().getId())
              .orgId(userDetails.getUser().getOrgId())
              .build());
      return ResponseEntity.ok(Map.of("message", "Run restored successfully"));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    }
  }

  /**
   * Force-retry a single PARTIAL or BLOCKED task within a PARTIAL/FAILED run. Resets the task,
   * re-runs the worker, reviewer, and merge gate. Returns 202 Accepted immediately; processing is
   * asynchronous.
   */
  @PostMapping("/{runId}/tasks/{taskId}/retry")
  public ResponseEntity<Map<String, Object>> retryTask(
      @PathVariable String runId,
      @PathVariable String taskId,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    log.info("POST /api/runs/{}/tasks/{}/retry", runId, taskId);
    try {
      runLifecycleService.retryTask(runId, taskId);
      // SDET 2026-05-16 (BUG-017) — return a non-empty body so the UI's generic ApiResult parser
      // can surface a confirmation message instead of "ok!" silence. The 4xx mapping (task not
      // found → 404, wrong state → 409) was already in place; the empty-202 was the residual
      // wart that made the SDET probe report ambiguous behaviour.
      return ResponseEntity.accepted()
          .body(
              Map.of(
                  "status",
                  "queued",
                  "runId",
                  runId,
                  "taskId",
                  taskId,
                  "message",
                  "Task retry queued — watch the run feed for progress."));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
  }

  @PostMapping("/{runId}/tasks/{taskId}/continuation/approve")
  public ResponseEntity<Map<String, Object>> approveContinuation(
      @PathVariable String runId,
      @PathVariable String taskId,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    log.info("POST /api/runs/{}/tasks/{}/continuation/approve", runId, taskId);
    try {
      var approval = runLifecycleService.approveContinuation(runId, taskId);
      return ResponseEntity.accepted()
          .body(
              Map.of(
                  "status",
                  "queued",
                  "runId",
                  runId,
                  "taskId",
                  taskId,
                  "provider",
                  approval.provider(),
                  "model",
                  approval.model(),
                  "postReview",
                  approval.postReview(),
                  "message",
                  "Approved continuation queued — watch the run feed for progress."));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
  }

  @PostMapping("/{runId}/implement-suggestions")
  public ResponseEntity<Void> implementSuggestions(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    throw new ResponseStatusException(
        HttpStatus.GONE, "Implement-suggestions not yet supported in V2.");
  }

  @PostMapping("/{runId}/rework")
  public ResponseEntity<Void> reworkRun(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    throw new ResponseStatusException(HttpStatus.GONE, "Rework not yet supported in V2.");
  }

  @PostMapping("/{runId}/reopen-merge")
  public ResponseEntity<Void> reopenMerge(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    throw new ResponseStatusException(
        HttpStatus.GONE, "V2 auto-merges; merge gate / reopen-merge removed.");
  }

  /**
   * Manually retry background validation (review + test gen + report) for a COMPLETE run whose
   * background validation failed. Returns 409 if validation is already running or max retries
   * exhausted.
   */
  @PostMapping("/{runId}/retry-validation")
  public ResponseEntity<Void> retryValidation(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    log.info("POST /api/runs/{}/retry-validation", runId);
    try {
      runLifecycleService.retryValidation(runId);
      return ResponseEntity.accepted().build();
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
  }

  /** Get recovery history for a run. */
  @GetMapping("/{runId}/recovery")
  public ResponseEntity<List<RecoveryResponse>> getRecovery(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunAccess(runId, userDetails);
    List<RunRecovery> recoveries =
        recoveryRepository.findByOriginalRunIdOrRestartRunId(runId, runId);
    List<RecoveryResponse> response =
        recoveries.stream()
            .map(
                r ->
                    new RecoveryResponse(
                        r.getId(),
                        r.getOriginalRunId(),
                        r.getRestartRunId(),
                        r.getStuckTaskId(),
                        r.getClassification(),
                        r.getClassificationDetail(),
                        r.getActionTaken(),
                        r.getRecoveredAt(),
                        r.getRestartCreatedAt()))
            .toList();
    return ResponseEntity.ok(response);
  }

  /** Return recent agent-message log for a run (up to 200 entries), sorted oldest-first. */
  @GetMapping("/{runId}/messages")
  public ResponseEntity<List<MessageResponse>> getMessages(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunAccess(runId, userDetails);
    List<AgentMessage> all = agentMessageRepository.findByRunIdOrderBySentAt(runId);
    int start = Math.max(0, all.size() - 200);
    List<MessageResponse> response =
        all.subList(start, all.size()).stream()
            .map(m -> new MessageResponse(m.getFromAgent(), m.getContentSummary(), m.getSentAt()))
            .collect(Collectors.toList());
    return ResponseEntity.ok(response);
  }

  /**
   * RL-2026-05-046 — the run's live-log events (the same stream the Live page shows), persisted in
   * the SSE replay log (24h TTL), so the run-detail "Logs" tab can render them with icons +
   * readable messaging even after the run finished. Returns [] for runs older than the replay
   * window.
   */
  @GetMapping("/{runId}/event-log")
  public ResponseEntity<List<java.util.Map<String, Object>>> getEventLog(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunAccess(runId, userDetails);
    return ResponseEntity.ok(broadcaster.getReplayLog(runId));
  }

  /** Download the workspace for a run as a zip file. */
  @GetMapping("/{id}/download")
  public ResponseEntity<?> downloadWorkspace(
      @PathVariable String id,
      @RequestParam(defaultValue = "zip") String format,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    PipelineRun run = guard.requireRunAccess(id, userDetails);
    try {
      // "repo" format — clean archive with .gitignore and README
      if ("repo".equals(format)) {
        byte[] repoZip = workspaceExportService.exportAsRepoZip(id);
        String runId8 = id.substring(0, Math.min(8, id.length()));
        return ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + runId8 + "-repo.zip\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(repoZip);
      }

      // Path 1: GCS archive — redirect to signed URL
      if (run.getGcsWorkspaceKey() != null) {
        URL signedUrl =
            gcsWorkspaceStore.signedDownloadUrl(run.getGcsWorkspaceKey(), Duration.ofMinutes(5));
        log.debug("Redirecting download for run {} to GCS signed URL", id);
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, signedUrl.toString())
            .build();
      }

      // Path 2: Local workspace — zip on the fly and stream
      try {
        Path zipPath = workspaceExportService.exportWorkspace(id);
        String runId8 = id.substring(0, Math.min(8, id.length()));
        String filename = "workspace-" + runId8 + ".zip";

        Resource resource = new FileSystemResource(zipPath);
        zipPath.toFile().deleteOnExit();

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(Files.size(zipPath))
            .body(resource);

      } catch (IllegalStateException e) {
        log.warn("Workspace not available for run {}: {}", id, e.getMessage());
        // Path 3: GitHub fallback
        if (run.getRepoUrl() != null && !run.getRepoUrl().isBlank()) {
          String[] ownerRepo =
              com.navneet.executespec.repo.GitHubRepoService.extractOwnerRepo(run.getRepoUrl());
          if (ownerRepo != null) {
            String githubZip =
                "https://github.com/"
                    + ownerRepo[0]
                    + "/"
                    + ownerRepo[1]
                    + "/archive/refs/heads/main.zip";
            log.info(
                "Local workspace missing for run {} — redirecting to GitHub zip: {}",
                id,
                githubZip);
            return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, githubZip)
                .build();
          }
        }
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Workspace not available for download. " + e.getMessage());
      }

    } catch (ResponseStatusException e) {
      throw e;
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Failed to export workspace: " + e.getMessage());
    }
  }

  /** List all workspace files (relative paths) for a run. */
  @GetMapping("/{id}/files")
  public ResponseEntity<List<String>> listWorkspaceFiles(
      @PathVariable String id, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunAccess(id, userDetails);
    try {
      return ResponseEntity.ok(workspaceFileService.listFiles(id));
    } catch (IOException e) {
      return ResponseEntity.ok(List.of());
    }
  }

  /**
   * 2026-05-02 — same listing as {@link #listWorkspaceFiles} but each entry carries a git-derived
   * {@code status} (ADDED / MODIFIED / UNCHANGED) so the run-detail file tree can decorate paths
   * with badges. User report: "no way to know which classes have been added/updated in code."
   * Multi-repo aware — runs {@code git status --porcelain} per {@code repos/<name>/} subdir.
   */
  @GetMapping("/{id}/files/status")
  public ResponseEntity<List<com.navneet.executespec.deployment.WorkspaceFileService.FileEntry>>
      listWorkspaceFilesWithStatus(
          @PathVariable String id, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunAccess(id, userDetails);
    try {
      return ResponseEntity.ok(workspaceFileService.listFilesWithStatus(id));
    } catch (IOException e) {
      return ResponseEntity.ok(List.of());
    }
  }

  /** Serve the generated OpenAPI 3.0 YAML spec for a run. */
  @GetMapping(value = "/{runId}/openapi.yaml", produces = MediaType.TEXT_PLAIN_VALUE)
  public ResponseEntity<String> getOpenApiSpec(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunAccess(runId, userDetails);
    // V2 planner mandate (runtimeSurface=HTTP) writes openapi.yaml at the repo root;
    // V1 ApiTestSuiteAgent writes api-docs/openapi.yaml. Try root first, fall back.
    String[] candidates = {"openapi.yaml", "openapi.yml", "api-docs/openapi.yaml"};
    try {
      String yaml = null;
      for (String path : candidates) {
        try {
          yaml = workspaceFileService.readFile(runId, path);
          break;
        } catch (java.nio.file.NoSuchFileException ignored) {
          // try next
        }
      }
      if (yaml == null) return ResponseEntity.notFound().build();
      return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "application/yaml").body(yaml);
    } catch (SecurityException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read OpenAPI spec: " + e.getMessage());
    }
  }

  /** Read the content of a single workspace file. */
  @GetMapping(value = "/{id}/file", produces = MediaType.TEXT_PLAIN_VALUE)
  public ResponseEntity<String> readWorkspaceFile(
      @PathVariable String id,
      @RequestParam String path,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunAccess(id, userDetails);
    try {
      return ResponseEntity.ok(workspaceFileService.readFile(id, path));
    } catch (SecurityException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
    } catch (java.nio.file.NoSuchFileException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found: " + path);
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read file: " + e.getMessage());
    }
  }

  /**
   * 2026-05-02 — base + head content for a single workspace file so the run-detail UI can render a
   * Monaco {@code DiffEditor} (only the diff is visible, not the whole file). The base is the
   * snapshot the run was cloned against ({@code RunRepoSnapshot.commitSha}); head is the current
   * filesystem state. ADDED files have empty {@code base}; deleted files have empty {@code head}.
   * Outside any {@code repos/<name>/} subdir there is no base — the worker is the only writer for
   * those (e.g. {@code .agent-context/project.json}) so they come back as ADDED.
   */
  @GetMapping(value = "/{id}/file/diff", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.navneet.executespec.deployment.WorkspaceFileService.FileDiff>
      readWorkspaceFileDiff(
          @PathVariable String id,
          @RequestParam String path,
          @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunAccess(id, userDetails);
    try {
      return ResponseEntity.ok(workspaceFileService.readFileDiff(id, path));
    } catch (SecurityException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
    } catch (java.nio.file.NoSuchFileException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found: " + path);
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read file diff: " + e.getMessage());
    }
  }

  @GetMapping("/{runId}/plan-versions")
  public ResponseEntity<List<com.navneet.executespec.state.entity.PlanVersion>> getPlanVersions(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunAccess(runId, userDetails);
    return ResponseEntity.ok(planVersionRepo.findByRunIdOrderByVersionDesc(runId));
  }

  @PostMapping("/{runId}/edit-plan")
  public ResponseEntity<Map<String, Object>> editPlan(
      @PathVariable String runId,
      @RequestBody com.navneet.executespec.api.dto.EditPlanRequest req,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    throw new ResponseStatusException(
        HttpStatus.GONE,
        "In-place plan editing not yet supported in V2 — reject the plan and submit a fresh run"
            + " with refined spec.");
  }

  /** Returns the task dependency graph for a run. */
  @GetMapping("/{runId}/task-graph")
  public ResponseEntity<List<TaskGraphNode>> getTaskGraph(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    PipelineRun run = guard.requireRunAccess(runId, userDetails);

    var tasks = stateStore.getAllTasks(runId);
    if (tasks.isEmpty()) return ResponseEntity.ok(List.of());

    com.fasterxml.jackson.databind.ObjectMapper om =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .setPropertyNamingStrategy(
                com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
    record TaskMeta(
        String taskId,
        String title,
        String status,
        String complexity,
        String assignedModel,
        List<String> dependsOn,
        String parentTaskId,
        Integer subtaskIndex,
        Integer subtaskTotal) {}

    List<TaskMeta> metas =
        tasks.stream()
            .map(
                t -> {
                  List<String> deps = List.of();
                  try {
                    var def =
                        om.readValue(
                            t.getTaskJson(),
                            com.navneet.executespec.agent.model.TaskDefinition.class);
                    if (def.getDependsOn() != null) deps = def.getDependsOn();
                  } catch (Exception ignored) {
                  }
                  return new TaskMeta(
                      t.getTaskId(),
                      t.getTitle(),
                      t.getStatus(),
                      t.getComplexity(),
                      t.getAssignedModel(),
                      deps,
                      t.getParentTaskId(),
                      t.getSubtaskIndex(),
                      t.getSubtaskTotal());
                })
            .toList();

    // Compute topological levels via Kahn's-style BFS
    Map<String, Integer> levels = new java.util.HashMap<>();
    Map<String, TaskMeta> byId = metas.stream().collect(Collectors.toMap(TaskMeta::taskId, m -> m));

    metas.stream().filter(m -> m.dependsOn().isEmpty()).forEach(m -> levels.put(m.taskId(), 0));

    boolean changed = true;
    while (changed) {
      changed = false;
      for (TaskMeta m : metas) {
        if (levels.containsKey(m.taskId())) continue;
        if (m.dependsOn().stream().allMatch(levels::containsKey)) {
          int level =
              m.dependsOn().stream().mapToInt(d -> levels.getOrDefault(d, 0)).max().orElse(-1) + 1;
          levels.put(m.taskId(), level);
          changed = true;
        }
      }
    }

    List<TaskGraphNode> nodes =
        metas.stream()
            .map(
                m ->
                    new TaskGraphNode(
                        m.taskId(),
                        m.title(),
                        m.status(),
                        m.complexity(),
                        m.assignedModel(),
                        m.dependsOn(),
                        levels.getOrDefault(m.taskId(), 0),
                        m.parentTaskId(),
                        m.subtaskIndex(),
                        m.subtaskTotal()))
            .sorted(
                java.util.Comparator.comparingInt(TaskGraphNode::level)
                    .thenComparing(TaskGraphNode::taskId))
            .toList();

    return ResponseEntity.ok(nodes);
  }

  public record TaskGraphNode(
      String taskId,
      String title,
      String status,
      String complexity,
      String assignedModel,
      List<String> dependsOn,
      int level,
      String parentTaskId,
      Integer subtaskIndex,
      Integer subtaskTotal) {}

  public record MessageResponse(String fromAgent, String contentSummary, Instant sentAt) {}

  public record RecoveryResponse(
      String id,
      String originalRunId,
      String restartRunId,
      String stuckTaskId,
      String classification,
      String classificationDetail,
      String actionTaken,
      Instant recoveredAt,
      Instant restartCreatedAt) {}
}
