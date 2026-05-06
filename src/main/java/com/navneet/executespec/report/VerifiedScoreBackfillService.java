package com.navneet.executespec.report;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.report.entity.ValidationReport;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * GA-readiness P1.2-followup (2026-05-06) — one-shot backfill that re-computes {@code
 * validation_report.verified_score} using the corrected formula shipped in P1.2.
 *
 * <p>Why this exists: the original commit landed the new {@link
 * ValidationReportService#computeVerifiedScore} overload + {@link
 * ValidationReportService#countUnfixedCritical} synth-issue filter for ALL new runs, but historical
 * rows continued to display the old number until the next run on each project flushed a fresh
 * report. This service re-scores every existing row in one pass so dashboards reflect the corrected
 * values immediately on deploy.
 *
 * <p>Idempotent: running it twice produces the same result. Backfill is per-row independent — a
 * crash mid-job leaves rows in mixed states but a re-run fixes them.
 *
 * <p>Triggered only by the SUPER_ADMIN endpoint (see {@code
 * SuperAdminController.backfillVerifiedScore} — to be added separately). Synchronous; the caller
 * times out after 60s if the table grew past ~10K rows.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerifiedScoreBackfillService {

  private final ValidationReportRepository reportRepo;
  private final ValidationReportService scoreService;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Outcome counts so the SUPER_ADMIN UI can show a summary after the run completes. */
  public record BackfillResult(int total, int updated, int unchanged, int skipped, int errors) {}

  @Transactional
  public BackfillResult backfillAll() {
    List<ValidationReport> rows = reportRepo.findAll();
    log.info("[VerifiedScoreBackfill] starting: {} rows to evaluate", rows.size());

    int updated = 0;
    int unchanged = 0;
    int skipped = 0;
    int errors = 0;

    for (ValidationReport row : rows) {
      try {
        // Skip rows whose required signals aren't recorded — backfill formula needs at least
        // finalBuildPassed + reviewerVerdict to produce a meaningful number.
        if (row.getFinalBuildPassed() == null && row.getReviewerVerdict() == null) {
          skipped++;
          continue;
        }

        int recomputed =
            scoreService.computeVerifiedScore(
                row.getFinalBuildPassed(),
                row.getTestsPassed() == null ? 0 : row.getTestsPassed(),
                row.getTestsTotal() == null ? 0 : row.getTestsTotal(),
                row.getReviewerVerdict(),
                countUnfixedCriticalFromIssuesJson(row.getIssuesJson()),
                endpointCoverageFromAccuracyJson(row.getAccuracyJson()));

        Integer existing = row.getVerifiedScore();
        if (existing != null && existing == recomputed) {
          unchanged++;
          continue;
        }

        row.setVerifiedScore(recomputed);
        reportRepo.save(row);
        updated++;
      } catch (RuntimeException ex) {
        // Per-row failure must NOT break the batch — log + continue.
        log.warn(
            "[VerifiedScoreBackfill] error on report id={} runId={}: {}",
            row.getId(),
            row.getRunId(),
            ex.getMessage());
        errors++;
      }
    }

    BackfillResult result = new BackfillResult(rows.size(), updated, unchanged, skipped, errors);
    log.info("[VerifiedScoreBackfill] done: {}", result);
    return result;
  }

  /**
   * Replicate the new {@code countUnfixedCritical} contract: count CRITICAL issues with {@code
   * !autoFixed || !fixSucceeded}, EXCLUDING ids prefixed {@code SYNTH-ACC-} (LLM
   * read-budget-derived synth issues that the new formula filters out).
   *
   * <p>Issues are stored as a JSON array on {@code validation_report.issues_json} per {@link
   * ValidationReportService.ReportIssueDTO}.
   */
  static int countUnfixedCriticalFromIssuesJson(String issuesJson) {
    if (issuesJson == null || issuesJson.isBlank()) return 0;
    try {
      List<JsonNode> issues = MAPPER.readValue(issuesJson, new TypeReference<List<JsonNode>>() {});
      int count = 0;
      for (JsonNode issue : issues) {
        String severity = issue.path("severity").asText("");
        if (!"CRITICAL".equals(severity)) continue;
        boolean autoFixed = issue.path("autoFixed").asBoolean(false);
        boolean fixSucceeded = issue.path("fixSucceeded").asBoolean(false);
        if (autoFixed && fixSucceeded) continue;
        String id = issue.path("id").asText("");
        if (id.startsWith("SYNTH-ACC-")) continue; // LLM-derived; filter per P1.2.
        count++;
      }
      return count;
    } catch (IOException ex) {
      // Malformed issuesJson — treat as 0 unfixed. Per-row error is logged by caller.
      return 0;
    }
  }

  /**
   * Pull {@code endpointCoverage.coveragePercent} from {@code validation_report.accuracy_json} if
   * present. Returns null when the run has no endpoint coverage block (non-API project) so the +10
   * bonus only fires for runs that actually delivered endpoints.
   */
  static Integer endpointCoverageFromAccuracyJson(String accuracyJson) {
    if (accuracyJson == null || accuracyJson.isBlank()) return null;
    try {
      JsonNode root = MAPPER.readTree(accuracyJson);
      JsonNode coverage = root.path("endpointCoverage");
      if (coverage.isMissingNode() || coverage.isNull()) return null;
      JsonNode percent = coverage.path("coveragePercent");
      if (percent.isMissingNode() || percent.isNull() || !percent.canConvertToInt()) return null;
      return percent.asInt();
    } catch (IOException ex) {
      return null;
    }
  }
}
