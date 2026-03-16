package com.navneet.executespec.deployment.analyzer;

/**
 * The three deployment paths available to users.
 */
public enum DeploymentPath {
    /** Stateless app → Cloud Run API deploy. Cheapest, simplest. */
    CLOUD_RUN_DIRECT,

    /** Stateful app → Docker Compose on a single GCE VM. Low cost, all-in-one. */
    COMPOSE_ON_VM,

    /** Stateful app → Cloud Run + Cloud SQL + Memorystore. Production-grade, higher cost. */
    MANAGED_INFRASTRUCTURE
}
