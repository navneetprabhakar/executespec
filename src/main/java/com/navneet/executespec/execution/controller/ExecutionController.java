package com.navneet.executespec.execution.controller;

import java.time.Instant;
import java.util.Map;

import com.navneet.executespec.api.PageBounds;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.JwtUtil;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.execution.dto.CreateExecutionRequest;
import com.navneet.executespec.execution.dto.DecisionRequest;
import com.navneet.executespec.execution.dto.ExecutionArchApprovalRequest;
import com.navneet.executespec.execution.dto.ExecutionRunDto;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.execution.entity.ExecutionSolutionRun;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRunRepository;
import com.navneet.executespec.execution.service.ExecutionOrchestrator;
import com.navneet.executespec.execution.service.ExecutionPlannerService;
import com.navneet.executespec.execution.service.SolutionExecutionService;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.sse.SseBroadcaster;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST entry points for the Execution View (v2.3.0). All routes gated by the {@code
 * execution.view.enabled} feature flag + the user's tier ({@link
 * TierConfig#isExecutionViewEnabled()}). Request access is org-scoped against the containing
 * project.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class ExecutionController {

  private static final String FEATURE_FLAG_KEY = "execution.view.enabled";

  /**
   * Scoped-SSE JWT claim namespace. Pattern: "exec:{executionRunId}" — distinct from per-run SSE
   * tokens so the same scoped-token infra can host both.
   */
  private static final String SSE_RESOURCE_PREFIX = "exec:";

  private final FeatureFlagService featureFlags;
  private final ExecutionRunRepository execRunRepo;
  private final ExecutionSolutionRepository solutionRepo;
  private final ExecutionSolutionRunRepository solutionRunRepo;
  private final ProjectAccessGuard projectAccessGuard;
  private final ProjectRepoService projectRepoService;
  private final OrgRepository orgRepo;
  private final TierConfigRepository tierConfigRepo;
  private final ExecutionOrchestrator orchestrator;
  private final ExecutionPlannerService plannerService;
  private final SolutionExecutionService solutionExecService;
  private final SseBroadcaster broadcaster;
  private final JwtUtil jwtUtil;
  private final com.navneet.executespec.repo.GitHubRepoService gitHubRepoService;
  private final com.navneet.executespec.auth.repository.AppUserRepository appUserRepo;

  // Create / list / get

  @PostMapping("/api/projects/{projectId}/executions")
  public ResponseEntity<ExecutionRunDto> createExecution(
      @PathVariable String projectId,
      @RequestBody CreateExecutionRequest req,
      @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    Project project = projectAccessGuard.assertCanWrite(projectId, principal);
    // RR-527 — the active-run exclusion + create is now one atomic, advisory-lock-serialized
    // transaction inside the orchestrator, so two concurrent creates for the same project cannot
    // both pass the check. A blocked admission is rejected with a 409 naming the active run.
    ExecutionRun run = orchestrator.admitAndCreateExecution(project, user, req);
    return ResponseEntity.status(HttpStatus.CREATED).body(ExecutionRunDto.from(run, null));
  }

  @GetMapping("/api/executions/{id}")
  @org.springframework.transaction.annotation.Transactional(readOnly = true)
  public ResponseEntity<ExecutionRunDto> getExecution(
      @PathVariable String id, @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    ExecutionRun run = requireRunAccess(id, principal);
    var solutions = solutionRepo.findByExecutionRun_IdOrderBySequenceNumberAsc(id);
    // Force-init the lazy dependencies collection before the session closes
    // so SolutionDto.from can walk it without a LazyInitializationException.
    solutions.forEach(
        s -> {
          if (s.getDependencies() != null) s.getDependencies().size();
        });
    return ResponseEntity.ok(ExecutionRunDto.from(run, solutions));
  }

  @GetMapping("/api/projects/{projectId}/executions")
  public ResponseEntity<Page<ExecutionRunDto>> listExecutions(
      @PathVariable String projectId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    projectAccessGuard.assertCanRead(projectId, principal);
    Page<ExecutionRun> runs =
        execRunRepo.findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            projectId, PageBounds.request(page, size, 100));
    return ResponseEntity.ok(runs.map(r -> ExecutionRunDto.from(r, null)));
  }

  @PostMapping("/api/executions/{id}/analyse")
  public ResponseEntity<Void> triggerAnalysis(
      @PathVariable String id, @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    requireRunWriteAccess(id, principal);
    orchestrator.triggerAnalysis(id);
    return ResponseEntity.accepted().build();
  }

  @PutMapping("/api/executions/{id}/arch-review")
  public ResponseEntity<Void> approveArch(
      @PathVariable String id,
      @RequestBody ExecutionArchApprovalRequest req,
      @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    ExecutionRun run = requireRunWriteAccess(id, principal);
    orchestrator.approveArchDoc(run, req.approved(), req.annotations());
    return ResponseEntity.ok().build();
  }

  @PostMapping("/api/executions/{id}/plan")
  public ResponseEntity<Void> generatePlan(
      @PathVariable String id, @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    requireRunWriteAccess(id, principal);
    // V188 (2026-05-13) — route through ExecutionOrchestrator.kickoffPlanning so the unified
    // V2-dispatch path can pre-empt the legacy LLM-split planner when the
    // execution.unified-dispatch.enabled flag is on (the default after V188). The orchestrator
    // owns the dispatch decision so it stays observable in one place during the deprecation
    // window — callers don't fork on the flag.
    orchestrator.kickoffPlanning(id);
    return ResponseEntity.accepted().build();
  }

  @PutMapping("/api/executions/{id}/plan/approve")
  public ResponseEntity<Void> approvePlan(
      @PathVariable String id, @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    ExecutionRun run = requireRunWriteAccess(id, principal);
    // v2.5.12 — reject plan-approve before the plan is generated. Observed
    // live on 2026-04-17: first approve 20 s after /plan returned 202 but
    // did NOT set planApprovedAt (concurrent planner save clobbered it).
    // Second approve 2 min later succeeded. Fix: reject the early approve
    // with 409 so the client knows to poll until the plan is ready.
    if (run.getExecutionPlan() == null || run.getExecutionPlan().isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Plan is not ready yet. Wait for the planner to finish "
              + "(poll GET /api/executions/"
              + id
              + " until executionPlan is present).");
    }
    orchestrator.approvePlanAndExecute(id);
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/api/executions/{id}/solutions/{solutionId}/decide")
  public ResponseEntity<Void> decide(
      @PathVariable String id,
      @PathVariable String solutionId,
      @RequestBody DecisionRequest req,
      @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    ExecutionRun run = requireRunWriteAccess(id, principal);
    ExecutionSolution sol =
        solutionRepo
            .findById(solutionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "solution"));
    if (!sol.getExecutionRun().getId().equals(run.getId())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "solution does not belong to run");
    }
    // RR-530 — the decision is a conditional transition out of one exact state. Report what
    // actually happened instead of accepting a replayed / illegal / unknown action as applied.
    var outcome = solutionExecService.processDecision(sol, req.action(), user.getId());
    return switch (outcome) {
      case APPLIED -> ResponseEntity.accepted().build();
      case UNKNOWN_ACTION ->
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "unsupported decision action: " + req.action());
      case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "solution");
      case ILLEGAL_STATE ->
          throw new ResponseStatusException(
              HttpStatus.CONFLICT,
              "solution is not awaiting a decision (it may have already been decided, retried or"
                  + " completed) — reload the launch and try again");
    };
  }

  @PostMapping("/api/executions/{id}/solutions/{solutionId}/manual-ready")
  public ResponseEntity<Void> manualReady(
      @PathVariable String id,
      @PathVariable String solutionId,
      @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    requireRunWriteAccess(id, principal);
    // RR-530 — manual-ready is legal only after a FIX decision put this solution into
    // AWAITING_MANUAL_FIX. Anything else is a replay or a stale client, not a completion.
    var outcome = solutionExecService.signalManualFixDone(id, solutionId, user.getId());
    return switch (outcome) {
      case APPLIED -> ResponseEntity.accepted().build();
      case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "solution");
      case ILLEGAL_STATE, UNKNOWN_ACTION ->
          throw new ResponseStatusException(
              HttpStatus.CONFLICT,
              "solution is not awaiting a manual fix — choose FIX on the paused solution first");
    };
  }

  /**
   * Re-kick execution from the Plan step without requiring the user to redo Setup / Analysis / Arch
   * review. Used when a run hangs in EXECUTING or AWAITING_ARCH_REVIEW because of a transient
   * server error.
   */
  @PostMapping("/api/executions/{id}/resume")
  public ResponseEntity<Void> resume(
      @PathVariable String id, @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    ExecutionRun run = requireRunWriteAccess(id, principal);
    // RR-351 — resume is a RECOVERY action for a launch that already cleared its plan gate, not a
    // second door into execution. Previously any writable launch, including one still in SETUP /
    // ANALYSING / PLANNING or one whose plan the user had rejected, could call this and go straight
    // into solution execution (and therefore provider spend and PR publication) without ever
    // proving plan approval. The durable planApprovedAt stamp is that proof; the service enforces
    // it again, and the check is repeated here so the caller gets an honest 409 rather than a 200
    // for work that will not happen.
    if (run.getPlanApprovedAt() == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "This launch has no approved plan. Approve the plan "
              + "(PUT /api/executions/"
              + id
              + "/plan/approve) before resuming.");
    }
    if (EXECUTION_TERMINAL.contains(run.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Launch is already terminal at " + run.getStatus() + " and cannot be resumed.");
    }
    log.info(
        "POST /api/executions/{}/resume — recovery requested by user {} (status={})",
        id,
        user.getId(),
        run.getStatus());
    orchestrator.executeApprovedPlan(run.getId());
    return ResponseEntity.ok().build();
  }

  /** RR-351 — launch states from which no recovery action is legal. */
  // RR-345 — PARTIAL is terminal; a PARTIAL launch cannot be resumed.
  private static final java.util.Set<String> EXECUTION_TERMINAL =
      java.util.Set.of("COMPLETE", "PARTIAL", "FAILED", "CANCELLED", "ABORTED");

  @PostMapping("/api/executions/{id}/abort")
  public ResponseEntity<Void> abort(
      @PathVariable String id, @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    ExecutionRun run = requireRunWriteAccess(id, principal);
    orchestrator.abortExecution(run);
    return ResponseEntity.ok().build();
  }

  @GetMapping("/api/executions/{id}/solutions/{solutionId}/runs")
  public ResponseEntity<java.util.List<ExecutionSolutionRun>> solutionRuns(
      @PathVariable String id,
      @PathVariable String solutionId,
      @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    ExecutionRun run = requireRunAccess(id, principal);
    ExecutionSolution sol =
        solutionRepo
            .findById(solutionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "solution"));
    if (!sol.getExecutionRun().getId().equals(run.getId())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "solution does not belong to run");
    }
    return ResponseEntity.ok(solutionRunRepo.findBySolutionIdOrderByAttemptNumberAsc(solutionId));
  }

  @GetMapping("/api/executions/{id}/report")
  public ResponseEntity<Map<String, Object>> getReport(
      @PathVariable String id, @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    ExecutionRun run = requireRunAccess(id, principal);
    return ResponseEntity.ok(orchestrator.buildReport(run));
  }

  @GetMapping("/api/projects/{projectId}/prs/{prNumber}/reviews")
  public ResponseEntity<Map<String, String>> prReviewComments(
      @PathVariable String projectId,
      @PathVariable int prNumber,
      @RequestParam java.util.UUID repoId,
      @RequestParam String repo,
      @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    projectAccessGuard.assertCanRead(projectId, principal);
    var linkedRepo = projectRepoService.resolveGitHubReviewRepoStrict(projectId, repoId, repo);
    String pat = appUserRepo.findById(user.getId()).map(u -> u.getGithubPat()).orElse(null);
    String comments =
        gitHubRepoService.fetchPrReviewComments(linkedRepo.getGithubRepoFullName(), prNumber, pat);
    return ResponseEntity.ok(Map.of("comments", comments));
  }

  @GetMapping("/api/executions/{id}/sse-token")
  public ResponseEntity<Map<String, String>> sseToken(
      @PathVariable String id, @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    requireRunAccess(id, principal);
    String token = jwtUtil.generateSseToken(user.getId(), SSE_RESOURCE_PREFIX + id);
    return ResponseEntity.ok(Map.of("token", token));
  }

  @GetMapping(value = "/api/executions/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter subscribe(
      @PathVariable String id,
      @RequestParam(required = false) String token,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
      @AuthenticationPrincipal AppUserDetails principal) {
    String channel = SSE_RESOURCE_PREFIX + id;
    if (token != null && !token.isBlank()) {
      String userId = jwtUtil.validateSseToken(token, channel);
      if (userId != null) return broadcaster.subscribe(channel, lastEventId);
    }
    if (principal == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Valid SSE token or JWT required");
    }
    requireRunAccess(id, principal);
    return broadcaster.subscribe(channel, lastEventId);
  }

  private AppUser requireFeatureEnabled(AppUserDetails principal) {
    if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if (!featureFlags.isEnabled(FEATURE_FLAG_KEY)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    AppUser user = principal.getUser();
    String orgId = user.getOrgId();
    String tier =
        orgId != null
            ? orgRepo.findById(orgId).map(Org::getTier).orElse(user.getTier())
            : user.getTier();
    if (tier == null) tier = "FREE";
    boolean allowed =
        tierConfigRepo.findById(tier).map(TierConfig::isExecutionViewEnabled).orElse(false);
    if (!allowed) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Execution View requires an eligible tier");
    }
    return user;
  }

  private ExecutionRun requireRunAccess(String runId, AppUserDetails principal) {
    return requireRunAccess(runId, principal, false);
  }

  private ExecutionRun requireRunWriteAccess(String runId, AppUserDetails principal) {
    return requireRunAccess(runId, principal, true);
  }

  private ExecutionRun requireRunAccess(
      String runId, AppUserDetails principal, boolean writeRequired) {
    ExecutionRun run =
        execRunRepo
            .findById(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "execution run"));
    if (run.getDeletedAt() != null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "execution run");
    }
    if (writeRequired) projectAccessGuard.assertCanWrite(run.getProjectId(), principal);
    else projectAccessGuard.assertCanRead(run.getProjectId(), principal);
    if (run.getUpdatedAt() == null) run.setUpdatedAt(Instant.now());
    return run;
  }
}
