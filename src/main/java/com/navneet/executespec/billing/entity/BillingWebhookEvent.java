package com.navneet.executespec.billing.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "billing_webhook_event")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** RAZORPAY, STRIPE */
    @Column(nullable = false, length = 20)
    private String gateway;

    @Column(name = "event_id", nullable = false, length = 200)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb")
    private String rawPayload;

    @Column(nullable = false)
    private boolean processed;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
