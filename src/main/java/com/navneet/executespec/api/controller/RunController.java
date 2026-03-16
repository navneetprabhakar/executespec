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
import com.navneet.executespec.harness.PipelineHarness;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentMessage;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.entity.RunRecovery;
import com.navneet.executespec.state.repository.AgentMessageRepository;
import com.navneet.executespec.state.repository.RunRecoveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.data.jpa.domain.Specification;
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

    private final PipelineHarness harness;
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

    /** Submit a new run. Returns immediately with the run summary (status may be PLANNING or QUEUED). */
    @PostMapping
    public ResponseEntity<RunSummary> submit(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @RequestBody RunRequest req) {
        guard.requireAuthenticated(userDetails);
        log.info("POST /api/runs — goal: {}", req.userRequest());

        // Enforce tier quota (throws 429 if over limit)
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
        PipelineRun run = harness.submitRun(
            req.userRequest(),
            req.source() != null ? req.source() : "REST",
            req.repoUrl(),
            req.repoMode(),
            githubPat,
            githubOwner,
            Boolean.TRUE.equals(req.skipApproval()),
            Boolean.TRUE.equals(req.autoMerge()),
            req.modelName(),
            userDetails.getUser().getId(),
            userDetails.getUser().getOrgId(),
            req.projectId()
        );

        // Increment run counter after successful submission
        tierEnforcementService.incrementRunCount(userDetails.getUser().getId());
        usageTrackingService.increment(userDetails.getUser().getId(), "RUNS", 1);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(mapper.toSummary(run));
    }

    /**
     * List runs, optionally filtered by status, projectId, and scope.
     * scope=mine restricts to the current user's runs (within org visibility).
     */
    @GetMapping
    public ResponseEntity<List<RunSummary>> list(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String scope,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        guard.requireAuthenticated(userDetails);
        var visSpec = runVisibilitySpec.forUser(userDetails.getUser(), userDetails.getOrgMember());

        // "mine" scope: filter to only the current user's runs
        if ("mine".equals(scope)) {
            Specification<PipelineRun> mineSpec = (root, query, cb) ->
                    cb.equal(root.get("createdByUserId"), userDetails.getUser().getId());
            visSpec = visSpec.and(mineSpec);
        }

        Page<PipelineRun> runs = stateStore.listRuns(status, projectId, PageRequest.of(page, size), visSpec);
        return ResponseEntity.ok(runs.getContent().stream().map(mapper::toSummary).toList());
    }

    /** Get a single run with task details. */
    @GetMapping("/{runId}")
    public ResponseEntity<RunDetail> get(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        PipelineRun run = guard.requireRunAccess(runId, userDetails);
        return ResponseEntity.ok(mapper.toDetail(run, stateStore.getAllTasks(runId)));
    }

    /** Approve or reject the plan-approval gate. */
    @PostMapping("/{runId}/approve")
    public ResponseEntity<Void> approvePlan(
            @PathVariable String runId,
            @RequestBody ApprovalRequest req,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        log.info("POST /api/runs/{}/approve — approved={}", runId, req.approved());
        boolean resolved = harness.resolvePlanGate(runId, req.approved(),
            req.comment() != null ? req.comment() : "");
        if (resolved) return ResponseEntity.ok().build();
        // Gate was lost (server restart). If the harness auto-failed the run, return 409
        // so the UI can show a helpful "resubmit" message rather than a generic 404.
        boolean stuckFailed = stateStore.findRun(runId)
            .map(r -> "FAILED".equals(r.getStatus())).orElse(false);
        return stuckFailed
            ? ResponseEntity.<Void>status(HttpStatus.CONFLICT).build()
            : ResponseEntity.<Void>notFound().build();
    }

    /** Resolve the context-clarification gate — user provides JIRA key, Confluence URL, etc. */
    @PostMapping("/{runId}/clarify")
    public ResponseEntity<Void> clarifyContext(
            @PathVariable String runId,
            @RequestBody ClarificationRequest req,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        log.info("POST /api/runs/{}/clarify — response length: {}", runId,
                req.response() != null ? req.response().length() : 0);
        boolean resolved = harness.resolveClarificationGate(runId, req.response());
        if (resolved) return ResponseEntity.ok().build();
        return ResponseEntity.notFound().build();
    }

    public record ClarificationRequest(String response) {}

    /** Approve or reject the merge-approval gate. */
    @PostMapping("/{runId}/merge")
    public ResponseEntity<Void> approveMerge(
            @PathVariable String runId,
            @RequestBody ApprovalRequest req,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        log.info("POST /api/runs/{}/merge — approved={}", runId, req.approved());
        boolean resolved = harness.resolveMergeGate(runId, req.approved(),
            req.comment() != null ? req.comment() : "");
        if (resolved) return ResponseEntity.ok().build();
        boolean stuckFailed = stateStore.findRun(runId)
            .map(r -> "FAILED".equals(r.getStatus())).orElse(false);
        return stuckFailed
            ? ResponseEntity.<Void>status(HttpStatus.CONFLICT).build()
            : ResponseEntity.<Void>notFound().build();
    }

    /**
     * Retry a failed or cancelled run by resubmitting the same goal as a fresh run.
     * Returns 202 Accepted with the new run's summary in the body.
     */
    @PostMapping("/{runId}/retry")
    public ResponseEntity<RunSummary> retryRun(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        PipelineRun original = guard.requireRunAccess(runId, userDetails);
        log.info("POST /api/runs/{}/retry", runId);
        if (!List.of("FAILED", "CANCELLED", "REJECTED", "PAUSED").contains(original.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only FAILED, CANCELLED, REJECTED or PAUSED runs can be retried");
        }
        String newRunId = harness.restartRun(original);
        PipelineRun newRun = stateStore.findRun(newRunId).orElseThrow();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(mapper.toSummary(newRun));
    }

    /**
     * Pause a running pipeline. Cancels in-flight workers and gates,
     * sets status to PAUSED for later goal editing and restart.
     */
    @PostMapping("/{runId}/pause")
    public ResponseEntity<Void> pauseRun(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        PipelineRun run = guard.requireRunAccess(runId, userDetails);
        log.info("POST /api/runs/{}/pause", runId);
        if (List.of("COMPLETE", "FAILED", "CANCELLED", "PAUSED").contains(run.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cannot pause a " + run.getStatus() + " run");
        }
        boolean paused = harness.pauseRun(runId);
        return paused ? ResponseEntity.ok().build() : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    /**
     * Edit the goal of a PAUSED run and restart it as a brand-new pipeline run.
     * The new run links back via sourceRunId, and the paused run's failureReason is updated
     * with a forward link to the new run.
     */
    @PostMapping("/{runId}/edit-and-restart")
    public ResponseEntity<RunSummary> editAndRestart(
            @PathVariable String runId,
            @RequestBody EditAndRestartRequest body,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        PipelineRun paused = guard.requireRunAccess(runId, userDetails);
        log.info("POST /api/runs/{}/edit-and-restart — newGoal: {}", runId, body.userRequest());
        if (!"PAUSED".equals(paused.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only PAUSED runs can be edited and restarted (current: " + paused.getStatus() + ")");
        }
        PipelineRun newRun = harness.restartWithNewGoal(paused, body.userRequest());
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toSummary(newRun));
    }

    public record EditAndRestartRequest(String userRequest) {}

    /**
     * Resume a PAUSED run — continues the same run from the worker phase,
     * preserving completed tasks and re-running incomplete ones.
     * Returns 200 on success, 409 if the run is not in PAUSED state.
     */
    @PostMapping("/{runId}/resume")
    public ResponseEntity<Void> resumeRun(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        PipelineRun run = guard.requireRunAccess(runId, userDetails);
        log.info("POST /api/runs/{}/resume", runId);
        if (!"PAUSED".equals(run.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only PAUSED runs can be resumed (current: " + run.getStatus() + ")");
        }
        boolean resumed = harness.resumeRun(runId);
        return resumed ? ResponseEntity.ok().build() : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    /**
     * Push an existing COMPLETE/PARTIAL run's workspace to GitHub.
     * If repoMode=NEW and no repoUrl, creates a new repo first.
     * This avoids re-running the pipeline just to push generated code.
     */
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
        harness.pushToGitHubManual(run);
        return ResponseEntity.ok(Map.of(
            "status", "pushed",
            "repoUrl", run.getRepoUrl() != null ? run.getRepoUrl() : ""));
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
        boolean cancelled = harness.cancelRun(runId);
        return cancelled ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
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
            harness.retryPartialTask(runId, taskId);
            return ResponseEntity.accepted().build();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * Implements post-merge suggestions (MEDIUM/LOW reviewer issues) for a run.
     */
    @PostMapping("/{runId}/implement-suggestions")
    public ResponseEntity<Void> implementSuggestions(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        log.info("POST /api/runs/{}/implement-suggestions", runId);
        try {
            harness.implementSuggestions(runId);
            return ResponseEntity.accepted().build();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * User-triggered rework: re-runs the tasks flagged by the reviewer with
     * remediation feedback injected, then re-reviews and re-opens the gate.
     */
    @PostMapping("/{runId}/rework")
    public ResponseEntity<Void> reworkRun(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        log.info("POST /api/runs/{}/rework", runId);
        try {
            harness.reworkRun(runId);
            return ResponseEntity.accepted().build();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * Re-runs the reviewer and re-opens the merge gate for a PARTIAL run where all
     * worker tasks are COMPLETE (e.g., after "Merge declined").
     * Returns 202 Accepted immediately; reviewer + gate run asynchronously.
     */
    @PostMapping("/{runId}/reopen-merge")
    public ResponseEntity<Void> reopenMerge(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        log.info("POST /api/runs/{}/reopen-merge", runId);
        try {
            harness.reopenMergeGate(runId);
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
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
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
        try {
            String yaml = workspaceFileService.readFile(runId, "api-docs/openapi.yaml");
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/yaml")
                .body(yaml);
        } catch (java.nio.file.NoSuchFileException e) {
            return ResponseEntity.notFound().build();
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
                        String assignedModel, List<String> dependsOn) {}

        List<TaskMeta> metas = tasks.stream().map(t -> {
            List<String> deps = List.of();
            try {
                var def = om.readValue(t.getTaskJson(),
                        com.navneet.executespec.agent.model.TaskDefinition.class);
                if (def.getDependsOn() != null) deps = def.getDependsOn();
            } catch (Exception ignored) {}
            return new TaskMeta(t.getTaskId(), t.getTitle(), t.getStatus(),
                    t.getComplexity(), t.getAssignedModel(), deps);
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
                        m.assignedModel(), m.dependsOn(), levels.getOrDefault(m.taskId(), 0)))
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
            int level
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
