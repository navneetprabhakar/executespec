package com.navneet.executespec.integration.slack.model;

import java.time.Instant;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "org_slack_integration")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrgSlackIntegration {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "org_id", nullable = false)
  private String orgId;

  @Column(name = "team_id", nullable = false)
  private String teamId;

  @Column(name = "team_name")
  private String teamName;

  @Column(name = "bot_token", nullable = false, columnDefinition = "TEXT")
  private String botToken;

  @Column(name = "bot_user_id")
  private String botUserId;

  @Column(name = "installed_by")
  private String installedBy;

  @Column(name = "installed_at", nullable = false)
  @Builder.Default
  private Instant installedAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private Instant updatedAt = Instant.now();
}
