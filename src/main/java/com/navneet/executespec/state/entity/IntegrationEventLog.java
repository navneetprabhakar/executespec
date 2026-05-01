package com.navneet.executespec.state.entity;

import java.time.Instant;

import jakarta.persistence.*;

import com.navneet.executespec.tenant.TenantInterceptor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Idempotency store — prevents duplicate runs from retried webhook deliveries. */
@Entity
@EntityListeners(TenantInterceptor.class)
@Table(
    name = "integration_event_log",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_integration_event_log_source_event",
            columnNames = {"source_type", "event_id"}),
    indexes = {
      @Index(name = "idx_integration_event_log_lookup", columnList = "source_type, event_id")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntegrationEventLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "source_type", nullable = false)
  private String sourceType;

  @Column(name = "event_id", nullable = false)
  private String eventId; // webhook delivery ID (idempotency key)

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "tenant_id", nullable = false)
  @Builder.Default
  private String tenantId = "default";
}
