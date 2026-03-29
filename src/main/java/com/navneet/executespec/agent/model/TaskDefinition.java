package com.navneet.executespec.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TaskDefinition {

    private String taskId;
    private String title;
    private String objective;
    private String boundaries;
    private String outputFormat;
    private String acceptanceCriteria;
    private List<String> dependsOn;
    private String complexity;          // LOW | MEDIUM | HIGH
    private String assignedModel;       // LLM to use; set by planner based on complexity — null = system default
    private String taskLayer;           // BACKEND | FRONTEND | DATABASE | INFRA

    // ── Tier 3 nullable fields (only set in FEASIBILITY mode) ────────────────
    private IterationEstimate estimatedIterations;       // null unless feasibility mode
    private Integer estimatedTokensPerIteration;         // null unless feasibility mode

    // ── File ownership ──────────────────────────────────────────────────────────
    // Declared by the planner: list of file paths this task will create or modify.
    // Used by PipelineHarness to pre-claim file locks before worker execution.
    private List<String> ownedFiles;

    // Files this task shares with other tasks (both tasks may modify).
    // Used for conflict-risk analysis — tasks sharing files should have depends_on
    // relationships or the planner should create a dedicated setup sub-task.
    private List<String> sharedFiles;

    // ── Auto-remediation feedback ─────────────────────────────────────────────
    // Injected by PipelineHarness during REWORK remediation cycles so the worker
    // knows exactly what the reviewer flagged. Null on first attempt.
    private String reworkFeedback;
}
