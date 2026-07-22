package com.navneet.executespec.state.entity;

import java.time.Instant;

import jakarta.persistence.*;

import com.navneet.executespec.tenant.TenantInterceptor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@EntityListeners(TenantInterceptor.class)
@Table(name = "agent_task")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTask {

  @Id private String taskId; // from planner JSON (snake_case)

  private String runId; // FK to pipeline_run

  private String title;
  private String complexity; // LOW | MEDIUM | HIGH
  private String status; // PENDING | LOCKED | WORKING | COMPLETE | PARTIAL | FAILED | BLOCKED

  @Column(columnDefinition = "TEXT")
  private String taskJson; // full TaskDefinition JSON

  @Column(columnDefinition = "TEXT")
  private String workerReportJson;

  @Column(columnDefinition = "TEXT")
  private String completionNotes; // Worker's success/summary message

  /**
   * v2.5.11 (Validation v3 / Pillar 1, V109) — JSON array of requirement IDs (from {@code
   * PipelineRun.planRequirementsJson}) that this task is expected to implement. Null for legacy
   * rows and runs submitted while {@code validation.v3-strict} is off. The reviewer cross-checks
   * this against {@code RequirementEvidence} rows in Pillar 2.
   */
  @Column(name = "meets_requirements_json", columnDefinition = "TEXT")
  private String meetsRequirementsJson;

  private String assignedModel;
  private String branchName; // agent/{runId}/{taskId}

  private Instant startedAt;
  private Instant completedAt;
  private Long durationMs;

  private Integer inputTokens;
  private Integer outputTokens;
  private Integer compactionCount; // how many times context was compacted

  @Builder.Default
  private Integer cacheReadTokens = 0; // Anthropic cache_read_input_tokens (billed at 0.10x)

  @Builder.Default
  private Integer cacheWriteTokens = 0; // Anthropic cache_creation_input_tokens (billed at 1.25x)

  private Boolean readmeUpdated; // null = not yet reviewed, true/false = reviewer verdict
  // NOTE: `provider`/`model` are the REQUESTED, tier-resolved route captured from
  // SequentialExecutor.tierConfigFor. They are what the executor asked for, not necessarily what
  // served the task — cross-provider failover and managed-AI tier locks rebuild the request before
  // dispatch. The `effective*` columns below (V305) carry the physical route projected from the
  // run_step_usage ledger. Do not redefine these two columns; historical rows live in them.
  private String provider; // requested/tier-resolved provider for this task (e.g. "anthropic")
  private String model; // requested/tier-resolved model (e.g. "claude-sonnet-4-5")

  /** V305 — physical provider that actually served this task, projected from the usage ledger. */
  @Column(name = "effective_provider", length = 40)
  private String effectiveProvider;

  /** V305 — physical model that actually served this task, projected from the usage ledger. */
  @Column(name = "effective_model", length = 128)
  private String effectiveModel;

  /**
   * V305 — {@code LEDGER} / {@code MIXED} / {@code UNATTRIBUTED}. MIXED means more than one route
   * served the task and is deliberately NOT collapsed to a single provider.
   */
  @Column(name = "effective_route_attribution", length = 24)
  private String effectiveRouteAttribution;

  // Tier 5 fields — stuck detection & recovery
  @Column(name = "last_activity_at")
  private Instant lastActivityAt;

  @Column(name = "stuck_detected_at")
  private Instant stuckDetectedAt;

  @Column(name = "recovery_attempt")
  private Integer recoveryAttempt;

  // Tier 5 fields — LLM escalation
  @Column(name = "current_provider")
  private String currentProvider;

  @Column(name = "current_model")
  private String currentModel;

  // 2026-05-10 (V184) — widened to TEXT after the dev-run analysis on
  // 7483896a showed the soft-completion path's `softWarnReason` (worker
  // stopped without completeTask + the historical assistant-text tail) routinely exceeded 255
  // chars and broke the COMPLETE/PARTIAL DB flip. Current writers persist metadata only.
  // Companion defensive cap at SequentialExecutor.persistTaskStatus
  // ensures the column never absorbs unbounded router stack traces.
  @Column(name = "last_llm_failure", columnDefinition = "TEXT")
  private String lastLlmFailure; // LlmFailure type name or special flag

  /**
   * V190 (2026-05-13, PR-A) — structured worker-outcome diagnostic for non-COMPLETE tasks.
   * Populated by {@code SequentialExecutor.persistTaskStatus} at terminal time when the task status
   * is anything other than COMPLETE. NULL on COMPLETE tasks. JSON shape (all keys optional):
   *
   * <pre>{
   *   "outcome":           "STOPPED_WITHOUT_COMPLETE",
   *   "iterations":         47,
   *   "iterBudget":         75,
   *   "lastToolCall":      "writeFile(sig=…)[target=app/x.py] -> ERR"
   * }</pre>
   *
   * <p>Surfaced via {@code GET /api/runs/{id}} so operators can diagnose stuck/FAILED tasks without
   * SSH access to the app log. Replaces the lossy single-string {@code error_message} / {@code
   * last_llm_failure} that buried the worker outcome enum + tool history.
   */
  @Column(name = "failure_diagnostic_json", columnDefinition = "TEXT")
  private String failureDiagnosticJson;

  /**
   * V250 (RL-2026-06-198) — structured goal-doneness result emitted by {@code SequentialExecutor}
   * at terminal task boundaries. Mirrors the SSE "Goal decision" event so API, web, and terminal
   * clients do not need to scrape logs.
   */
  @Column(name = "goal_doneness_json", columnDefinition = "TEXT")
  private String goalDonenessJson;

  /**
   * V250 (RL-2026-06-198) — structured continuation advice emitted by {@code SequentialExecutor}.
   * Advisory-only: this records step-up/step-down/provider-change recommendations without implying
   * that the executor switched model/provider.
   */
  @Column(name = "continuation_decision_json", columnDefinition = "TEXT")
  private String continuationDecisionJson;

  /**
   * V252 (RL-2026-06-205) — append-only JSON array of continuation actions actually attempted or
   * skipped by {@code SequentialExecutor}. This preserves automatic model/provider upgrades after a
   * rerun overwrites the final advisory decision.
   */
  @Column(name = "continuation_actions_json", columnDefinition = "TEXT")
  private String continuationActionsJson;

  @Column(name = "tenant_id", nullable = false)
  @Builder.Default
  private String tenantId = "default";

  @Column(name = "parent_task_id")
  private String parentTaskId; // null for top-level tasks

  // RR-1446 — zero-based planner-intent order within the plan. Primary DTO sort key so the
  // approval gate shows the planner's sequence; status is decoration only. Null for legacy rows.
  @Column(name = "plan_index")
  private Integer planIndex;

  @Column(name = "subtask_index")
  private Integer subtaskIndex; // 1-based index within parent

  @Column(name = "subtask_total")
  private Integer subtaskTotal; // total subtasks in parent

  @Column(name = "prompt_hash", length = 12)
  private String promptHash; // 12-char SHA-256 prefix of assembled prompt

  // Hierarchical Planner (v2.6.2 — V130)
  /** "LEAF" (terminal task) | "EPIC" (will spawn subtasks via expander). */
  @Column(name = "task_type", length = 16)
  private String taskType;

  /** "PENDING" | "EXPANDED" | "FAILED" | null (not part of hierarchical run). */
  @Column(name = "expansion_status", length = 16)
  private String expansionStatus;

  @Column(name = "expanded_at")
  private java.time.Instant expandedAt;
}
