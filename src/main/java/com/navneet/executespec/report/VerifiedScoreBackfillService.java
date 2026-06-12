package com.navneet.executespec.report;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.report.entity.ValidationReport;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
 * <p>RL-2026-06-026: because the service delegates to the live scoring method, it also applies the
 * current trust caps (review/build/test blockers cap the maximum credible headline). Triggered only
 * by the SUPER_ADMIN endpoint (see {@code SuperAdminController.backfillVerifiedScore}).
 * Synchronous; use {@code limit} and {@code dryRun} before running it against a large table.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerifiedScoreBackfillService {

  private final ValidationReportRepository reportRepo;
  private final ValidationReportService scoreService;

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int MAX_LIMIT = 10_000;
  private static final int MAX_CHANGE_SAMPLES = 50;

  /**
   * Optional SUPER_ADMIN request. Null body keeps backward-compatible behaviour: mutate every
   * report.
   */
  public record BackfillRequest(String runId, Integer limit, Boolean dryRun) {}

  /** Before/after sample so operators can audit what the job would change. */
  public record ScoreChange(
      String reportId,
      String runId,
      Integer oldVerifiedScore,
      Integer newVerifiedScore,
      Integer oldRubricScore,
      Integer newRubricScore) {}

  /** Outcome counts so the SUPER_ADMIN UI can show a summary after the run completes. */
  public record BackfillResult(
      int total,
      int evaluated,
      int updated,
      int unchanged,
      int skipped,
      int errors,
      boolean dryRun,
      String runId,
      Integer limit,
      List<ScoreChange> changes) {}

  @Transactional
  public BackfillResult backfillAll() {
    return backfill(new BackfillRequest(null, null, false));
  }

  @Transactional
  public BackfillResult backfill(BackfillRequest request) {
    BackfillRequest req = request == null ? new BackfillRequest(null, null, false) : request;
    boolean dryRun = Boolean.TRUE.equals(req.dryRun());
    Integer safeLimit = safeLimit(req.limit());
    List<ValidationReport> rows = rowsFor(req.runId(), safeLimit);
    log.info(
        "[VerifiedScoreBackfill] starting: rows={} runId={} limit={} dryRun={}",
        rows.size(),
        req.runId(),
        safeLimit,
        dryRun);

    int updated = 0;
    int unchanged = 0;
    int skipped = 0;
    int errors = 0;
    List<ScoreChange> changes = new java.util.ArrayList<>();

    for (ValidationReport row : rows) {
      try {
        if (row == null) {
          skipped++;
          continue;
        }

        Integer endpointCoverage = endpointCoverageFromAccuracyJson(row.getAccuracyJson());
        String testCoverageLevel = testCoverageLevelFromAccuracyJson(row.getAccuracyJson());
        int unfixedCritical = countUnfixedCriticalFromIssuesJson(row.getIssuesJson());
        int recomputedVerified =
            scoreService.computeVerifiedScore(
                row.getFinalBuildPassed(),
                row.getTestsPassed() == null ? 0 : row.getTestsPassed(),
                row.getTestsTotal() == null ? 0 : row.getTestsTotal(),
                row.getReviewerVerdict(),
                unfixedCritical,
                endpointCoverage,
                testCoverageLevel,
                row.getFilesGenerated());
        int recomputedRubric =
            scoreService.computeRubricScore(
                row.getFinalBuildPassed(),
                row.getTestsPassed() == null ? 0 : row.getTestsPassed(),
                row.getTestsTotal() == null ? 0 : row.getTestsTotal(),
                row.getReviewerVerdict(),
                unfixedCritical,
                endpointCoverage,
                testCoverageLevel);

        Integer existingVerified = row.getVerifiedScore();
        Integer existingRubric = row.getRubricScore();
        boolean same =
            java.util.Objects.equals(existingVerified, recomputedVerified)
                && java.util.Objects.equals(existingRubric, recomputedRubric);
        if (same) {
          unchanged++;
          continue;
        }

        if (changes.size() < MAX_CHANGE_SAMPLES) {
          changes.add(
              new ScoreChange(
                  row.getId(),
                  row.getRunId(),
                  existingVerified,
                  recomputedVerified,
                  existingRubric,
                  recomputedRubric));
        }
        if (!dryRun) {
          row.setVerifiedScore(recomputedVerified);
          row.setRubricScore(recomputedRubric);
          reportRepo.save(row);
        }
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

    BackfillResult result =
        new BackfillResult(
            rows.size(),
            updated + unchanged + skipped + errors,
            updated,
            unchanged,
            skipped,
            errors,
            dryRun,
            blankToNull(req.runId()),
            safeLimit,
            List.copyOf(changes));
    log.info("[VerifiedScoreBackfill] done: {}", result);
    return result;
  }

  private List<ValidationReport> rowsFor(String runId, Integer limit) {
    String targetRun = blankToNull(runId);
    if (targetRun != null) {
      Optional<ValidationReport> one = reportRepo.findByRunId(targetRun);
      return one.map(List::of).orElseGet(List::of);
    }
    if (limit != null) {
      return reportRepo
          .findAll(PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt")))
          .getContent();
    }
    return reportRepo.findAll();
  }

  private static Integer safeLimit(Integer raw) {
    if (raw == null) return null;
    return Math.max(1, Math.min(MAX_LIMIT, raw));
  }

  private static String blankToNull(String value) {
    if (value == null || value.isBlank()) return null;
    return value.trim();
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

  static String testCoverageLevelFromAccuracyJson(String accuracyJson) {
    if (accuracyJson == null || accuracyJson.isBlank()) return null;
    try {
      JsonNode root = MAPPER.readTree(accuracyJson);
      JsonNode level = root.path("testCoverageLevel");
      if (level.isMissingNode() || level.isNull() || !level.isTextual()) return null;
      String text = level.asText();
      return text == null || text.isBlank() ? null : text.trim().toUpperCase();
    } catch (IOException ex) {
      return null;
    }
  }
}
