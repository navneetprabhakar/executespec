package com.navneet.executespec.api.controller;

import com.navneet.executespec.api.RunMapper;
import com.navneet.executespec.api.dto.ApprovalRequest;
import com.navneet.executespec.api.dto.RunDetail;
import com.navneet.executespec.api.dto.RunRequest;
import com.navneet.executespec.api.dto.RunSummary;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.auth.security.RunVisibilitySpec;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.billing.service.UsageTrackingService;
import com.navneet.executespec.deployment.GcsWorkspaceStore;
import com.navneet.executespec.deployment.WorkspaceExportService;
import com.navneet.executespec.deployment.WorkspaceFileService;
import com.navneet.executespec.integration.scm.ScmPushService;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentMessage;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.entity.RunRecovery;
import com.navneet.executespec.state.repository.AgentMessageRepository;
import com.navneet.executespec.state.repository.RunRecoveryRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
@Slf4j
public class RunController {

    private final com.navneet.executespec.v2.integration.V2RunOrchestrator v2Orchestrator;
    private final ScmPushService scmPushService;
    private final StateStore stateStore;
    private final RunMapper mapper;
    private final RunRecoveryRepository recoveryRepository;
    private final WorkspaceExportService workspaceExportService;
    private final WorkspaceFileService workspaceFileService;
    private final GcsWorkspaceStore gcsWorkspaceStore;
    private final AgentMessageRepository agentMessageRepository;
    private final TierEnforcementService tierEnforcementService;
    private final UsageTrackingService usageTrackingService;
    private final RunVisibilitySpec runVisibilitySpec;
    private final RunAccessGuard guard;
    private final com.navneet.executespec.agent.ApiKeyResolver apiKeyResolver;
    private final com.navneet.executespec.state.repository.PlanVersionRepository planVersionRepo;
    private final com.navneet.executespec.auth.service.ActivityAuditService activityAuditService;
    private final com.navneet.executespec.sse.SseBroadcaster broadcaster;
    private final com.navneet.executespec.agent.CostEstimator costEstimator;
    private final com.navneet.executespec.v2.cost.V2RunCostTracker v2CostTracker;
    private final com.navneet.executespec.harness.GateStore gateStore;
    private final com.navneet.executespec.config.featureflag.FeatureFlagService featureFlagService;
    private final com.navneet.executespec.security.InputSanitizer inputSanitizer;

    /** Submit a new run. Returns immediately with the run summary (status may be PLANNING or QUEUED). */
    @PostMapping
    public ResponseEntity<RunSummary> submit(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @Valid @RequestBody RunRequest req) {
        guard.requireAuthenticated(userDetails);
        log.info("POST /api/runs — goal: {}", req.userRequest());

        // Content-level security scan (prompt injection, shell embedding, abuse patterns)
        inputSanitizer.scan(req.userRequest());

        // Check if submissions are paused (maintenance mode)
        if (featureFlagService.isEnabled("submissions.paused")) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Run submissions are temporarily paused for maintenance. Please try again later.");
        }

        // Check quota availability (no increment — credits are charged at plan approval,
        // after cost estimation determines how many credits this run requires)
        tierEnforcementService.assertCanSubmitRun(userDetails.getUser().getId());
        // Enforce billing usage limits (throws 402 if limit exceeded)
        usageTrackingService.assertWithinLimits(userDetails.getUser().getId(), "RUNS");
        // Pre-flight: in OWN_KEYS mode, ensure at least one enabled provider has a key
        apiKeyResolver.validateKeyAvailability(userDetails.getUser().getId());

        // Extract per-user GitHub credentials to pass into the run
        String githubPat   = userDetails.getUser().getGithubPat();
        String githubOwner = userDetails.getUser().getGithubOwner();

        // Pass ALL pre-async fields INTO submitRun so they're set on the entity
        // BEFORE the async pipeline thread starts (avoids race condition where the
        // pipeline thread re-loads and re-saves the run without ownership fields).
        String executionMode = req.executionMode() != null ? req.executionMode() : "PRODUCTION";

        // V2 is the only harness; HarnessVersionResolver retired with deprecation P3.
        PipelineRun run = v2Orchestrator.submitRun(
                req.userRequest(),
                req.source() != null ? req.source() : "REST",
                req.repoUrl(),
                req.repoMode(),
                githubPat,
                githubOwner,
                Boolean.TRUE.equals(req.skipApproval()),
                Boolean.TRUE.equals(req.autoMerge()),
                req.modelName(),
                req.aiProvider(),
                userDetails.getUser().getId(),
                userDetails.getUser().getOrgId(),
                req.projectId(),
                executionMode);

        // Run count already incremented atomically in assertAndIncrementRunCount above
        usageTrackingService.increment(userDetails.getUser().getId(), "RUNS", 1);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(mapper.toSummary(run));
    }

    /**
     * List runs, optionally filtered by status, projectId, and scope.
     * scope=mine restricts to the current user's runs (within org visibility).
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

        // scope=mine  → only the current user's runs
        // scope=org   → only runs in the user's org (useful for SUPER_ADMIN to see org-only view)
        // scope=all   → all runs (SUPER_ADMIN only — default for super admin when no scope)
        if ("mine".equals(scope)) {
            Specification<PipelineRun> mineSpec = (root, query, cb) ->
                    cb.equal(root.get("createdByUserId"), userDetails.getUser().getId());
            visSpec = visSpec.and(mineSpec);
        } else if ("org".equals(scope) && userDetails.getUser().getOrgId() != null) {
            String orgId = userDetails.getUser().getOrgId();
            Specification<PipelineRun> orgSpec = (root, query, cb) ->
                    cb.equal(root.get("orgId"), orgId);
            visSpec = visSpec.and(orgSpec);
        }

        Page<PipelineRun> runs = stateStore.listRuns(status, projectId, archived, PageRequest.of(page, size), visSpec);
        List<RunSummary> content = runs.getContent().stream().map(mapper::toSummary).toList();
        return ResponseEntity.ok(Map.of(
                "content", content,
                "page", runs.getNumber(),
                "size", runs.getSize(),
                "totalElements", runs.getTotalElements(),
                "totalPages", runs.getTotalPages(),
                "hasNext", runs.hasNext(),
                "hasPrevious", runs.hasPrevious()
        ));
    }

    /** Get a single run with task details. */
    @GetMapping("/{runId}")
    public ResponseEntity<RunDetail> get(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        PipelineRun run = guard.requireRunAccess(runId, userDetails);
        return ResponseEntity.ok(mapper.toDetail(run, stateStore.getAllTasks(runId)));
    }

    /** Live cost cache: runId → [costUsd, computedAtMillis]. Completed runs cached forever, in-progress cached 15s. */
    private final java.util.concurrent.ConcurrentHashMap<String, double[]> costCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long COST_CACHE_TTL_MS = 30_000;
    private static final java.util.Set<String> TERMINAL_STATUSES = java.util.Set.of("COMPLETE", "FAILED", "CANCELLED", "PARTIAL");

    /** Compute the live cost for a run. Cached: terminal runs forever, in-progress runs for 15s. */
    @GetMapping("/{runId}/cost")
    public ResponseEntity<java.util.Map<String, Object>> getLiveCost(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        var run = guard.requireRunAccess(runId, userDetails);

        // Terminal run with stored cost → return immediately
        if (TERMINAL_STATUSES.contains(run.getStatus()) && run.getActualCostUsd() != null) {
            return ResponseEntity.ok(java.util.Map.of("runId", runId, "costUsd", run.getActualCostUsd()));
        }

        // V2 in-progress: live cost comes from V2RunCostTracker (V1's computeCostForRun
        // reads agent_task token columns which V2 doesn't populate mid-run).
        if ("v2".equals(run.getHarnessVersion())) {
            double liveUsd = v2CostTracker.snapshot(runId).costUsd();
            return ResponseEntity.ok(java.util.Map.of("runId", runId, "costUsd", liveUsd));
        }

        double[] cached = costCache.compute(runId, (k, existing) -> {
            if (existing != null) {
                boolean isTerminal = TERMINAL_STATUSES.contains(run.getStatus());
                if (isTerminal || (System.currentTimeMillis() - existing[1]) < COST_CACHE_TTL_MS) {
                    return existing;
                }
            }
            double cost = costEstimator.computeCostForRun(runId);
            return new double[]{cost, System.currentTimeMillis()};
        });
        return ResponseEntity.ok(java.util.Map.of("runId", runId, "costUsd", cached[0]));
    }

    /** Approve or reject the plan-approval gate. */
    @PostMapping("/{runId}/approve")
    public ResponseEntity<Void> approvePlan(
            @PathVariable String runId,
            @RequestBody ApprovalRequest req,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        PipelineRun approveRun = guard.requireRunAccess(runId, userDetails);
        log.info("POST /api/runs/{}/approve — approved={}, creditsConfirmed={}, executionMode={}, harness={}",
                runId, req.approved(), req.creditsConfirmed(), req.executionMode(),
                approveRun.getHarnessVersion());

        // Validate creditsConfirmed matches the current estimate (prevents stale approvals)
        if (req.approved() && req.creditsConfirmed() != null) {
            stateStore.findRun(runId).ifPresent(run -> {
                int expected;
                if ("BATCH".equalsIgnoreCase(req.executionMode()) && run.getBatchCredits() != null) {
                    expected = run.getBatchCredits();
                } else {
                    expected = run.getCreditsCharged() != null ? run.getCreditsCharged() : 1;
                }
                if (!req.creditsConfirmed().equals(expected)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Credits mismatch: expected " + expected + " but confirmed " +
                            req.creditsConfirmed() + ". Cost estimate may have changed — please review.");
                }
            });
        }

        // Validate and persist execution mode choice
        if (req.approved() && "BATCH".equalsIgnoreCase(req.executionMode())) {
            stateStore.findRun(runId).ifPresent(run -> {
                if (!Boolean.TRUE.equals(run.getBatchAvailable())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Batch execution is not available for this run.");
                }
            });
            stateStore.setApprovedExecutionMode(runId, "BATCH");
        }

        // V2 orchestrator blocks on GateStore.awaitPlanGate() which completes
        // instantly on the in-memory future.
        gateStore.setPlanGate(runId, req.approved());
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
        guard.requireRunAccess(runId, userDetails);
        throw new ResponseStatusException(HttpStatus.GONE,
                "Clarification gate removed in V2 — this endpoint is no longer supported.");
    }

    @PostMapping("/{runId}/merge")
    public ResponseEntity<Void> approveMerge(
            @PathVariable String runId,
            @RequestBody ApprovalRequest req,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        throw new ResponseStatusException(HttpStatus.GONE,
                "V2 auto-merges; explicit merge gate removed.");
    }

    @PostMapping("/{runId}/retry")
    public ResponseEntity<Void> retryRun(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        throw new ResponseStatusException(HttpStatus.GONE,
                "Run retry not yet supported in V2 — submit a fresh run instead.");
    }

    @PostMapping("/{runId}/pause")
    public ResponseEntity<Void> pauseRun(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        throw new ResponseStatusException(HttpStatus.GONE,
                "Pause/resume not yet supported in V2.");
    }

    @PostMapping("/{runId}/edit-and-restart")
    public ResponseEntity<Void> editAndRestart(
            @PathVariable String runId,
            @RequestBody EditAndRestartRequest body,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        throw new ResponseStatusException(HttpStatus.GONE,
                "Edit-and-restart not supported in V2 — submit a fresh run.");
    }

    @PostMapping("/{runId}/resume")
    public ResponseEntity<Void> resumeRun(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        throw new ResponseStatusException(HttpStatus.GONE,
                "Pause/resume not yet supported in V2.");
    }

    /**
     * Push an existing COMPLETE/PARTIAL run's workspace to GitHub.
     * If repoMode=NEW and no repoUrl, creates a new repo first.
     * This avoids re-running the pipeline just to push generated code.
     *
     * @deprecated Use {@code POST /api/runs/{runId}/push-scm} instead, which routes to the
     *             correct SCM provider (GitHub or Bitbucket) based on user credentials.
     */
    @Deprecated
    @PostMapping("/{runId}/push-github")
    public ResponseEntity<Map<String, String>> pushToGitHub(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @PathVariable String runId,
            @RequestParam(required = false) String repoUrl,
            @RequestParam(required = false, defaultValue = "NEW") String repoMode) {
        PipelineRun run = guard.requireRunAccess(runId, userDetails);
        log.info("POST /api/runs/{}/push-github — repoMode={} repoUrl={}", runId, repoMode, repoUrl);
        if (!List.of("COMPLETE", "PARTIAL").contains(run.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only COMPLETE or PARTIAL runs can be pushed to GitHub (current: " + run.getStatus() + ")");
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
        return ResponseEntity.ok(Map.of(
            "status", "pushed",
            "repoUrl", run.getRepoUrl() != null ? run.getRepoUrl() : ""));
    }

    /**
     * Push an existing COMPLETE/PARTIAL run's workspace to the appropriate SCM provider.
     * Routes to GitHub or Bitbucket based on user credentials:
     * - If user has Bitbucket credentials → pushes to Bitbucket, creates PR
     * - Else if user has GitHub PAT → pushes to GitHub, creates PR
     * - If neither → returns 400
     */
    @PostMapping("/{runId}/push-scm")
    public ResponseEntity<Map<String, String>> pushToScm(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @PathVariable String runId,
            @RequestParam(required = false) String repoUrl,
            @RequestParam(required = false, defaultValue = "EXISTING") String repoMode) {
        PipelineRun run = guard.requireRunAccess(runId, userDetails);
        log.info("POST /api/runs/{}/push-scm — repoMode={} repoUrl={}", runId, repoMode, repoUrl);
        if (!List.of("COMPLETE", "PARTIAL").contains(run.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
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
        if ((run.getGithubPat() == null || run.getGithubPat().isBlank()) && user.getGithubPat() != null) {
            run.setGithubPat(user.getGithubPat());
            run.setGithubOwner(user.getGithubOwner());
        }

        stateStore.save(run);
        scmPushService.push(run);

        // Reload to pick up PR URL set during push
        PipelineRun updated = stateStore.findRun(runId).orElse(run);
        String prUrl = updated.getScmPrUrl() != null ? updated.getScmPrUrl()
                : (updated.getGithubPrUrl() != null ? updated.getGithubPrUrl() : "");
        String provider = updated.getScmProvider() != null ? updated.getScmProvider() : "";

        return ResponseEntity.ok(Map.of(
            "status", "pushed",
            "repoUrl", updated.getRepoUrl() != null ? updated.getRepoUrl() : "",
            "prUrl", prUrl,
            "scmProvider", provider));
    }

    /** Update the project association of an existing run. Send null or empty projectId to un-assign. */
    @PatchMapping("/{runId}/project")
    public ResponseEntity<RunSummary> updateRunProject(
            @PathVariable String runId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        PipelineRun run = guard.requireRunAccess(runId, userDetails);
        String newProjectId = body.get("projectId");
        run.setProjectId(newProjectId != null && !newProjectId.isBlank() ? newProjectId : null);
        stateStore.save(run);
        log.info("PATCH /api/runs/{}/project — projectId={}", runId, run.getProjectId());
        return ResponseEntity.ok(mapper.toSummary(run));
    }

    /** Cancel a run. */
    @DeleteMapping("/{runId}")
    public ResponseEntity<Void> cancel(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        log.info("DELETE /api/runs/{}", runId);
        boolean cancelled = v2Orchestrator.cancel(runId);
        return cancelled ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    /**
     * Archive a run. Only terminal runs (COMPLETE, FAILED, CANCELLED, PARTIAL) can be archived.
     * This is irreversible — archived runs are permanently deleted after 90 days.
     */
    @PostMapping("/{runId}/archive")
    public ResponseEntity<Map<String, String>> archiveRun(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
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
     * Force-retry a single PARTIAL or BLOCKED task within a PARTIAL/FAILED run.
     * Resets the task, re-runs the worker, reviewer, and merge gate.
     * Returns 202 Accepted immediately; processing is asynchronous.
     */
    @PostMapping("/{runId}/tasks/{taskId}/retry")
    public ResponseEntity<Void> retryTask(
            @PathVariable String runId,
            @PathVariable String taskId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        log.info("POST /api/runs/{}/tasks/{}/retry", runId, taskId);
        try {
            v2Orchestrator.retryTask(runId, taskId);
            return ResponseEntity.accepted().build();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/{runId}/implement-suggestions")
    public ResponseEntity<Void> implementSuggestions(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        throw new ResponseStatusException(HttpStatus.GONE,
                "Implement-suggestions not yet supported in V2.");
    }

    @PostMapping("/{runId}/rework")
    public ResponseEntity<Void> reworkRun(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        throw new ResponseStatusException(HttpStatus.GONE,
                "Rework not yet supported in V2.");
    }

    @PostMapping("/{runId}/reopen-merge")
    public ResponseEntity<Void> reopenMerge(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        throw new ResponseStatusException(HttpStatus.GONE,
                "V2 auto-merges; merge gate / reopen-merge removed.");
    }

    /**
     * Manually retry background validation (review + test gen + report) for a COMPLETE run
     * whose background validation failed. Returns 409 if validation is already running or
     * max retries exhausted.
     */
    @PostMapping("/{runId}/retry-validation")
    public ResponseEntity<Void> retryValidation(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        log.info("POST /api/runs/{}/retry-validation", runId);
        try {
            v2Orchestrator.retryValidation(runId);
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
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        List<RunRecovery> recoveries = recoveryRepository
            .findByOriginalRunIdOrRestartRunId(runId, runId);
        List<RecoveryResponse> response = recoveries.stream()
            .map(r -> new RecoveryResponse(
                r.getId(), r.getOriginalRunId(), r.getRestartRunId(),
                r.getStuckTaskId(), r.getClassification().name(),
                r.getClassificationDetail(), r.getActionTaken(),
                r.getRecoveredAt(), r.getRestartCreatedAt()
            ))
            .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * Return recent agent-message log for a run (up to 200 entries), sorted oldest-first.
     */
    @GetMapping("/{runId}/messages")
    public ResponseEntity<List<MessageResponse>> getMessages(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        List<AgentMessage> all = agentMessageRepository.findByRunIdOrderBySentAt(runId);
        int start = Math.max(0, all.size() - 200);
        List<MessageResponse> response = all.subList(start, all.size()).stream()
            .map(m -> new MessageResponse(m.getFromAgent(), m.getContentSummary(), m.getSentAt()))
            .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    /**
     * Download the workspace for a run as a zip file.
     */
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
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + runId8 + "-repo.zip\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(repoZip);
            }

            // Path 1: GCS archive — redirect to signed URL
            if (run.getGcsWorkspaceKey() != null) {
                URL signedUrl = gcsWorkspaceStore.signedDownloadUrl(
                    run.getGcsWorkspaceKey(), Duration.ofMinutes(5));
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
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(Files.size(zipPath))
                    .body(resource);

            } catch (IllegalStateException e) {
                log.warn("Workspace not available for run {}: {}", id, e.getMessage());
                // Path 3: GitHub fallback
                if (run.getRepoUrl() != null && !run.getRepoUrl().isBlank()) {
                    String[] ownerRepo = com.navneet.executespec.repo.GitHubRepoService.extractOwnerRepo(run.getRepoUrl());
                    if (ownerRepo != null) {
                        String githubZip = "https://github.com/" + ownerRepo[0] + "/" + ownerRepo[1]
                                + "/archive/refs/heads/main.zip";
                        log.info("Local workspace missing for run {} — redirecting to GitHub zip: {}", id, githubZip);
                        return ResponseEntity.status(HttpStatus.FOUND)
                            .header(HttpHeaders.LOCATION, githubZip)
                            .build();
                    }
                }
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Workspace not available for download. " + e.getMessage());
            }

        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to export workspace: " + e.getMessage());
        }
    }

    /** List all workspace files (relative paths) for a run. */
    @GetMapping("/{id}/files")
    public ResponseEntity<List<String>> listWorkspaceFiles(
            @PathVariable String id,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(id, userDetails);
        try {
            return ResponseEntity.ok(workspaceFileService.listFiles(id));
        } catch (IOException e) {
            return ResponseEntity.ok(List.of());
        }
    }

    /**
     * Serve the generated OpenAPI 3.0 YAML spec for a run.
     */
    @GetMapping(value = "/{runId}/openapi.yaml", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getOpenApiSpec(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        // V2 planner mandate (runtimeSurface=HTTP) writes openapi.yaml at the repo root;
        // V1 ApiTestSuiteAgent writes api-docs/openapi.yaml. Try root first, fall back.
        String[] candidates = { "openapi.yaml", "openapi.yml", "api-docs/openapi.yaml" };
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
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/yaml")
                .body(yaml);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to read OpenAPI spec: " + e.getMessage());
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
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to read file: " + e.getMessage());
        }
    }

    // ── Plan Versioning ──────────────────────────────────────────────────────

    @GetMapping("/{runId}/plan-versions")
    public ResponseEntity<List<com.navneet.executespec.state.entity.PlanVersion>> getPlanVersions(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        return ResponseEntity.ok(planVersionRepo.findByRunIdOrderByVersionDesc(runId));
    }

    @PostMapping("/{runId}/edit-plan")
    public ResponseEntity<Map<String, Object>> editPlan(
            @PathVariable String runId,
            @RequestBody com.navneet.executespec.api.dto.EditPlanRequest req,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        throw new ResponseStatusException(HttpStatus.GONE,
                "In-place plan editing not yet supported in V2 — reject the plan and submit a fresh run with refined spec.");
    }

    // ── Task Execution Graph ──────────────────────────────────────────────────

    /**
     * Returns the task dependency graph for a run.
     */
    @GetMapping("/{runId}/task-graph")
    public ResponseEntity<List<TaskGraphNode>> getTaskGraph(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        PipelineRun run = guard.requireRunAccess(runId, userDetails);

        var tasks = stateStore.getAllTasks(runId);
        if (tasks.isEmpty()) return ResponseEntity.ok(List.of());

        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper()
                .setPropertyNamingStrategy(
                        com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        record TaskMeta(String taskId, String title, String status, String complexity,
                        String assignedModel, List<String> dependsOn,
                        String parentTaskId, Integer subtaskIndex, Integer subtaskTotal) {}

        List<TaskMeta> metas = tasks.stream().map(t -> {
            List<String> deps = List.of();
            try {
                var def = om.readValue(t.getTaskJson(),
                        com.navneet.executespec.agent.model.TaskDefinition.class);
                if (def.getDependsOn() != null) deps = def.getDependsOn();
            } catch (Exception ignored) {}
            return new TaskMeta(t.getTaskId(), t.getTitle(), t.getStatus(),
                    t.getComplexity(), t.getAssignedModel(), deps,
                    t.getParentTaskId(), t.getSubtaskIndex(), t.getSubtaskTotal());
        }).toList();

        // Compute topological levels via Kahn's-style BFS
        Map<String, Integer> levels = new java.util.HashMap<>();
        Map<String, TaskMeta> byId = metas.stream()
                .collect(Collectors.toMap(TaskMeta::taskId, m -> m));

        metas.stream().filter(m -> m.dependsOn().isEmpty())
                .forEach(m -> levels.put(m.taskId(), 0));

        boolean changed = true;
        while (changed) {
            changed = false;
            for (TaskMeta m : metas) {
                if (levels.containsKey(m.taskId())) continue;
                if (m.dependsOn().stream().allMatch(levels::containsKey)) {
                    int level = m.dependsOn().stream()
                            .mapToInt(d -> levels.getOrDefault(d, 0)).max().orElse(-1) + 1;
                    levels.put(m.taskId(), level);
                    changed = true;
                }
            }
        }

        List<TaskGraphNode> nodes = metas.stream()
                .map(m -> new TaskGraphNode(
                        m.taskId(), m.title(), m.status(), m.complexity(),
                        m.assignedModel(), m.dependsOn(), levels.getOrDefault(m.taskId(), 0),
                        m.parentTaskId(), m.subtaskIndex(), m.subtaskTotal()))
                .sorted(java.util.Comparator.comparingInt(TaskGraphNode::level)
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
            Integer subtaskTotal
    ) {}

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
        Instant restartCreatedAt
    ) {}
}
