package com.navneet.executespec.integration;

import static com.navneet.executespec.integration.RunPipelineSupport.lastPathSegment;
import static com.navneet.executespec.integration.RunPipelineSupport.truncate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.contracts.ContractRegistryRepository;
import com.navneet.executespec.contracts.ContractVersion;
import com.navneet.executespec.contracts.ContractVersionRepository;
import com.navneet.executespec.contracts.gate.PlanBreakingChangeReport;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.reviewer.model.IssueCategory;
import com.navneet.executespec.reviewer.model.IssueSeverity;
import com.navneet.executespec.reviewer.model.ReviewIssue;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Post-run breaking-change detection, extracted from {@code RunOrchestrator} (RR-819).
 *
 * <p>This is the SECOND, tighten-only gate. It runs after the worker has already written files, so
 * it can only describe what happened; it is not, and never was, an approval control. The control
 * that can actually refuse to proceed is {@link
 * com.navneet.executespec.contracts.gate.PlanContractGateService}, which fires at the plan-approval
 * gate before any provider spend or publication.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunBreakingChangeDetector {

  private final ProjectRepoService projectRepoService;
  private final PlanBreakingChangeReport planBreakingChangeReport;
  private final ContractRegistryRepository contractRegistryRepository;
  private final ContractVersionRepository contractVersionRepository;

  /**
   * Classify breaking changes to contracts the run touched. Previous content comes from the latest
   * {@link ContractVersion#getInlineContent()}; new content is read from the workspace disk at the
   * contract's detected path (per-repo subdir for multi-repo runs). Returns report-only (MINOR) run
   * issues so a breaking change is visible in the Issues panel without triggering auto-fix or
   * penalising the score. Non-fatal; empty on any miss.
   */
  public List<ReviewIssue> detect(
      String runId, String projectId, Path workspaceRoot, List<UUID> selectedRepoIds) {
    if (selectedRepoIds == null || selectedRepoIds.isEmpty()) return List.of();
    var repos = projectRepoService.resolveForRun(projectId, selectedRepoIds);
    if (repos == null || repos.isEmpty()) return List.of();
    boolean multiRepo = repos.size() > 1;
    List<PlanBreakingChangeReport.IntendedChange> intended = new ArrayList<>();
    for (var repo : repos) {
      Path repoRoot =
          multiRepo
              ? workspaceRoot
                  .resolve("repos")
                  .resolve(lastPathSegment(repo.getGithubRepoFullName()))
              : workspaceRoot;
      for (var contract : contractRegistryRepository.findByRepoId(repo.getId())) {
        if (contract.getDetectedPath() == null || contract.getDetectedPath().isBlank()) continue;
        String prev =
            contractVersionRepository
                .findFirstByContractIdOrderByRegistryVersionDesc(contract.getId())
                .map(ContractVersion::getInlineContent)
                .orElse(null);
        if (prev == null) continue; // first-version or GCS-pointer contract: skip (best-effort)
        Path f = repoRoot.resolve(contract.getDetectedPath());
        if (!java.nio.file.Files.isRegularFile(f)) continue;
        String now;
        try {
          now = java.nio.file.Files.readString(f);
        } catch (Exception e) {
          continue;
        }
        if (now.equals(prev)) continue; // unchanged → not a candidate
        intended.add(new PlanBreakingChangeReport.IntendedChange(contract.getId(), prev, now));
      }
    }
    if (intended.isEmpty()) return List.of();
    var report = planBreakingChangeReport.compose(projectId, intended);
    var issues = toBreakingChangeIssues(report);
    if (!issues.isEmpty()) {
      log.info(
          "RunOrchestrator run={} breaking-change: {} contract(s) flagged (typedAck={})",
          runId,
          issues.size(),
          report.requiresTypedAck());
    }
    return issues;
  }

  /** Pure mapping: breaking-change report rows → report-only (MINOR) run issues. */
  public static List<ReviewIssue> toBreakingChangeIssues(PlanBreakingChangeReport.Report report) {
    List<ReviewIssue> out = new ArrayList<>();
    if (report == null || report.breakingChanges() == null) return out;
    int n = 0;
    for (var row : report.breakingChanges()) {
      n++;
      String consumers =
          row.affectedConsumers() == null || row.affectedConsumers().isEmpty()
              ? "no declared consumers"
              : "consumers: " + String.join(", ", row.affectedConsumers());
      out.add(
          ReviewIssue.builder()
              .id("SYNTH-CONTRACT-" + n)
              .severity(IssueSeverity.MINOR)
              .category(IssueCategory.CROSS_TASK_MISMATCH)
              .description(
                  "Breaking change to contract '"
                      + row.contractName()
                      + "' ("
                      + row.type()
                      + "): "
                      + truncate(row.summary(), 160)
                      + " — "
                      + consumers
                      + (row.requiresFriction() ? " [REQUIRES ACK]" : ""))
              .autoFixed(false)
              .fixSucceeded(false)
              .build());
    }
    return out;
  }
}
