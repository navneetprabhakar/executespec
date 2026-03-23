package com.navneet.executespec.deployment.preview;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
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

/**
 * Preview service — builds and runs ephemeral dev servers using Docker Compose
 * on the local machine. Caddy reverse proxy provides HTTPS routing via wildcard
 * domain (*.preview.executespec.ai).
 *
 * <p>Lifecycle: IDLE → ANALYZING → BUILDING → STARTING → RUNNING → STOPPED.
 * Each preview auto-expires after the configured TTL (default 10 minutes).
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

    /** Optional — only present when preview.proxy.enabled=true (VM with Caddy). */
    @Autowired(required = false)
    private CaddyProxyManager caddyProxy;

    /** runId → session */
    private final ConcurrentHashMap<String, PreviewSession> active = new ConcurrentHashMap<>();

    private static final Set<String> COMPLETE_STATUSES = Set.of("COMPLETE", "PARTIAL");
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
                          MeterRegistry meterRegistry) {
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
        if (!COMPLETE_STATUSES.contains(run.getStatus())) {
            throw new PreviewException("Run must be complete to preview (current status: " + run.getStatus() + ")");
        }

        // Guard: idempotent — return existing session if still active.
        // If FAILED or TIMED_OUT, remove it so the user can retry.
        // If ANALYZING/BUILDING/STARTING/RUNNING, return as-is (no restart).
        PreviewSession existing = active.get(runId);
        if (existing != null) {
            if (existing.status() == PreviewStatus.FAILED
                    || existing.status() == PreviewStatus.TIMED_OUT) {
                // Clean up stale failed session so retry starts fresh
                cleanupCompose(existing);
                active.remove(runId);
                log.info("Cleared failed preview session for run {} — allowing retry", runId);
            } else {
                // ANALYZING, BUILDING, STARTING, RUNNING — return current state, don't restart
                return existing;
            }
        }

        // Guard: concurrent preview limit
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
        Path workspace = restoreService.ensureWorkspaceAvailable(runId);
        PreviewProjectType type = dockerfileGen.detect(workspace);
        if (type == null) {
            throw new PreviewException("Unsupported project type — could not detect build system.");
        }

        // Create initial session
        String projectName = composeProjectName(runId);
        Duration ttl = Duration.ofMinutes(props.getPreviewTtlMinutes());

        PreviewSession session = new PreviewSession(
                runId, projectName, type, null,
                PreviewStatus.ANALYZING, null, 0, null,
                Instant.now(), Instant.now().plus(ttl)
        );
        active.put(runId, session);

        // Launch async build + start on a virtual thread
        Metrics.ACTIVE_PREVIEWS.incrementAndGet();
        Counter.builder(PREVIEW_STARTS).register(meterRegistry).increment();
        Map<String, String> safeEnvVars = envVars != null ? Map.copyOf(envVars) : Map.of();
        Thread.startVirtualThread(() -> buildAndStart(runId, workspace, type, projectName, safeEnvVars));

        log.info("Preview started for run {} (type={}, project={}, ttl={}min)",
                runId, type, projectName, ttl.toMinutes());
        return session;
    }

    /** Backward-compatible start without env vars. */
    public PreviewSession start(String runId, AppUser user) {
        return start(runId, user, null);
    }

    /**
     * Stop a running preview. Tears down Docker Compose project, removes Caddy route,
     * releases port. Idempotent.
     */
    public void stop(String runId) {
        PreviewSession session = active.remove(runId);
        if (session == null) return;
        Metrics.ACTIVE_PREVIEWS.decrementAndGet();

        cleanupCompose(session);

        broadcaster.emit(runId, new RunEvent("PREVIEW_STOPPED", runId, null, null, Instant.now()));
        log.info("Preview stopped for run {} (project={})", runId, session.composeProjectName());
    }

    /**
     * Get the current preview session for a run.
     * Returns null if no preview is active (IDLE).
     */
    public PreviewSession getStatus(String runId) {
        return active.get(runId);
    }

    /**
     * Get logs from Docker Compose for a running preview.
     */
    public String getLogs(String runId, int tailLines, String filter) {
        PreviewSession session = active.get(runId);
        if (session == null) return "";
        if (session.composeProjectName() == null) return "Preview is still initializing...";

        String service = null;
        if (filter != null && !filter.isBlank() && !"all".equalsIgnoreCase(filter)) {
            service = filter; // "app", "db", etc.
        }

        String logs = composeExecutor.logs(session.composeProjectName(), service, tailLines > 0 ? tailLines : 100);
        return logs.isEmpty() ? "No logs available yet." : logs;
    }

    // ── Private: build and start with Docker Compose ────────────────────────

    private void buildAndStart(String runId, Path workspace, PreviewProjectType type,
                               String projectName, Map<String, String> envVars) {
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
                        s.runId(), s.composeProjectName(), s.projectType(), dbType,
                        PreviewStatus.ANALYZING, s.url(), s.hostPort(), null,
                        s.startedAt(), s.expiresAt()));
            }

            // Phase 2: Generate Dockerfile (standard, no Cloud Run port patching)
            checkDeadline(deadline, "Dockerfile generation");
            Files.writeString(workspace.resolve(".dockerignore"), dockerfileGen.dockerIgnore());
            // Backup user's docker-compose/Dockerfile if they exist (our generated ones take precedence)
            backupIfExists(workspace.resolve("docker-compose.yml"), workspace.resolve("docker-compose.yml.user"));
            backupIfExists(workspace.resolve("docker-compose.yaml"), workspace.resolve("docker-compose.yaml.user"));
            String generatedDockerfile = dockerfileGen.generate(type, workspace);
            log.info("Preview Dockerfile for run {} (type={}):\n{}", runId, type, generatedDockerfile);
            Files.writeString(workspace.resolve("Dockerfile"), generatedDockerfile);

            // Phase 3: Allocate port + generate docker-compose.yml
            checkDeadline(deadline, "compose file generation");
            hostPort = portPool.allocate(runId);
            Path composeFile = composeFileGen.generate(workspace, type, dbType, envVars, hostPort);
            log.info("Preview compose file for run {} at port {}", runId, hostPort);

            // Phase 4: docker compose up --build (build + start containers)
            Instant buildStart = Instant.now();
            final int buildPort = hostPort;
            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.composeProjectName(), s.projectType(), s.databaseType(),
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
                caddyProxy.addRoute(runId, hostPort);
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
                    s.runId(), s.composeProjectName(), s.projectType(), s.databaseType(),
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
                    s.runId(), s.composeProjectName(), s.projectType(), s.databaseType(),
                    PreviewStatus.RUNNING, previewUrl, finalPort, null,
                    s.startedAt(), s.expiresAt()));
            broadcaster.emit(runId, new RunEvent("PREVIEW_READY", runId, null,
                    previewUrl, Instant.now()));

            Timer.builder(PREVIEW_DURATION).register(meterRegistry)
                    .record(Duration.between(previewStart, Instant.now()));
            log.info("Preview ready for run {} at {} (port={}, db={})",
                    runId, previewUrl, hostPort, dbType);

        } catch (DeadlineExceededException e) {
            Counter.builder(PREVIEW_FAILURES).tag("reason", "timeout")
                    .register(meterRegistry).increment();
            cleanupOnFailure(runId, projectName, hostPort);
            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.composeProjectName(), s.projectType(), s.databaseType(),
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

    private void checkDeadline(Instant deadline, String phase) {
        if (Instant.now().isAfter(deadline)) {
            throw new DeadlineExceededException(phase);
        }
    }

    private void fail(String runId, String message, int hostPort) {
        Counter.builder(PREVIEW_FAILURES).tag("reason", classifyFailureReason(message))
                .register(meterRegistry).increment();

        PreviewSession session = active.get(runId);
        if (session != null) {
            cleanupOnFailure(runId, session.composeProjectName(), hostPort);
        }

        updateSession(runId, s -> new PreviewSession(
                s.runId(), s.composeProjectName(), s.projectType(), s.databaseType(),
                PreviewStatus.FAILED, s.url(), s.hostPort(), message,
                s.startedAt(), s.expiresAt()));
        broadcaster.emit(runId, new RunEvent("PREVIEW_FAILED", runId, null, message, Instant.now()));
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
                });
    }

    /**
     * Orphan cleanup: find Docker Compose projects with our prefix
     * that are not in our active map (e.g., from a previous server instance).
     */
    @Scheduled(fixedRate = 1800000) // every 30 minutes
    void cleanupOrphans() {
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
            log.warn("Orphan cleanup failed: {}", e.getMessage());
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
