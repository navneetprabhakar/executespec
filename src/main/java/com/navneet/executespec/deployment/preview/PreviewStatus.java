package com.navneet.executespec.deployment.preview;

public enum PreviewStatus {
    IDLE,
    ANALYZING,     // detecting DB, env vars
    BUILDING,      // Docker build
    PUSHING,       // pushing image to Artifact Registry
    DEPLOYING,     // deploying to Cloud Run
    STARTING,      // waiting for app readiness (health check)
    RUNNING,
    FAILED,
    STOPPED,
    TIMED_OUT      // total startup deadline exceeded
}
