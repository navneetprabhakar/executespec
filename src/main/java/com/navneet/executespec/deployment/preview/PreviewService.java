package com.navneet.executespec.deployment.preview;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.ArtifactRegistryClient;
import com.navneet.executespec.deployment.CloudRunClient;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class PreviewService {

    private final DevDockerfileGenerator dockerfileGen;
    private final DatabaseDetector databaseDetector;
    private final EnvVarDetector envVarDetector;
    private final ArtifactRegistryClient artifactRegistry;
    private final CloudRunClient cloudRun;
    private final PreviewProperties props;
    private final SseBroadcaster broadcaster;
    private final StateStore stateStore;
    private final AgentProperties agentProps;
    private final TierConfigRepository tierConfigRepo;
    private final WorkspaceRestoreService restoreService;

    /** runId → session */
    private final ConcurrentHashMap<String, PreviewSession> active = new ConcurrentHashMap<>();

    private static final Set<String> COMPLETE_STATUSES = Set.of("COMPLETE", "PARTIAL");

    public PreviewService(DevDockerfileGenerator dockerfileGen,
                          DatabaseDetector databaseDetector,
                          EnvVarDetector envVarDetector,
                          ArtifactRegistryClient artifactRegistry,
                          CloudRunClient cloudRun,
                          PreviewProperties props,
                          SseBroadcaster broadcaster,
                          StateStore stateStore,
                          AgentProperties agentProps,
                          TierConfigRepository tierConfigRepo,
                          WorkspaceRestoreService restoreService) {
        this.dockerfileGen = dockerfileGen;
        this.databaseDetector = databaseDetector;
        this.envVarDetector = envVarDetector;
        this.artifactRegistry = artifactRegistry;
        this.cloudRun = cloudRun;
        this.props = props;
        this.broadcaster = broadcaster;
        this.stateStore = stateStore;
        this.agentProps = agentProps;
        this.tierConfigRepo = tierConfigRepo;
        this.restoreService = restoreService;
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Analyze workspace for database and env var requirements (pre-start step).
     */
    public PreviewAnalysis analyze(String runId) {
        Path workspace = restoreService.ensureWorkspaceAvailable(runId);
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
     * The actual build → push → deploy happens on a virtual thread via Cloud Run.
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

        // Guard: idempotent — return existing session if still running.
        // If FAILED or TIMED_OUT, remove it so the user can retry.
        PreviewSession existing = active.get(runId);
        if (existing != null) {
            if (existing.status() == PreviewStatus.FAILED
                    || existing.status() == PreviewStatus.TIMED_OUT) {
                // Clean up stale failed session so retry starts fresh
                active.remove(runId);
                cleanupOnFailure(runId, existing.cloudRunServiceName());
                log.info("Cleared failed preview session for run {} — allowing retry", runId);
            } else {
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
            throw new PreviewException("Unsupported project type. Use Deploy to Cloud Run instead.");
        }

        // Create initial session
        String serviceName = cloudRunServiceName(runId);
        Duration ttl = Duration.ofMinutes(props.getPreviewTtlMinutes());

        PreviewSession session = new PreviewSession(
                runId, serviceName, type, null,
                PreviewStatus.ANALYZING, null, null, null,
                Instant.now(), Instant.now().plus(ttl)
        );
        active.put(runId, session);

        // Launch async build → push → deploy on a virtual thread
        Map<String, String> safeEnvVars = envVars != null ? Map.copyOf(envVars) : Map.of();
        Thread.startVirtualThread(() -> buildPushAndDeploy(runId, workspace, type, serviceName, safeEnvVars));

        log.info("Preview started for run {} (type={}, service={}, ttl={}min)",
                runId, type, serviceName, ttl.toMinutes());
        return session;
    }

    /** Backward-compatible start without env vars. */
    public PreviewSession start(String runId, AppUser user) {
        return start(runId, user, null);
    }

    /**
     * Stop a running preview. Deletes Cloud Run service and AR image. Idempotent.
     */
    public void stop(String runId) {
        PreviewSession session = active.remove(runId);
        if (session == null) return;

        // Delete Cloud Run service
        if (session.cloudRunServiceName() != null) {
            try {
                cloudRun.deleteService(session.cloudRunServiceName());
            } catch (Exception e) {
                log.warn("Failed to delete Cloud Run service {} for run {}: {}",
                        session.cloudRunServiceName(), runId, e.getMessage());
            }
        }

        // Delete AR image
        if (session.imageUri() != null) {
            try {
                cloudRun.deleteImage(session.imageUri());
            } catch (Exception e) {
                log.warn("Failed to delete AR image {} for run {}: {}",
                        session.imageUri(), runId, e.getMessage());
            }
        }

        broadcaster.emit(runId, new RunEvent("PREVIEW_STOPPED", runId, null, null, Instant.now()));
        log.info("Preview stopped for run {} (service={}, image={})",
                runId, session.cloudRunServiceName(), session.imageUri());
    }

    /**
     * Get the current preview session for a run.
     * Returns null if no preview is active (IDLE).
     */
    public PreviewSession getStatus(String runId) {
        return active.get(runId);
    }

    /**
     * Get logs for a Cloud Run preview. Currently returns a status message.
     * Future: fetch via gcloud logging read.
     */
    public String getLogs(String runId, int tailLines, String filter) {
        PreviewSession session = active.get(runId);
        if (session == null) return "";
        if (session.url() == null) return "Preview is still starting up...";
        return "Preview is running at: " + session.url() + "\nCloud Run console logs available in GCP Console.";
    }

    // ── Private: build, push, deploy with deadline ──────────────────────────

    private void buildPushAndDeploy(String runId, Path workspace, PreviewProjectType type,
                                     String serviceName, Map<String, String> envVars) {
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
                        s.runId(), s.cloudRunServiceName(), s.projectType(), dbType,
                        PreviewStatus.ANALYZING, s.url(), s.imageUri(), null,
                        s.startedAt(), s.expiresAt()));
            }

            // Phase 2: Generate Cloud Run-compatible Dockerfile
            checkDeadline(deadline, "Dockerfile generation");
            Files.writeString(workspace.resolve(".dockerignore"), dockerfileGen.dockerIgnore());
            String generatedDockerfile = dockerfileGen.generateForCloudRun(type, workspace);
            log.info("Preview Dockerfile for run {} (type={}):\n{}", runId, type, generatedDockerfile);
            Files.writeString(workspace.resolve("Dockerfile"), generatedDockerfile);

            // Phase 3: Docker build
            checkDeadline(deadline, "Docker build");
            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.cloudRunServiceName(), s.projectType(), s.databaseType(),
                    PreviewStatus.BUILDING, s.url(), s.imageUri(), null,
                    s.startedAt(), s.expiresAt()));
            broadcaster.emit(runId, new RunEvent("PREVIEW_BUILDING", runId, null,
                    "Building " + type + " container...", Instant.now()));

            String versionTag = "preview-" + runId.substring(0, 8) + "-" + Instant.now().getEpochSecond();
            var buildResult = artifactRegistry.buildImage(workspace, versionTag);
            String imageUri = buildResult.imageTag();

            // Phase 4: Push to Artifact Registry
            checkDeadline(deadline, "image push");
            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.cloudRunServiceName(), s.projectType(), s.databaseType(),
                    PreviewStatus.PUSHING, s.url(), imageUri, null,
                    s.startedAt(), s.expiresAt()));
            broadcaster.emit(runId, new RunEvent("PREVIEW_PUSHING", runId, null,
                    "Pushing image to registry...", Instant.now()));

            artifactRegistry.pushImage(imageUri);

            // Phase 5: Deploy to Cloud Run
            checkDeadline(deadline, "Cloud Run deploy");
            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.cloudRunServiceName(), s.projectType(), s.databaseType(),
                    PreviewStatus.DEPLOYING, s.url(), imageUri, null,
                    s.startedAt(), s.expiresAt()));
            broadcaster.emit(runId, new RunEvent("PREVIEW_DEPLOYING", runId, null,
                    "Deploying to Cloud Run...", Instant.now()));

            // Build env vars: inject embedded DB config if DB detected + user env vars
            Map<String, String> deployEnvVars = new HashMap<>(envVars);
            // PORT is reserved by Cloud Run (auto-set to 8080) — do not include it
            deployEnvVars.remove("PORT");
            if (dbType != null) {
                injectEmbeddedDbConfig(type, dbType, deployEnvVars);
            }

            var deployResult = cloudRun.deployPreview(serviceName, imageUri,
                    props.getCloudRunCpu(), props.getCloudRunMemory(), deployEnvVars);

            // Phase 6: Health check
            checkDeadline(deadline, "health check");
            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.cloudRunServiceName(), s.projectType(), s.databaseType(),
                    PreviewStatus.STARTING, deployResult.serviceUrl(), imageUri, null,
                    s.startedAt(), s.expiresAt()));
            broadcaster.emit(runId, new RunEvent("PREVIEW_STARTING", runId, null,
                    "Waiting for application to be ready...", Instant.now()));

            boolean healthy = cloudRun.healthCheck(deployResult.serviceUrl());
            if (!healthy) {
                fail(runId, "Application health check failed after deployment", imageUri, serviceName);
                return;
            }

            // Success!
            if (!active.containsKey(runId)) return; // was stopped while starting

            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.cloudRunServiceName(), s.projectType(), s.databaseType(),
                    PreviewStatus.RUNNING, deployResult.serviceUrl(), imageUri, null,
                    s.startedAt(), s.expiresAt()));
            broadcaster.emit(runId, new RunEvent("PREVIEW_READY", runId, null,
                    deployResult.serviceUrl(), Instant.now()));

            log.info("Preview ready for run {} at {} (db={})", runId, deployResult.serviceUrl(), dbType);

        } catch (DeadlineExceededException e) {
            cleanupOnFailure(runId, serviceName);
            updateSession(runId, s -> new PreviewSession(
                    s.runId(), s.cloudRunServiceName(), s.projectType(), s.databaseType(),
                    PreviewStatus.TIMED_OUT, s.url(), s.imageUri(),
                    "Preview startup timed out after " + props.getTotalStartupTimeoutSeconds()
                            + "s during: " + e.getPhase(),
                    s.startedAt(), s.expiresAt()));
            broadcaster.emit(runId, new RunEvent("PREVIEW_TIMED_OUT", runId, null,
                    "Timed out during: " + e.getPhase(), Instant.now()));
            log.warn("Preview timed out for run {} during: {}", runId, e.getPhase());

        } catch (IOException e) {
            fail(runId, "Build/push failed: " + e.getMessage(), null, serviceName);
        } catch (PreviewException e) {
            fail(runId, e.getMessage(), null, serviceName);
        } catch (Exception e) {
            fail(runId, "Unexpected error: " + e.getMessage(), null, serviceName);
            log.error("Preview build/push/deploy failed for run {}", runId, e);
        }
    }

    private void checkDeadline(Instant deadline, String phase) {
        if (Instant.now().isAfter(deadline)) {
            throw new DeadlineExceededException(phase);
        }
    }

    private void fail(String runId, String message, String imageUri, String serviceName) {
        cleanupOnFailure(runId, serviceName);

        updateSession(runId, s -> new PreviewSession(
                s.runId(), s.cloudRunServiceName(), s.projectType(), s.databaseType(),
                PreviewStatus.FAILED, s.url(), imageUri != null ? imageUri : s.imageUri(), message,
                s.startedAt(), s.expiresAt()));
        broadcaster.emit(runId, new RunEvent("PREVIEW_FAILED", runId, null, message, Instant.now()));
        log.warn("Preview failed for run {}: {}", runId, message);
    }

    private void cleanupOnFailure(String runId, String serviceName) {
        if (serviceName != null) {
            try {
                cloudRun.deleteService(serviceName);
            } catch (Exception e) {
                log.debug("Cleanup: failed to delete Cloud Run service {}: {}", serviceName, e.getMessage());
            }
        }
    }

    private void updateSession(String runId, java.util.function.UnaryOperator<PreviewSession> updater) {
        active.computeIfPresent(runId, (k, old) -> updater.apply(old));
    }

    /**
     * Inject embedded database config for Cloud Run previews (no DB sidecar available).
     */
    private void injectEmbeddedDbConfig(PreviewProjectType type, DatabaseType dbType,
                                         Map<String, String> envVars) {
        switch (type) {
            case SPRING_BOOT -> {
                // H2 in-memory for Spring Boot
                envVars.put("SPRING_DATASOURCE_URL", "jdbc:h2:mem:preview;DB_CLOSE_DELAY=-1");
                envVars.put("SPRING_DATASOURCE_DRIVER_CLASS_NAME", "org.h2.Driver");
                envVars.put("SPRING_JPA_DATABASE_PLATFORM", "org.hibernate.dialect.H2Dialect");
                log.info("Injected H2 in-memory DB config for {} preview", type);
            }
            case NODE_BACKEND, FULL_STACK -> {
                // SQLite for Node.js
                envVars.put("DATABASE_URL", "sqlite://:memory:");
                log.info("Injected SQLite in-memory DB config for {} preview", type);
            }
            case PYTHON -> {
                // SQLite for Python/Django
                envVars.putIfAbsent("DATABASE_URL", "sqlite:///db.sqlite3");
                log.info("Injected SQLite DB config for Python preview");
            }
            default -> log.debug("No embedded DB injection needed for {}", type);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Cloud Run service name derived from runId.
     * Must be lowercase alphanumeric + hyphens, max 63 chars.
     */
    private String cloudRunServiceName(String runId) {
        String safe = runId.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        return "es-preview-" + safe.substring(0, Math.min(12, safe.length()));
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
     * Orphan cleanup: find Cloud Run services with the preview label
     * that are not in our active map (e.g., from a previous server instance).
     */
    @Scheduled(fixedRate = 1800000) // every 30 minutes
    void cleanupOrphans() {
        try {
            List<String> services = cloudRun.listPreviewServices();
            Set<String> activeServiceNames = ConcurrentHashMap.newKeySet();
            active.values().forEach(s -> activeServiceNames.add(s.cloudRunServiceName()));

            for (String serviceName : services) {
                if (!activeServiceNames.contains(serviceName)) {
                    log.info("Deleting orphaned preview Cloud Run service: {}", serviceName);
                    try {
                        cloudRun.deleteService(serviceName);
                    } catch (Exception e) {
                        log.warn("Failed to delete orphaned service {}: {}", serviceName, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Orphan cleanup failed: {}", e.getMessage());
        }
    }

    @PostConstruct
    void recoverOrphans() {
        // On startup, schedule a one-time orphan cleanup after 60 seconds
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(60000);
                cleanupOrphans();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
