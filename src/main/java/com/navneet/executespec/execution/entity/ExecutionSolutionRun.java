package com.navneet.executespec.execution.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One V2 {@code PipelineRun} dispatched under an {@link ExecutionSolution}. A
 * solution owns 1..N of these — the initial attempt, any automatic retries, and
 * any user-triggered retries. The solution's {@code pipelineRunId} is a
 * convenience pointer to the latest row; full history lives here.
 */
@Entity
@Table(name = "execution_solution_run")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ExecutionSolutionRun {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "solution_id", nullable = false, length = 36)
    private String solutionId;

    @Column(name = "pipeline_run_id", nullable = false, length = 36)
    private String pipelineRunId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "terminal_status", length = 30)
    private String terminalStatus;

    @Column(name = "input_tokens")
    private Long inputTokens;

    @Column(name = "output_tokens")
    private Long outputTokens;

    @Column(name = "cost_usd", precision = 10, scale = 4)
    private BigDecimal costUsd;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        if (startedAt == null) startedAt = now;
        if (createdAt == null) createdAt = now;
    }
}
