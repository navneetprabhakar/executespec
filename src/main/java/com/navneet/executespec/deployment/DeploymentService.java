package com.navneet.executespec.deployment;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.strategy.DeploymentStrategy;
import com.navneet.executespec.state.entity.Deployment;
import com.navneet.executespec.state.entity.Deployment.DeployStatus;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.entity.RunRepo;
import com.navneet.executespec.state.repository.DeploymentRepository;
import com.navneet.executespec.state.repository.RunRepoRepository;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.navneet.executespec.config.CustomMetricsConfig.*;
import com.navneet.executespec.config.CustomMetricsConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@Slf4j
public class DeploymentService {

    private final DeploymentProperties props;
    private final AgentProperties agentProps;
    private final DeploymentRepository deploymentRepo;
    private final RunRepoRepository runRepoRepo;
    private final DockerfileGenerator dockerfileGenerator;
    private final ArtifactRegistryClient artifactRegistry;
    private final CloudRunClient cloudRun;
    private final DeploymentGitHubService gitHubService;
    private final SseBroadcaster sseBroadcaster;
    private final StateStore stateStore;
    private final MeterRegistry meterRegistry;

    public DeploymentService(DeploymentProperties props, AgentProperties agentProps,
                              DeploymentRepository deploymentRepo, RunRepoRepository runRepoRepo,
                              DockerfileGenerator dockerfileGenerator,
                              ArtifactRegistryClient artifactRegistry, CloudRunClient cloudRun,
                              DeploymentGitHubService gitHubService, SseBroadcaster sseBroadcaster,
                              StateStore stateStore, MeterRegistry meterRegistry) {
        this.props = props;
        this.agentProps = agentProps;
        this.deploymentRepo = deploymentRepo;
        this.runRepoRepo = runRepoRepo;
        this.dockerfileGenerator = dockerfileGenerator;
        this.artifactRegistry = artifactRegistry;
        this.cloudRun = cloudRun;
        this.gitHubService = gitHubService;
        this.sseBroadcaster = sseBroadcaster;
        this.stateStore = stateStore;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void validatePrerequisites() {
        if (!props.enabled()) {
            log.info("Deployment tier disabled");
            return;
        }
        log.info("Deployment tier enabled. GCP project: {}, region: {}",
            props.gcpProject(), props.gcpRegion());

        // Docker
        try {
            Process p = new ProcessBuilder("docker", "info")
                .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor();
            log.info("✓ Docker available");
        } catch (Exception e) {
            log.warn("✗ Docker not available — deploys will fail at build step");
        }

        // gcloud
        try {
            Process p = new ProcessBuilder("gcloud", "auth", "list",
                "--filter=status:ACTIVE", "--format=value(account)")
                .redirectErrorStream(true).start();
            String account = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (!account.isBlank()) {
                log.info("✓ GCP auth: {}", account);
            } else {
                log.warn("✗ No active GCP account — deploys will fail at push step");
            }
        } catch (Exception e) {
            log.warn("✗ gcloud not available — deploys will fail");
        }

        // GitHub PAT
        String pat = agentProps.getGithub().getPat();
        if (pat != null && !pat.isBlank() && !pat.equals("${GITHUB_PAT}")) {
            log.info("✓ GitHub PAT configured");
        } else {
            log.warn("✗ GITHUB_PAT not set — GitHub repo creation will fail (NONE mode still works)");
        }
    }

    public boolean isEnabled() {
        return props.enabled();
    }

    /**
     * Deploy a completed run. Returns immediately with PENDING deployment.
     * Actual pipeline runs async on a virtual thread.
     */
    public Deployment deploy(String runId, RunRepo.RepoMode repoMode,
                              String repoUrl, String repoName) {

        // Guard: run must be COMPLETE
        PipelineRun run = stateStore.findRun(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        if (!"COMPLETE".equals(run.getStatus())) {
            throw new IllegalStateException(
                "Can only deploy COMPLETE runs. Current status: " + run.getStatus());
        }

        // Guard: no concurrent deploy for same run
        int active = deploymentRepo.countActiveByRunId(runId);
        if (active > 0) {
            throw new IllegalStateException(
                "Deploy already in progress for run " + runId.substring(0, 8)
                + ". Wait for it to complete or fail before redeploying.");
        }

        // Resolve workspace path
        Path workspacePath = resolveWorkspace(runId);

        // Detect strategy + generate Dockerfile (or use existing)
        DeploymentStrategy strategy;
        try {
            strategy = dockerfileGenerator.detectAndGenerate(workspacePath);
        } catch (Exception e) {
            return createFailed(runId, "Strategy detection failed: " + e.getMessage());
        }

        // Determine version number and service name
        int versionNum = deploymentRepo.countByRunId(runId) + 1;
        String runId8 = runId.substring(0, 8);
        String versionTag = runId8 + "-v" + versionNum;
        String serviceName = "demo-" + runId8;

        // Create deployment record (PENDING)
        Deployment deployment = Deployment.create(runId, versionTag, strategy.type(),
            artifactRegistry.fullImageUri(versionTag), serviceName,
            props.gcpProject(), props.gcpRegion(), props.ttlDays());
        deployment.setDockerfilePath(workspacePath.resolve("Dockerfile").toString());
        deploymentRepo.save(deployment);

        // SSE: deployment started
        sseBroadcaster.emit(runId,
            RunEvent.deployStarted(runId, versionTag, strategy.type().name()));

        // Run async pipeline on virtual thread
        Thread.startVirtualThread(() ->
            executeDeployPipeline(deployment, run, workspacePath, repoMode, repoUrl, repoName));

        return deployment;
    }

    private void executeDeployPipeline(Deployment deployment, PipelineRun run,
                                        Path workspacePath, RunRepo.RepoMode repoMode,
                                        String repoUrl, String repoName) {
        Metrics.ACTIVE_DEPLOYS.incrementAndGet();
        Counter.builder(DEPLOY_TOTAL).register(meterRegistry).increment();
        java.time.Instant deployStart = java.time.Instant.now();
        java.time.Instant buildPhaseStart = null;
        java.time.Instant healthCheckStart = null;
        try {
            // ── Phase 1: GitHub (optional) ──
            RunRepo repo = handleGitHub(deployment, run, workspacePath, repoMode, repoUrl, repoName);
            deployment.setRunRepoId(repo.getId());
            deploymentRepo.save(deployment);

            // ── Phase 2: Docker build ──
            buildPhaseStart = java.time.Instant.now();
            deployment.setStatus(DeployStatus.BUILDING);
            deploymentRepo.save(deployment);
            sseBroadcaster.emit(deployment.getRunId(),
                RunEvent.deployBuilding(deployment.getRunId(), deployment.getVersionTag()));

            ArtifactRegistryClient.BuildResult buildResult =
                artifactRegistry.buildImage(workspacePath, deployment.getVersionTag());
            deployment.setBuildLog(buildResult.buildLog());
            deploymentRepo.save(deployment);

            // ── Phase 3: Push to Artifact Registry ──
            deployment.setStatus(DeployStatus.PUSHING);
            deploymentRepo.save(deployment);
            sseBroadcaster.emit(deployment.getRunId(),
                RunEvent.deployPushing(deployment.getRunId(), deployment.getVersionTag()));

            artifactRegistry.pushImage(buildResult.imageTag());

            // ── Phase 4: Deploy to Cloud Run ──
            deployment.setStatus(DeployStatus.DEPLOYING);
            deploymentRepo.save(deployment);

            CloudRunClient.DeployResult deployResult =
                cloudRun.deploy(deployment.getServiceName(), deployment.getImageUri());

            // Record build duration (phases 2-4)
            if (buildPhaseStart != null) {
                Timer.builder(DEPLOY_BUILD_DURATION).register(meterRegistry)
                        .record(java.time.Duration.between(buildPhaseStart, java.time.Instant.now()));
            }

            // ── Phase 5: Health check ──
            healthCheckStart = java.time.Instant.now();
            if (!cloudRun.healthCheck(deployResult.serviceUrl())) {
                throw new IOException(
                    "Post-deploy health check failed — service returned 5xx at "
                    + deployResult.serviceUrl());
            }

            // ── Success ──
            deployment.markLive(deployResult.serviceUrl(), deployResult.revisionName());
            deploymentRepo.save(deployment);

            sseBroadcaster.emit(deployment.getRunId(),
                RunEvent.deployLive(deployment.getRunId(), deployment.getVersionTag(),
                    deployResult.serviceUrl()));

            // Record health check duration
            if (healthCheckStart != null) {
                Timer.builder(DEPLOY_HEALTH_CHECK_DURATION).register(meterRegistry)
                        .record(java.time.Duration.between(healthCheckStart, java.time.Instant.now()));
            }
            // Record total deploy duration
            Timer.builder(DEPLOY_DURATION).register(meterRegistry)
                    .record(java.time.Duration.between(deployStart, java.time.Instant.now()));

            log.info("Deployment LIVE: {} → {} (revision: {})",
                deployment.getVersionTag(), deployResult.serviceUrl(), deployResult.revisionName());

        } catch (Exception e) {
            log.error("Deployment failed: {}", deployment.getVersionTag(), e);
            Counter.builder(DEPLOY_FAILURES).tag("reason", e.getClass().getSimpleName())
                    .register(meterRegistry).increment();
            deployment.markFailed(e.getMessage());
            deploymentRepo.save(deployment);
            sseBroadcaster.emit(deployment.getRunId(),
                RunEvent.deployFailed(deployment.getRunId(), deployment.getVersionTag(),
                    e.getMessage()));
        } finally {
            Metrics.ACTIVE_DEPLOYS.decrementAndGet();
        }
    }

    private RunRepo handleGitHub(Deployment deployment, PipelineRun run,
                                  Path workspacePath, RunRepo.RepoMode repoMode,
                                  String repoUrl, String repoName) throws IOException {
        return switch (repoMode) {
            case NEW -> {
                String name = (repoName != null && !repoName.isBlank())
                    ? repoName : "demo-" + run.getRunId().substring(0, 8);
                yield gitHubService.createAndPush(run.getRunId(), name, workspacePath);
            }
            case EXISTING -> gitHubService.pushToExisting(run.getRunId(), repoUrl, workspacePath);
            case NONE -> gitHubService.skipGitHub(run.getRunId());
        };
    }

    /**
     * Rollback to a previous deployment's revision.
     * Routes 100% of traffic to the specified revision — instant, no rebuild.
     */
    public void rollback(String deploymentId) throws IOException, InterruptedException {
        Deployment target = deploymentRepo.findById(deploymentId)
            .orElseThrow(() -> new IllegalArgumentException("Deployment not found: " + deploymentId));

        if (target.getRevisionName() == null) {
            throw new IllegalStateException(
                "No revision name recorded for deployment " + deploymentId + " — cannot rollback");
        }

        cloudRun.rollbackToRevision(target.getServiceName(), target.getRevisionName());
        log.info("Rolled back to deployment {} revision {}", deploymentId, target.getRevisionName());
    }

    /** Manual deletion of a live deployment. */
    public void deleteDeployment(String deploymentId) {
        Deployment d = deploymentRepo.findById(deploymentId)
            .orElseThrow(() -> new IllegalArgumentException("Deployment not found: " + deploymentId));
        cleanupDeployment(d, "manual");
    }

    /** Shared cleanup logic — used by both TTL job and manual deletion. */
    void cleanupDeployment(Deployment d, String reason) {
        d.setStatus(DeployStatus.CLEANING_UP);
        deploymentRepo.save(d);
        try {
            cloudRun.deleteService(d.getServiceName());
            cloudRun.deleteImage(d.getImageUri());
            d.markDeleted();
            deploymentRepo.save(d);
            sseBroadcaster.emit(d.getRunId(),
                RunEvent.deployDeleted(d.getRunId(), d.getVersionTag(), reason));
            log.info("Cleaned up deployment: {} (reason: {})", d.getVersionTag(), reason);
        } catch (Exception e) {
            log.error("Cleanup failed for {}: {}", d.getVersionTag(), e.getMessage());
            d.markFailed("Cleanup failed: " + e.getMessage());
            deploymentRepo.save(d);
        }
    }

    private Deployment createFailed(String runId, String error) {
        Deployment d = new Deployment();
        d.setId(UUID.randomUUID().toString());
        d.setRunId(runId);
        d.setVersionTag("failed");
        d.setStrategy(null);
        d.setImageUri("");
        d.setServiceName("");
        d.setStatus(DeployStatus.FAILED);
        d.setGcpProject(props.gcpProject());
        d.setGcpRegion(props.gcpRegion());
        d.setTtlExpiresAt(java.time.Instant.now()
            .plus(props.ttlDays(), ChronoUnit.DAYS).toString());
        d.setCreatedAt(java.time.Instant.now().toString());
        d.markFailed(error);
        return deploymentRepo.save(d);
    }

    private Path resolveWorkspace(String runId) {
        return Path.of(agentProps.getWorkspace(), runId);
    }
}
