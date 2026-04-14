package com.navneet.executespec.execution.dto;

import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.execution.entity.ExecutionSolutionRepo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
        List<SolutionDto> solutions,
        Instant createdAt,
        Instant updatedAt) {

    public static ExecutionRunDto from(ExecutionRun r, List<ExecutionSolution> solutions) {
        List<SolutionDto> mapped = solutions == null
                ? List.of()
                : solutions.stream().map(SolutionDto::from).toList();
        return new ExecutionRunDto(
                r.getId(), r.getProjectId(), r.getStatus(), r.getRequirement(),
                r.getUnifiedArchDoc(), r.getArchApprovedAt(),
                r.getExecutionPlan(), r.getPlanApprovedAt(),
                r.getTotalEstimatedTokens(), r.getTotalActualTokens(), r.getTotalCostUsd(),
                r.isJiraEnabled(),
                mapped, r.getCreatedAt(), r.getUpdatedAt());
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
            Set<String> depIds = s.getDependencies() == null
                    ? Set.of()
                    : s.getDependencies().stream().map(ExecutionSolution::getId).collect(Collectors.toSet());
            List<RepoMappingDto> repos = s.getRepoMappings() == null
                    ? List.of()
                    : s.getRepoMappings().stream().map(RepoMappingDto::from).toList();
            return new SolutionDto(
                    s.getId(), s.getSequenceNumber(), s.getTitle(), s.getDescription(), s.getStatus(),
                    s.getFailurePhase(), s.getFailureReason(), s.getRetryCount(), s.getComplexity(),
                    s.getEstimatedTokens(), s.getActualTokens(), s.getCostUsd(),
                    s.getPipelineRunId(), s.getPrNumber(), s.getPrUrl(), s.getPrStatus(),
                    s.getJiraTicketId(), s.isJiraEnabled(),
                    s.getStartedAt(), s.getCompletedAt(), depIds, repos);
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
                    r.getTargetFiles(), r.getActualFiles(), r.getCodemapVersion());
        }
    }
}
