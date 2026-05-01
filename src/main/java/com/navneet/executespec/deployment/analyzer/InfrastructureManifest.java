package com.navneet.executespec.deployment.analyzer;

import java.util.List;

import com.navneet.executespec.deployment.preview.DatabaseType;
import com.navneet.executespec.deployment.preview.EnvVarDetector;
import com.navneet.executespec.deployment.preview.PreviewProjectType;

/**
 * Complete infrastructure profile of a generated application. Produced by {@link
 * DeploymentAnalyzer}, consumed by the decision engine.
 *
 * <p>All fields are deterministic — derived from static code analysis, not LLM inference.
 */
public record InfrastructureManifest(
    /** Detected project type (Spring Boot, Node, Python, etc.) */
    PreviewProjectType appType,

    /** Primary port the app listens on */
    int port,

    /** Whether the app is stateless (safe for Cloud Run scale-to-zero) */
    boolean stateless,

    /** Health check endpoint path (e.g., /actuator/health, /health) */
    String healthCheckPath,

    /** Database requirement, or null if none detected */
    DatabaseRequirement database,

    /** Cache requirement, or null if none detected */
    CacheRequirement cache,

    /** Message queue requirement, or null if none detected */
    QueueRequirement queue,

    /** Object storage requirement, or null if none detected */
    StorageRequirement storage,

    /** Environment variables the app needs (excluding auto-configured ones) */
    List<EnvVarDetector.DetectedEnvVar> envVars) {
  /** Convenience: true if any external stateful dependency detected */
  public boolean hasStatefulDependencies() {
    return database != null || cache != null || queue != null || storage != null;
  }

  // ── Nested requirement records ────────────────────────────────────────────

  public record DatabaseRequirement(
      DatabaseType type, String detectedFrom // e.g., "spring-boot-starter-data-jpa in pom.xml"
      ) {}

  public record CacheRequirement(CacheType type, String detectedFrom) {
    public enum CacheType {
      REDIS,
      MEMCACHED
    }
  }

  public record QueueRequirement(QueueType type, String detectedFrom) {
    public enum QueueType {
      KAFKA,
      RABBITMQ,
      PUBSUB
    }
  }

  public record StorageRequirement(StorageType type, String detectedFrom) {
    public enum StorageType {
      GCS,
      S3,
      LOCAL_FILESYSTEM
    }
  }
}
