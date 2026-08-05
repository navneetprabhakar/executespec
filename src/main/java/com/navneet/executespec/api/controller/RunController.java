package com.navneet.executespec.api.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import com.navneet.executespec.deployment.GcsWorkspaceStore;
import com.navneet.executespec.deployment.WorkspaceExportService;
import com.navneet.executespec.deployment.WorkspaceFileService;
import com.navneet.executespec.integration.scm.PublicationAdmissionService;
import com.navneet.executespec.integration.scm.PublicationEligibilityPresenter;
import com.navneet.executespec.integration.scm.ScmPushService;
import com.navneet.executespec.run.CustomerRunSubmissionService;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentMessage;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.entity.RunRecovery;
import com.navneet.executespec.state.entity.RunRepo;
import com.navneet.executespec.state.repository.AgentMessageRepository;
import com.navneet.executespec.state.repository.RunRecoveryRepository;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
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

  private final CustomerRunSubmissionService customerRunSubmissionService;
  private final com.navneet.executespec.integration.RunLifecycleService runLifecycleService;
  private final com.navneet.executespec.integration.PlanApprovalService planApprovalService;
  private final ScmPushService scmPushService;
  private final PublicationEligibilityPresenter publicationEligibilityPresenter;
  private final StateStore stateStore;
  private final RunMapper mapper;
  private final RunRecoveryRepository recoveryRepository;
  private final WorkspaceExportService workspaceExportService;
  private final WorkspaceFileService workspaceFileService;
  private final GcsWorkspaceStore gcsWorkspaceStore;
  private final AgentMessageRepository agentMessageRepository;
  private final RunVisibilitySpec runVisibilitySpec;
  private final com.navneet.executespec.auth.repository.ProjectMemberRepository
      projectMemberRepository;
  private final RunAccessGuard guard;
  private final com.navneet.executespec.project.security.ProjectAccessGuard projectAccessGuard;

  private final com.navneet.executespec.contracts.gate.PlanContractGateService planContractGate;

  private final com.navneet.executespec.run.sync.RunSyncService runSyncService;
  private final com.navneet.executespec.state.repository.PlanVersionRepository planVersionRepo;
  private final com.navneet.executespec.auth.service.ActivityAuditService activityAuditService;
  private final com.navneet.executespec.sse.SseBroadcaster broadcaster;
  private final com.navneet.executespec.agent.CostEstimator costEstimator;
  private final com.navneet.executespec.cost.RunCostTracker v2CostTracker;
  private final com.navneet.executespec.cost.RunCostEvidenceService runCostEvidenceService;
  private final com.navneet.executespec.harness.GateStore gateStore;
  private final com.navneet.executespec.config.featureflag.FeatureFlagService featureFlagService;

  /** Audit r5 (#5, 2026-05-06) — staged-PR-before-push mode. */
  private final com.navneet.executespec.run.RunSkipPushFlag runSkipPushFlag;

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
    boolean patAuthenticated = isPatAuthenticated(httpRequest);
    var submission =
        customerRunSubmissionService.submit(
            CustomerRunSubmissionService.Command.builder()
                .userRequest(req.userRequest())
                .source(req.source())
                .repoUrl(req.repoUrl())
                .repoMode(req.repoMode())
                .skipApproval(Boolean.TRUE.equals(req.skipApproval()))
                .autoMerge(Boolean.TRUE.equals(req.autoMerge()))
                .modelName(req.modelName())
                .aiProvider(req.aiProvider())
                .rememberProviderPreference(req.aiProvider() != null && !req.aiProvider().isBlank())
                .projectId(req.projectId())
                .executionMode(req.executionMode())
                .selectedRepoIds(req.selectedRepoIds())
                .figmaUrl(req.figmaUrl())
                .tddMode(Boolean.TRUE.equals(req.tddMode()))
                .branchOverride(req.branch())
                .prReviewComments(req.prReviewComments())
                .userCostCap(req.userCostCap())
                .skipAutoPush(Boolean.TRUE.equals(req.skipAutoPush()))
                .requireConfirmationBeforeExpensiveContinuation(
                    req.requireConfirmationBeforeExpensiveContinuation())
                .requireBenchmarkPriorForAutomaticContinuation(
                    req.requireBenchmarkPriorForAutomaticContinuation())
                .lowComplexityWorkerStartTier(req.lowComplexityWorkerStartTier())
                .localExecution(Boolean.TRUE.equals(req.localExecution()))
                .patAuthenticated(patAuthenticated)
                .localInstallationId(req.localInstallationId())
                .localRepositoryInventory(req.localRepositoryInventory())
                .idempotencyKey(idempotencyKey)
                .requestFingerprint(
                    com.navneet.executespec.run.RunRequestFingerprint.of(
                        new java.util.ArrayList<>(fingerprintOf(req).values())))
                .build(),
            userDetails);
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(
            mapper.toSummary(
                submission.run(), submission.localExecution(), submission.localExecution()));
  }

  /**
   * The values that decide what a run builds and what it costs. A reused Idempotency-Key carrying
   * any different value here is a conflict, never a silent replay of the earlier run.
   *
   * <p>RR-1553 — this list must cover EVERY {@link RunRequest} component. It previously omitted
   * {@code requireConfirmationBeforeExpensiveContinuation} and {@code
   * requireBenchmarkPriorForAutomaticContinuation} (both real continuation spend guards), {@code
   * skipAutoPush} (publication policy), {@code prReviewComments} and {@code
   * localRepositoryInventory} (planner input) and {@code localInstallationId} (the LOCAL source
   * binding). A user who submitted, noticed a spend guard was off, and retried with the same key
   * and the guard on was silently handed back the earlier UNGUARDED run with HTTP 202. Keyed by
   * component name so {@code RunControllerSubmitFingerprintTest} can assert reflectively that a new
   * DTO field cannot be forgotten here.
   */
  static java.util.LinkedHashMap<String, String> fingerprintOf(RunRequest req) {
    java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
    values.put("userRequest", req.userRequest());
    values.put("source", req.source());
    values.put("repoUrl", req.repoUrl());
    values.put("repoMode", req.repoMode());
    values.put("projectId", req.projectId());
    values.put("modelName", req.modelName());
    values.put("aiProvider", req.aiProvider());
    values.put("executionMode", req.executionMode());
    values.put("branch", req.branch());
    values.put("figmaUrl", req.figmaUrl());
    values.put("prReviewComments", req.prReviewComments());
    values.put("skipApproval", String.valueOf(req.skipApproval()));
    values.put("autoMerge", String.valueOf(req.autoMerge()));
    values.put("skipAutoPush", String.valueOf(req.skipAutoPush()));
    values.put("tddMode", String.valueOf(req.tddMode()));
    values.put("localExecution", String.valueOf(req.localExecution()));
    values.put("localInstallationId", String.valueOf(req.localInstallationId()));
    values.put("localRepositoryInventory", req.localRepositoryInventory());
    values.put("userCostCap", String.valueOf(req.userCostCap()));
    values.put("selectedRepoIds", canonicalRepoIds(req.selectedRepoIds()));
    values.put("lowComplexityWorkerStartTier", String.valueOf(req.lowComplexityWorkerStartTier()));
    values.put(
        "requireConfirmationBeforeExpensiveContinuation",
        String.valueOf(req.requireConfirmationBeforeExpensiveContinuation()));
    values.put(
        "requireBenchmarkPriorForAutomaticContinuation",
        String.valueOf(req.requireBenchmarkPriorForAutomaticContinuation()));
    return values;
  }

  /**
   * Repo selection is a set, not a sequence — {@code List.toString} made a reordered but
   * semantically identical selection hash differently and produced a spurious CONFLICT. Sorting
   * canonicalizes it; a genuinely different membership still changes the hash.
   */
  private static String canonicalRepoIds(java.util.List<java.util.UUID> ids) {
    if (ids == null) return "null";
    return ids.stream()
        .map(id -> id == null ? "" : id.toString())
        .sorted()
        .collect(java.util.stream.Collectors.joining(","));
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
    var requestedPage = com.navneet.executespec.api.PageBounds.request(page, size, 100);
    // RR-212 — include runs of projects the caller actively belongs to (cross-org collaborations
    // included) so an accepted collaborator can discover them in the list, not only open a known
    // run URL. Detail access is still enforced per-run by RunAccessGuard.
    java.util.List<String> memberProjectIds =
        projectMemberRepository.findActiveMemberProjectIdsByUserId(userDetails.getUser().getId());
    var visSpec =
        runVisibilitySpec.forUser(
            userDetails.getUser(), userDetails.getOrgMember(), memberProjectIds);
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
        stateStore.listRuns(status, projectId, archived, requestedPage, visSpec);
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
    return ResponseEntity.ok(mapper.toDetail(run, stateStore.getAllTasks(runId), userDetails));
  }

  /**
   * Read-only preview of the exact deterministic gates that the SCM/deployment mutation boundary
   * will repeat immediately before publication.
   *
   * <p>The gate verdict is unchanged {@link PublicationAdmissionService#evaluate}; the presenter
   * only adds the additive {@code alreadyPublished} / {@code publishedPrUrls} acknowledgment so a
   * BLOCKED re-publication does not contradict a visibly created/merged PR.
   */
  @GetMapping("/{runId}/publication-eligibility")
  public ResponseEntity<PublicationEligibilityPresenter.Response> getPublicationEligibility(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    PipelineRun run = guard.requireRunAccess(runId, userDetails);
    return ResponseEntity.ok(publicationEligibilityPresenter.present(run));
  }

  /**
   * Live cost cache: runId → [costUsd, computedAtMillis].
   *
   * <p>RR-238 — this used to be an unbounded process-wide map that retained terminal legacy-run
   * entries forever, so querying distinct historical run costs grew JVM heap monotonically for the
   * lifetime of the process. It is now a bounded access-order LRU: the eldest entry is evicted once
   * {@link #COST_CACHE_MAX_ENTRIES} is reached. Terminal entries are still preferred (never TTL-
   * expired) but are no longer immortal — under cache pressure they fall out and recompute.
   */
  private static final int COST_CACHE_MAX_ENTRIES = 2_000;

  private final Map<String, double[]> costCache =
      java.util.Collections.synchronizedMap(
          new java.util.LinkedHashMap<String, double[]>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, double[]> eldest) {
              return size() > COST_CACHE_MAX_ENTRIES;
            }
          });

  private static final long COST_CACHE_TTL_MS = 30_000;
  private static final java.util.Set<String> TERMINAL_STATUSES =
      java.util.Set.of("COMPLETE", "FAILED", "CANCELLED", "PARTIAL");

  /** Compute the live cost for a run. Cached: terminal runs forever, in-progress runs for 15s. */
  @GetMapping("/{runId}/cost")
  public ResponseEntity<java.util.Map<String, Object>> getLiveCost(
      @PathVariable String runId,
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestParam(value = "stepCallsPage", defaultValue = "0") int stepCallsPage,
      @RequestParam(
              value = "stepCallsSize",
              defaultValue =
                  ""
                      + com.navneet.executespec.cost.RunCostEvidenceService
                          .DEFAULT_STEP_CALLS_PAGE_SIZE)
          int stepCallsSize) {
    var run = guard.requireRunAccess(runId, userDetails);
    // RR-1552 — the per-attempt ledger carries key-source, exact routing and per-attempt spend.
    // cost/CLAUDE.md restricts that detail to own-org ORG_ADMIN / SUPER_ADMIN; the run read guard
    // is broader (BILLING_ADMIN, org developers, cross-org project viewers).
    boolean attemptDetail =
        com.navneet.executespec.cost.RunCostEvidenceService.canViewPhysicalAttemptDetail(
            userDetails, run);

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
      runCostEvidenceService.enrich(body, run, attemptDetail, stepCallsPage, stepCallsSize);
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
      long liveIn = Math.max(snap.inputTokens(), safeTok(run.getTotalInputTokens()));
      long liveOut = Math.max(snap.outputTokens(), safeTok(run.getTotalOutputTokens()));
      Map<String, Object> body = baseCostBody(runId, snap.costUsd());
      body.put("inputTokens", liveIn);
      body.put("outputTokens", liveOut);
      body.put("cacheReadTokens", snap.cacheReadTokens());
      body.put("cacheWriteTokens", snap.cacheWriteTokens());
      body.put("reasoningTokens", snap.reasoningTokens());
      body.put("calls", snap.calls());
      body.put("source", "live_tracker");
      runCostEvidenceService.enrich(body, run, attemptDetail, stepCallsPage, stepCallsSize);
      return ResponseEntity.ok(body);
    }

    // RR-238 — the bounded LRU is not a ConcurrentMap, so the read/compute/insert is done under
    // one lock. removeEldestEntry (fired on put) and access-order both need the whole sequence to
    // be atomic; cost estimation is best-effort so a rare concurrent recompute is acceptable.
    double[] cached;
    synchronized (costCache) {
      double[] existing = costCache.get(runId);
      boolean fresh =
          existing != null
              && (TERMINAL_STATUSES.contains(run.getStatus())
                  || (System.currentTimeMillis() - existing[1]) < COST_CACHE_TTL_MS);
      if (fresh) {
        cached = existing;
      } else {
        cached = new double[] {costEstimator.computeCostForRun(runId), System.currentTimeMillis()};
        costCache.put(runId, cached);
      }
    }
    Map<String, Object> body = baseCostBody(runId, cached[0]);
    body.put("inputTokens", safeTok(run.getTotalInputTokens()));
    body.put("outputTokens", safeTok(run.getTotalOutputTokens()));
    body.put("cacheReadTokens", null);
    body.put("cacheWriteTokens", null);
    body.put("reasoningTokens", null);
    body.put("calls", null);
    body.put("source", "legacy_estimator");
    runCostEvidenceService.enrich(body, run, attemptDetail, stepCallsPage, stepCallsSize);
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

  static void rejectRepositoryDestinationOverride(String repoUrl) {
    if (repoUrl != null && !repoUrl.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Repository destination is bound to the run and cannot be overridden during push.");
    }
  }

  /**
   * RR-1671 — parse the untyped {@code repoMode} request parameter to the canonical {@link
   * RunRepo.RepoMode} at the controller boundary and reject an unknown value with a 400. This was
   * previously written straight onto the run and only refused late at the service publication
   * chokepoint (RR-900); an unparseable value is a client error, not a deferred publication
   * failure.
   */
  static String parseRepoMode(String repoMode) {
    if (repoMode == null || repoMode.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "repoMode is required. Must be NEW, EXISTING, or NONE.");
    }
    try {
      return RunRepo.RepoMode.valueOf(repoMode.trim().toUpperCase(java.util.Locale.ROOT)).name();
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Invalid repoMode: " + repoMode + ". Must be NEW, EXISTING, or NONE.");
    }
  }

  /** Approve or reject the plan-approval gate. */
  /**
   * RR-819 — what the approver must be shown before approving. Returns the durable gate report
   * composed while the run parked at AWAITING_APPROVAL, or 204 when this plan touches no registered
   * contract. Read-only; run-read guarded.
   */
  @GetMapping("/{runId}/plan-contract-gate")
  public ResponseEntity<com.navneet.executespec.contracts.gate.PlanContractGateReport>
      planContractGate(
          @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunAccess(runId, userDetails);
    return planContractGate
        .find(runId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

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

    var decision =
        planApprovalService.decide(
            runId,
            new com.navneet.executespec.integration.PlanApprovalService.Command(
                req.approved(),
                req.creditsConfirmed(),
                req.executionMode(),
                req.planFingerprint(),
                req.breakingChangeAck(),
                req.breakingChangeReportDigest()),
            userDetails,
            com.navneet.executespec.integration.PlanGateDecisionService.ResolutionSource
                .PLAN_APPROVAL_API);
    var outcome = decision.outcome();

    // Wave 2.1 — activation funnel step 4 (fires once per user, only on
    // positive approval — rejections don't count as "crossed the credit bar").
    if (req.approved()
        && outcome
            == com.navneet.executespec.integration.RunLifecycleService.PlanApprovalOutcome
                .RESUME_QUEUED
        && approveRun.getCreatedByUserId() != null) {
      eventTracking.track(
          approveRun.getCreatedByUserId(),
          com.navneet.executespec.analytics.EventName.PLAN_APPROVED,
          java.util.Map.of(
              "executionMode",
              decision.executionMode(),
              "creditsConfirmed",
              req.creditsConfirmed() == null ? 0 : req.creditsConfirmed()));
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
   * exactly the same setup — they can override the request, execution mode, or provider via the
   * optional body before submitting. A provider change clears the source planner-model pin because
   * model identifiers are provider-specific.
   */
  @PostMapping("/{runId}/fork")
  public ResponseEntity<RunSummary> forkRun(
      @PathVariable String runId,
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestBody(required = false) ForkOverrides overrides,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    guard.requireRunAccess(runId, userDetails);
    PipelineRun source =
        stateStore
            .findRun(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));

    String userRequest =
        overrides != null && overrides.userRequest() != null && !overrides.userRequest().isBlank()
            ? overrides.userRequest()
            : source.getUserRequest();
    // RR-1217 — two different axes were being confused here. `executionMode` is the
    // PRODUCTION/PROTOTYPE governance axis that decides whether the plan gate runs;
    // `approvedExecutionMode` is the REALTIME/BATCH scheduling axis. The fork used to read the
    // latter into the former, so a default source value of "REALTIME" was persisted as the run's
    // executionMode — and because the gate opens only on an exact "PRODUCTION" match, every
    // ordinary fork silently skipped the human plan gate and went straight to worker/provider
    // spend. Inherit the correct field, validate any override, and fail closed to PRODUCTION.
    String requestedExecMode =
        overrides != null
                && overrides.executionMode() != null
                && !overrides.executionMode().isBlank()
            ? overrides.executionMode()
            : source.getExecutionMode();
    String execMode = normalizeForkExecutionMode(requestedExecMode);
    ForkRoute forkRoute =
        resolveForkRoute(source.getAiProvider(), source.getPlannerModel(), overrides);

    log.info("Forking run {} as new run for user {}", runId, userDetails.getUser().getId());
    var commandBuilder =
        CustomerRunSubmissionService.Command.builder()
            .userRequest(userRequest)
            .source("FORK")
            .repoUrl(source.getRepoUrl())
            .repoMode(source.getRepoUrl() != null ? "EXISTING" : null)
            .modelName(forkRoute.modelName())
            .aiProvider(forkRoute.aiProvider())
            .projectId(source.getProjectId())
            .executionMode(execMode)
            // RR-1217 — preserve the source run's exact repository selection. Passing null meant
            // "every linked repo", so a multi-repo fork could silently broaden scope.
            .selectedRepoIds(
                com.navneet.executespec.integration.RunPipelineSupport.deserializeRepoIds(
                    source.getSelectedRepoIds()))
            .figmaUrl(source.getFigmaUrl())
            .tddMode(source.isTddMode())
            .userCostCap(source.getUserCostCap())
            .skipAutoPush(source.isSkipAutoPush())
            .idempotencyKey(idempotencyKey);
    CustomerRunSubmissionService.Command effectiveCommand = commandBuilder.build();
    String requestFingerprint =
        com.navneet.executespec.run.RunRequestFingerprint.of(
            new java.util.ArrayList<>(forkFingerprintOf(runId, effectiveCommand).values()));
    var submission =
        customerRunSubmissionService.submit(
            commandBuilder.requestFingerprint(requestFingerprint).build(), userDetails);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(mapper.toSummary(submission.run()));
  }

  /**
   * RR-1459 — stable identity of a run's persisted plan. Returns null when the run has no plan, in
   * which case no client-supplied fingerprint can match and the approval is rejected.
   */
  static String planFingerprintOf(String planJson) {
    return com.navneet.executespec.agent.requirement.ExactPlanFingerprint.sha256(planJson);
  }

  /**
   * RR-1217 — a fork's execution mode is the PRODUCTION/PROTOTYPE governance axis. Only those two
   * values are legal; anything else (including a REALTIME/BATCH value leaking in from the
   * scheduling axis, or an unknown override) fails closed to PRODUCTION so the fork keeps its human
   * plan gate.
   */
  static String normalizeForkExecutionMode(String requested) {
    if (requested == null) return "PRODUCTION";
    String normalized = requested.trim().toUpperCase(java.util.Locale.ROOT);
    return "PROTOTYPE".equals(normalized) ? "PROTOTYPE" : "PRODUCTION";
  }

  /** Optional overrides for {@code POST /api/runs/{id}/fork}. All fields nullable. */
  public record ForkOverrides(String userRequest, String executionMode, String aiProvider) {}

  static ForkRoute resolveForkRoute(
      String sourceProvider, String sourceModelName, ForkOverrides overrides) {
    String requestedProvider =
        overrides == null || overrides.aiProvider() == null || overrides.aiProvider().isBlank()
            ? null
            : overrides.aiProvider().trim().toUpperCase(java.util.Locale.ROOT);
    String normalizedSourceProvider =
        sourceProvider == null || sourceProvider.isBlank()
            ? null
            : sourceProvider.trim().toUpperCase(java.util.Locale.ROOT);
    boolean providerChanged =
        requestedProvider != null && !requestedProvider.equals(normalizedSourceProvider);
    return new ForkRoute(
        requestedProvider != null ? requestedProvider : sourceProvider,
        providerChanged ? null : sourceModelName);
  }

  record ForkRoute(String aiProvider, String modelName) {}

  /**
   * Complete effective fork identity. Transport-only idempotency fields are intentionally excluded;
   * the source run id is included so two source runs with coincidentally equal settings are not
   * interchangeable.
   */
  static LinkedHashMap<String, String> forkFingerprintOf(
      String sourceRunId, CustomerRunSubmissionService.Command command) {
    LinkedHashMap<String, String> values = new LinkedHashMap<>();
    values.put("sourceRunId", sourceRunId);
    values.put("userRequest", command.userRequest());
    values.put("source", command.source());
    values.put("repoUrl", command.repoUrl());
    values.put("repoMode", command.repoMode());
    values.put("skipApproval", String.valueOf(command.skipApproval()));
    values.put("autoMerge", String.valueOf(command.autoMerge()));
    values.put("modelName", command.modelName());
    values.put("aiProvider", command.aiProvider());
    values.put("rememberProviderPreference", String.valueOf(command.rememberProviderPreference()));
    values.put(
        "inferProviderFromModelFirst", String.valueOf(command.inferProviderFromModelFirst()));
    values.put("projectId", command.projectId());
    values.put("executionMode", command.executionMode());
    values.put("selectedRepoIds", canonicalRepoIds(command.selectedRepoIds()));
    values.put("figmaUrl", command.figmaUrl());
    values.put("tddMode", String.valueOf(command.tddMode()));
    values.put("branchOverride", command.branchOverride());
    values.put("checkoutBranch", command.checkoutBranch());
    values.put("existingPrRepoFullName", command.existingPrRepoFullName());
    values.put("existingPrNumber", String.valueOf(command.existingPrNumber()));
    values.put("prReviewComments", command.prReviewComments());
    values.put("userCostCap", String.valueOf(command.userCostCap()));
    values.put("skipAutoPush", String.valueOf(command.skipAutoPush()));
    values.put(
        "requireConfirmationBeforeExpensiveContinuation",
        String.valueOf(command.requireConfirmationBeforeExpensiveContinuation()));
    values.put(
        "requireBenchmarkPriorForAutomaticContinuation",
        String.valueOf(command.requireBenchmarkPriorForAutomaticContinuation()));
    values.put("lowComplexityWorkerStartTier", command.lowComplexityWorkerStartTier());
    values.put("localExecution", String.valueOf(command.localExecution()));
    values.put("patAuthenticated", String.valueOf(command.patAuthenticated()));
    values.put("localInstallationId", String.valueOf(command.localInstallationId()));
    values.put("localRepositoryInventory", command.localRepositoryInventory());
    return values;
  }

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
      @RequestParam(required = false, defaultValue = "EXISTING") String repoMode) {
    PipelineRun run = guard.requireRunWriteAccess(runId, userDetails);
    rejectRepositoryDestinationOverride(repoUrl);
    // RR-1671 — validate the mode at the boundary, and default the deprecated compatibility route
    // to EXISTING rather than the destructive create-a-repository NEW mode.
    String parsedMode = parseRepoMode(repoMode);
    log.info("POST /api/runs/{}/push-github — repoMode={}", runId, parsedMode);
    if (!List.of("COMPLETE", "PARTIAL").contains(run.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Only COMPLETE or PARTIAL runs can be pushed to GitHub (current: "
              + run.getStatus()
              + ")");
    }
    run.setRepoMode(parsedMode);
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
    // RR-1555 — `branch` reaches `git fetch` / `git reset --hard` as an argv token. Git parses
    // options anywhere before `--`, so a value beginning with `-` would be consumed as a git
    // option rather than a refspec. Reject the shape here; the service validates again.
    if (branch != null
        && !branch.isBlank()
        && !com.navneet.executespec.run.sync.RunSyncService.isValidBranch(branch)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "branch must be a plain branch name (letters, digits, '.', '_', '-', '/')");
    }
    var outcome = runSyncService.sync(runId, branch, syncCredentialFor(run, userDetails));
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
    var outcome = runSyncService.syncToOriginalCommit(runId, syncCredentialFor(run, userDetails));
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

  /**
   * RR-1554 — resolve the git credential the sync children must authenticate with. Mirrors the
   * run-then-user precedence {@code push-scm} already uses, so a sync uses exactly the credential
   * that cloned the workspace. Returns {@link
   * com.navneet.executespec.run.sync.RunSyncService.GitCredential#NONE} for public repositories,
   * which still work unauthenticated.
   */
  private com.navneet.executespec.run.sync.RunSyncService.GitCredential syncCredentialFor(
      PipelineRun run, AppUserDetails userDetails) {
    var user = userDetails == null ? null : userDetails.getUser();
    String githubPat = firstNonBlank(run.getGithubPat(), user == null ? null : user.getGithubPat());
    if (githubPat != null) {
      return com.navneet.executespec.run.sync.RunSyncService.GitCredential.github(githubPat);
    }
    String bitbucket =
        firstNonBlank(
            run.getBitbucketAppPassword(), user == null ? null : user.getBitbucketAppPassword());
    if (bitbucket != null) {
      return com.navneet.executespec.run.sync.RunSyncService.GitCredential.bitbucket(bitbucket);
    }
    return com.navneet.executespec.run.sync.RunSyncService.GitCredential.NONE;
  }

  private static String firstNonBlank(String a, String b) {
    if (a != null && !a.isBlank()) return a;
    return b != null && !b.isBlank() ? b : null;
  }

  @PostMapping("/{runId}/push-scm")
  public ResponseEntity<ScmPushResponse> pushToScm(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @PathVariable String runId,
      @RequestParam(required = false) String repoUrl,
      @RequestParam(required = false, defaultValue = "EXISTING") String repoMode) {
    PipelineRun run = guard.requireRunWriteAccess(runId, userDetails);
    rejectRepositoryDestinationOverride(repoUrl);
    // RR-1671 — validate the mode at the boundary; an unknown value is a 400, not a late failure.
    String parsedMode = parseRepoMode(repoMode);
    log.info("POST /api/runs/{}/push-scm — repoMode={}", runId, parsedMode);
    if (!List.of("COMPLETE", "PARTIAL").contains(run.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Only COMPLETE or PARTIAL runs can be pushed to SCM (current: " + run.getStatus() + ")");
    }
    run.setRepoMode(parsedMode);

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
    ScmPushService.PushResult pushResult = scmPushService.push(run);

    // Reload to pick up PR URL set during push
    PipelineRun updated = stateStore.findRun(runId).orElse(run);
    String prUrl =
        updated.getScmPrUrl() != null
            ? updated.getScmPrUrl()
            : (updated.getGithubPrUrl() != null ? updated.getGithubPrUrl() : "");
    String provider = updated.getScmProvider() != null ? updated.getScmProvider() : "";

    String resultPrUrl = pushResult.prUrl().isBlank() ? prUrl : pushResult.prUrl();
    return ResponseEntity.ok(
        new ScmPushResponse(
            pushResult.status(),
            updated.getRepoUrl() != null ? updated.getRepoUrl() : "",
            resultPrUrl,
            provider.isBlank() ? pushResult.scmProvider() : provider,
            pushResult.message(),
            pushResult.attempted(),
            pushResult.succeeded(),
            pushResult.failed(),
            pushResult.code(),
            pushResult.blockingGate(),
            pushResult.publicationEligible(),
            pushResult.retryable(),
            pushResult.retryGuidance(),
            pushResult.remediation()));
  }

  public record ScmPushResponse(
      String status,
      String repoUrl,
      String prUrl,
      String scmProvider,
      String message,
      int attempted,
      int succeeded,
      int failed,
      String code,
      String blockingGate,
      boolean publicationEligible,
      boolean retryable,
      String retryGuidance,
      String remediation) {}

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
      var project = projectAccessGuard.assertCanWrite(newProjectId, userDetails);
      // RR-237 — write access to the run and independent write access to the host project were
      // both satisfiable across orgs (external ProjectMember). Injecting a home-org run into a
      // foreign host project corrupts that tenant's memory/analytics/evidence ownership. Run
      // ownership is not authority to reassign the run into another org's project; refuse the
      // cross-org move. A supported transfer would need an explicit audited command that
      // re-authorizes every dependent owner atomically.
      assertSameOrgReassignment(project.getOrgId(), run.getOrgId());
    }
    run.setProjectId(newProjectId != null && !newProjectId.isBlank() ? newProjectId : null);
    stateStore.save(run);
    log.info("PATCH /api/runs/{}/project — projectId={}", runId, run.getProjectId());
    return ResponseEntity.ok(mapper.toSummary(run));
  }

  /**
   * RR-237 — refuse a cross-org run→project reassignment. When both orgs are known and differ, the
   * move is a tenant-boundary violation (409). Unknown orgs on either side are permitted (legacy
   * rows), preserving the prior un-assign / same-org behavior.
   */
  static void assertSameOrgReassignment(String projectOrgId, String runOrgId) {
    if (projectOrgId != null && runOrgId != null && !projectOrgId.equals(runOrgId)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Cannot reassign a run into a project owned by a different organization.");
    }
  }

  /** Cancel a run. */
  @DeleteMapping("/{runId}")
  public ResponseEntity<Void> cancel(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    log.info("DELETE /api/runs/{}", runId);
    boolean cancelled = runLifecycleService.cancel(runId, userDetails);
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
      boolean completedReplay =
          approval.replayed() && "COMPLETED".equals(approval.approvalStatus());
      String responseStatus =
          completedReplay ? "already_completed" : approval.replayed() ? "already_queued" : "queued";
      return ResponseEntity.accepted()
          .body(
              Map.of(
                  "status",
                  responseStatus,
                  "runId",
                  runId,
                  "taskId",
                  taskId,
                  "approvalId",
                  approval.approvalId(),
                  "provider",
                  approval.provider(),
                  "model",
                  approval.model(),
                  "postReview",
                  approval.postReview(),
                  "replayed",
                  approval.replayed(),
                  "message",
                  completedReplay
                      ? "This exact continuation approval already completed."
                      : approval.replayed()
                          ? "This exact continuation approval was already queued."
                          : "Approved continuation queued — watch the run feed for progress."));
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
    return ResponseEntity.ok(broadcaster.getReplayLog(runId).events());
  }

  /**
   * Metadata-bearing replay contract used by reconnecting clients; legacy event-log stays a list.
   */
  @GetMapping("/{runId}/event-log/replay")
  public ResponseEntity<com.navneet.executespec.sse.SseBroadcaster.ReplayLog> getEventReplay(
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
      // "repo" format — clean archive with .gitignore and README. RR-165: streamed from a
      // quota-bounded temp file instead of a heap-materialized byte[].
      if ("repo".equals(format)) {
        Path repoZipPath = workspaceExportService.exportAsRepoZip(id);
        String runId8 = id.substring(0, Math.min(8, id.length()));
        Resource repoResource = new FileSystemResource(repoZipPath);
        repoZipPath.toFile().deleteOnExit();
        return ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + runId8 + "-repo.zip\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(Files.size(repoZipPath))
            .body(repoResource);
      }

      // Local workspaces are zipped and scanned on the fly. Archived workspaces are downloaded
      // through the same service and every ZIP member is scanned before bytes are returned. A
      // direct GCS redirect would bypass that final publication gate for historical archives.
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
    } catch (WorkspaceExportService.ExportTooLargeException e) {
      // RR-165: an over-quota export is refused explicitly rather than degrading to an OOM.
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
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

  static void validateLocalRepositoryInventory(String inventory) {
    CustomerRunSubmissionService.validateLocalRepositoryInventory(inventory);
  }

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
