package com.navneet.executespec.deployment.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.navneet.executespec.deployment.DockerfileGenerator;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.deployment.analyzer.DeploymentPath;
import com.navneet.executespec.deployment.credential.UserGcpCredentialProvider;
import com.navneet.executespec.deployment.strategy.DeploymentStrategy;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.Deployment;
import com.navneet.executespec.state.entity.Deployment.DeployStatus;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.DeploymentRepository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates the deploy engine pipeline for Path A (Cloud Run Direct).
 *
 * <p>Separated from the existing {@code DeploymentService} which handles system-level preview
 * deployments via gcloud CLI. This orchestrator uses the Java client libraries with per-user
 * credentials to deploy into the <em>user's</em> GCP project.
 *
 * <p>Pipeline phases:
 *
 * <ol>
 *   <li>Validate & restore workspace
 *   <li>Ensure infrastructure (AR repo, build bucket)
 *   <li>Generate Dockerfile
 *   <li>Build image via Cloud Build
 *   <li>Deploy to Cloud Run
 *   <li>Health check
 * </ol>
 */
@Service
@ConditionalOnProperty(name = "agent.deployment.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class DeployEngineOrchestrator {

  private static final String DEFAULT_AR_REPO = "executespec-apps";
  private static final int DEFAULT_TTL_DAYS = 30;

  private final UserGcpCredentialProvider credentialProvider;
  private final UserCloudRunDeployer cloudRunDeployer;
  private final UserImageBuilder imageBuilder;
  private final WorkspaceRestoreService workspaceRestore;
  private final DockerfileGenerator dockerfileGenerator;
  private final DeploymentRepository deploymentRepo;
  private final StateStore stateStore;
  private final SseBroadcaster sseBroadcaster;
  private final ObjectMapper objectMapper;

  /**
   * Initiate a Path A (Cloud Run Direct) deployment. Returns immediately with a PENDING deployment;
   * actual pipeline runs async.
   *
   * @param runId the pipeline run to deploy
   * @param userId the deploying user's ID
   * @param region target GCP region (e.g., us-central1)
   * @param envVars user-provided environment variables
   * @param manifestJson JSON of the InfrastructureManifest from analysis
   * @param healthCheckPath detected health check path
   * @param appPort detected application port
   * @return the created Deployment entity (PENDING status)
   */
  public Deployment deployCloudRunDirect(
      String runId,
      String userId,
      String region,
      Map<String, String> envVars,
      String manifestJson,
      String healthCheckPath,
      int appPort) {

    // Guard: run must be COMPLETE or FAILED
    PipelineRun run =
        stateStore
            .findRun(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    if (!"COMPLETE".equals(run.getStatus()) && !"FAILED".equals(run.getStatus())) {
      throw new IllegalStateException(
          "Can only deploy COMPLETE/FAILED runs. Current: " + run.getStatus());
    }

    // Guard: user must have GCP credentials
    GoogleCredentials credentials = credentialProvider.getCredentialsOrThrow(userId);
    String gcpProject =
        credentialProvider
            .getGcpProjectId(userId)
            .orElseThrow(
                () -> new IllegalStateException("No GCP project ID found for user credentials"));

    // Guard: no concurrent deploy for same run
    int active = deploymentRepo.countActiveByRunId(runId);
    if (active > 0) {
      throw new IllegalStateException(
          "Deploy already in progress for this run. Wait for completion or failure.");
    }

    // Determine version and service name
    int versionNum = deploymentRepo.countByRunId(runId) + 1;
    String runId8 = runId.substring(0, Math.min(8, runId.length()));
    String versionTag = runId8 + "-v" + versionNum;
    String serviceName = "app-" + runId8;

    // Create deployment record
    Deployment deployment = new Deployment();
    deployment.setId(UUID.randomUUID().toString());
    deployment.setRunId(runId);
    deployment.setUserId(userId);
    deployment.setVersionTag(versionTag);
    deployment.setDeploymentPath(DeploymentPath.CLOUD_RUN_DIRECT.name());
    deployment.setStatus(DeployStatus.PENDING);
    deployment.setServiceName(serviceName);
    deployment.setGcpProject(gcpProject);
    deployment.setGcpRegion(region);
    // Parse manifest JSON to Map for JSONB storage
    if (manifestJson != null && !manifestJson.isBlank()) {
      try {
        @SuppressWarnings("unchecked")
        Map<String, Object> manifestMap = objectMapper.readValue(manifestJson, Map.class);
        deployment.setManifestJson(manifestMap);
      } catch (Exception e) {
        log.warn("Failed to parse manifest JSON, storing as null: {}", e.getMessage());
      }
    }
    deployment.setHealthCheckPath(healthCheckPath);
    deployment.setAppPort(appPort);
    deployment.setCreatedAt(Instant.now().toString());
    deployment.setTtlExpiresAt(Instant.now().plus(DEFAULT_TTL_DAYS, ChronoUnit.DAYS).toString());
    deployment.setCredentialId(gcpProject); // reference to credential's project

    deploymentRepo.save(deployment);

    // SSE: deployment started
    sseBroadcaster.emit(
        runId,
        RunEvent.engineDeployStarted(runId, versionTag, DeploymentPath.CLOUD_RUN_DIRECT.name()));

    // Run async pipeline
    Thread.startVirtualThread(
        () -> executePathA(deployment, credentials, gcpProject, region, envVars));

    return deployment;
  }

  private void executePathA(
      Deployment deployment,
      GoogleCredentials credentials,
      String gcpProject,
      String region,
      Map<String, String> envVars) {
    String runId = deployment.getRunId();
    String versionTag = deployment.getVersionTag();

    try {
      updateStatus(deployment, DeployStatus.BUILDING, "Preparing workspace...");
      sseBroadcaster.emit(
          runId, RunEvent.enginePhase(runId, "WORKSPACE_RESTORE", "Restoring workspace..."));

      Path workspace = workspaceRestore.ensureWorkspaceAvailable(runId);
      log.info("[{}] Workspace available: {}", versionTag, workspace);

      sseBroadcaster.emit(
          runId, RunEvent.enginePhase(runId, "DOCKERFILE_GENERATE", "Generating Dockerfile..."));

      DeploymentStrategy strategy = dockerfileGenerator.detectAndGenerate(workspace);
      deployment.setStrategy(strategy.type());
      deployment.setDockerfilePath(workspace.resolve("Dockerfile").toString());
      deploymentRepo.save(deployment);

      sseBroadcaster.emit(
          runId,
          RunEvent.enginePhase(
              runId, "INFRA_SETUP", "Setting up Artifact Registry and build bucket..."));

      imageBuilder.ensureBuildBucket(credentials, gcpProject, region);
      imageBuilder.ensureArtifactRegistryRepo(credentials, gcpProject, region, DEFAULT_AR_REPO);

      // Phase 4: Build image via Cloud Build
      updateStatus(deployment, DeployStatus.BUILDING, "Building container image...");
      sseBroadcaster.emit(runId, RunEvent.engineBuilding(runId, versionTag));

      UserImageBuilder.BuildResult buildResult =
          imageBuilder.build(
              credentials, gcpProject, region, workspace, versionTag, DEFAULT_AR_REPO);

      deployment.setImageUri(buildResult.imageUri());
      deployment.setBuildLog(buildResult.buildLogUrl());
      deploymentRepo.save(deployment);
      log.info("[{}] Image built: {}", versionTag, buildResult.imageUri());

      // Phase 5: Deploy to Cloud Run
      updateStatus(deployment, DeployStatus.DEPLOYING, "Deploying to Cloud Run...");
      sseBroadcaster.emit(runId, RunEvent.engineDeploying(runId, versionTag));

      int port = deployment.getAppPort() != null ? deployment.getAppPort() : 8080;
      UserCloudRunDeployer.DeployResult deployResult =
          cloudRunDeployer.deploy(
              credentials,
              gcpProject,
              region,
              deployment.getServiceName(),
              buildResult.imageUri(),
              port,
              envVars,
              true);

      updateStatus(deployment, DeployStatus.HEALTH_CHECK, "Running health checks...");
      sseBroadcaster.emit(
          runId, RunEvent.enginePhase(runId, "HEALTH_CHECK", "Checking service health..."));

      boolean healthy =
          cloudRunDeployer.healthCheck(deployResult.serviceUrl(), deployment.getHealthCheckPath());

      if (!healthy) {
        log.warn(
            "[{}] Health check failed — marking as LIVE anyway (service may need warmup)",
            versionTag);
      }

      deployment.markLive(deployResult.serviceUrl(), deployResult.revisionName());
      deploymentRepo.save(deployment);

      sseBroadcaster.emit(
          runId, RunEvent.engineDeployLive(runId, versionTag, deployResult.serviceUrl()));

      log.info(
          "[{}] Deployment LIVE: {} → {} (revision: {})",
          versionTag,
          deployment.getServiceName(),
          deployResult.serviceUrl(),
          deployResult.revisionName());

    } catch (Exception e) {
      log.error("[{}] Deploy engine failed: {}", versionTag, e.getMessage(), e);
      deployment.markFailed(e.getMessage());
      deploymentRepo.save(deployment);
      sseBroadcaster.emit(runId, RunEvent.engineDeployFailed(runId, versionTag, e.getMessage()));
    }
  }

  /** Rollback a Cloud Run Direct deployment to a specific revision. */
  public void rollback(String deploymentId, String userId) throws IOException {
    Deployment d =
        deploymentRepo
            .findById(deploymentId)
            .orElseThrow(
                () -> new IllegalArgumentException("Deployment not found: " + deploymentId));

    if (d.getRevisionName() == null) {
      throw new IllegalStateException("No revision recorded — cannot rollback");
    }
    if (!DeploymentPath.CLOUD_RUN_DIRECT.name().equals(d.getDeploymentPath())) {
      throw new IllegalStateException("Rollback only supported for CLOUD_RUN_DIRECT path");
    }

    GoogleCredentials credentials = credentialProvider.getCredentialsOrThrow(userId);
    cloudRunDeployer.rollbackToRevision(
        credentials, d.getGcpProject(), d.getGcpRegion(), d.getServiceName(), d.getRevisionName());

    d.setStatus(DeployStatus.LIVE);
    deploymentRepo.save(d);

    sseBroadcaster.emit(
        d.getRunId(),
        RunEvent.enginePhase(
            d.getRunId(), "ROLLED_BACK", "Rolled back to revision " + d.getRevisionName()));
  }

  /** Destroy a Cloud Run Direct deployment — delete the service and image. */
  public void destroy(String deploymentId, String userId) throws IOException {
    Deployment d =
        deploymentRepo
            .findById(deploymentId)
            .orElseThrow(
                () -> new IllegalArgumentException("Deployment not found: " + deploymentId));

    if (!DeploymentPath.CLOUD_RUN_DIRECT.name().equals(d.getDeploymentPath())) {
      throw new IllegalStateException("Destroy only supported for CLOUD_RUN_DIRECT path");
    }

    GoogleCredentials credentials = credentialProvider.getCredentialsOrThrow(userId);

    d.setStatus(DeployStatus.DESTROYING);
    deploymentRepo.save(d);

    try {
      cloudRunDeployer.deleteService(
          credentials, d.getGcpProject(), d.getGcpRegion(), d.getServiceName());
      d.setStatus(DeployStatus.DESTROYED);
      d.setDeletedAt(Instant.now().toString());
      deploymentRepo.save(d);

      sseBroadcaster.emit(
          d.getRunId(), RunEvent.enginePhase(d.getRunId(), "DESTROYED", "Deployment destroyed"));
    } catch (Exception e) {
      d.markFailed("Destroy failed: " + e.getMessage());
      deploymentRepo.save(d);
      throw new IOException("Failed to destroy deployment: " + e.getMessage(), e);
    }
  }

  private void updateStatus(Deployment d, DeployStatus status, String message) {
    d.setStatus(status);
    deploymentRepo.save(d);
    log.info("[{}] Status: {} — {}", d.getVersionTag(), status, message);
  }
}
