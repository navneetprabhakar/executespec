package com.navneet.executespec.agentsession.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Durable conversation/session shell for CLI + VS Code agent surfaces.
 *
 * <p>This is intentionally separate from {@code pipeline_run}: open-ended conversation may inspect
 * projects, list gates, or propose a run, but it is not itself a billable execution run.
 */
@Entity
@Table(name = "agent_session")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSession {

  @Id private String id;

  @Column(name = "org_id", nullable = false)
  private String orgId;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(name = "project_id")
  private String projectId;

  @Column(name = "repo_remote", columnDefinition = "TEXT")
  private String repoRemote;

  @Column(name = "repo_root_hash", length = 128)
  private String repoRootHash;

  @Column(name = "client_surface", nullable = false, length = 24)
  @Builder.Default
  private String clientSurface = "CLI";

  @Column(nullable = false, length = 240)
  private String title;

  @Column(nullable = false, length = 24)
  @Builder.Default
  private String status = "ACTIVE";

  @Column(name = "default_provider", length = 32)
  private String defaultProvider;

  @Column(name = "default_model", length = 128)
  private String defaultModel;

  @Column(name = "default_execution_mode", nullable = false, length = 24)
  @Builder.Default
  private String defaultExecutionMode = "LOCAL";

  @Column(name = "approval_policy", nullable = false, length = 24)
  @Builder.Default
  private String approvalPolicy = "PLAN";

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private Instant updatedAt = Instant.now();
}
