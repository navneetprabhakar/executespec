package com.navneet.agentteam.agent.model;

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

    // ── Tier 3 nullable fields (only set in FEASIBILITY mode) ────────────────
    private IterationEstimate estimatedIterations;       // null unless feasibility mode
    private Integer estimatedTokensPerIteration;         // null unless feasibility mode
}
