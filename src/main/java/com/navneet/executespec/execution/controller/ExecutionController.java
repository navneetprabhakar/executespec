package com.navneet.executespec.execution.controller;

import java.time.Instant;
import java.util.Map;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
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
import com.navneet.executespec.sse.SseBroadcaster;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
  private final ProjectRepository projectRepo;
  private final ProjectMemberRepository projectMemberRepo;
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
    Project project = requireProjectAccess(projectId, user);
    var activeRuns = execRunRepo.findActiveByProjectId(projectId);
    if (!activeRuns.isEmpty()) {
      ExecutionRun active = activeRuns.get(0);
      String reason;
      String status = active.getStatus() == null ? "" : active.getStatus().toUpperCase();
      switch (status) {
        case "PAUSED" ->
            reason =
                "Previous execution is paused awaiting a user decision on a failed solution. "
                    + "Resolve it (retry / skip / fix manually) or abort it to start a new one.";
        case "AWAITING_APPROVAL", "ARCH_REVIEW" ->
            reason =
                "Previous execution is waiting for your approval ("
                    + status
                    + "). "
                    + "Approve it or abort it to start a new one.";
        case "EXECUTING", "PLANNING", "ANALYSING", "SETUP" ->
            reason =
                "Previous execution is still running (status="
                    + status
                    + "). "
                    + "Wait for it to finish or abort it to start a new one.";
        default ->
            reason =
                "Previous execution has not finished or been cancelled (status="
                    + status
                    + "). "
                    + "Finish it or abort it to start a new one.";
      }
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, reason + " [execution id: " + active.getId() + "]");
    }
    ExecutionRun run = orchestrator.createExecution(project, user, req);
    return ResponseEntity.status(HttpStatus.CREATED).body(ExecutionRunDto.from(run, null));
  }

  @GetMapping("/api/executions/{id}")
  @org.springframework.transaction.annotation.Transactional(readOnly = true)
  public ResponseEntity<ExecutionRunDto> getExecution(
      @PathVariable String id, @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    ExecutionRun run = requireRunAccess(id, user);
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
    requireProjectAccess(projectId, user);
    Page<ExecutionRun> runs =
        execRunRepo.findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            projectId, PageRequest.of(page, size));
    return ResponseEntity.ok(runs.map(r -> ExecutionRunDto.from(r, null)));
  }

  @PostMapping("/api/executions/{id}/analyse")
  public ResponseEntity<Void> triggerAnalysis(
      @PathVariable String id, @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    requireRunAccess(id, user);
    orchestrator.triggerAnalysis(id);
    return ResponseEntity.accepted().build();
  }

  @PutMapping("/api/executions/{id}/arch-review")
  public ResponseEntity<Void> approveArch(
      @PathVariable String id,
      @RequestBody ExecutionArchApprovalRequest req,
      @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    ExecutionRun run = requireRunAccess(id, user);
    orchestrator.approveArchDoc(run, req.approved(), req.annotations());
    return ResponseEntity.ok().build();
  }

  @PostMapping("/api/executions/{id}/plan")
  public ResponseEntity<Void> generatePlan(
      @PathVariable String id, @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    requireRunAccess(id, user);
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
    ExecutionRun run = requireRunAccess(id, user);
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
    ExecutionRun run = requireRunAccess(id, user);
    ExecutionSolution sol =
        solutionRepo
            .findById(solutionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "solution"));
    if (!sol.getExecutionRun().getId().equals(run.getId())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "solution does not belong to run");
    }
    solutionExecService.processDecision(sol, req.action());
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/api/executions/{id}/solutions/{solutionId}/manual-ready")
  public ResponseEntity<Void> manualReady(
      @PathVariable String id,
      @PathVariable String solutionId,
      @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    requireRunAccess(id, user);
    solutionExecService.signalManualFixDone(solutionId);
    return ResponseEntity.accepted().build();
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
    ExecutionRun run = requireRunAccess(id, user);
    orchestrator.executeApprovedPlan(run.getId());
    return ResponseEntity.ok().build();
  }

  @PostMapping("/api/executions/{id}/abort")
  public ResponseEntity<Void> abort(
      @PathVariable String id, @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    ExecutionRun run = requireRunAccess(id, user);
    orchestrator.abortExecution(run);
    return ResponseEntity.ok().build();
  }

  @GetMapping("/api/executions/{id}/solutions/{solutionId}/runs")
  public ResponseEntity<java.util.List<ExecutionSolutionRun>> solutionRuns(
      @PathVariable String id,
      @PathVariable String solutionId,
      @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    ExecutionRun run = requireRunAccess(id, user);
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
    ExecutionRun run = requireRunAccess(id, user);
    return ResponseEntity.ok(orchestrator.buildReport(run));
  }

  @GetMapping("/api/projects/{projectId}/prs/{prNumber}/reviews")
  public ResponseEntity<Map<String, String>> prReviewComments(
      @PathVariable String projectId,
      @PathVariable int prNumber,
      @RequestParam String repo,
      @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    requireProjectAccess(projectId, user);
    String pat = appUserRepo.findById(user.getId()).map(u -> u.getGithubPat()).orElse(null);
    String comments = gitHubRepoService.fetchPrReviewComments(repo, prNumber, pat);
    return ResponseEntity.ok(Map.of("comments", comments));
  }

  @GetMapping("/api/executions/{id}/sse-token")
  public ResponseEntity<Map<String, String>> sseToken(
      @PathVariable String id, @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    requireRunAccess(id, user);
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
    requireRunAccess(id, principal.getUser());
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

  private Project requireProjectAccess(String projectId, AppUser user) {
    Project p =
        projectRepo
            .findById(projectId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "project"));
    // RL-2026-05-056 — honour V175 cross-org ProjectMember access (consistent with
    // ProjectAccessGuard.assertCanRead). An accepted cross-org invitee reaches the project via a
    // ProjectMember row, not org match; the old org-only check 403'd them on the Execution View.
    boolean orgMatch = java.util.Objects.equals(p.getOrgId(), user.getOrgId());
    boolean isMember = projectMemberRepo.existsByProjectIdAndUserId(projectId, user.getId());
    if (!orgMatch && !isMember) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "project not in caller org");
    }
    return p;
  }

  private ExecutionRun requireRunAccess(String runId, AppUser user) {
    ExecutionRun run =
        execRunRepo
            .findById(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "execution run"));
    if (run.getDeletedAt() != null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "execution run");
    }
    if (!java.util.Objects.equals(run.getOrgId(), user.getOrgId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in caller org");
    }
    if (run.getUpdatedAt() == null) run.setUpdatedAt(Instant.now());
    return run;
  }
}
