package com.navneet.executespec.deployment.preview;

public enum PreviewStatus {
    IDLE,
    ANALYZING,     // detecting DB, env vars
    BUILDING,
    DB_STARTING,   // waiting for database healthcheck
    STARTING,      // waiting for app readiness
    RUNNING,
    FAILED,
    STOPPED,
    TIMED_OUT      // 5-minute total startup deadline exceeded
}
