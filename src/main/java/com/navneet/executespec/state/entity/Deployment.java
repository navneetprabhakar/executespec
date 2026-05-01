package com.navneet.executespec.state.entity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.*;

import com.navneet.executespec.tenant.TenantInterceptor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@EntityListeners(TenantInterceptor.class)
@Table(name = "deployment")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Deployment {

  @Id private String id;

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

  // ── Deploy Engine fields (V30) ────────────────────────────────────────────

  /** Deployment path: CLOUD_RUN_DIRECT, COMPOSE_ON_VM, MANAGED_INFRASTRUCTURE */
  @Column(name = "deployment_path")
  private String deploymentPath;

  /** FK to deployment_credential */
  @Column(name = "credential_id")
  private String credentialId;

  /** Full infrastructure manifest as JSON */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "manifest_json", columnDefinition = "jsonb")
  private Map<String, Object> manifestJson;

  /** User who initiated the deploy */
  @Column(name = "user_id")
  private String userId;

  // Path B (Compose on VM)
  @Column(name = "vm_name")
  private String vmName;

  @Column(name = "vm_zone")
  private String vmZone;

  @Column(name = "vm_external_ip")
  private String vmExternalIp;

  @Column(name = "vm_machine_type")
  private String vmMachineType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "compose_services", columnDefinition = "jsonb")
  private Map<String, Object> composeServices;

  // Path C (Terraform)
  @Column(name = "terraform_state_bucket")
  private String terraformStateBucket;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "terraform_outputs", columnDefinition = "jsonb")
  private Map<String, Object> terraformOutputs;

  // Lifecycle
  @Column(name = "auto_destroy_at")
  private Instant autoDestroyAt;

  @Column(name = "health_check_path")
  private String healthCheckPath;

  @Column(name = "app_port")
  private Integer appPort;

  // ── Enums ─────────────────────────────────────────────────────────────────

  public enum DeployStrategy {
    FULLSTACK,
    SPRING_BOOT,
    GRADLE,
    NODE,
    PYTHON,
    GO,
    RUST,
    DOTNET,
    STATIC
  }

  public enum DeployStatus {
    PENDING,
    BUILDING,
    PUSHING,
    DEPLOYING,
    LIVE,
    CLEANING_UP,
    DELETED,
    FAILED,
    // Deploy engine statuses
    ANALYZING,
    MANIFEST_READY,
    USER_CONFIRMED,
    PROVISIONING_VM,
    CONFIGURING_VM,
    DEPLOYING_COMPOSE,
    GENERATING_TERRAFORM,
    PROVISIONING_INFRA,
    HEALTH_CHECK,
    HEALTH_CHECK_FAILED,
    DEGRADED,
    STOPPED,
    ROLLING_BACK,
    ROLLED_BACK,
    DESTROYING,
    DESTROYED
  }

  public static Deployment create(
      String runId,
      String versionTag,
      DeployStrategy strategy,
      String imageUri,
      String serviceName,
      String gcpProject,
      String gcpRegion,
      int ttlDays) {
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
