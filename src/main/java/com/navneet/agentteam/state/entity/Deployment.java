package com.navneet.agentteam.state.entity;

import com.navneet.agentteam.tenant.TenantInterceptor;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@EntityListeners(TenantInterceptor.class)
@Table(name = "deployment")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Deployment {

    @Id
    private String id;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(name = "run_repo_id")
    private String runRepoId;

    @Column(name = "version_tag", nullable = false)
    private String versionTag;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeployStrategy strategy;

    @Column(name = "image_uri", nullable = false)
    private String imageUri;

    @Column(name = "service_name", nullable = false)
    private String serviceName;

    @Column(name = "revision_name")
    private String revisionName;

    @Column(name = "service_url")
    private String serviceUrl;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeployStatus status = DeployStatus.PENDING;

    @Column(name = "gcp_project", nullable = false)
    private String gcpProject;

    @Column(name = "gcp_region", nullable = false)
    private String gcpRegion;

    @Column(name = "estimated_cost_usd")
    private Double estimatedCostUsd;

    @Column(name = "actual_cost_usd")
    private Double actualCostUsd;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "dockerfile_path")
    private String dockerfilePath;

    @Column(name = "build_log", length = 8000)
    private String buildLog;

    @Column(name = "ttl_expires_at", nullable = false)
    private String ttlExpiresAt;

    @Column(name = "created_at", nullable = false)
    private String createdAt;

    @Column(name = "deployed_at")
    private String deployedAt;

    @Column(name = "deleted_at")
    private String deletedAt;

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private String tenantId = "default";


    public enum DeployStrategy { SPRING_BOOT, STATIC, PYTHON, GO, RUBY, PHP }

    public enum DeployStatus {
        PENDING, BUILDING, PUSHING, DEPLOYING, LIVE,
        CLEANING_UP, DELETED, FAILED
    }

    public static Deployment create(String runId, String versionTag, DeployStrategy strategy,
                                     String imageUri, String serviceName,
                                     String gcpProject, String gcpRegion, int ttlDays) {
        Deployment d = new Deployment();
        d.id = UUID.randomUUID().toString();
        d.runId = runId;
        d.versionTag = versionTag;
        d.strategy = strategy;
        d.imageUri = imageUri;
        d.serviceName = serviceName;
        d.status = DeployStatus.PENDING;
        d.gcpProject = gcpProject;
        d.gcpRegion = gcpRegion;
        d.estimatedCostUsd = 0.008;
        d.ttlExpiresAt = Instant.now().plus(ttlDays, ChronoUnit.DAYS).toString();
        d.createdAt = Instant.now().toString();
        return d;
    }

    public void markLive(String serviceUrl, String revisionName) {
        this.serviceUrl = serviceUrl;
        this.revisionName = revisionName;
        this.deployedAt = Instant.now().toString();
        this.status = DeployStatus.LIVE;
    }

    public void markFailed(String error) {
        this.errorMessage = error;
        this.status = DeployStatus.FAILED;
    }

    public void markDeleted() {
        this.deletedAt = Instant.now().toString();
        this.status = DeployStatus.DELETED;
    }
}
