package com.navneet.executespec.project.dashboard;

import java.util.List;
import java.util.Set;

import com.navneet.executespec.api.RunMapper;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.codeintel.ProjectArchitectureService;
import com.navneet.executespec.codeintel.StalenessService;
import com.navneet.executespec.cost.CostVisibilityPolicy;
import com.navneet.executespec.project.pr.PullRequestRecord;
import com.navneet.executespec.project.pr.PullRequestRecordRepository;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.ValidationReportValidityService;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Pilot R1 — aggregates project-level metrics for the dashboard header. Single endpoint; all five
 * widgets read from the same payload to keep the page snappy.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class ProjectDashboardController {

  private static final Set<String> ACTIVE_STATUSES =
      Set.of("PLANNING", "WORKING", "REVIEWING", "AWAITING_APPROVAL");

  private final PipelineRunRepository runRepo;
  private final AgentTaskRepository agentTaskRepo;
  private final RunMapper runMapper;
  private final ValidationReportRepository reportRepo;
  private final ValidationReportValidityService reportValidityService;
  private final PullRequestRecordRepository prRepo;
  private final ProjectArchitectureService archService;
  private final StalenessService stalenessService;
  private final com.navneet.executespec.project.service.WorkspaceHealthService
      workspaceHealthService;
  private final com.navneet.executespec.project.pr.PostMergeCascadeService postMergeCascadeService;
  private final com.navneet.executespec.project.estimation.EstimationService estimationService;
  private final com.navneet.executespec.project.techdebt.TechDebtService techDebtService;
  private final com.navneet.executespec.project.techdebt.TechDebtFixService techDebtFixService;

  /** v2.5.8 — Launch runs accumulate tokens + cost here; dashboard now reads both. */
  private final com.navneet.executespec.execution.repository.ExecutionRunRepository execRunRepo;

  /**
   * v2.6.4 — code-review + pr-review + arch + generated-doc cost rollup. Each of these surfaces
   * persists tokens + cost per artefact; the dashboard now sums them alongside pipeline_run +
   * execution_run cost so "Project spend" reflects every LLM dollar spent in the project's
   * lifetime, not just the worker pipeline.
   */
  private final com.navneet.executespec.codereview.repository.CodeReviewScanRepository
      codeReviewRepo;

  private final com.navneet.executespec.prreview.repository.PrReviewRepository prReviewRepo;
  private final com.navneet.executespec.codeintel.repository.RepoArchitectureDocRepository
      repoArchRepo;
  private final com.navneet.executespec.codeintel.repository.ProjectArchitectureDocRepository
      projectArchRepo;
  private final com.navneet.executespec.codeintel.repository.ProjectGeneratedDocRepository
      generatedDocRepo;

  private final com.navneet.executespec.project.security.ProjectAccessGuard projectAccessGuard;
  private final ProjectRepository projectRepository;

  public record TaskSummary(
      String taskId, String runId, String name, String status, Integer score, String createdAt) {}

  public record PrSummaryDto(
      String repoFullName,
      Integer prNumber,
      String prUrl,
      String status,
      String createdAt,
      String mergedAt,
      String ciStatus,
      Integer ciFailingCount,
      String ciCheckedAt) {
    static PrSummaryDto from(PullRequestRecord r) {
      return new PrSummaryDto(
          r.getRepoFullName(),
          r.getPrNumber(),
          r.getPrUrl(),
          r.getStatus().name(),
          r.getCreatedAt() != null ? r.getCreatedAt().toString() : null,
          r.getMergedAt() != null ? r.getMergedAt().toString() : null,
          r.getCiStatus() != null ? r.getCiStatus().name() : "UNKNOWN",
          r.getCiFailingCount() != null ? r.getCiFailingCount() : 0,
          r.getCiCheckedAt() != null ? r.getCiCheckedAt().toString() : null);
    }
  }

  /**
   * v2.6.4 — adds {@code costBreakdown} so the UI can show "where the spend went" without an extra
   * API call. Existing fields ({@code totalTokens}, {@code totalCostUsd}) keep their meaning but
   * now aggregate every LLM-spending surface in the project, not just runs:
   *
   * <ul>
   *   <li>{@code runs} — pipeline_run.actual_cost_usd
   *   <li>{@code launches} — execution_run.total_cost_usd
   *   <li>{@code codeReviews} — code_review_scan.cost_usd
   *   <li>{@code prReviews} — pr_review.cost_usd
   *   <li>{@code architecture} — project_architecture_doc.cost_usd + repo_architecture_doc.cost_usd
   *   <li>{@code generatedDocs} — project_generated_doc.cost_usd
   * </ul>
   *
   * Sum of these equals {@code totalCostUsd}.
   */
  public record CostBreakdown(
      double runs,
      double launches,
      double codeReviews,
      double prReviews,
      double architecture,
      double generatedDocs) {}

  /**
   * Run counters are bucketed by the customer-facing DISPLAY status (the same fail-closed
   * task-proof projection the run list uses via {@link RunMapper#displayRunStatus}), not by the
   * persisted crash-resume status alone. A persisted-COMPLETE run whose durable goal proof is not
   * DONE therefore lands in {@code partialRuns}, never in {@code completedRuns}, and each run is
   * counted in exactly one bucket. {@code partialRuns} is additive (older clients ignore it).
   */
  public record DashboardDto(
      int activeRuns,
      int completedRuns,
      int partialRuns,
      int failedRuns,
      double avgScore,
      int excludedScoreReports,
      long totalTokens,
      double totalCostUsd,
      CostBreakdown costBreakdown,
      // RR-563: true only for billing-role viewers of the project's own org. When false,
      // totalCostUsd
      // and every costBreakdown field are zeroed (raw provider-dollar COGS is not a general
      // project-read figure). tokens/credits remain visible. Clients should branch on this flag.
      boolean costVisible,
      List<TaskSummary> recentTasks,
      List<PrSummaryDto> recentPrs,
      String architectureDocStatus,
      Integer architectureDocVersion) {}

  @GetMapping("/api/projects/{projectId}/dashboard")
  public ResponseEntity<DashboardDto> dashboard(
      @PathVariable String projectId,
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.navneet.executespec.auth.security.AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    // Counters bucket by DISPLAY status so the dashboard agrees with the run list: a persisted
    // COMPLETE run whose task goal proof is not DONE is customer-facing PARTIAL
    // (RunMapper.displayRunStatus), and persisted PARTIAL runs were previously invisible in all
    // three legacy buckets. Each run lands in exactly one bucket — the flipped COMPLETE runs move
    // from completed to partial rather than double-counting.
    long persistedComplete = runRepo.countByProjectIdAndStatus(projectId, "COMPLETE");
    long completeShownAsPartial = countCompleteRunsDisplayedAsPartial(projectId);
    long completed = persistedComplete - completeShownAsPartial;
    long partial = runRepo.countByProjectIdAndStatus(projectId, "PARTIAL") + completeShownAsPartial;
    long failed = runRepo.countByProjectIdAndStatus(projectId, "FAILED");
    long active =
        ACTIVE_STATUSES.stream()
            .mapToLong(s -> runRepo.countByProjectIdAndStatus(projectId, s))
            .sum();

    List<PipelineRun> recentRuns =
        runRepo.findByProjectIdOrderByStartedAtDesc(projectId, PageRequest.of(0, 10));

    // Project-wide token + cost totals (across all runs, not just recent).
    // v2.5.8 — now aggregates from BOTH PipelineRun (standalone runs) and
    // ExecutionRun (Launches). Before this fix, Launches ran and consumed
    // tokens but the project dashboard rendered "—" because it only
    // summed PipelineRun. The ExecutionRun totals are themselves maintained
    // by SolutionExecutionService.accumulateExecutionRunTotals on each
    // solution completion (v2.5.7 fix).
    // B4 (audit 2026-05-29) — project lifetime totals as DB SUMs. Was a
    // findByProjectIdOrderByStartedAtDesc(... 2000)-then-stream heap scan for BOTH the run and the
    // launch halves (~2K + 2K rows materialised per dashboard hit, on a constant-hit path).
    long pipelineTokens = runRepo.sumTotalTokensByProjectId(projectId);
    double pipelineCost = runRepo.sumActualCostByProjectId(projectId);
    long execTokens = execRunRepo.sumTotalActualTokensByProjectId(projectId);
    java.math.BigDecimal execCostBd = execRunRepo.sumTotalCostUsdByProjectId(projectId);
    double execCost = execCostBd == null ? 0.0 : execCostBd.doubleValue();

    // v2.6.4 — fold review + arch + doc spend into the project rollup. Each finder is a single
    // SUM(cost_usd) query against an indexed project_id column, so this adds 5 cheap reads to
    // the dashboard call.
    double codeReviewCost = codeReviewRepo.sumCostUsdByProject(projectId);
    long codeReviewTokens = codeReviewRepo.sumTokensByProject(projectId);
    double prReviewCost = prReviewRepo.sumCostUsdByProject(projectId);
    long prReviewTokens = prReviewRepo.sumTokensByProject(projectId);
    double archCost =
        projectArchRepo.sumCostUsdByProject(projectId)
            + repoArchRepo.sumCostUsdByProject(projectId);
    double generatedDocCost = generatedDocRepo.sumCostUsdByProject(projectId);

    long totalTokens = pipelineTokens + execTokens + codeReviewTokens + prReviewTokens;

    // RR-563: raw provider-dollar spend is a billing figure hidden from ordinary customer surfaces
    // (managed AI credits are shown instead). Only a billing-role viewer of the project's own org
    // sees the USD rollup; for everyone else the dollar figures are zeroed and costVisible=false so
    // the client renders "hidden"/credits rather than a real COGS number. Tokens stay visible.
    String projectOrgId = projectRepository.findById(projectId).map(Project::getOrgId).orElse(null);
    boolean costVisible = CostVisibilityPolicy.canViewRawCost(userDetails, projectOrgId);
    double totalCost =
        costVisible
            ? pipelineCost + execCost + codeReviewCost + prReviewCost + archCost + generatedDocCost
            : 0.0;
    CostBreakdown costBreakdown =
        costVisible
            ? new CostBreakdown(
                pipelineCost, execCost, codeReviewCost, prReviewCost, archCost, generatedDocCost)
            : new CostBreakdown(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

    List<ValidationReport> reports = reportRepo.findAllByProjectId(projectId);
    // v2.5.10+: aggregate only verifiedScore (objective build+tests+verdict signal).
    // Legacy accuracyScore is an LLM self-assessment and must never be promoted into
    // a dashboard aggregate when deterministic evidence is absent.
    ValidationReportValidityService.Aggregate scoreAggregate =
        reportValidityService.aggregate(reports);
    List<ValidationReport> canonicalReports = scoreAggregate.canonicalReports();
    int excludedScoreReports = scoreAggregate.excludedCount();
    double avgScore = scoreAggregate.averageScore();

    List<TaskSummary> recentTasks =
        recentRuns.stream()
            .map(
                r ->
                    new TaskSummary(
                        r.getRunId(),
                        r.getRunId(),
                        r.getRunTitle() != null ? r.getRunTitle() : r.getUserRequest(),
                        r.getStatus(),
                        latestScoreForRun(canonicalReports, r.getRunId()),
                        r.getStartedAt() != null ? r.getStartedAt().toString() : null))
            .toList();

    List<PrSummaryDto> recentPrs =
        recentRuns.stream()
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
        String pat = userDetails != null ? userDetails.getUser().getGithubPat() : null;
        var staleness = stalenessService.report(projectId, pat);
        // RR-1657: "GENERATED" is an affirmative claim that the architecture matches the current
        // repository state, so it must only be made when the probe actually verified that. A
        // project whose repos could not be read (SCM outage, expired PAT) or that has only
        // LOCAL/non-GitHub sources is UNVERIFIED, not current. Collapsing those into GENERATED was
        // the user-visible half of the fail-open that RR-1585 closed inside StalenessService.
        if (staleness.stale()) {
          archStatus = "STALE";
        } else if (staleness.verified()) {
          archStatus = "GENERATED";
        } else {
          archStatus = "UNVERIFIED";
        }
      } catch (Exception e) {
        // RR-1657: a failed staleness probe proves nothing about freshness. It previously reported
        // GENERATED, which is the strongest possible claim on the weakest possible evidence.
        log.warn(
            "[Dashboard] Staleness probe failed for project {}; reporting UNVERIFIED: {}",
            projectId,
            e.toString());
        archStatus = "UNVERIFIED";
      }
    }
    Integer archVersion = arch.map(a -> a.getVersion()).orElse(null);

    return ResponseEntity.ok(
        new DashboardDto(
            (int) active,
            (int) completed,
            (int) partial,
            (int) failed,
            avgScore,
            excludedScoreReports,
            totalTokens,
            totalCost,
            costBreakdown,
            costVisible,
            recentTasks,
            recentPrs,
            archStatus,
            archVersion));
  }

  /**
   * v2.5.2 — per-repo workspace health rollup for the Project Overview tab. Uses the caller's PAT
   * for origin-SHA lookups; cached 10 min in Redis.
   */
  @GetMapping("/api/projects/{projectId}/workspace-health")
  public ResponseEntity<com.navneet.executespec.project.service.WorkspaceHealthService.Report>
      workspaceHealth(
          @PathVariable String projectId,
          @org.springframework.security.core.annotation.AuthenticationPrincipal
              com.navneet.executespec.auth.security.AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    String userId = userDetails == null ? null : userDetails.getUser().getId();
    return ResponseEntity.ok(workspaceHealthService.report(projectId, userId));
  }

  /**
   * v2.5.2 — read the transient post-merge banner marker. Returns 200 with {@code null} body when
   * no banner is active.
   */
  @GetMapping("/api/projects/{projectId}/post-merge-bar")
  public ResponseEntity<
          List<com.navneet.executespec.project.pr.PostMergeCascadeService.PostMergeBar>>
      postMergeBar(
          @PathVariable String projectId,
          @org.springframework.security.core.annotation.AuthenticationPrincipal
              com.navneet.executespec.auth.security.AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    return ResponseEntity.ok(postMergeCascadeService.readPostMergeBars(projectId));
  }

  /**
   * v2.5.2 — pre-submit Launch estimate. Non-LLM heuristic derived from prior project runs; safe to
   * call on every keystroke (debounced on the client).
   */
  @GetMapping("/api/projects/{projectId}/estimate")
  public ResponseEntity<com.navneet.executespec.project.estimation.EstimationService.Estimate>
      estimate(
          @PathVariable String projectId,
          @org.springframework.web.bind.annotation.RequestParam(
                  value = "requirement",
                  required = false)
              String requirement,
          @org.springframework.web.bind.annotation.RequestParam(
                  value = "provider",
                  required = false)
              String provider,
          @org.springframework.web.bind.annotation.RequestParam(value = "model", required = false)
              String model,
          @org.springframework.security.core.annotation.AuthenticationPrincipal
              com.navneet.executespec.auth.security.AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    return ResponseEntity.ok(estimationService.estimate(projectId, requirement, provider, model));
  }

  /**
   * v2.5.2 — Tech Debt rollup: issues-by-category/severity, top-5 file hotspots, dimension-score
   * trend. All derived from existing ValidationReport rows; no LLM call. {@code since} filters to
   * the last N days (default and operational maximum: 366 days; at most 1,000 reports).
   */
  @GetMapping("/api/projects/{projectId}/tech-debt")
  public ResponseEntity<com.navneet.executespec.project.techdebt.TechDebtService.Report> techDebt(
      @PathVariable String projectId,
      @org.springframework.web.bind.annotation.RequestParam(value = "since", required = false)
          Integer since,
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.navneet.executespec.auth.security.AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    return ResponseEntity.ok(techDebtService.build(projectId, since));
  }

  /** Body for {@link #fixTechDebt}. */
  public record FixTechDebtRequest(String category) {}

  /** Response for {@link #fixTechDebt}. */
  public record FixTechDebtResponse(String runId, String findingKey, String outcome) {}

  /**
   * Phase 2 (V183) — dispatch a patch-mode run that fixes every issue of the named category across
   * the project's linked repos. One PR per category keeps diffs coherent. Idempotent: an in-flight
   * run for the same (project, category) returns the existing run with {@code
   * outcome=REUSED_ACTIVE} instead of dispatching a duplicate.
   */
  @org.springframework.web.bind.annotation.PostMapping("/api/projects/{projectId}/tech-debt/fix")
  public ResponseEntity<FixTechDebtResponse> fixTechDebt(
      @PathVariable String projectId,
      @org.springframework.web.bind.annotation.RequestBody FixTechDebtRequest req,
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.navneet.executespec.auth.security.AppUserDetails userDetails) {
    if (userDetails == null) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.UNAUTHORIZED, "authentication required");
    }
    projectAccessGuard.assertCanWrite(projectId, userDetails);
    if (req == null || req.category() == null || req.category().isBlank()) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "category is required");
    }
    var result =
        techDebtFixService.dispatchFixForCategory(projectId, req.category(), userDetails.getUser());
    return ResponseEntity.status(org.springframework.http.HttpStatus.ACCEPTED)
        .body(
            new FixTechDebtResponse(result.runId(), result.findingKey(), result.outcome().name()));
  }

  /** v2.5.2 — dismiss the post-merge banner (user clicked the CTA or X). */
  @org.springframework.web.bind.annotation.DeleteMapping("/api/projects/{projectId}/post-merge-bar")
  public ResponseEntity<Void> dismissPostMergeBar(
      @PathVariable String projectId,
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.navneet.executespec.auth.security.AppUserDetails userDetails) {
    projectAccessGuard.assertCanWrite(projectId, userDetails);
    postMergeCascadeService.dismissPostMergeBars(projectId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Counts persisted-COMPLETE runs whose customer-facing display status is PARTIAL because durable
   * task goal proof is present and not DONE. Reuses the exact run-list projection ({@link
   * RunMapper#displayRunStatus}) over a run-id-only query plus one batched task read, so the
   * dashboard cannot disagree with the run list about what counts as completed. Legacy COMPLETE
   * runs without task rows or goal proof stay in the completed bucket.
   */
  private long countCompleteRunsDisplayedAsPartial(String projectId) {
    List<String> completeRunIds = runRepo.findRunIdsByProjectIdAndStatus(projectId, "COMPLETE");
    if (completeRunIds.isEmpty()) return 0;
    java.util.Map<String, List<AgentTask>> tasksByRun =
        agentTaskRepo.findByRunIdIn(completeRunIds).stream()
            .collect(java.util.stream.Collectors.groupingBy(AgentTask::getRunId));
    return completeRunIds.stream()
        .filter(
            runId ->
                "PARTIAL"
                    .equals(
                        runMapper.displayRunStatus(
                            "COMPLETE", tasksByRun.getOrDefault(runId, List.of()))))
        .count();
  }

  private static Integer latestScoreForRun(List<ValidationReport> reports, String runId) {
    return reports.stream()
        .filter(r -> runId.equals(r.getRunId()))
        .map(ProjectDashboardController::preferredScore)
        .filter(s -> s != null)
        .findFirst()
        .orElse(null);
  }

  /**
   * v2.5.10+: the headline score shown in the dashboard is the verified score (V106) when present —
   * an objective blend of build pass, test pass-rate, and reviewer verdict. Accuracy score (LLM
   * self-assessment) is deliberately excluded when verified evidence is absent.
   */
  private static Integer preferredScore(ValidationReport report) {
    if (report == null) return null;
    Integer v = report.getVerifiedScore();
    if (v != null) return v;
    return null;
  }
}
