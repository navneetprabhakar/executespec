package com.navneet.agentteam.api.controller;

import com.navneet.agentteam.api.RunMapper;
import com.navneet.agentteam.api.dto.ApprovalRequest;
import com.navneet.agentteam.api.dto.RunDetail;
import com.navneet.agentteam.api.dto.RunRequest;
import com.navneet.agentteam.api.dto.RunSummary;
import com.navneet.agentteam.auth.security.AppUserDetails;
import com.navneet.agentteam.auth.security.RunVisibilitySpec;
import com.navneet.agentteam.auth.service.TierEnforcementService;
import com.navneet.agentteam.deployment.WorkspaceExportService;
import com.navneet.agentteam.deployment.WorkspaceFileService;
import com.navneet.agentteam.harness.PipelineHarness;
import com.navneet.agentteam.recovery.StuckClassification;
import com.navneet.agentteam.state.StateStore;
import com.navneet.agentteam.state.entity.AgentMessage;
import com.navneet.agentteam.state.entity.PipelineRun;
import com.navneet.agentteam.state.entity.RunRecovery;
import com.navneet.agentteam.state.repository.AgentMessageRepository;
import com.navneet.agentteam.state.repository.RunRecoveryRepository;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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
    private final AgentMessageRepository agentMessageRepository;
    private final TierEnforcementService tierEnforcementService;
    private final RunVisibilitySpec runVisibilitySpec;

    /** Submit a new run. Returns immediately with the run summary (status may be PLANNING or QUEUED). */
    @PostMapping
    public ResponseEntity<RunSummary> submit(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @RequestBody RunRequest req) {
        log.info("POST /api/runs — goal: {}", req.userRequest());

        // Enforce tier quota (throws 429 if over limit)
        if (userDetails != null) {
            tierEnforcementService.assertCanSubmitRun(userDetails.getUser().getId());
        }

        // Extract per-user GitHub credentials to pass into the run
        String githubPat   = userDetails != null ? userDetails.getUser().getGithubPat()   : null;
        String githubOwner = userDetails != null ? userDetails.getUser().getGithubOwner() : null;

        PipelineRun run = harness.submitRun(
            req.userRequest(),
            req.source() != null ? req.source() : "REST",
            req.repoUrl(),
            req.repoMode(),
            githubPat,
            githubOwner
        );

        // Stamp ownership and optional org/project association
        boolean dirty = false;
        if (userDetails != null) {
            run.setCreatedByUserId(userDetails.getUser().getId());
            run.setOrgId(userDetails.getUser().getOrgId());
            dirty = true;
        }
        if (req.projectId() != null && !req.projectId().isBlank()) {
            run.setProjectId(req.projectId());
            dirty = true;
        }

        // Apply optional overrides that the front-end may send
        if (Boolean.TRUE.equals(req.skipApproval())) {
            run.setSkipApproval(true);
            dirty = true;
        }
        if (req.modelName() != null && !req.modelName().isBlank()) {
            run.setPlannerModel(req.modelName());
            run.setWorkerModel(req.modelName());
            run.setReviewerModel(req.modelName());
            dirty = true;
        }
        if (dirty) {
            run = stateStore.save(run);
        }

        // Increment run counter after successful submission
        if (userDetails != null) {
            tierEnforcementService.incrementRunCount(userDetails.getUser().getId());
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(mapper.toSummary(run));
    }

    /** List runs, optionally filtered by status and/or projectId. Paginated (default page=0, size=20). */
    @GetMapping
    public ResponseEntity<List<RunSummary>> list(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var visSpec = userDetails != null
                ? runVisibilitySpec.forUser(userDetails.getUser(), userDetails.getOrgMember())
                : null;
        Page<PipelineRun> runs = stateStore.listRuns(status, projectId, PageRequest.of(page, size), visSpec);
        return ResponseEntity.ok(runs.getContent().stream().map(mapper::toSummary).toList());
    }

    /** Get a single run with task details. */
    @GetMapping("/{runId}")
    public ResponseEntity<RunDetail> get(@PathVariable String runId) {
        return stateStore.findRun(runId)
            .map(run -> ResponseEntity.ok(mapper.toDetail(run, stateStore.getAllTasks(runId))))
            .orElse(ResponseEntity.notFound().build());
    }

    /** Approve or reject the plan-approval gate. */
    @PostMapping("/{runId}/approve")
    public ResponseEntity<Void> approvePlan(@PathVariable String runId,
                                             @RequestBody ApprovalRequest req) {
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

    /** Approve or reject the merge-approval gate. */
    @PostMapping("/{runId}/merge")
    public ResponseEntity<Void> approveMerge(@PathVariable String runId,
                                              @RequestBody ApprovalRequest req) {
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
    public ResponseEntity<RunSummary> retryRun(@PathVariable String runId) {
        log.info("POST /api/runs/{}/retry", runId);
        PipelineRun original = stateStore.findRun(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        if (!List.of("FAILED", "CANCELLED", "REJECTED").contains(original.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only FAILED, CANCELLED or REJECTED runs can be retried");
        }
        String newRunId = harness.restartRun(original);
        PipelineRun newRun = stateStore.findRun(newRunId).orElseThrow();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(mapper.toSummary(newRun));
    }

    /** Cancel a run. */
    @DeleteMapping("/{runId}")
    public ResponseEntity<Void> cancel(@PathVariable String runId) {
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
    public ResponseEntity<Void> retryTask(@PathVariable String runId,
                                          @PathVariable String taskId) {
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

    /** Get recovery history for a run. */
    @GetMapping("/{runId}/recovery")
    public ResponseEntity<List<RecoveryResponse>> getRecovery(@PathVariable String runId) {
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
     * Used by the UI to pre-populate the live-log pane when revisiting a run.
     */
    @GetMapping("/{runId}/messages")
    public ResponseEntity<List<MessageResponse>> getMessages(@PathVariable String runId) {
        List<AgentMessage> all = agentMessageRepository.findByRunIdOrderBySentAt(runId);
        // Cap at 200 most-recent to keep the response small
        int start = Math.max(0, all.size() - 200);
        List<MessageResponse> response = all.subList(start, all.size()).stream()
            .map(m -> new MessageResponse(m.getFromAgent(), m.getContentSummary(), m.getSentAt()))
            .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    /** Download the workspace for a run as a zip file. Works for any run status. */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadWorkspace(@PathVariable String id) {
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
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to export workspace: " + e.getMessage());
        }
    }

    /** List all workspace files (relative paths) for a run. */
    @GetMapping("/{id}/files")
    public ResponseEntity<List<String>> listWorkspaceFiles(@PathVariable String id) {
        try {
            return ResponseEntity.ok(workspaceFileService.listFiles(id));
        } catch (IOException e) {
            return ResponseEntity.ok(List.of());
        }
    }

    /**
     * Serve the generated OpenAPI 3.0 YAML spec for a run.
     * Returns 404 if ApiTestSuiteAgent has not run yet (it runs post-merge).
     */
    @GetMapping(value = "/{runId}/openapi.yaml", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getOpenApiSpec(@PathVariable String runId) {
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
            @RequestParam String path) {
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
     * Each node includes taskId, title, status, complexity, assignedModel,
     * dependsOn list, and a computed topological level (0 = root tasks).
     */
    @GetMapping("/{runId}/task-graph")
    public ResponseEntity<List<TaskGraphNode>> getTaskGraph(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        PipelineRun run = stateStore.findRun(runId).orElse(null);
        if (run == null) return ResponseEntity.notFound().build();

        var tasks = stateStore.getAllTasks(runId);
        if (tasks.isEmpty()) return ResponseEntity.ok(List.of());

        // Parse dependsOn from each task's JSON.
        // TaskDefinition uses @JsonNaming(SnakeCaseStrategy) so the stored key is "depends_on".
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper()
                .setPropertyNamingStrategy(
                        com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        record TaskMeta(String taskId, String title, String status, String complexity,
                        String assignedModel, List<String> dependsOn) {}

        List<TaskMeta> metas = tasks.stream().map(t -> {
            List<String> deps = List.of();
            try {
                var def = om.readValue(t.getTaskJson(),
                        com.navneet.agentteam.agent.model.TaskDefinition.class);
                if (def.getDependsOn() != null) deps = def.getDependsOn();
            } catch (Exception ignored) {}
            return new TaskMeta(t.getTaskId(), t.getTitle(), t.getStatus(),
                    t.getComplexity(), t.getAssignedModel(), deps);
        }).toList();

        // Compute topological levels via Kahn's-style BFS
        Map<String, Integer> levels = new java.util.HashMap<>();
        Map<String, TaskMeta> byId = metas.stream()
                .collect(Collectors.toMap(TaskMeta::taskId, m -> m));

        // roots = tasks with no deps
        metas.stream().filter(m -> m.dependsOn().isEmpty())
                .forEach(m -> levels.put(m.taskId(), 0));

        // propagate levels
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
