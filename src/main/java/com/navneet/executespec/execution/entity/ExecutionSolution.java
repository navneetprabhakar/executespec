package com.navneet.executespec.execution.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "execution_solution")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
@ToString(exclude = {"executionRun", "dependencies", "repoMappings"})
public class ExecutionSolution {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_run_id", nullable = false)
    private ExecutionRun executionRun;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "failure_phase", length = 20)
    private String failurePhase;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    @Builder.Default
    private int maxRetries = 3;

    @Column(length = 10)
    private String complexity;

    @Column(name = "estimated_tokens")
    private Long estimatedTokens;

    @Column(name = "actual_tokens", nullable = false)
    @Builder.Default
    private Long actualTokens = 0L;

    @Column(name = "cost_usd", nullable = false, precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal costUsd = BigDecimal.ZERO;

    @Column(name = "git_tag_start", length = 255)
    private String gitTagStart;

    @Column(name = "git_tag_end", length = 255)
    private String gitTagEnd;

    @Column(name = "pipeline_run_id", length = 36)
    private String pipelineRunId;

    @Column(name = "pr_number")
    private Integer prNumber;

    @Column(name = "pr_url", length = 500)
    private String prUrl;

    @Column(name = "pr_status", length = 20)
    private String prStatus;

    @Column(name = "jira_ticket_id", length = 50)
    private String jiraTicketId;

    @Column(name = "jira_enabled", nullable = false)
    @Builder.Default
    private boolean jiraEnabled = false;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "execution_solution_dependency",
            joinColumns = @JoinColumn(name = "solution_id"),
            inverseJoinColumns = @JoinColumn(name = "depends_on_id"))
    @Builder.Default
    private Set<ExecutionSolution> dependencies = new HashSet<>();

    @OneToMany(mappedBy = "solution", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ExecutionSolutionRepo> repoMappings;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = "QUEUED";
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
