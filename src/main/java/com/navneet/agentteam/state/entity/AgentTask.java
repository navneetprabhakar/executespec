package com.navneet.agentteam.state.entity;

import com.navneet.agentteam.tenant.TenantInterceptor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@EntityListeners(TenantInterceptor.class)
@Table(name = "agent_task")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTask {

    @Id
    private String taskId;              // from planner JSON (snake_case)

    private String runId;               // FK to pipeline_run

    private String title;
    private String complexity;          // LOW | MEDIUM | HIGH
    private String status;              // PENDING | LOCKED | WORKING | COMPLETE | PARTIAL | FAILED | BLOCKED

    @Column(columnDefinition = "TEXT")
    private String taskJson;            // full TaskDefinition JSON

    @Column(columnDefinition = "TEXT")
    private String workerReportJson;

    private String assignedModel;
    private String branchName;          // agent/{runId}/{taskId}

    private Instant startedAt;
    private Instant completedAt;
    private Long durationMs;

    private Integer inputTokens;
    private Integer outputTokens;
    private Integer compactionCount;    // how many times context was compacted

    @Column(columnDefinition = "TEXT")
    private String lastCheckpointPath;  // path to most recent checkpoint file

    // ── Tier 3 fields ─────────────────────────────────────────────────────────
    private Boolean readmeUpdated;      // null = not yet reviewed, true/false = reviewer verdict
    private String provider;            // provider used for this task (e.g. "anthropic")
    private String model;               // model used (e.g. "claude-sonnet-4-5-20250929")

    // ── Tier 5 fields — stuck detection & recovery ────────────────────────────
    @Column(name = "last_activity_at")
    private Instant lastActivityAt;

    @Column(name = "stuck_detected_at")
    private Instant stuckDetectedAt;

    @Column(name = "recovery_attempt")
    private Integer recoveryAttempt;        // null = 0 for legacy rows

    // ── Tier 5 fields — LLM escalation ────────────────────────────────────────
    @Column(name = "current_provider")
    private String currentProvider;     // null = use global config

    @Column(name = "current_model")
    private String currentModel;        // null = use global config

    @Column(name = "last_llm_failure")
    private String lastLlmFailure;      // LlmFailure type name or special flag

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private String tenantId = "default";

}
