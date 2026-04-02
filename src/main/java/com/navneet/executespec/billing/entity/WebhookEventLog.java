package com.navneet.executespec.billing.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "webhook_event_log", uniqueConstraints = @UniqueConstraint(columnNames = {"gateway", "event_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookEventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String gateway;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "payload_hash", length = 64)
    private String payloadHash;

    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    /** RECEIVED, PROCESSED, IGNORED, FAILED */
    @Column(name = "processing_status", nullable = false, length = 32)
    @Builder.Default
    private String processingStatus = "RECEIVED";

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Builder.Default
    private int attempts = 1;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;
}
