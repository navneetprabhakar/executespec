package com.navneet.executespec.execution.controller;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.OrgRepository;
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
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRepository;
import com.navneet.executespec.execution.service.ExecutionOrchestrator;
import com.navneet.executespec.execution.service.ExecutionPlannerService;
import com.navneet.executespec.execution.service.SolutionExecutionService;
import com.navneet.executespec.sse.SseBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Map;

/**
 * REST entry points for the Execution View (v2.3.0). All routes gated by
 * the {@code execution.view.enabled} feature flag + the user's tier
 * ({@link TierConfig#isExecutionViewEnabled()}). Request access is org-scoped
 * against the containing project.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class ExecutionController {

    private static final String FEATURE_FLAG_KEY = "execution.view.enabled";
    /** Scoped-SSE JWT claim namespace. Pattern: "exec:{executionRunId}" — distinct
     * from per-run SSE tokens so the same scoped-token infra can host both. */
    private static final String SSE_RESOURCE_PREFIX = "exec:";

    private final FeatureFlagService featureFlags;
    private final ExecutionRunRepository execRunRepo;
    private final ExecutionSolutionRepository solutionRepo;
    private final ProjectRepository projectRepo;
    private final OrgRepository orgRepo;
    private final TierConfigRepository tierConfigRepo;
    private final ExecutionOrchestrator orchestrator;
    private final ExecutionPlannerService plannerService;
    private final SolutionExecutionService solutionExecService;
    private final SseBroadcaster broadcaster;
    private final JwtUtil jwtUtil;

    // ── Create / list / get ────────────────────────────────────────────────

    @PostMapping("/api/projects/{projectId}/executions")
    public ResponseEntity<ExecutionRunDto> createExecution(
            @PathVariable String projectId,
            @RequestBody CreateExecutionRequest req,
            @AuthenticationPrincipal AppUserDetails principal) {
        AppUser user = requireFeatureEnabled(principal);
        Project project = requireProjectAccess(projectId, user);
        ExecutionRun run = orchestrator.createExecution(project, user, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ExecutionRunDto.from(run, null));
    }

    @GetMapping("/api/executions/{id}")
    public ResponseEntity<ExecutionRunDto> getExecution(
            @PathVariable String id, @AuthenticationPrincipal AppUserDetails principal) {
        AppUser user = requireFeatureEnabled(principal);
        ExecutionRun run = requireRunAccess(id, user);
        var solutions = solutionRepo.findByExecutionRun_IdOrderBySequenceNumberAsc(id);
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
        Page<ExecutionRun> runs = execRunRepo.findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                projectId, PageRequest.of(page, size));
        return ResponseEntity.ok(runs.map(r -> ExecutionRunDto.from(r, null)));
    }

    // ── Phase triggers ─────────────────────────────────────────────────────

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
        plannerService.generatePlan(id);
        return ResponseEntity.accepted().build();
    }

    @PutMapping("/api/executions/{id}/plan/approve")
    public ResponseEntity<Void> approvePlan(
            @PathVariable String id, @AuthenticationPrincipal AppUserDetails principal) {
        AppUser user = requireFeatureEnabled(principal);
        requireRunAccess(id, user);
        orchestrator.approvePlanAndExecute(id);
        return ResponseEntity.accepted().build();
    }

    // ── Execution control ──────────────────────────────────────────────────

    @PostMapping("/api/executions/{id}/solutions/{solutionId}/decide")
    public ResponseEntity<Void> decide(
            @PathVariable String id,
            @PathVariable String solutionId,
            @RequestBody DecisionRequest req,
            @AuthenticationPrincipal AppUserDetails principal) {
        AppUser user = requireFeatureEnabled(principal);
        ExecutionRun run = requireRunAccess(id, user);
        ExecutionSolution sol = solutionRepo.findById(solutionId)
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

    @PostMapping("/api/executions/{id}/abort")
    public ResponseEntity<Void> abort(
            @PathVariable String id, @AuthenticationPrincipal AppUserDetails principal) {
        AppUser user = requireFeatureEnabled(principal);
        ExecutionRun run = requireRunAccess(id, user);
        orchestrator.abortExecution(run);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/api/executions/{id}/report")
    public ResponseEntity<Map<String, Object>> getReport(
            @PathVariable String id, @AuthenticationPrincipal AppUserDetails principal) {
        AppUser user = requireFeatureEnabled(principal);
        ExecutionRun run = requireRunAccess(id, user);
        return ResponseEntity.ok(orchestrator.buildReport(run));
    }

    // ── SSE ────────────────────────────────────────────────────────────────

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

    // ── Access + feature gating ────────────────────────────────────────────

    private AppUser requireFeatureEnabled(AppUserDetails principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!featureFlags.isEnabled(FEATURE_FLAG_KEY)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        AppUser user = principal.getUser();
        String orgId = user.getOrgId();
        String tier = orgId != null
                ? orgRepo.findById(orgId).map(Org::getTier).orElse(user.getTier())
                : user.getTier();
        if (tier == null) tier = "FREE";
        boolean allowed = tierConfigRepo.findById(tier)
                .map(TierConfig::isExecutionViewEnabled)
                .orElse(false);
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Execution View requires an eligible tier");
        }
        return user;
    }

    private Project requireProjectAccess(String projectId, AppUser user) {
        Project p = projectRepo.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "project"));
        if (!java.util.Objects.equals(p.getOrgId(), user.getOrgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "project not in caller org");
        }
        return p;
    }

    private ExecutionRun requireRunAccess(String runId, AppUser user) {
        ExecutionRun run = execRunRepo.findById(runId)
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
