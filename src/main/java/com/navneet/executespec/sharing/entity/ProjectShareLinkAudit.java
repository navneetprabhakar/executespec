package com.navneet.executespec.sharing.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Append-only, secret-free lifecycle/access fact for a public project-share credential. */
@Entity
@Table(name = "project_share_link_audit")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ProjectShareLinkAudit {

  @Id private UUID id;

  @Column(name = "link_id", nullable = false)
  private UUID linkId;

  @Column(name = "project_id", nullable = false, length = 255)
  private String projectId;

  @Column(name = "event_type", nullable = false, length = 16)
  private String eventType;

  @Column(nullable = false, length = 48)
  private String outcome;

  @Column(nullable = false, length = 32)
  private String surface;

  @Column(name = "actor_user_id", length = 255)
  private String actorUserId;

  @Column(name = "client_ip_hash", length = 64)
  private String clientIpHash;

  @Column(name = "user_agent_hash", length = 64)
  private String userAgentHash;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;
}
