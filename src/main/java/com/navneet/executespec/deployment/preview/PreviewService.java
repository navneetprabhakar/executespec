package com.navneet.executespec.deployment.preview;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class PreviewService {

    private final ComposeExecutor compose;
    private final DockerExecutor docker;
    private final DevDockerfileGenerator dockerfileGen;
    private final ComposeFileGenerator composeFileGen;
    private final DatabaseDetector databaseDetector;
    private final EnvVarDetector envVarDetector;
    private final PreviewPortPool portPool;
    private final PreviewProperties props;
    private final SseBroadcaster broadcaster;
    private final StateStore stateStore;
    private final AgentProperties agentProps;

    @Autowired(required = false)
    private CaddyProxyManager caddyProxyManager;

    /** runId → session */
    private final ConcurrentHashMap<String, PreviewSession> active = new ConcurrentHashMap<>();

    private static final Set<String> COMPLETE_STATUSES = Set.of("COMPLETE", "PARTIAL");

    public PreviewService(ComposeExecutor compose,
                          DockerExecutor docker,
                          DevDockerfileGenerator dockerfileGen,
                          ComposeFileGenerator composeFileGen,
                          DatabaseDetector databaseDetector,
                          EnvVarDetector envVarDetector,
                          PreviewPortPool portPool,
                          PreviewProperties props,
                          SseBroadcaster broadcaster,
                          StateStore stateStore,
                          AgentProperties agentProps) {
        this.compose = compose;
        this.docker = docker;
        this.dockerfileGen = dockerfileGen;
        this.composeFileGen = composeFileGen;
        this.databaseDetector = databaseDetector;
        this.envVarDetector = envVarDetector;
        this.portPool = portPool;
        this.props = props;
        this.broadcaster = broadcaster;
        this.stateStore = stateStore;
        this.agentProps = agentProps;
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Analyze workspace for database and env var requirements (pre-start step).
     */
    public PreviewAnalysis analyze(String runId) {
        Path workspace = Path.of(agentProps.getWorkspace(), runId);
        DatabaseType dbType = databaseDetector.detect(workspace);
        List<EnvVarDetector.DetectedEnvVar> envVars = envVarDetector.detect(workspace);

        // Filter out DATABASE category vars — they're auto-injected
        List<EnvVarDetector.DetectedEnvVar> userVars = envVars.stream()
                .filter(v -> v.category() != EnvVarDetector.EnvVarCategory.DATABASE)
                .toList();

        return new PreviewAnalysis(dbType, userVars);
    }

    /**
     * Start a preview for a completed run. Returns immediately with ANALYZING status.
     * The actual build+start happens on a virtual thread.
     *
     * @param envVars user-provided environment variables (may be null/empty)
     */
    public PreviewSession start(String runId, AppUser user, Map<String, String> envVars) {
        // Guard: preview enabled
        if (!props.isEnabled()) {
            throw new PreviewException("Preview is disabled");
        }

        // Guard: Docker available
        if (!docker.isDockerAvailable()) {
            throw new PreviewException("Docker is not available on this host");
        }

        // Guard: run must exist and be complete
        PipelineRun run = stateStore.findRun(runId)
                .orElseThrow(() -> new PreviewException("Run not found: " + runId));
        if (!COMPLETE_STATUSES.contains(run.getStatus())) {
            throw new PreviewException("Run must be complete to preview (current status: " + run.getStatus() + ")");
        }

        // Guard: idempotent — return existing session if active
        PreviewSession existing = active.get(runId);
        if (existing != null) {
            return existing;
        }

        // Guard: concurrent preview limit
        long userPreviewCount = active.values().stream()
                .filter(s -> {
                    return stateStore.findRun(s.runId())
                            .map(r -> user.getId().equals(r.getCreatedByUserId()))
                            .orElse(false);
                })
                .count();
        int maxConcurrent = getMaxConcurrent(user.getTier());
        if (userPreviewCount >= maxConcurrent) {
            throw new PreviewException("Max concurrent previews reached (" + maxConcurrent +
                    " for " + user.getTier() + " tier). Stop an existing preview first.");
        }

        // Detect project type
        Path workspace = Path.of(agentProps.getWorkspace(), runId);
        PreviewProjectType type = dockerfileGen.detect(workspace);
        if (type == null) {
            throw new PreviewException("Unsupported project type. Use Deploy to Cloud Run instead.");
        }

        // Allocate port and create initial session
        int port = portPool.allocate(runId);
        String projectName = composeProjectName(runId);
        String url = props.getProxy().isEnabled()
                ? props.getProxy().getBaseUrl() + "/preview/" + runId
                : "http://localhost:" + port;
        Duration ttl = getTtl(user.getTier());

        PreviewSession session = new PreviewSession(
                runId, projectName, type, null,
                PreviewStatus.ANALYZING, port, url, null,
                Instant.now(), Instant.now().plus(ttl)
        );
        active.put(runId, session);

        // Launch async build+start on a virtual thread
        Map<String, String> safeEnvVars = envVars != null ? Map.copyOf(envVars) : Map.of();
        Thread.startVirtualThread(() -> buildAndStart(runId, workspace, type, port, projectName, safeEnvVars));

        log.info("Preview started for run {} (type={}, port={}, ttl={}min)",
                runId, type, port, ttl.toMinutes());
        return session;
    }

    /**
     * Backward-compatible start without env vars.
     */
    public PreviewSession start(String runId, AppUser user) {
        return start(runId, user, null);
    }

    /**
     * Stop a running preview. Tears down compose project. Idempotent.
     */
    public void stop(String runId) {
        PreviewSession session = active.get(runId);
        if (session == null) return;

        // Tear down compose project (removes containers, networks, volumes)
        compose.down(session.composeProjectName(), true);
        portPool.release(session.hostPort());

        if (caddyProxyManager != null) {
            caddyProxyManager.removeRoute(runId);
        }

        active.remove(runId);
        broadcaster.emit(runId, new RunEvent("PREVIEW_STOPPED", runId, null, null, Instant.now()));

        // Cleanup workspace files
        cleanupWorkspaceFiles(runId);

        log.info("Preview stopped for run {}", runId);
    }

    /**
     * Get the current preview session for a run.
     * Returns null if no preview is active (IDLE).
     */
    public PreviewSession getStatus(String runId) {
        return active.get(runId);
    }

    /**
     * Get container logs for a running preview.
     *
     * @param tailLines if <= 0, returns ALL logs from container start
     * @param filter    "backend", "frontend", "db", or "all"
     */
    public String getLogs(String runId, int tailLines, String filter) {
        PreviewSession session = active.get(runId);
        if (session == null) return "";

        String serviceName = null;
        if ("db".equalsIgnoreCase(filter) && session.databaseType() != null) {
            serviceName = "db";
        }
        // For backend/frontend filter on full-stack, we still get app logs and filter by prefix
        String raw = compose.logs(session.composeProjectName(), serviceName, tailLines);

        if (serviceName != null || filter == null || filter.isBlank() || "all".equalsIgnoreCase(filter)) {
            return raw;
        }

        // Filter lines by prefix: [backend] or [frontend]
        String prefix = "[" + filter.toLowerCase() + "] ";
        return raw.lines()
                .filter(line -> line.contains(prefix))
                .map(line -> {
                    int idx = line.indexOf(prefix);
                    return idx >= 0 ? line.substring(idx + prefix.length()) : line;
                })
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    // ── Private: build and start with deadline ───────────────────────────────

    private void buildAndStart(String runId, Path workspace, PreviewProjectType type,
                               int port, String projectName, Map<String, String> envVars) {
        Instant deadline = Instant.now().plusSeconds(props.getTotalStartupTimeoutSeconds());

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
                        PreviewStatus.ANALYZING, s.hostPort(), s.url(), null,
                        s.startedAt(), s.expiresAt()));
            }

            // Phase 2: Write .dockerignore and Dockerfile
            checkDeadline(deadline, "Dockerfile generation");
            Files.writeString(workspace.resolve(".dockerignore"), dockerfileGen.dockerIgnore());
            Files.writeString(workspace.resolve("Dockerfile"), dockerfileGen.generate(type, workspace));

            // Phase 3: Generate docker-compose.yml
            checkDeadline(deadline, "compose file generation");
            Path composeFile = composeFileGen.generate(workspace, type, dbType, envVars, port);

            // Phase 4: Docker Compose up (build + start)
            checkDeadline(deadline, "container build");
            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.composeProjectName(), s.projectType(), s.databaseType(),
                    PreviewStatus.BUILDING, s.hostPort(), s.url(), null,
                    s.startedAt(), s.expiresAt()));
            broadcaster.emit(runId, new RunEvent("PREVIEW_BUILDING", runId, null,
                    "Building " + type + " container" + (dbType != null ? " + " + dbType + " database" : "") + "...",
                    Instant.now()));

            Duration buildTimeout = Duration.between(Instant.now(), deadline);
            compose.up(composeFile, projectName, buildTimeout);

            log.info("Compose up complete for run {} (project={})", runId, projectName);

            // Phase 5: Wait for DB healthy (if applicable)
            if (dbType != null) {
                checkDeadline(deadline, "database readiness");
                updateSession(runId, s -> new PreviewSession(
                        s.runId(), s.composeProjectName(), s.projectType(), s.databaseType(),
                        PreviewStatus.DB_STARTING, s.hostPort(), s.url(), null,
                        s.startedAt(), s.expiresAt()));
                broadcaster.emit(runId, new RunEvent("PREVIEW_DB_STARTING", runId, null,
                        "Waiting for " + dbType + " database to be ready...", Instant.now()));

                Duration dbTimeout = Duration.ofSeconds(Math.min(60,
                        Duration.between(Instant.now(), deadline).toSeconds()));
                compose.waitForHealthy(projectName, "db", dbTimeout);

                log.info("Database {} is healthy for run {}", dbType, runId);
            }

            // Phase 6: Wait for app readiness (HTTP poll)
            checkDeadline(deadline, "application readiness");
            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.composeProjectName(), s.projectType(), s.databaseType(),
                    PreviewStatus.STARTING, s.hostPort(), s.url(), null,
                    s.startedAt(), s.expiresAt()));
            broadcaster.emit(runId, new RunEvent("PREVIEW_STARTING", runId, null,
                    "Waiting for application to be ready...", Instant.now()));

            Duration appTimeout = Duration.between(Instant.now(), deadline);
            boolean ready = waitForReadiness(port, (int) appTimeout.toSeconds());

            if (!ready) {
                String logs = compose.logs(projectName, "app", 30);
                fail(runId, "Application startup timed out\n" + logs, projectName);
                return;
            }

            // Success!
            PreviewSession session = active.get(runId);
            if (session == null) return; // was stopped while starting

            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.composeProjectName(), s.projectType(), s.databaseType(),
                    PreviewStatus.RUNNING, s.hostPort(), s.url(), null,
                    s.startedAt(), s.expiresAt()));

            if (caddyProxyManager != null) {
                caddyProxyManager.addRoute(runId, port);
            }

            String previewUrl = active.get(runId) != null ? active.get(runId).url() : "http://localhost:" + port;
            broadcaster.emit(runId, new RunEvent("PREVIEW_READY", runId, null, previewUrl, Instant.now()));

            log.info("Preview ready for run {} at {} (db={})", runId, previewUrl, dbType);

        } catch (DeadlineExceededException e) {
            compose.down(projectName, true);
            portPool.release(port);
            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.composeProjectName(), s.projectType(), s.databaseType(),
                    PreviewStatus.TIMED_OUT, s.hostPort(), s.url(),
                    "Preview startup timed out after " + props.getTotalStartupTimeoutSeconds()
                            + "s during: " + e.getPhase(),
                    s.startedAt(), s.expiresAt()));
            broadcaster.emit(runId, new RunEvent("PREVIEW_TIMED_OUT", runId, null,
                    "Timed out during: " + e.getPhase(), Instant.now()));
            log.warn("Preview timed out for run {} during: {}", runId, e.getPhase());

        } catch (IOException e) {
            fail(runId, "Failed to write Dockerfile: " + e.getMessage(), projectName);
        } catch (PreviewException e) {
            fail(runId, e.getMessage(), projectName);
        } catch (Exception e) {
            fail(runId, "Unexpected error: " + e.getMessage(), projectName);
            log.error("Preview build/start failed for run {}", runId, e);
        }
    }

    private void checkDeadline(Instant deadline, String phase) {
        if (Instant.now().isAfter(deadline)) {
            throw new DeadlineExceededException(phase);
        }
    }

    private void fail(String runId, String message, String projectName) {
        // Clean up compose project on failure
        try {
            compose.down(projectName, true);
        } catch (Exception e) {
            log.debug("Compose down failed during cleanup for {}: {}", runId, e.getMessage());
        }

        updateSession(runId, s -> new PreviewSession(
                s.runId(), s.composeProjectName(), s.projectType(), s.databaseType(),
                PreviewStatus.FAILED, s.hostPort(), s.url(), message,
                s.startedAt(), s.expiresAt()));
        broadcaster.emit(runId, new RunEvent("PREVIEW_FAILED", runId, null, message, Instant.now()));
        log.warn("Preview failed for run {}: {}", runId, message);
    }

    private void updateSession(String runId, java.util.function.UnaryOperator<PreviewSession> updater) {
        active.computeIfPresent(runId, (k, old) -> updater.apply(old));
    }

    private boolean waitForReadiness(int port, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (System.currentTimeMillis() < deadline) {
            try {
                var conn = (HttpURLConnection) URI.create("http://localhost:" + port).toURL().openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code >= 200 && code < 500) {
                    return true;
                }
            } catch (Exception ignored) {
                // Server not ready yet
            }
            try {
                Thread.sleep(props.getReadinessPollIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Compose project name derived from runId. Must be lowercase alphanumeric + hyphens.
     */
    private String composeProjectName(String runId) {
        String safe = runId.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        return "es-preview-" + safe.substring(0, Math.min(12, safe.length()));
    }

    private Duration getTtl(String tier) {
        return switch (tier) {
            case "ENTERPRISE" -> Duration.ofMinutes(props.getTtl().getEnterpriseMinutes());
            case "PRO" -> Duration.ofMinutes(props.getTtl().getProMinutes());
            default -> Duration.ofMinutes(props.getTtl().getFreeMinutes());
        };
    }

    private int getMaxConcurrent(String tier) {
        return switch (tier) {
            case "ENTERPRISE" -> props.getConcurrency().getEnterpriseMax();
            case "PRO" -> props.getConcurrency().getProMax();
            default -> props.getConcurrency().getFreeMax();
        };
    }

    private void cleanupWorkspaceFiles(String runId) {
        try {
            Path workspace = Path.of(agentProps.getWorkspace(), runId);
            Files.deleteIfExists(workspace.resolve("Dockerfile"));
            Files.deleteIfExists(workspace.resolve(".dockerignore"));
            Files.deleteIfExists(workspace.resolve("docker-compose.yml"));
        } catch (IOException e) {
            log.debug("Failed to cleanup preview files for run {}: {}", runId, e.getMessage());
        }
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
