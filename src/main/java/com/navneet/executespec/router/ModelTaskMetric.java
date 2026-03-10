package com.navneet.executespec.router;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Tracks outcomes of model usage per task for future data-driven routing.
 * Collected from day 1, used for routing decisions in Phase 2.
 */
@Entity
@Table(name = "model_task_metric")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelTaskMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id")
    private String runId;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "model_name", nullable = false)
    private String modelName;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "tier", nullable = false)
    private String tier;

    @Column(name = "complexity", nullable = false)
    private String complexity;

    @Column(name = "language")
    private String language;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "validation_passed", nullable = false)
    private boolean validationPassed;

    @Column(name = "error_count")
    private int errorCount;

    @Column(name = "first_error_gate")
    private String firstErrorGate;

    @Column(name = "duration_ms")
    private long durationMs;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
