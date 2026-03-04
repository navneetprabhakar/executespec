package com.navneet.agentteam.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "payment_audit")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentAudit {

    @Id
    private String id;

    /** USER | ORG */
    @Column(name = "entity_type", nullable = false, length = 32)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private String entityId;

    /** PLAN_ASSIGNED | PLAN_UPGRADED | PLAN_DOWNGRADED | PAYMENT_RECEIVED | PAYMENT_FAILED
     *  | SUBSCRIPTION_CANCELLED | MANUAL_OVERRIDE */
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

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
