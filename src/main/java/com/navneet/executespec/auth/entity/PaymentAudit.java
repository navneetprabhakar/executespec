package com.navneet.executespec.auth.entity;

import java.time.Instant;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_audit")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentAudit {

  @Id private String id;

  /** USER | ORG */
  @Column(name = "entity_type", nullable = false, length = 32)
  private String entityType;

  @Column(name = "entity_id", nullable = false)
  private String entityId;

  @Column(name = "event_type", nullable = false, length = 64)
  private String eventType;

  @Column(name = "old_tier", length = 32)
  private String oldTier;

  @Column(name = "new_tier", length = 32)
  private String newTier;

  @Column(name = "payment_ref_id")
  private String paymentRefId;

  @Column(name = "performed_by")
  private String performedBy;

  @Column(columnDefinition = "TEXT")
  private String notes;

  @Column(name = "gateway_provider", length = 20)
  private String gatewayProvider;

  @Column(name = "gateway_event_id")
  private String gatewayEventId;

  @Column(name = "gateway_payment_id")
  private String gatewayPaymentId;

  @Column(name = "currency", length = 8)
  private String currency;

  @Column(name = "amount_minor")
  private Long amountMinor;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata", columnDefinition = "jsonb")
  private Map<String, Object> metadata;

  @Column(name = "idempotency_key")
  private String idempotencyKey;

  @Column(name = "user_id")
  private String userId;

  @Column(name = "org_id")
  private String orgId;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();
}
