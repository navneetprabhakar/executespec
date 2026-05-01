package com.navneet.executespec.billing.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "run_topup")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunTopup {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "user_id")
  private String userId;

  @Column(name = "org_id")
  private String orgId;

  @Column(nullable = false)
  private int quantity;

  @Column(nullable = false)
  private int remaining;

  /** PURCHASE or ADMIN_GRANT */
  @Column(nullable = false, length = 20)
  private String source;

  @Column(name = "gateway_order_id", length = 100)
  private String gatewayOrderId;

  @Column(name = "gateway_payment_id", length = 100)
  private String gatewayPaymentId;

  @Column(name = "amount_paid_inr")
  private long amountPaidInr;

  @Column(name = "granted_by")
  private String grantedBy;

  @Column(name = "grant_reason", columnDefinition = "TEXT")
  private String grantReason;

  /** NULL = never expires (purchases); set for admin grants */
  @Column(name = "expires_at")
  private Instant expiresAt;

  /** PENDING, ACTIVE, EXHAUSTED, EXPIRED */
  @Column(nullable = false, length = 20)
  private String status;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void prePersist() {
    var now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
    if (status == null) status = "PENDING";
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }
}
