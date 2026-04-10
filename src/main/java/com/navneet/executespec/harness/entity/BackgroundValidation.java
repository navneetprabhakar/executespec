package com.navneet.executespec.harness.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * DB-backed record for a background validation run (review + test gen + report).
 * Replaces the in-memory {@code ConcurrentHashSet<String> backgroundValidationRuns}.
 */
@Entity
@Table(name = "background_validation")
@Data
@NoArgsConstructor
public class BackgroundValidation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(nullable = false)
    private String status = "PENDING"; // PENDING, RUNNING, COMPLETED, FAILED

    @Column(name = "current_phase")
    private String currentPhase;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "phase_results", columnDefinition = "jsonb")
    private Map<String, Object> phaseResults = new java.util.LinkedHashMap<>();

    @Column(nullable = false)
    private int attempt = 1;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public static BackgroundValidation create(String runId) {
        BackgroundValidation bv = new BackgroundValidation();
        bv.runId = runId;
        bv.status = "PENDING";
        bv.attempt = 1;
        bv.maxAttempts = 3;
        bv.createdAt = Instant.now();
        return bv;
    }

    public boolean isTerminal() {
        return "COMPLETED".equals(status) || "FAILED".equals(status);
    }

    public boolean canRetry() {
        return "FAILED".equals(status) && attempt < maxAttempts;
    }
}
