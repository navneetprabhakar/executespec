package com.navneet.executespec.project.dashboard;

import com.navneet.executespec.codeintel.ProjectArchitectureService;
import com.navneet.executespec.codeintel.StalenessService;
import com.navneet.executespec.project.pr.PullRequestRecord;
import com.navneet.executespec.project.pr.PullRequestRecordRepository;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * Pilot R1 — aggregates project-level metrics for the dashboard header. Single
 * endpoint; all five widgets read from the same payload to keep the page snappy.
 */
@RestController
@RequiredArgsConstructor
public class ProjectDashboardController {

    private static final Set<String> ACTIVE_STATUSES =
            Set.of("PLANNING", "WORKING", "REVIEWING", "AWAITING_APPROVAL");

    private final PipelineRunRepository runRepo;
    private final ValidationReportRepository reportRepo;
    private final PullRequestRecordRepository prRepo;
    private final ProjectArchitectureService archService;
    private final StalenessService stalenessService;

    public record TaskSummary(
            String taskId, String runId, String name, String status,
            Integer score, String createdAt) {}

    public record PrSummaryDto(
            String repoFullName, Integer prNumber, String prUrl, String status,
            String createdAt, String mergedAt) {
        static PrSummaryDto from(PullRequestRecord r) {
            return new PrSummaryDto(
                    r.getRepoFullName(), r.getPrNumber(), r.getPrUrl(),
                    r.getStatus().name(),
                    r.getCreatedAt() != null ? r.getCreatedAt().toString() : null,
                    r.getMergedAt() != null ? r.getMergedAt().toString() : null);
        }
    }

    public record DashboardDto(
            int activeRuns,
            int completedRuns,
            int failedRuns,
            double avgScore,
            long totalTokens,
            double totalCostUsd,
            List<TaskSummary> recentTasks,
            List<PrSummaryDto> recentPrs,
            String architectureDocStatus,
            Integer architectureDocVersion) {}

    @GetMapping("/api/projects/{projectId}/dashboard")
    public ResponseEntity<DashboardDto> dashboard(@PathVariable String projectId) {
        long completed = runRepo.countByProjectIdAndStatus(projectId, "COMPLETE");
        long failed = runRepo.countByProjectIdAndStatus(projectId, "FAILED");
        long active = ACTIVE_STATUSES.stream()
                .mapToLong(s -> runRepo.countByProjectIdAndStatus(projectId, s))
                .sum();

        List<PipelineRun> recentRuns = runRepo.findByProjectIdOrderByStartedAtDesc(
                projectId, PageRequest.of(0, 10));

        // Project-wide token + cost totals (across all runs, not just recent).
        List<PipelineRun> allRuns = runRepo.findByProjectIdOrderByStartedAtDesc(
                projectId, PageRequest.of(0, 2000));
        long totalTokens = allRuns.stream()
                .mapToLong(r -> (r.getTotalInputTokens() == null ? 0 : r.getTotalInputTokens())
                              + (r.getTotalOutputTokens() == null ? 0 : r.getTotalOutputTokens()))
                .sum();
        double totalCost = allRuns.stream()
                .filter(r -> r.getActualCostUsd() != null)
                .mapToDouble(PipelineRun::getActualCostUsd)
                .sum();

        List<ValidationReport> reports = reportRepo.findAllByProjectId(projectId);
        double avgScore = reports.stream()
                .filter(r -> r.getAccuracyScore() != null)
                .mapToInt(ValidationReport::getAccuracyScore)
                .average()
                .orElse(0.0);

        List<TaskSummary> recentTasks = recentRuns.stream()
                .map(r -> new TaskSummary(
                        r.getRunId(), r.getRunId(),
                        r.getRunTitle() != null ? r.getRunTitle() : r.getUserRequest(),
                        r.getStatus(),
                        latestScoreForRun(reports, r.getRunId()),
                        r.getStartedAt() != null ? r.getStartedAt().toString() : null))
                .toList();

        List<PrSummaryDto> recentPrs = recentRuns.stream()
                .flatMap(r -> prRepo.findByRunIdOrderByCreatedAtAsc(r.getRunId()).stream())
                .limit(15)
                .map(PrSummaryDto::from)
                .toList();

        var arch = archService.latest(projectId);
        String archStatus;
        if (arch.isEmpty()) {
            archStatus = "NONE";
        } else {
            try {
                archStatus = stalenessService.report(projectId).stale() ? "STALE" : "GENERATED";
            } catch (Exception e) {
                archStatus = "GENERATED";
            }
        }
        Integer archVersion = arch.map(a -> a.getVersion()).orElse(null);

        return ResponseEntity.ok(new DashboardDto(
                (int) active, (int) completed, (int) failed,
                avgScore, totalTokens, totalCost,
                recentTasks, recentPrs, archStatus, archVersion));
    }

    private static Integer latestScoreForRun(List<ValidationReport> reports, String runId) {
        return reports.stream()
                .filter(r -> runId.equals(r.getRunId()))
                .map(ValidationReport::getAccuracyScore)
                .filter(s -> s != null)
                .findFirst()
                .orElse(null);
    }
}
