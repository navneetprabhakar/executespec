package com.navneet.executespec.deployment;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.deployment")
public record DeploymentProperties(
    boolean enabled,
    String gcpProject,
    String gcpRegion,
    ArtifactRegistryConfig artifactRegistry,
    CloudRunConfig cloudRun,
    int ttlDays,
    String cleanupCron,
    DockerConfig docker,
    StaticConfig staticSite,
    SpringBootConfig springBoot,
    WorkspaceConfig workspace) {
  public record ArtifactRegistryConfig(String repository, String host) {}

  /**
   * @param serviceAccount runtime service account for generated Cloud Run services (RR-998).
   *     Omitting {@code --service-account} makes Cloud Run fall back to the project's default
   *     compute identity, so generated customer code inherits whatever that identity can do —
   *     obtainable as a metadata token from inside the container. This must name a dedicated,
   *     minimal-permission SA.
   * @param requireServiceAccount fail closed when {@code serviceAccount} is unset rather than
   *     silently deploying under the default identity. Default true.
   */
  public record CloudRunConfig(
      String cpu,
      String memory,
      int maxInstances,
      boolean allowUnauthenticated,
      String serviceAccount,
      Boolean requireServiceAccount) {

    /** Defaults to fail-closed when the property is absent. */
    public boolean serviceAccountRequired() {
      return requireServiceAccount == null || requireServiceAccount;
    }
  }

  public record DockerConfig(int buildTimeoutSeconds) {}

  public record StaticConfig(String nginxImage) {}

  public record SpringBootConfig(String baseImage) {}

  public record WorkspaceConfig(
      int retentionDays,
      String gcsBucket, // GCS bucket for archived workspaces
      int archiveAfterHours, // hours after run completion before archiving (default 24)
      boolean gcsEnabled // false = skip all GCS uploads/archiving (local dev)
      ) {}
}
