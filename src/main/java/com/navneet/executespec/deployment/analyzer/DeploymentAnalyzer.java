package com.navneet.executespec.deployment.analyzer;

import com.navneet.executespec.deployment.preview.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * Aggregates all infrastructure detectors to produce a complete
 * {@link InfrastructureManifest} for a workspace.
 * <p>
 * All detection is deterministic (rule-based, not LLM).
 * This is critical because detection results drive real cloud resource provisioning.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeploymentAnalyzer {

    private final DevDockerfileGenerator dockerfileGenerator;
    private final DatabaseDetector databaseDetector;
    private final EnvVarDetector envVarDetector;
    private final CacheDetector cacheDetector;
    private final QueueDetector queueDetector;
    private final StorageDetector storageDetector;
    private final HealthCheckDetector healthCheckDetector;
    private final StatelessnessDetector statelessnessDetector;

    /**
     * Analyze a workspace and produce a complete infrastructure manifest.
     *
     * @param workspacePath path to the run's workspace directory
     * @return full infrastructure manifest describing what the app needs
     */
    public InfrastructureManifest analyze(Path workspacePath) {
        log.info("Analyzing workspace for infrastructure requirements: {}", workspacePath);

        // 1. Detect project type (reuses existing DevDockerfileGenerator)
        PreviewProjectType appType = dockerfileGenerator.detect(workspacePath);
        if (appType == null) {
            log.warn("Could not detect project type for workspace: {}", workspacePath);
            appType = PreviewProjectType.STATIC_HTML; // safe fallback
        }

        // 2. Detect database
        DatabaseType dbType = databaseDetector.detect(workspacePath);
        InfrastructureManifest.DatabaseRequirement dbReq = null;
        if (dbType != null && dbType != DatabaseType.REDIS) {
            // Redis is detected by DatabaseDetector but is actually a cache, not a DB
            dbReq = new InfrastructureManifest.DatabaseRequirement(dbType, "Detected by DatabaseDetector");
        }

        // 3. Detect cache (Redis is handled here, separate from DB)
        InfrastructureManifest.CacheRequirement cacheReq = cacheDetector.detect(workspacePath);
        // If DatabaseDetector found Redis but CacheDetector didn't, fill it in
        if (cacheReq == null && dbType == DatabaseType.REDIS) {
            cacheReq = new InfrastructureManifest.CacheRequirement(
                    InfrastructureManifest.CacheRequirement.CacheType.REDIS,
                    "Redis detected via DatabaseDetector");
        }

        // 4. Detect queue
        InfrastructureManifest.QueueRequirement queueReq = queueDetector.detect(workspacePath);

        // 5. Detect storage
        InfrastructureManifest.StorageRequirement storageReq = storageDetector.detect(workspacePath);

        // 6. Detect env vars
        List<EnvVarDetector.DetectedEnvVar> envVars = envVarDetector.detect(workspacePath);

        // 7. Detect health check path
        String healthCheckPath = healthCheckDetector.detect(workspacePath, appType);

        // 8. Determine statelessness
        boolean stateless = statelessnessDetector.isStateless(
                workspacePath, dbType, cacheReq != null);

        // 9. Resolve port
        int port = appType.getContainerPort();

        InfrastructureManifest manifest = new InfrastructureManifest(
                appType, port, stateless, healthCheckPath,
                dbReq, cacheReq, queueReq, storageReq, envVars
        );

        log.info("Infrastructure analysis complete: type={}, stateless={}, db={}, cache={}, queue={}, storage={}, envVars={}",
                appType, stateless,
                dbReq != null ? dbReq.type() : "none",
                cacheReq != null ? cacheReq.type() : "none",
                queueReq != null ? queueReq.type() : "none",
                storageReq != null ? storageReq.type() : "none",
                envVars.size());

        return manifest;
    }
}
