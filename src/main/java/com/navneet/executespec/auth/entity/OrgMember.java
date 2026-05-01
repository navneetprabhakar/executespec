package com.navneet.executespec.auth.entity;

import java.time.Instant;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "org_member",
    uniqueConstraints = @UniqueConstraint(columnNames = {"org_id", "user_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrgMember {

  @Id private String id;

  @Column(name = "org_id", nullable = false)
  private String orgId;

  @Column(name = "user_id", nullable = false)
  private String userId;

  /** ORG_ADMIN | DEVELOPER | BILLING_ADMIN */
  @Column(name = "org_role", nullable = false)
  @Builder.Default
  private String orgRole = "DEVELOPER";

  @Column(name = "joined_at", nullable = false)
  @Builder.Default
  private Instant joinedAt = Instant.now();
}
