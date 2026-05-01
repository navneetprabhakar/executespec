package com.navneet.executespec.deployment.preview;

import java.time.Duration;
import java.time.Instant;

public record PreviewSession(
    String runId,
    String composeProjectName, // docker compose --project-name value
    PreviewProjectType projectType,
    PreviewMode previewMode, // WEB, CLI_TERMINAL, or MCP_INSPECTOR
    DatabaseType databaseType,
    PreviewStatus status,
    String url, // https://preview-{short-id}.executespec.ai or Cloud Run URL
    int hostPort, // allocated host port (for Docker cleanup; 0 for Cloud Run)
    String errorMessage,
    Instant startedAt,
    Instant expiresAt,
    String cloudRunServiceName, // Cloud Run service name (null for Docker mode)
    String imageUri, // Artifact Registry image URI (null for Docker mode)
    String buildOutput, // Docker build output (Cloud Run mode; null for Docker mode)
    String dockerfile // Generated Dockerfile content (Cloud Run mode; null for Docker mode)
    ) {
  /** Backward-compatible constructor without build diagnostics. */
  public PreviewSession(
      String runId,
      String composeProjectName,
      PreviewProjectType projectType,
      PreviewMode previewMode,
      DatabaseType databaseType,
      PreviewStatus status,
      String url,
      int hostPort,
      String errorMessage,
      Instant startedAt,
      Instant expiresAt,
      String cloudRunServiceName,
      String imageUri) {
    this(
        runId,
        composeProjectName,
        projectType,
        previewMode,
        databaseType,
        status,
        url,
        hostPort,
        errorMessage,
        startedAt,
        expiresAt,
        cloudRunServiceName,
        imageUri,
        null,
        null);
  }

  /** Backward-compatible constructor for Docker mode. */
  public PreviewSession(
      String runId,
      String composeProjectName,
      PreviewProjectType projectType,
      PreviewMode previewMode,
      DatabaseType databaseType,
      PreviewStatus status,
      String url,
      int hostPort,
      String errorMessage,
      Instant startedAt,
      Instant expiresAt) {
    this(
        runId,
        composeProjectName,
        projectType,
        previewMode,
        databaseType,
        status,
        url,
        hostPort,
        errorMessage,
        startedAt,
        expiresAt,
        null,
        null,
        null,
        null);
  }

  public boolean isExpired() {
    return expiresAt != null && Instant.now().isAfter(expiresAt);
  }

  /** Seconds remaining until auto-shutdown. Returns 0 if expired. */
  public long ttlRemainingSeconds() {
    if (expiresAt == null) return 0;
    long remaining = Duration.between(Instant.now(), expiresAt).toSeconds();
    return Math.max(0, remaining);
  }
}
