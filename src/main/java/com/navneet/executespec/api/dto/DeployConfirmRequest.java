package com.navneet.executespec.api.dto;

import java.util.Map;

/**
 * Request to confirm and initiate a deployment after analysis.
 * The user has reviewed the analysis results, chosen a deployment path,
 * and provided any required environment variables.
 */
public record DeployConfirmRequest(
        /** The pipeline run ID to deploy */
        String runId,

        /** Chosen deployment path: CLOUD_RUN_DIRECT, COMPOSE_ON_VM, MANAGED_INFRASTRUCTURE */
        String deploymentPath,

        /** Target GCP region (e.g., us-central1). If null, defaults to credential's project region. */
        String region,

        /** User-provided environment variables (secrets, config) */
        Map<String, String> envVars,

        /** JSON string of the InfrastructureManifest from the analyze step */
        String manifestJson,

        /** Detected health check path (from analysis) */
        String healthCheckPath,

        /** Detected application port (from analysis) */
        Integer appPort
) {}
