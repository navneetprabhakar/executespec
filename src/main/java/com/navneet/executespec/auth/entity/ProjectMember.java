package com.navneet.executespec.auth.entity;

import java.time.Instant;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "project_member",
    uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "user_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectMember {

  @Id private String id;

  @Column(name = "project_id", nullable = false)
  private String projectId;

  @Column(name = "user_id", nullable = false)
  private String userId;

  /** PROJECT_ADMIN | PROJECT_DEVELOPER */
  @Column(name = "project_role", nullable = false)
  @Builder.Default
  private String projectRole = "PROJECT_DEVELOPER";

  @Column(name = "assigned_at", nullable = false)
  @Builder.Default
  private Instant assignedAt = Instant.now();
}
