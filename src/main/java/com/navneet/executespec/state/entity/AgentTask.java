package com.navneet.executespec.state.entity;

import com.navneet.executespec.tenant.TenantInterceptor;
import jakarta.persistence.*;
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

    @Column(columnDefinition = "TEXT")
    private String completionNotes;       // Worker's success/summary message

    /**
     * v2.5.11 (Validation v3 / Pillar 1, V109) — JSON array of requirement IDs
     * (from {@code PipelineRun.planRequirementsJson}) that this task is
     * expected to implement. Null for legacy rows and runs submitted while
     * {@code validation.v3-strict} is off. The reviewer cross-checks this
     * against {@code RequirementEvidence} rows in Pillar 2.
     */
    @Column(name = "meets_requirements_json", columnDefinition = "TEXT")
    private String meetsRequirementsJson;

    private String assignedModel;
    private String branchName;          // agent/{runId}/{taskId}

    private Instant startedAt;
    private Instant completedAt;
    private Long durationMs;

    private Integer inputTokens;
    private Integer outputTokens;
    private Integer compactionCount;    // how many times context was compacted

    @Builder.Default
    private Integer cacheReadTokens  = 0;  // Anthropic cache_read_input_tokens (billed at 0.10x)
    @Builder.Default
    private Integer cacheWriteTokens = 0;  // Anthropic cache_creation_input_tokens (billed at 1.25x)

    // ── Tier 3 fields ─────────────────────────────────────────────────────────
    private Boolean readmeUpdated;      // null = not yet reviewed, true/false = reviewer verdict
    private String provider;            // provider used for this task (e.g. "anthropic")
    private String model;               // model used (e.g. "claude-sonnet-4-5")

    // ── Tier 5 fields — stuck detection & recovery ────────────────────────────
    @Column(name = "last_activity_at")
    private Instant lastActivityAt;

    @Column(name = "stuck_detected_at")
    private Instant stuckDetectedAt;

    @Column(name = "recovery_attempt")
    private Integer recoveryAttempt;

    // ── Tier 5 fields — LLM escalation ────────────────────────────────────────
    @Column(name = "current_provider")
    private String currentProvider;

    @Column(name = "current_model")
    private String currentModel;

    @Column(name = "last_llm_failure")
    private String lastLlmFailure;      // LlmFailure type name or special flag

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private String tenantId = "default";

    // ── Subtask decomposition ─────────────────────────────────────────────────
    @Column(name = "parent_task_id")
    private String parentTaskId;        // null for top-level tasks

    @Column(name = "subtask_index")
    private Integer subtaskIndex;       // 1-based index within parent

    @Column(name = "subtask_total")
    private Integer subtaskTotal;       // total subtasks in parent

    // ── Prompt tracking ──────────────────────────────────────────────────────
    @Column(name = "prompt_hash", length = 12)
    private String promptHash;          // 12-char SHA-256 prefix of assembled prompt

}
