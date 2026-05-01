package com.navneet.executespec.deployment.preview;

public enum PreviewStatus {
  IDLE,
  QUEUED, // waiting for a VM/capacity slot to become available
  ANALYZING, // detecting DB, env vars, project type
  BUILDING, // docker compose up --build (build + start)
  STARTING, // waiting for app readiness (health check)
  RUNNING,
  FAILED,
  STOPPED,
  TIMED_OUT // total startup deadline exceeded
}
