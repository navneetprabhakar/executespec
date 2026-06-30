package com.navneet.executespec.billing.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "managed_ai_usage_period")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManagedAiUsagePeriod {

  @Id private String id;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(name = "org_id")
  private String orgId;

  @Column(nullable = false, length = 32)
  private String tier;

  @Column(name = "period_start", nullable = false)
  private Instant periodStart;

  @Column(name = "period_end", nullable = false)
  private Instant periodEnd;

  @Column(name = "included_ai_credits", nullable = false)
  private long includedAiCredits;

  @Column(name = "purchased_ai_credits", nullable = false)
  private long purchasedAiCredits;

  @Column(name = "ai_credits_used", nullable = false)
  private long aiCreditsUsed;

  @Column(name = "raw_cogs_cap_usd", nullable = false)
  private double rawCogsCapUsd;

  @Column(name = "raw_cogs_used_usd", nullable = false)
  private double rawCogsUsedUsd;

  @Column(name = "frontier_raw_cap_usd", nullable = false)
  private double frontierRawCapUsd;

  @Column(name = "frontier_raw_used_usd", nullable = false)
  private double frontierRawUsedUsd;

  @Column(name = "value_mode_after_raw_usd", nullable = false)
  private double valueModeAfterRawUsd;

  @Column(name = "cheap_only_after_raw_usd", nullable = false)
  private double cheapOnlyAfterRawUsd;

  @Column(name = "hard_stop_after_raw_usd", nullable = false)
  private double hardStopAfterRawUsd;

  @Column(name = "routing_state", nullable = false, length = 32)
  private String routingState;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version private long version;
}
