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
    WorkspaceConfig workspace
) {
    public record ArtifactRegistryConfig(String repository, String host) {}
    public record CloudRunConfig(String cpu, String memory, int maxInstances,
                                  boolean allowUnauthenticated) {}
    public record DockerConfig(int buildTimeoutSeconds) {}
    public record StaticConfig(String nginxImage) {}
    public record SpringBootConfig(String baseImage) {}
    public record WorkspaceConfig(
        int    retentionDays,
        String gcsBucket,          // GCS bucket for archived workspaces
        int    archiveAfterHours,  // hours after run completion before archiving (default 24)
        boolean gcsEnabled         // false = skip all GCS uploads/archiving (local dev)
    ) {}
}
