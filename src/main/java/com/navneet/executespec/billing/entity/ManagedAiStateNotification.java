package com.navneet.executespec.billing.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "managed_ai_state_notification")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManagedAiStateNotification {

  @Id private String id;

  @Column(name = "period_id", nullable = false)
  private String periodId;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(name = "org_id")
  private String orgId;

  @Column(name = "from_state", length = 32)
  private String fromState;

  @Column(name = "to_state", nullable = false, length = 32)
  private String toState;

  @Column(nullable = false, length = 16)
  private String channel;

  @Column(name = "sent_at", nullable = false)
  private Instant sentAt;
}
