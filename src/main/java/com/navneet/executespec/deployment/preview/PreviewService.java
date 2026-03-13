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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class PreviewService {

    private final DockerExecutor docker;
    private final DevDockerfileGenerator dockerfileGen;
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

    public PreviewService(DockerExecutor docker,
                          DevDockerfileGenerator dockerfileGen,
                          PreviewPortPool portPool,
                          PreviewProperties props,
                          SseBroadcaster broadcaster,
                          StateStore stateStore,
                          AgentProperties agentProps) {
        this.docker = docker;
        this.dockerfileGen = dockerfileGen;
        this.portPool = portPool;
        this.props = props;
        this.broadcaster = broadcaster;
        this.stateStore = stateStore;
        this.agentProps = agentProps;
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Start a preview for a completed run. Returns immediately with BUILDING status.
     * The actual build+start happens on a virtual thread.
     */
    public PreviewSession start(String runId, AppUser user) {
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
        String containerName = containerName(runId);
        String url = props.getProxy().isEnabled()
                ? props.getProxy().getBaseUrl() + "/preview/" + runId
                : "http://localhost:" + port;
        Duration ttl = getTtl(user.getTier());

        PreviewSession session = new PreviewSession(
                runId, null, containerName, type,
                PreviewStatus.BUILDING, port, url, null,
                Instant.now(), Instant.now().plus(ttl)
        );
        active.put(runId, session);

        // Launch async build+start on a virtual thread
        Thread.startVirtualThread(() -> buildAndStart(runId, workspace, type, port));

        log.info("Preview started for run {} (type={}, port={}, ttl={}min)",
                runId, type, port, ttl.toMinutes());
        return session;
    }

    /**
     * Stop a running preview. Idempotent.
     */
    public void stop(String runId) {
        PreviewSession session = active.get(runId);
        if (session == null) return;

        docker.stop(session.containerName());
        portPool.release(session.hostPort());

        if (caddyProxyManager != null) {
            caddyProxyManager.removeRoute(runId);
        }

        active.remove(runId);
        broadcaster.emit(runId, new RunEvent("PREVIEW_STOPPED", runId, null, null, Instant.now()));

        // Cleanup Dockerfile and .dockerignore
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
     */
    public String getLogs(String runId, int tailLines) {
        PreviewSession session = active.get(runId);
        if (session == null) return "";
        return docker.logs(session.containerName(), tailLines);
    }

    // ── Private: build and start ────────────────────────────────────────────

    private void buildAndStart(String runId, Path workspace, PreviewProjectType type, int port) {
        try {
            // Write .dockerignore
            Files.writeString(workspace.resolve(".dockerignore"), dockerfileGen.dockerIgnore());

            // Write Dockerfile
            Files.writeString(workspace.resolve("Dockerfile"), dockerfileGen.generate(type, workspace));

            // Emit BUILDING event
            broadcaster.emit(runId, new RunEvent("PREVIEW_BUILDING", runId, null,
                    "Building " + type + " container...", Instant.now()));

            // Build Docker image
            String imageTag = "preview-" + runId;
            var buildResult = docker.build(workspace, imageTag);

            if (!buildResult.success()) {
                String lastLines = lastNLines(buildResult.logs(), 20);
                fail(runId, "Docker build failed:\n" + lastLines);
                return;
            }

            log.info("Preview image built for {} in {}ms", runId, buildResult.durationMs());

            // Determine resource limits
            String cpu = (type == PreviewProjectType.SPRING_BOOT) ? props.getSpringBootCpuLimit() : props.getCpuLimit();
            String mem = (type == PreviewProjectType.SPRING_BOOT) ? props.getSpringBootMemoryLimit() : props.getMemoryLimit();

            // Run container
            String containerName = containerName(runId);
            String containerId = docker.run(imageTag, containerName, port, type.getContainerPort(), cpu, mem);

            // Update session to STARTING
            updateSession(runId, s -> new PreviewSession(
                    s.runId(), containerId, s.containerName(), s.projectType(),
                    PreviewStatus.STARTING, s.hostPort(), s.url(), null,
                    s.startedAt(), s.expiresAt()
            ));
            broadcaster.emit(runId, new RunEvent("PREVIEW_STARTING", runId, null,
                    "Container started, waiting for readiness...", Instant.now()));

            // Wait for readiness
            int readinessTimeout = (type == PreviewProjectType.SPRING_BOOT)
                    ? props.getReadinessTimeoutSeconds()
                    : 30;
            boolean ready = waitForReadiness(port, readinessTimeout);

            if (!ready) {
                String logs = docker.logs(containerName, 30);
                fail(runId, "Startup timed out after " + readinessTimeout + "s\n" + logs);
                docker.stop(containerName);
                portPool.release(port);
                return;
            }

            // Update session to RUNNING
            PreviewSession session = active.get(runId);
            if (session == null) return; // was stopped while starting

            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.containerId(), s.containerName(), s.projectType(),
                    PreviewStatus.RUNNING, s.hostPort(), s.url(), null,
                    s.startedAt(), s.expiresAt()
            ));

            if (caddyProxyManager != null) {
                caddyProxyManager.addRoute(runId, port);
            }

            String previewUrl = active.get(runId) != null ? active.get(runId).url() : "http://localhost:" + port;
            broadcaster.emit(runId, new RunEvent("PREVIEW_READY", runId, null, previewUrl, Instant.now()));

            log.info("Preview ready for run {} at {}", runId, previewUrl);

        } catch (IOException e) {
            fail(runId, "Failed to write Dockerfile: " + e.getMessage());
        } catch (PreviewException e) {
            fail(runId, e.getMessage());
        } catch (Exception e) {
            fail(runId, "Unexpected error: " + e.getMessage());
            log.error("Preview build/start failed for run {}", runId, e);
        }
    }

    private void fail(String runId, String message) {
        updateSession(runId, s -> new PreviewSession(
                s.runId(), s.containerId(), s.containerName(), s.projectType(),
                PreviewStatus.FAILED, s.hostPort(), s.url(), message,
                s.startedAt(), s.expiresAt()
        ));
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
                // Accept any response 200-499 as "ready" (4xx means app is up)
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

    private String containerName(String runId) {
        // Container names must match [a-zA-Z0-9][a-zA-Z0-9_.-]
        String safe = runId.replaceAll("[^a-zA-Z0-9]", "");
        return "preview-" + safe.substring(0, Math.min(12, safe.length()));
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

    private String lastNLines(String text, int n) {
        if (text == null) return "";
        String[] lines = text.split("\n");
        int start = Math.max(0, lines.length - n);
        var sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString();
    }

    private void cleanupWorkspaceFiles(String runId) {
        try {
            Path workspace = Path.of(agentProps.getWorkspace(), runId);
            Files.deleteIfExists(workspace.resolve("Dockerfile"));
            Files.deleteIfExists(workspace.resolve(".dockerignore"));
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
                .toList() // collect first to avoid ConcurrentModificationException
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
}
