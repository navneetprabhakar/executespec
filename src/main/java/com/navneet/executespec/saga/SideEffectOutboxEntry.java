package com.navneet.executespec.saga;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "side_effect_outbox")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SideEffectOutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(name = "effect_type", nullable = false)
    private String effectType;

    @Column(columnDefinition = "JSONB", nullable = false)
    private String payload;  // JSON string

    @Column(nullable = false)
    private String status;   // PENDING | PROCESSING | COMPLETED | FAILED

    @Column(nullable = false)
    private int attempt;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "next_retry_at", nullable = false)
    private Instant nextRetryAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
