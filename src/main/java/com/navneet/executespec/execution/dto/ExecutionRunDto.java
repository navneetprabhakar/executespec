package com.navneet.executespec.execution.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.execution.entity.ExecutionSolutionRepo;

public record ExecutionRunDto(
    String id,
    String projectId,
    String status,
    String requirement,
    String unifiedArchDoc,
    Instant archApprovedAt,
    String executionPlan,
    Instant planApprovedAt,
    Long totalEstimatedTokens,
    Long totalActualTokens,
    BigDecimal totalCostUsd,
    boolean jiraEnabled,
    String confluenceUrl,
    String figmaUrl,
    List<SolutionDto> solutions,
    Instant createdAt,
    Instant updatedAt) {

  public static ExecutionRunDto from(ExecutionRun r, List<ExecutionSolution> solutions) {
    List<SolutionDto> mapped =
        solutions == null ? List.of() : solutions.stream().map(SolutionDto::from).toList();
    return new ExecutionRunDto(
        r.getId(),
        r.getProjectId(),
        r.getStatus(),
        r.getRequirement(),
        r.getUnifiedArchDoc(),
        r.getArchApprovedAt(),
        r.getExecutionPlan(),
        r.getPlanApprovedAt(),
        r.getTotalEstimatedTokens(),
        r.getTotalActualTokens(),
        r.getTotalCostUsd(),
        r.isJiraEnabled(),
        r.getConfluenceUrl(),
        r.getFigmaUrl(),
        mapped,
        r.getCreatedAt(),
        r.getUpdatedAt());
  }

  /**
   * 2026-05-02 — synthetic Launch row for project-scoped {@code pipeline_run}s (the runs submitted
   * from the project chat / V3ProjectHome composer). User report: "no launches being shown although
   * run is progressing" — pipeline_run rows weren't surfaced on the Launches tab because that tab
   * originally only iterated {@code execution_run}. This factory adapts a pipeline_run into the
   * same shape so both kinds of run live in the same Launches list. No solution rows (those are an
   * ExecutionRun-only concept); the per-repo PRs surface separately via the project's Pull requests
   * tab.
   */
  public static ExecutionRunDto fromPipelineRun(
      com.navneet.executespec.state.entity.PipelineRun r) {
    return new ExecutionRunDto(
        r.getRunId(),
        r.getProjectId(),
        r.getStatus(),
        r.getUserRequest(),
        null, // unifiedArchDoc — pipeline_run doesn't carry one
        null, // archApprovedAt
        null, // executionPlan
        r.getStartedAt(), // planApprovedAt — pipeline_run pre-approves at submit
        null, // totalEstimatedTokens
        (r.getTotalInputTokens() == null && r.getTotalOutputTokens() == null)
            ? null
            : (long) (r.getTotalInputTokens() == null ? 0 : r.getTotalInputTokens())
                + (r.getTotalOutputTokens() == null ? 0 : r.getTotalOutputTokens()),
        r.getActualCostUsd() == null ? null : BigDecimal.valueOf(r.getActualCostUsd()),
        false, // jiraEnabled
        null, // confluenceUrl
        r.getFigmaUrl(),
        List.of(), // solutions — pipeline_run has no per-solution rows
        r.getStartedAt(),
        r.getCompletedAt() == null ? r.getStartedAt() : r.getCompletedAt());
  }

  public record SolutionDto(
      String id,
      int sequenceNumber,
      String title,
      String description,
      String status,
      String failurePhase,
      String failureReason,
      int retryCount,
      int maxRetries,
      // v2.5.12 — V111 failure-signature dedup columns (see SolutionExecutionService.onV2Partial).
      String lastFailureSignature,
      int consecutiveFailureCount,
      String complexity,
      Long estimatedTokens,
      Long actualTokens,
      BigDecimal costUsd,
      String pipelineRunId,
      Integer prNumber,
      String prUrl,
      String prStatus,
      String jiraTicketId,
      boolean jiraEnabled,
      Instant startedAt,
      Instant completedAt,
      Set<String> dependencyIds,
      List<RepoMappingDto> repos) {

    public static SolutionDto from(ExecutionSolution s) {
      Set<String> depIds =
          s.getDependencies() == null
              ? Set.of()
              : s.getDependencies().stream()
                  .map(ExecutionSolution::getId)
                  .collect(Collectors.toSet());
      List<RepoMappingDto> repos =
          s.getRepoMappings() == null
              ? List.of()
              : s.getRepoMappings().stream().map(RepoMappingDto::from).toList();
      return new SolutionDto(
          s.getId(),
          s.getSequenceNumber(),
          s.getTitle(),
          s.getDescription(),
          s.getStatus(),
          s.getFailurePhase(),
          s.getFailureReason(),
          s.getRetryCount(),
          s.getMaxRetries(),
          s.getLastFailureSignature(),
          s.getConsecutiveFailureCount(),
          s.getComplexity(),
          s.getEstimatedTokens(),
          s.getActualTokens(),
          s.getCostUsd(),
          s.getPipelineRunId(),
          s.getPrNumber(),
          s.getPrUrl(),
          s.getPrStatus(),
          s.getJiraTicketId(),
          s.isJiraEnabled(),
          s.getStartedAt(),
          s.getCompletedAt(),
          depIds,
          repos);
    }
  }

  public record RepoMappingDto(
      String repoId,
      String executionBranch,
      String targetFiles,
      String actualFiles,
      int codemapVersion) {
    public static RepoMappingDto from(ExecutionSolutionRepo r) {
      return new RepoMappingDto(
          r.getRepoId() == null ? null : r.getRepoId().toString(),
          r.getExecutionBranch(),
          r.getTargetFiles(),
          r.getActualFiles(),
          r.getCodemapVersion());
    }
  }
}
