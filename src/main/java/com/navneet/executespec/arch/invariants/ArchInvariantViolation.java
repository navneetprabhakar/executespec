package com.navneet.executespec.arch.invariants;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * E3.14 — recorded invariant violation. Detected at either
 * {@link DetectionPhase#PLAN} (plan-time check before approval) or
 * {@link DetectionPhase#ARCH_REGEN} (continuous check on every
 * post-merge architecture-doc regeneration).
 */
@Entity
@Table(name = "arch_invariant_violation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArchInvariantViolation {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "invariant_id", nullable = false)
    private UUID invariantId;

    @Column(name = "detected_at", nullable = false)
    @Builder.Default
    private Instant detectedAt = Instant.now();

    @Column(name = "detection_phase", nullable = false, length = 32)
    private String detectionPhase;

    @Column(columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String detail;

    public DetectionPhase phaseEnum() {
        if (detectionPhase == null) return DetectionPhase.PLAN;
        try { return DetectionPhase.valueOf(detectionPhase); }
        catch (IllegalArgumentException ex) { return DetectionPhase.PLAN; }
    }

    public enum DetectionPhase { PLAN, ARCH_REGEN }
}
