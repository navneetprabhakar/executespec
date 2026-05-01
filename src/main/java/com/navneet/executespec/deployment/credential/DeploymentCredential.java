package com.navneet.executespec.deployment.credential;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Stores encrypted GCP service account credentials for deploying to a user's own cloud project.
 *
 * <p>Credentials can be scoped to an ExecuteSpec project (different teams → different GCP projects)
 * or stored as user-level defaults (projectId = null).
 *
 * <p>Resolution order: project-level credential → user-level default → not found.
 */
@Entity
@Table(name = "deployment_credential")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeploymentCredential {

  @Id private String id;

  @Column(name = "user_id", nullable = false)
  private String userId;

  /**
   * Optional: scopes this credential to a specific ExecuteSpec project. Null means this is the
   * user's default credential (used when no project-level one exists).
   */
  @Column(name = "project_id")
  private String projectId;

  @Column(name = "cloud_provider", nullable = false)
  @Builder.Default
  private String cloudProvider = "GCP";

  @Column(name = "credential_type", nullable = false)
  @Builder.Default
  private String credentialType = "SERVICE_ACCOUNT_KEY";

  /**
   * AES-256 encrypted service account JSON key. NEVER log, return in API responses, or store in
   * plain text.
   */
  @Column(name = "encrypted_data", nullable = false, columnDefinition = "BYTEA")
  private byte[] encryptedData;

  @Column(name = "gcp_project_id")
  private String gcpProjectId;

  @Column(name = "service_account_email")
  private String serviceAccountEmail;

  @Column(name = "validated_at")
  private Instant validatedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** Create a user-level default credential (no project scope). */
  public static DeploymentCredential create(
      String userId, byte[] encryptedData, String gcpProjectId, String serviceAccountEmail) {
    return create(userId, null, encryptedData, gcpProjectId, serviceAccountEmail);
  }

  /** Create a credential, optionally scoped to an ExecuteSpec project. */
  public static DeploymentCredential create(
      String userId,
      String projectId,
      byte[] encryptedData,
      String gcpProjectId,
      String serviceAccountEmail) {
    var now = Instant.now();
    return DeploymentCredential.builder()
        .id(UUID.randomUUID().toString())
        .userId(userId)
        .projectId(projectId)
        .cloudProvider("GCP")
        .credentialType("SERVICE_ACCOUNT_KEY")
        .encryptedData(encryptedData)
        .gcpProjectId(gcpProjectId)
        .serviceAccountEmail(serviceAccountEmail)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  public void markValidated() {
    this.validatedAt = Instant.now();
    this.updatedAt = Instant.now();
  }
}
