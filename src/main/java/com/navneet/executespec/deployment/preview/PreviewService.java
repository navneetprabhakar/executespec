package com.navneet.executespec.deployment.preview;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.ArtifactRegistryClient;
import com.navneet.executespec.deployment.CloudRunClient;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.deployment.preview.vm.PreviewDeploymentService;
import com.navneet.executespec.deployment.preview.vm.PreviewVmResult;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.micrometer.context.ContextSnapshotFactory;
import static com.navneet.executespec.config.CustomMetricsConfig.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * Preview service — builds and runs ephemeral dev servers in one of two modes:
 * <ul>
 *   <li><b>docker</b> (default): Docker Compose on local machine, Caddy reverse proxy for HTTPS.
 *       TTL: 5 minutes.</li>
 *   <li><b>cloud-run</b>: Build image → push to Artifact Registry → deploy to GCP Cloud Run.
 *       Cloud Run provides managed HTTPS with public URLs, no proxy needed. TTL: 5 minutes.</li>
 * </ul>
 *
 * <p>Mode is configured via {@code preview.mode} property (default: "docker").
 *
 * <p>Lifecycle: IDLE → ANALYZING → BUILDING → STARTING → RUNNING → STOPPED/FAILED/TIMED_OUT.
 * Each preview auto-expires after the configured TTL.
 *
 * <p><b>Reliability:</b> All subprocess commands have timeouts. Virtual threads use
 * {@link io.micrometer.context.ContextSnapshotFactory} for trace propagation. The
 * {@code ACTIVE_PREVIEWS} gauge is decremented on all terminal states. Cloud Run resources
 * (service + AR image) are cleaned up on failure. Logs are available for both Docker Compose
 * and Cloud Run modes.
 */
@Service
@Slf4j
public class PreviewService {

    private final DevDockerfileGenerator dockerfileGen;
    private final DatabaseDetector databaseDetector;
    private final EnvVarDetector envVarDetector;
    private final ComposeFileGenerator composeFileGen;
    private final ComposeExecutor composeExecutor;
    private final PreviewPortPool portPool;
    private final PreviewProperties props;
    private final SseBroadcaster broadcaster;
    private final StateStore stateStore;
    private final AgentProperties agentProps;
    private final TierConfigRepository tierConfigRepo;
    private final WorkspaceRestoreService restoreService;
    private final MeterRegistry meterRegistry;
    private final GlobalPreviewLimiter globalPreviewLimiter;

    /** Optional — only present when preview.proxy.enabled=true (VM with Caddy). */
    @Autowired(required = false)
    private CaddyProxyManager caddyProxy;

    /** Optional — for Cloud Run preview mode. */
    @Autowired(required = false)
    private ArtifactRegistryClient artifactRegistryClient;

    /** Optional — for Cloud Run preview mode. */
    @Autowired(required = false)
    private CloudRunClient cloudRunClient;

    /** Notifies the test execution pipeline that the preview is ready. */
    @Autowired(required = false)
    private com.navneet.executespec.testgen.TestPreviewCoordinator testPreviewCoordinator;

    /** Optional — for VM-based preview mode. */
    @Autowired(required = false)
    private PreviewDeploymentService vmPreviewService;

    /** runId → session */
    private final ConcurrentHashMap<String, PreviewSession> active = new ConcurrentHashMap<>();

    /** runId → last-used env vars (preserved across rebuild so users don't re-enter secrets) */
    private final ConcurrentHashMap<String, Map<String, String>> lastEnvVars = new ConcurrentHashMap<>();

    /**
     * Queue of preview start requests that couldn't start immediately because all VM slots
     * were at capacity. Drained (FIFO) whenever a preview stops and a slot opens up.
     */
    private record QueuedPreview(String runId, AppUser user, Map<String, String> envVars) {}
    private final ConcurrentLinkedQueue<QueuedPreview> previewQueue = new ConcurrentLinkedQueue<>();

    private static final Set<String> COMPLETE_STATUSES = Set.of("COMPLETE", "PARTIAL");
    private static final Set<String> PREVIEW_ALLOWED_STATUSES = Set.of(
            "COMPLETE", "PARTIAL", "WORKING", "REVIEWING", "AWAITING_MERGE");
    private static final String COMPOSE_PREFIX = "es-preview-";
    private static final int HEALTH_CHECK_RETRIES = 30;
    private static final int HEALTH_CHECK_INTERVAL_MS = 2000;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1) // Force HTTP/1.1 — dev servers (Vite, Flask) may not support HTTP/2
            .build();

    public PreviewService(DevDockerfileGenerator dockerfileGen,
                          DatabaseDetector databaseDetector,
                          EnvVarDetector envVarDetector,
                          ComposeFileGenerator composeFileGen,
                          ComposeExecutor composeExecutor,
                          PreviewPortPool portPool,
                          PreviewProperties props,
                          SseBroadcaster broadcaster,
                          StateStore stateStore,
                          AgentProperties agentProps,
                          TierConfigRepository tierConfigRepo,
                          WorkspaceRestoreService restoreService,
                          MeterRegistry meterRegistry,
                          GlobalPreviewLimiter globalPreviewLimiter) {
        this.dockerfileGen = dockerfileGen;
        this.databaseDetector = databaseDetector;
        this.envVarDetector = envVarDetector;
        this.composeFileGen = composeFileGen;
        this.composeExecutor = composeExecutor;
        this.portPool = portPool;
        this.props = props;
        this.broadcaster = broadcaster;
        this.stateStore = stateStore;
        this.agentProps = agentProps;
        this.tierConfigRepo = tierConfigRepo;
        this.restoreService = restoreService;
        this.meterRegistry = meterRegistry;
        this.globalPreviewLimiter = globalPreviewLimiter;
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Analyze workspace for database and env var requirements (pre-start step).
     */
    public PreviewAnalysis analyze(String runId) {
        Path workspace = restoreService.ensureWorkspaceAvailable(runId);
        DatabaseType dbType = databaseDetector.detect(workspace);
        List<EnvVarDetector.DetectedEnvVar> envVars = envVarDetector.detect(workspace);

        // Filter out DATABASE category vars — they're auto-injected by ComposeFileGenerator
        List<EnvVarDetector.DetectedEnvVar> userVars = envVars.stream()
                .filter(v -> v.category() != EnvVarDetector.EnvVarCategory.DATABASE)
                .toList();

        return new PreviewAnalysis(dbType, userVars);
    }

    /**
     * Start a preview for a completed run. Returns immediately with ANALYZING status.
     * The actual build + start happens on a virtual thread via Docker Compose.
     *
     * @param envVars user-provided environment variables (may be null/empty)
     */
    public PreviewSession start(String runId, AppUser user, Map<String, String> envVars) {
        // Guard: preview enabled
        if (!props.isEnabled()) {
            throw new PreviewException("Preview is disabled");
        }

        // Guard: run must exist and be complete
        PipelineRun run = stateStore.findRun(runId)
                .orElseThrow(() -> new PreviewException("Run not found: " + runId));
        if (!PREVIEW_ALLOWED_STATUSES.contains(run.getStatus())) {
            throw new PreviewException("Run must be complete to preview (current status: " + run.getStatus() + ")");
        }

        // SECURITY: synchronized block ensures atomic check-count-insert to prevent
        // race condition where two concurrent requests both pass the limit check.
        PreviewSession session;
        PreviewProjectType type;
        PreviewMode mode;
        Path workspace;
        String projectName;
        Duration ttl;
        synchronized (active) {
            // Guard: idempotent — return existing session if still active.
            // If FAILED or TIMED_OUT, remove it so the user can retry.
            // If ANALYZING/BUILDING/STARTING/RUNNING, return as-is (no restart).
            PreviewSession existing = active.get(runId);
            if (existing != null) {
                if (existing.status() == PreviewStatus.FAILED
                        || existing.status() == PreviewStatus.TIMED_OUT) {
                    // Clean up stale failed session so retry starts fresh
                    if (isVmMode() && vmPreviewService != null && existing.composeProjectName() != null) {
                        try { vmPreviewService.teardown(existing.composeProjectName()); }
                        catch (Exception ignored) { /* best-effort cleanup */ }
                    } else if (existing.cloudRunServiceName() != null) {
                        cleanupCloudRun(existing.cloudRunServiceName(), existing.imageUri());
                    } else {
                        cleanupCompose(existing);
                    }
                    active.remove(runId);
                    log.info("Cleared failed preview session for run {} — allowing retry", runId);
                } else {
                    // ANALYZING, BUILDING, STARTING, RUNNING — return current state, don't restart
                    return existing;
                }
            }

            // Guard: concurrent preview limit (in-memory check for single-instance correctness)
            long userPreviewCount = active.values().stream()
                    .filter(s -> stateStore.findRun(s.runId())
                            .map(r -> user.getId().equals(r.getCreatedByUserId()))
                            .orElse(false))
                    .count();
            int maxConcurrent = getMaxConcurrent(user.getTier());
            if (userPreviewCount >= maxConcurrent) {
                throw new PreviewException("Max concurrent previews reached (" + maxConcurrent +
                        " for " + user.getTier() + " tier). Stop an existing preview first.");
            }

            // Guard: Redis-backed global limit (cross-instance enforcement)
            if (!globalPreviewLimiter.tryAcquire(user.getId().toString(), user.getTier())) {
                throw new PreviewException("Max concurrent previews reached for your tier (" +
                        user.getTier() + "). Stop an existing preview first.");
            }

            // Guard: per-run preview start limit
            int maxStarts = tierConfigRepo.findById(user.getTier())
                    .map(TierConfig::getMaxPreviewStarts)
                    .orElse(3);
            if (maxStarts >= 0 && run.getPreviewStartCount() >= maxStarts) {
                throw new PreviewException("Preview limit reached for this run ("
                        + run.getPreviewStartCount() + "/" + maxStarts
                        + "). Upgrade your plan for more.");
            }

            // Increment preview start counter
            run.setPreviewStartCount(run.getPreviewStartCount() + 1);
            stateStore.saveRun(run);

            // Detect project type (restore from GCS if needed)
            workspace = restoreService.ensureWorkspaceAvailable(runId);
            type = dockerfileGen.detect(workspace);
            if (type == null) {
                throw new PreviewException("Unsupported project type — could not detect build system.");
            }

            // Detect preview mode: WEB, CLI_TERMINAL, or MCP_INSPECTOR
            mode = dockerfileGen.detectMode(type, workspace);
            log.info("Preview mode for run {}: {} (type={})", runId, mode, type);

            // Create initial session
            projectName = composeProjectName(runId);
            ttl = Duration.ofMinutes(props.getPreviewTtlMinutes());

            // For Cloud Run mode, store the service name upfront so the orphan cleanup
            // job sees it as active during the build/push/deploy cycle.
            String crServiceName = isCloudRunMode()
                    ? "es-preview-" + (runId.length() > 8 ? runId.substring(0, 8) : runId)
                    : null;

            session = new PreviewSession(
                    runId, projectName, type, mode, null,
                    PreviewStatus.ANALYZING, null, 0, null,
                    Instant.now(), Instant.now().plus(ttl),
                    crServiceName, null
            );
            active.put(runId, session);
        } // end synchronized

        // Launch async build + start on a virtual thread
        Metrics.ACTIVE_PREVIEWS.incrementAndGet();
        Counter.builder(PREVIEW_STARTS).register(meterRegistry).increment();
        Map<String, String> safeEnvVars = envVars != null ? Map.copyOf(envVars) : Map.of();
        if (!safeEnvVars.isEmpty()) lastEnvVars.put(runId, safeEnvVars);

        // Capture observation context so virtual threads propagate traceId/spanId
        var snapshot = ContextSnapshotFactory.builder().build().captureAll();
        String shortId = runId.length() > 8 ? runId.substring(0, 8) : runId;
        if (isVmMode()) {
            // Check VM capacity before spawning the thread — if at capacity, queue this
            // request and return a QUEUED session immediately rather than failing later.
            if (vmPreviewService != null && !vmPreviewService.hasCapacity()) {
                log.info("VM preview at capacity for run {} — queuing (user={})", runId, user.getId());
                previewQueue.add(new QueuedPreview(runId, user, safeEnvVars));
                Metrics.ACTIVE_PREVIEWS.decrementAndGet(); // undo the increment above; not active yet
                PreviewSession queued = new PreviewSession(
                        runId, null, type, mode, null,
                        PreviewStatus.QUEUED, null, 0, "Waiting for a preview slot...",
                        Instant.now(), null, null, null);
                active.put(runId, queued);
                broadcaster.emit(runId, RunEvent.log(runId, null,
                        "⏳ All preview VMs at capacity — queued. Will start automatically when a slot opens."));
                return queued;
            }
            Thread.ofVirtual().name("preview-vm-" + shortId)
                    .start(snapshot.wrap(() -> buildAndStartVm(runId, workspace, type, mode, projectName)));
        } else if (isCloudRunMode()) {
            Thread.ofVirtual().name("preview-cr-" + shortId)
                    .start(snapshot.wrap(() -> buildAndStartCloudRun(runId, workspace, type, mode, projectName, safeEnvVars)));
        } else {
            Thread.ofVirtual().name("preview-docker-" + shortId)
                    .start(snapshot.wrap(() -> buildAndStart(runId, workspace, type, mode, projectName, safeEnvVars)));
        }

        log.info("Preview started for run {} (type={}, mode={}, project={}, ttl={}min)",
                runId, type, mode, projectName, ttl.toMinutes());
        return session;
    }

    /** Backward-compatible start without env vars. */
    public PreviewSession start(String runId, AppUser user) {
        return start(runId, user, null);
    }

    /**
     * Stop a running preview. Tears down Docker Compose or Cloud Run service. Idempotent.
     */
    public void stop(String runId) {
        PreviewSession session = active.remove(runId);
        if (session == null) return;
        // Note: lastEnvVars NOT removed here — rebuild() needs them after stop()
        Metrics.ACTIVE_PREVIEWS.decrementAndGet();
        releaseGlobalPreviewSlot(runId);

        String mode;
        if (isVmMode() && vmPreviewService != null && session.composeProjectName() != null) {
            // VM mode: teardown via SSH on the preview VM
            try {
                vmPreviewService.teardown(session.composeProjectName());
            } catch (Exception e) {
                log.warn("VM preview teardown failed for run {}: {}", runId, e.getMessage());
            }
            mode = "vm";
        } else if (session.cloudRunServiceName() != null) {
            cleanupCloudRun(session.cloudRunServiceName(), session.imageUri());
            mode = "cloud-run";
        } else {
            cleanupCompose(session);
            mode = "docker";
        }

        broadcaster.emit(runId, new RunEvent("PREVIEW_STOPPED", runId, null, null, Instant.now()));
        log.info("Preview stopped for run {} (mode={})", runId, mode);

        // A slot just opened — start the next queued preview if any
        if (isVmMode()) {
            drainPreviewQueue();
        }
    }

    /**
     * Rebuild a preview: stop the current session, re-detect project type, regenerate
     * Dockerfile, and start fresh. Preserves user-provided env vars from the last start.
     */
    public PreviewSession rebuild(String runId, AppUser user) {
        Map<String, String> preserved = lastEnvVars.getOrDefault(runId, Map.of());
        stop(runId);
        return start(runId, user, preserved);
    }

    /**
     * Get the current preview session for a run.
     * Returns null if no preview is active (IDLE).
     */
    public PreviewSession getStatus(String runId) {
        return active.get(runId);
    }

    /**
     * Get logs from Docker Compose or Cloud Run for a running preview.
     */
    public String getLogs(String runId, int tailLines, String filter) {
        PreviewSession session = active.get(runId);
        if (session == null) return "";

        // VM mode: logs are on the remote VM, not available locally
        if (isVmMode()) {
            return "Preview running on VM — check VM logs via /api/v1/previews/" + runId + "/logs";
        }

        // Cloud Run mode: fetch logs via gcloud
        if (session.cloudRunServiceName() != null) {
            return getCloudRunLogs(session.cloudRunServiceName(), tailLines > 0 ? tailLines : 50);
        }

        // Docker mode: fetch logs via docker compose
        if (session.composeProjectName() == null) return "Preview is still initializing...";

        String service = null;
        if (filter != null && !filter.isBlank() && !"all".equalsIgnoreCase(filter)) {
            service = filter; // "app", "db", etc.
        }

        String logs = composeExecutor.logs(session.composeProjectName(), service, tailLines > 0 ? tailLines : 100);
        return logs.isEmpty() ? "No logs available yet." : logs;
    }

    // ── Allowed commands for container exec (Console feature) ─────────────────
    private static final java.util.Set<String> EXEC_ALLOWLIST = java.util.Set.of(
            "ls", "ps", "cat", "curl", "env", "df", "free", "netstat", "ss", "ping",
            "whoami", "hostname", "date", "uptime", "pwd"
    );

    /**
     * Execute an allowlisted command inside the running preview app container.
     * Only available in Docker mode (not Cloud Run or VM).
     *
     * @param runId   run owning the preview
     * @param cmd     command name (must be in EXEC_ALLOWLIST)
     * @param args    command arguments (must not contain shell metacharacters)
     * @return stdout/stderr from the container exec
     * @throws IllegalStateException   if preview is not running in Docker mode
     * @throws IllegalArgumentException if command is not allowlisted or args are unsafe
     */
    public ExecResult execInContainer(String runId, String cmd, List<String> args) {
        PreviewSession session = active.get(runId);
        if (session == null || session.status() != PreviewStatus.RUNNING) {
            throw new IllegalStateException("Preview is not running for run " + runId);
        }
        if (session.composeProjectName() == null) {
            throw new IllegalStateException("Container exec is only available in Docker preview mode");
        }
        if (!EXEC_ALLOWLIST.contains(cmd)) {
            throw new IllegalArgumentException("Command not allowed: " + cmd
                    + ". Allowed: " + String.join(", ", EXEC_ALLOWLIST.stream().sorted().toList()));
        }
        // Reject args containing shell metacharacters to prevent injection
        for (String arg : args) {
            if (arg.contains("|") || arg.contains("&") || arg.contains(";")
                    || arg.contains("$") || arg.contains("`") || arg.contains(">")
                    || arg.contains("<") || arg.contains("..") || arg.contains("\n")) {
                throw new IllegalArgumentException("Argument contains unsafe characters: " + arg);
            }
        }
        var fullCmd = new java.util.ArrayList<String>();
        fullCmd.add(cmd);
        fullCmd.addAll(args);
        log.info("[PreviewExec][{}] exec: {}", runId, fullCmd);
        return composeExecutor.execInService(session.composeProjectName(), "app",
                fullCmd, java.time.Duration.ofSeconds(10));
    }

    /**
     * Fetch Cloud Run service logs via gcloud CLI.
     */
    private String getCloudRunLogs(String serviceName, int tailLines) {
        if (cloudRunClient == null) return "Cloud Run client not available.";
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "gcloud", "logging", "read",
                    "resource.type=cloud_run_revision AND resource.labels.service_name=" + serviceName,
                    "--limit", String.valueOf(Math.min(tailLines, 200)),
                    "--format", "value(textPayload)",
                    "--freshness", "10m",
                    "--order", "asc"
            ).redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Log fetch timed out.";
            }
            return output.isEmpty() ? "No logs available yet (Cloud Run may still be starting)." : output;
        } catch (Exception e) {
            log.warn("Failed to fetch Cloud Run logs for {}: {}", serviceName, e.getMessage());
            return "Failed to fetch logs: " + e.getMessage();
        }
    }

    private boolean isCloudRunMode() {
        return "cloud-run".equalsIgnoreCase(props.getMode());
    }

    private boolean isVmMode() {
        return "vm".equalsIgnoreCase(props.getMode());
    }

    // ── Private: build and start with VM ──────────────────────────────────

    private void buildAndStartVm(String runId, Path workspace, PreviewProjectType type,
                                  PreviewMode mode, String projectName) {
        if (vmPreviewService == null) {
            fail(runId, "VM preview mode configured but PreviewDeploymentService is not available. " +
                    "Ensure preview.vm.enabled=true is set.", 0);
            return;
        }

        try {
            // Phase 1: Detect database
            broadcaster.emit(runId, RunEvent.log(runId, null, "Detecting project dependencies..."));
            DatabaseType dbType = databaseDetector.detect(workspace);

            // Phase 2: Generate Dockerfile + docker-compose.yml (same as Docker path)
            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.composeProjectName(), type, mode, dbType,
                    PreviewStatus.BUILDING, null, 0, null,
                    s.startedAt(), s.expiresAt(), null, null));
            broadcaster.emit(runId, RunEvent.log(runId, null, "Generating Dockerfile and compose config..."));

            Files.writeString(workspace.resolve(".dockerignore"), dockerfileGen.dockerIgnore());
            backupIfExists(workspace.resolve("docker-compose.yml"), workspace.resolve("docker-compose.yml.user"));
            backupIfExists(workspace.resolve("docker-compose.yaml"), workspace.resolve("docker-compose.yaml.user"));
            String generatedDockerfile = dockerfileGen.generate(type, workspace, mode);
            Files.writeString(workspace.resolve("Dockerfile"), generatedDockerfile);
            log.info("VM preview Dockerfile for run {} (type={}):\n{}", runId, type, generatedDockerfile);

            // Generate docker-compose.yml with port 8080 (standard container port)
            composeFileGen.generate(workspace, type, mode, dbType, Map.of(), 8080);

            // Phase 3: SCP + deploy to VM
            broadcaster.emit(runId, RunEvent.log(runId, null, "Deploying preview to VM..."));
            PreviewVmResult result = vmPreviewService.deploy(projectName, runId, workspace);

            String previewUrl = result.getUrl();
            log.info("VM preview deployed for run {}: {}", runId, previewUrl);

            updateSession(runId, s -> new PreviewSession(
                    s.runId(), result.getPreviewId(), type, mode, null,
                    PreviewStatus.RUNNING, previewUrl, result.getPort(), null,
                    s.startedAt(), s.expiresAt(), null, null));

            broadcaster.emit(runId, new RunEvent("PREVIEW_READY", runId, null, previewUrl, Instant.now()));

            // Notify test execution pipeline
            if (testPreviewCoordinator != null) {
                try {
                    testPreviewCoordinator.onPreviewReady(runId, previewUrl);
                } catch (Exception e) {
                    log.warn("Failed to notify test coordinator for run {}: {}", runId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("VM preview deploy failed for run {}: {}", runId, e.getMessage());
            fail(runId, "VM preview failed: " + e.getMessage(), 0);
        }
    }

    /**
     * Drain one entry from the preview queue when a slot opens (called from {@link #stop}).
     * Runs the dequeued preview start in a virtual thread to avoid blocking the stop caller.
     */
    private void drainPreviewQueue() {
        QueuedPreview next = previewQueue.poll();
        if (next == null) return;
        log.info("Preview slot opened — starting queued preview for run {}", next.runId());
        // Remove the QUEUED placeholder so start() treats it as a fresh start
        active.remove(next.runId());
        Thread.ofVirtual().name("preview-queue-drain-" + next.runId()).start(() -> {
            try {
                start(next.runId(), next.user(), next.envVars().isEmpty() ? null : next.envVars());
            } catch (Exception e) {
                log.warn("Queued preview start failed for run {}: {}", next.runId(), e.getMessage());
            }
        });
    }

    // ── Private: build and start with Cloud Run ───────────────────────────

    private void buildAndStartCloudRun(String runId, Path workspace, PreviewProjectType type,
                                        PreviewMode mode, String projectName, Map<String, String> envVars) {
        Instant previewStart = Instant.now();
        String shortId = runId.length() > 8 ? runId.substring(0, 8) : runId;
        String serviceName = "es-preview-" + shortId;
        String imageUri = null;

        try {
            if (artifactRegistryClient == null || cloudRunClient == null) {
                fail(runId, "Cloud Run preview mode requires ArtifactRegistryClient and CloudRunClient beans. "
                        + "Check GCP deployment configuration.", 0);
                return;
            }

            // Phase 1: Generate Dockerfile (Cloud Run-compatible, no ttyd for CLI projects)
            broadcaster.emit(runId, new RunEvent("PREVIEW_ANALYZING", runId, null,
                    "Generating Dockerfile...", Instant.now()));
            Files.writeString(workspace.resolve(".dockerignore"), dockerfileGen.dockerIgnore());
            backupIfExists(workspace.resolve("docker-compose.yml"), workspace.resolve("docker-compose.yml.user"));
            backupIfExists(workspace.resolve("docker-compose.yaml"), workspace.resolve("docker-compose.yaml.user"));
            // Use Cloud Run-specific Dockerfile generator — handles $PORT env var,
            // and avoids ttyd (CLI_TERMINAL) which doesn't work on Cloud Run (WebSocket + 429 on startup probe).
            String generatedDockerfile = dockerfileGen.generateForCloudRun(type, workspace);
            log.info("Preview Dockerfile for run {} (type={}, cloud-run):\n{}", runId, type, generatedDockerfile);
            Files.writeString(workspace.resolve("Dockerfile"), generatedDockerfile);

            // Phase 2: Build Docker image — store Dockerfile in session for diagnostics
            final String dockerfileContent = generatedDockerfile;
            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.composeProjectName(), s.projectType(), s.previewMode(), s.databaseType(),
                    PreviewStatus.BUILDING, s.url(), 0, null,
                    s.startedAt(), s.expiresAt(), null, null, null, dockerfileContent));
            broadcaster.emit(runId, new RunEvent("PREVIEW_BUILDING", runId, null,
                    "Building Docker image...", Instant.now()));

            var buildResult = artifactRegistryClient.buildImage(workspace, "preview-" + shortId);
            imageUri = buildResult.imageTag();
            // Store build output in session for copilot/frontend diagnostics on failure
            final String buildOutput = buildResult.buildLog() != null
                    ? buildResult.buildLog().substring(Math.max(0, buildResult.buildLog().length() - 8000))
                    : null;
            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.composeProjectName(), s.projectType(), s.previewMode(), s.databaseType(),
                    s.status(), s.url(), s.hostPort(), s.errorMessage(),
                    s.startedAt(), s.expiresAt(), s.cloudRunServiceName(), s.imageUri(),
                    buildOutput, s.dockerfile()));
            log.info("Preview image built for run {}: {}", runId, imageUri);

            // Phase 3: Push image to Artifact Registry
            broadcaster.emit(runId, new RunEvent("PREVIEW_BUILDING", runId, null,
                    "Pushing image to registry...", Instant.now()));
            artifactRegistryClient.pushImage(imageUri);

            // Phase 4: Deploy to Cloud Run (preserve buildOutput/dockerfile in session)
            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.composeProjectName(), s.projectType(), s.previewMode(), s.databaseType(),
                    PreviewStatus.STARTING, s.url(), 0, null,
                    s.startedAt(), s.expiresAt(), null, null,
                    s.buildOutput(), s.dockerfile()));
            broadcaster.emit(runId, new RunEvent("PREVIEW_STARTING", runId, null,
                    "Deploying to Cloud Run...", Instant.now()));

            // Cloud Run always uses port 8080 — generateForCloudRun() normalizes all ports to 8080
            CloudRunClient.DeployResult deployResult = cloudRunClient.deployPreview(
                    serviceName, imageUri, "1", "512Mi", envVars, 8080);

            String previewUrl = deployResult.serviceUrl();
            if (previewUrl == null || previewUrl.isBlank()) {
                fail(runId, "Cloud Run deploy succeeded but returned no service URL.", 0);
                cleanupCloudRun(serviceName, imageUri);
                return;
            }

            // Phase 5: Health check against Cloud Run URL
            broadcaster.emit(runId, new RunEvent("PREVIEW_STARTING", runId, null,
                    "Waiting for Cloud Run service to be ready...", Instant.now()));

            boolean healthy = cloudRunClient.healthCheck(previewUrl);
            if (!healthy) {
                // Fetch Cloud Run logs to include in the error for diagnostics
                String crLogs = getCloudRunLogs(serviceName, 30);
                String errorDetail = "Cloud Run health check failed for " + previewUrl;
                if (crLogs != null && !crLogs.isBlank()
                        && !crLogs.startsWith("No logs") && !crLogs.startsWith("Failed to fetch")) {
                    errorDetail += "\n\nCloud Run logs:\n" + crLogs;
                }
                fail(runId, errorDetail, 0);
                cleanupCloudRun(serviceName, imageUri);
                return;
            }

            // Success!
            if (!active.containsKey(runId)) {
                // Was stopped while starting — clean up Cloud Run
                cleanupCloudRun(serviceName, imageUri);
                return;
            }

            final String finalUrl = previewUrl;
            final String finalServiceName = serviceName;
            final String finalImageUri = imageUri;
            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.composeProjectName(), s.projectType(), s.previewMode(), s.databaseType(),
                    PreviewStatus.RUNNING, finalUrl, 0, null,
                    s.startedAt(), s.expiresAt(), finalServiceName, finalImageUri));
            broadcaster.emit(runId, new RunEvent("PREVIEW_READY", runId, null,
                    previewUrl, Instant.now()));

            // Notify test execution pipeline
            if (testPreviewCoordinator != null) {
                try {
                    testPreviewCoordinator.onPreviewReady(runId, previewUrl);
                } catch (Exception e) {
                    log.warn("Failed to notify test coordinator for run {}: {}", runId, e.getMessage());
                }
            }

            Timer.builder(PREVIEW_DURATION).register(meterRegistry)
                    .record(Duration.between(previewStart, Instant.now()));
            log.info("Cloud Run preview ready for run {} at {}", runId, previewUrl);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // Store Cloud Run metadata in session so fail() can clean up (preserve buildOutput/dockerfile)
            final String failServiceName = serviceName;
            final String failImageUri = imageUri;
            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.composeProjectName(), s.projectType(), s.previewMode(), s.databaseType(),
                    s.status(), s.url(), s.hostPort(), s.errorMessage(),
                    s.startedAt(), s.expiresAt(), failServiceName, failImageUri,
                    s.buildOutput(), s.dockerfile()));
            fail(runId, "Cloud Run preview failed: " + e.getMessage(), 0);
        } catch (Exception e) {
            final String failServiceName = serviceName;
            final String failImageUri = imageUri;
            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.composeProjectName(), s.projectType(), s.previewMode(), s.databaseType(),
                    s.status(), s.url(), s.hostPort(), s.errorMessage(),
                    s.startedAt(), s.expiresAt(), failServiceName, failImageUri,
                    s.buildOutput(), s.dockerfile()));
            fail(runId, "Unexpected error in Cloud Run preview: " + e.getMessage(), 0);
            log.error("Cloud Run preview build/start failed for run {}", runId, e);
        }
    }

    private void cleanupCloudRun(String serviceName, String imageUri) {
        if (cloudRunClient == null) return;
        try {
            if (serviceName != null) {
                cloudRunClient.deleteService(serviceName);
            }
        } catch (Exception e) {
            log.warn("Failed to cleanup Cloud Run service {}: {}", serviceName, e.getMessage());
        }
        try {
            if (imageUri != null) {
                cloudRunClient.deleteImage(imageUri);
            }
        } catch (Exception e) {
            log.warn("Failed to cleanup image {}: {}", imageUri, e.getMessage());
        }
    }

    // ── Private: build and start with Docker Compose ────────────────────────

    private void buildAndStart(String runId, Path workspace, PreviewProjectType type,
                               PreviewMode mode, String projectName, Map<String, String> envVars) {
        Instant deadline = Instant.now().plusSeconds(props.getTotalStartupTimeoutSeconds());
        Instant previewStart = Instant.now();
        int hostPort = 0;

        try {
            // Phase 1: Detect database
            checkDeadline(deadline, "database detection");
            broadcaster.emit(runId, new RunEvent("PREVIEW_ANALYZING", runId, null,
                    "Detecting project dependencies...", Instant.now()));

            DatabaseType dbType = databaseDetector.detect(workspace);
            if (dbType != null) {
                log.info("Preview for run {}: detected database {}", runId, dbType);
                updateSession(runId, s -> new PreviewSession(
                        s.runId(), s.composeProjectName(), s.projectType(), s.previewMode(), dbType,
                        PreviewStatus.ANALYZING, s.url(), s.hostPort(), null,
                        s.startedAt(), s.expiresAt()));
            }

            // Phase 2: Generate Dockerfile (standard, no Cloud Run port patching)
            checkDeadline(deadline, "Dockerfile generation");
            Files.writeString(workspace.resolve(".dockerignore"), dockerfileGen.dockerIgnore());
            // Backup user's docker-compose/Dockerfile if they exist (our generated ones take precedence)
            backupIfExists(workspace.resolve("docker-compose.yml"), workspace.resolve("docker-compose.yml.user"));
            backupIfExists(workspace.resolve("docker-compose.yaml"), workspace.resolve("docker-compose.yaml.user"));
            String generatedDockerfile = dockerfileGen.generate(type, workspace, mode);
            log.info("Preview Dockerfile for run {} (type={}):\n{}", runId, type, generatedDockerfile);
            Files.writeString(workspace.resolve("Dockerfile"), generatedDockerfile);

            // Phase 3: Allocate port + generate docker-compose.yml
            checkDeadline(deadline, "compose file generation");
            hostPort = portPool.allocate(runId);
            Path composeFile = composeFileGen.generate(workspace, type, mode, dbType, envVars, hostPort);
            log.info("Preview compose file for run {} at port {}", runId, hostPort);

            // Phase 4: docker compose up --build (build + start containers)
            Instant buildStart = Instant.now();
            final int buildPort = hostPort;
            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.composeProjectName(), s.projectType(), s.previewMode(), s.databaseType(),
                    PreviewStatus.BUILDING, s.url(), buildPort, null,
                    s.startedAt(), s.expiresAt()));
            broadcaster.emit(runId, new RunEvent("PREVIEW_BUILDING", runId, null,
                    "Building & starting containers...", Instant.now()));

            long remainingSeconds = Duration.between(Instant.now(), deadline).toSeconds();
            String upOutput = composeExecutor.up(composeFile, projectName, Duration.ofSeconds(Math.max(30, remainingSeconds)));
            log.info("Preview compose up output for run {}: {}", runId, upOutput);

            Timer.builder(PREVIEW_BUILD_DURATION).register(meterRegistry)
                    .record(Duration.between(buildStart, Instant.now()));

            // Verify containers are actually running after compose up
            if (!composeExecutor.isRunning(projectName)) {
                String upLogs = composeExecutor.logs(projectName, null, 30);
                String errMsg = "Containers failed to start after docker compose up.";
                if (!upLogs.isBlank()) errMsg += "\n" + upLogs;
                else if (!upOutput.isBlank()) errMsg += "\nCompose output:\n" + upOutput;
                fail(runId, errMsg, hostPort);
                return;
            }

            // Wait for DB service to become healthy before checking the app
            if (dbType != null) {
                checkDeadline(deadline, "database readiness");
                try {
                    composeExecutor.waitForHealthy(projectName, "db", Duration.ofSeconds(30));
                } catch (PreviewException e) {
                    log.warn("DB health check failed for run {}: {} — continuing to app health check", runId, e.getMessage());
                }
            }

            // Brief pause to let Docker port mapping stabilize after compose up
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            // Quick TCP reachability check — if the port isn't reachable at all, fail fast with a clear message
            if (!isPortReachable(hostPort)) {
                log.warn("Port {} is not reachable after compose up for run {} — checking if port is blocked", hostPort, runId);
                // Wait a bit more and retry in case of slow Docker port mapping
                try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (!isPortReachable(hostPort)) {
                    String containerLogs = composeExecutor.logs(projectName, null, 15);
                    fail(runId, "Port " + hostPort + " is not reachable on the host. "
                        + "This port may be blocked by a firewall or already in use by another process. "
                        + "Container logs:\n" + containerLogs, hostPort);
                    return;
                }
            }

            // Phase 5: Build preview URL
            // Priority: Caddy proxy → Spring Boot proxy (PreviewProxyFilter) → fallback host → localhost
            String previewUrl;
            if (caddyProxy != null) {
                try {
                    caddyProxy.addRoute(runId, hostPort);
                } catch (PreviewException e) {
                    log.error("Caddy route creation failed for preview {}: {}", runId, e.getMessage());
                    fail(runId, "Proxy route creation failed: " + e.getMessage(), hostPort);
                    return;
                }
                previewUrl = caddyProxy.buildUrl(runId);
            } else if (props.getProxy().isEnabled()) {
                // Spring Boot preview proxy mode — PreviewProxyFilter handles preview-*.executespec.ai
                // Traffic flows: LB → Spring Boot :8080 → PreviewProxyFilter → localhost:{port}
                String shortId = runId.length() > 8 ? runId.substring(0, 8) : runId;
                previewUrl = "https://preview-" + shortId + "." + props.getPreviewDomain();
            } else {
                // Fallback: direct port access (local dev only).
                String fallbackHost = props.getFallbackHost();
                if (fallbackHost != null && !fallbackHost.isBlank()) {
                    previewUrl = fallbackHost + ":" + hostPort;
                } else {
                    previewUrl = "http://localhost:" + hostPort;
                }
            }

            // Phase 6: Health check
            checkDeadline(deadline, "health check");
            final int allocatedPort = hostPort;
            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.composeProjectName(), s.projectType(), s.previewMode(), s.databaseType(),
                    PreviewStatus.STARTING, previewUrl, allocatedPort, null,
                    s.startedAt(), s.expiresAt()));
            broadcaster.emit(runId, new RunEvent("PREVIEW_STARTING", runId, null,
                    "Waiting for application to be ready...", Instant.now()));

            // Health check directly against localhost:{port} (more reliable than going through Caddy)
            boolean healthy = healthCheck("http://localhost:" + hostPort, deadline);
            if (!healthy) {
                // Include container logs in the error message so the user can diagnose
                String containerLogs = composeExecutor.logs(projectName, null, 30);
                boolean running = composeExecutor.isRunning(projectName);
                log.warn("Health check failed for run {}. Containers running: {}. Logs:\n{}", runId, running, containerLogs);

                var errorMsg = new StringBuilder("Application health check failed on http://localhost:" + hostPort);
                errorMsg.append("\nContainers running: ").append(running);
                if (!containerLogs.isBlank()) {
                    errorMsg.append("\nProcess output (").append(containerLogs.lines().count()).append(" lines):\n").append(containerLogs);
                } else {
                    errorMsg.append("\nNo container logs available — containers may have crashed on startup.");
                }
                fail(runId, errorMsg.toString(), hostPort);
                return;
            }

            // Success!
            if (!active.containsKey(runId)) return; // was stopped while starting

            final int finalPort = hostPort;
            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.composeProjectName(), s.projectType(), s.previewMode(), s.databaseType(),
                    PreviewStatus.RUNNING, previewUrl, finalPort, null,
                    s.startedAt(), s.expiresAt()));
            broadcaster.emit(runId, new RunEvent("PREVIEW_READY", runId, null,
                    previewUrl, Instant.now()));

            // Notify test execution pipeline that preview is ready
            if (testPreviewCoordinator != null) {
                try {
                    testPreviewCoordinator.onPreviewReady(runId, previewUrl);
                } catch (Exception e) {
                    log.warn("Failed to notify test coordinator for run {}: {}", runId, e.getMessage());
                }
            }

            Timer.builder(PREVIEW_DURATION).register(meterRegistry)
                    .record(Duration.between(previewStart, Instant.now()));
            log.info("Preview ready for run {} at {} (port={}, db={})",
                    runId, previewUrl, hostPort, dbType);

        } catch (DeadlineExceededException e) {
            Counter.builder(PREVIEW_FAILURES).tag("reason", "timeout")
                    .register(meterRegistry).increment();
            Metrics.ACTIVE_PREVIEWS.decrementAndGet();
            releaseGlobalPreviewSlot(runId);
            cleanupOnFailure(runId, projectName, hostPort);
            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.composeProjectName(), s.projectType(), s.previewMode(), s.databaseType(),
                    PreviewStatus.TIMED_OUT, s.url(), s.hostPort(),
                    "Preview startup timed out after " + props.getTotalStartupTimeoutSeconds()
                            + "s during: " + e.getPhase(),
                    s.startedAt(), s.expiresAt()));
            broadcaster.emit(runId, new RunEvent("PREVIEW_TIMED_OUT", runId, null,
                    "Timed out during: " + e.getPhase(), Instant.now()));
            log.warn("Preview timed out for run {} during: {}", runId, e.getPhase());

        } catch (IOException e) {
            fail(runId, "Build failed: " + e.getMessage(), hostPort);
        } catch (PreviewException e) {
            fail(runId, e.getMessage(), hostPort);
        } catch (Exception e) {
            fail(runId, "Unexpected error: " + e.getMessage(), hostPort);
            log.error("Preview build/start failed for run {}", runId, e);
        }
    }

    // ── Health check ────────────────────────────────────────────────────────

    private boolean healthCheck(String url, Instant deadline) {
        String lastError = null;
        for (int attempt = 1; attempt <= HEALTH_CHECK_RETRIES; attempt++) {
            if (Instant.now().isAfter(deadline)) {
                log.warn("Health check deadline exceeded after {} attempts (last error: {})", attempt - 1, lastError);
                return false;
            }

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build();
                HttpResponse<Void> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.discarding());

                if (response.statusCode() < 500) {
                    log.info("Health check passed for {} (status={}, attempt {})", url, response.statusCode(), attempt);
                    return true;
                }
                lastError = "HTTP " + response.statusCode();
                log.info("Health check attempt {}/{} for {}: status={}", attempt, HEALTH_CHECK_RETRIES, url, response.statusCode());
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                // Log every 5th attempt at INFO to avoid flooding but ensure visibility
                if (attempt % 5 == 1 || attempt == HEALTH_CHECK_RETRIES) {
                    log.info("Health check attempt {}/{} for {}: {}", attempt, HEALTH_CHECK_RETRIES, url, lastError);
                } else {
                    log.debug("Health check attempt {}/{} for {}: {}", attempt, HEALTH_CHECK_RETRIES, url, lastError);
                }
            }

            try {
                Thread.sleep(HEALTH_CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.warn("Health check failed after {} attempts for {} (last error: {})", HEALTH_CHECK_RETRIES, url, lastError);
        return false;
    }

    /**
     * Quick TCP-level reachability check — verifies the port is accepting connections.
     * Does NOT check HTTP status, just that something is listening.
     */
    private boolean isPortReachable(int port) {
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("localhost", port), 3000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Rename a file if it exists — used to backup user-generated docker-compose/Dockerfile
     * before we overwrite with our generated ones.
     */
    private void backupIfExists(Path source, Path dest) {
        try {
            if (Files.exists(source)) {
                Files.move(source, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.info("Backed up user file: {} -> {}", source.getFileName(), dest.getFileName());
            }
        } catch (IOException e) {
            log.warn("Failed to backup {}: {}", source, e.getMessage());
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void releaseGlobalPreviewSlot(String runId) {
        stateStore.findRun(runId).ifPresent(run -> {
            if (run.getCreatedByUserId() != null) {
                globalPreviewLimiter.release(run.getCreatedByUserId().toString());
            }
        });
    }

    private void checkDeadline(Instant deadline, String phase) {
        if (Instant.now().isAfter(deadline)) {
            throw new DeadlineExceededException(phase);
        }
    }

    private void fail(String runId, String message, int hostPort) {
        Counter.builder(PREVIEW_FAILURES).tag("reason", classifyFailureReason(message))
                .register(meterRegistry).increment();
        Metrics.ACTIVE_PREVIEWS.decrementAndGet();
        releaseGlobalPreviewSlot(runId);

        PreviewSession session = active.get(runId);
        if (session != null) {
            if (isVmMode()) {
                // VM mode: no local cleanup needed, VM state is managed by PreviewDeploymentService
            } else if (session.cloudRunServiceName() != null) {
                cleanupCloudRun(session.cloudRunServiceName(), session.imageUri());
            } else {
                cleanupOnFailure(runId, session.composeProjectName(), hostPort);
            }
        }

        updateSession(runId, s -> new PreviewSession(
                s.runId(), s.composeProjectName(), s.projectType(), s.previewMode(), s.databaseType(),
                PreviewStatus.FAILED, s.url(), s.hostPort(), message,
                s.startedAt(), s.expiresAt(), s.cloudRunServiceName(), s.imageUri(),
                s.buildOutput(), s.dockerfile()));
        broadcaster.emit(runId, new RunEvent("PREVIEW_FAILED", runId, null, message, Instant.now()));
        // Keep FAILED session in active map so clients can see error via GET /preview/status.
        // TTL cleanup (cleanupExpired) will remove it after expiry. start() clears FAILED on retry.
        log.warn("Preview failed for run {}: {}", runId, message);
    }

    private void cleanupCompose(PreviewSession session) {
        // Tear down Docker Compose project
        if (session.composeProjectName() != null) {
            try {
                composeExecutor.down(session.composeProjectName(), true);
            } catch (Exception e) {
                log.warn("Failed to stop compose project {} for run {}: {}",
                        session.composeProjectName(), session.runId(), e.getMessage());
            }
        }

        // Remove Caddy route
        if (caddyProxy != null) {
            caddyProxy.removeRoute(session.runId());
        }

        // Release port
        portPool.release(session.runId());
    }

    private void cleanupOnFailure(String runId, String projectName, int hostPort) {
        if (projectName != null) {
            try {
                composeExecutor.down(projectName, true);
            } catch (Exception e) {
                log.debug("Cleanup: failed to stop compose project {}: {}", projectName, e.getMessage());
            }
        }
        if (caddyProxy != null) {
            caddyProxy.removeRoute(runId);
        }
        if (hostPort > 0) {
            portPool.release(runId);
        }
    }

    private static String classifyFailureReason(String message) {
        if (message == null) return "unknown";
        String lower = message.toLowerCase();
        if (lower.contains("health check")) return "health_check";
        if (lower.contains("build") || lower.contains("docker")) return "build";
        if (lower.contains("compose")) return "compose";
        return "other";
    }

    private void updateSession(String runId, java.util.function.UnaryOperator<PreviewSession> updater) {
        active.computeIfPresent(runId, (k, old) -> updater.apply(old));
    }

    private String composeProjectName(String runId) {
        String safe = runId.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        return COMPOSE_PREFIX + safe.substring(0, Math.min(12, safe.length()));
    }

    private int getMaxConcurrent(String tier) {
        return switch (tier) {
            case "ENTERPRISE" -> props.getConcurrency().getEnterpriseMax();
            case "MAX" -> props.getConcurrency().getEnterpriseMax();
            case "PRO" -> props.getConcurrency().getProMax();
            default -> props.getConcurrency().getFreeMax();
        };
    }

    // ── Scheduled cleanup ───────────────────────────────────────────────────

    @Scheduled(fixedRate = 60000)
    void cleanupExpired() {
        active.values().stream()
                .filter(PreviewSession::isExpired)
                .map(PreviewSession::runId)
                .toList()
                .forEach(runId -> {
                    log.info("Preview expired for run {}, stopping", runId);
                    stop(runId);
                    lastEnvVars.remove(runId);
                });
    }

    /**
     * Orphan cleanup: find Docker Compose projects and Cloud Run services with our prefix
     * that are not in our active map (e.g., from a previous server instance).
     */
    @Scheduled(fixedRate = 1800000) // every 30 minutes
    void cleanupOrphans() {
        // Docker Compose orphans
        try {
            List<String> projects = composeExecutor.listProjects(COMPOSE_PREFIX);
            Set<String> activeProjects = ConcurrentHashMap.newKeySet();
            active.values().forEach(s -> activeProjects.add(s.composeProjectName()));

            for (String project : projects) {
                if (!activeProjects.contains(project)) {
                    log.info("Tearing down orphaned preview compose project: {}", project);
                    composeExecutor.down(project, true);
                }
            }
        } catch (Exception e) {
            log.warn("Docker orphan cleanup failed: {}", e.getMessage());
        }

        // Cloud Run orphans
        if (cloudRunClient != null) {
            try {
                List<String> services = cloudRunClient.listPreviewServices();
                Set<String> activeServices = ConcurrentHashMap.newKeySet();
                active.values().stream()
                        .filter(s -> s.cloudRunServiceName() != null)
                        .forEach(s -> activeServices.add(s.cloudRunServiceName()));

                for (String service : services) {
                    if (!activeServices.contains(service)) {
                        log.info("Deleting orphaned Cloud Run preview service: {}", service);
                        cloudRunClient.deleteService(service);
                    }
                }
            } catch (Exception e) {
                log.warn("Cloud Run orphan cleanup failed: {}", e.getMessage());
            }
        }
    }

    @PreDestroy
    void shutdownAll() {
        int count = active.size();
        if (count > 0) {
            log.info("Shutting down {} active previews", count);
            active.keySet().forEach(this::stop);
        }
    }

    // ── Analysis result ─────────────────────────────────────────────────────

    public record PreviewAnalysis(
            DatabaseType databaseType,
            List<EnvVarDetector.DetectedEnvVar> requiredEnvVars
    ) {}
}
