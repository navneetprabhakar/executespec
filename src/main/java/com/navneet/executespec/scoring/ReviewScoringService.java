package com.navneet.executespec.scoring;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.reviewer.model.AccuracyReport;
import com.navneet.executespec.reviewer.model.IssueCategory;
import com.navneet.executespec.reviewer.model.IssueSeverity;
import com.navneet.executespec.reviewer.model.ReviewIssue;
import com.navneet.executespec.reviewer.model.ReviewReportV2;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Pilot R6 — produces review dimensions when there's real signal, suppresses them (returns
 * {@code null}) when the only signal would be the anchor passthrough. Closes the b171fa52 follow-up
 * where the dashboard showed Code quality = 0, Architecture = 0, Security = 40, Performance = 40
 * for a run where: (a) every issue double-counted into codeQuality + its category, (b) we have no
 * actual security or performance measurement so those bars showed the anchor unchanged and looked
 * like authoritative scores.
 *
 * <p>Honesty rules per dimension:
 *
 * <ul>
 *   <li>{@code codeQuality} — always {@code null}. The verifiedScore IS the run's code-quality
 *       summary; surfacing a separate "code quality" bar that's just anchor − issues was a
 *       double-count and a misleading framing.
 *   <li>{@code testCoverage} — real measurement (workspace test/source file ratio cross-checked
 *       with the LLM's coverage level). Already returned {@code null} for doc-only runs.
 *   <li>{@code architecture} — anchor − arch-category issues, but only when there's at least one
 *       arch-category issue (CROSS_TASK_MISMATCH / LOGIC_ERROR / ERROR_HANDLING /
 *       SPEC_MISMATCH / MISSING_ENDPOINT / MISSING_FEATURE). When there are none, returns
 *       {@code null} instead of showing the anchor with no adjustment.
 *   <li>{@code security} — anchor − security issues, plus +5 when auth/validation patterns are
 *       detected in the workspace. Returns {@code null} when neither signal fires.
 *   <li>{@code performance} — always {@code null}. We never benchmark performance; deployability
 *       was a poor proxy and the bar misleadingly read as "X performance score" when it's just
 *       anchor − a handful of CI / Dockerfile issues.
 *   <li>{@code designCompliance} — only when {@code figmaUrl} is set.
 * </ul>
 *
 * Deterministic — no extra LLM call. Workspace filesystem scans are capped to 5000 files so runs
 * with huge workspaces can't drag scoring latency.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewScoringService {

  private final WorkspaceInitService workspaceInitService;

  private static final Pattern TEST_FILE =
      Pattern.compile(
          "(?i)(^|/)(test|tests|__tests__|spec)/|"
              + "(?i)\\.(test|spec)\\.(js|jsx|ts|tsx|mjs|cjs|py|rb|go|java|kt|rs|php)$|"
              + "(?i)_test\\.(go|py)$|"
              + "(?i)test_[^/]+\\.py$|"
              + "(?i)Tests?\\.java$");

  private static final Pattern SOURCE_FILE =
      Pattern.compile("(?i)\\.(js|jsx|ts|tsx|mjs|cjs|py|rb|go|java|kt|rs|php|swift|scala)$");

  private static final Pattern AUTH_HINT =
      Pattern.compile(
          "(?i)(jwt|bcrypt|passport|oauth|authenticate|authorize|csrf|helmet|rate[-"
              + " ]?limit|sanitize|escape|validator|joi|zod|yup)");

  public record Scores(
      Integer codeQuality,
      Integer testCoverage,
      Integer architecture,
      Integer security,
      Integer performance,
      Integer designCompliance) {}

  /**
   * Doc / config files that should NOT count toward source-file denominators when deciding whether
   * a run meaningfully changed testable code.
   */
  private static final Pattern DOC_OR_CONFIG =
      Pattern.compile(
          "(?i)(^|/)(readme|changelog|license|contributing|authors|notice)(\\.(md|txt|rst))?$|"
              + "(?i)\\.(md|txt|rst|yml|yaml|toml|ini|cfg|conf|properties|env|gitignore|gitattributes|editorconfig|npmrc|prettierrc|dockerignore)$|"
              + "(?i)(^|/)Dockerfile(\\..+)?$|(?i)(^|/)Makefile$");

  /**
   * v2.5.10 overload: when {@code verifiedScore} is non-null, the 6 dimensions anchor on it (a
   * deterministic build+tests+verdict signal) instead of the LLM-generated {@code
   * accuracy.specAdherenceScore}. Caller (ValidationReportService) gates the decision on the {@code
   * scoring.v2-verified} feature flag.
   */
  public Scores score(
      PipelineRun run,
      ReviewReportV2 review,
      AccuracyReport accuracy,
      Integer verifiedScore,
      String figmaUrl) {
    List<ReviewIssue> issues =
        (review != null && review.getIssues() != null) ? review.getIssues() : List.of();

    int anchor = (verifiedScore != null) ? clamp(verifiedScore) : resolveAnchor(accuracy, issues);

    int codeIssuePenalty = 0;
    int secIssuePenalty = 0;
    int archIssuePenalty = 0;
    int perfIssuePenalty = 0;
    int designIssues = 0;

    for (ReviewIssue issue : issues) {
      int penalty = severityPenalty(issue.getSeverity());
      codeIssuePenalty += penalty;
      IssueCategory category = issue.getCategory();
      if (category != null)
        switch (category) {
          case SECURITY -> secIssuePenalty += penalty * 2;
          case CROSS_TASK_MISMATCH, LOGIC_ERROR, ERROR_HANDLING -> archIssuePenalty += penalty * 2;
          case DEPLOYABILITY -> perfIssuePenalty += penalty * 2;
          case MISSING_ENDPOINT, MISSING_FEATURE, SPEC_MISMATCH -> archIssuePenalty += penalty;
          default -> {
            /* counted in codeIssuePenalty */
          }
        }
      String desc = issue.getDescription() == null ? "" : issue.getDescription().toLowerCase();
      if (desc.contains("figma") || desc.contains("design") || desc.contains("ui ")) designIssues++;
    }

    WorkspaceStats ws = scanWorkspace(run);

    // 2026-05-02 — honest dimensions. codeIssuePenalty / perfIssuePenalty are no longer used
    // to drive a visible dimension; verifiedScore (the anchor) is the run-level code-quality
    // summary, and we don't actually benchmark performance. archIssuePenalty + secIssuePenalty
    // gate their dimensions on having a real category signal (or auth hint, for security).
    Integer codeQuality = null;
    Integer testCoverage = deriveTestCoverage(ws, accuracy);
    Integer architecture = (archIssuePenalty > 0) ? clamp(anchor - archIssuePenalty) : null;
    Integer security =
        (secIssuePenalty > 0 || ws.hasAuthHints)
            ? clamp(anchor - secIssuePenalty + (ws.hasAuthHints ? 5 : 0))
            : null;
    Integer performance = null;

    Integer designCompliance = null;
    if (figmaUrl != null && !figmaUrl.isBlank()) {
      designCompliance = clamp(anchor - designIssues * 8);
    }

    Scores scores =
        new Scores(
            codeQuality, testCoverage, architecture, security, performance, designCompliance);
    // codeIssuePenalty + perfIssuePenalty intentionally logged but not surfaced — they're
    // useful for back-end scoring telemetry / future revival of those dimensions, but never
    // bound to a UI bar.
    log.info(
        "Scored run={} anchor={} scores={} codePenalty={} perfPenalty={} srcChanged={} testChanged={}",
        run != null ? run.getRunId() : "?",
        anchor,
        scores,
        codeIssuePenalty,
        perfIssuePenalty,
        ws.sourceFilesChanged,
        ws.testFilesChanged);
    return scores;
  }

  /**
   * Base score for every dimension. Prefer the LLM's spec-adherence score; fall back to a heuristic
   * derived from issue count when the LLM didn't produce one (e.g. very fast runs where accuracy
   * didn't populate).
   */
  private static int resolveAnchor(AccuracyReport accuracy, List<ReviewIssue> issues) {
    if (accuracy != null && accuracy.getSpecAdherenceScore() > 0) {
      return accuracy.getSpecAdherenceScore();
    }
    int critical = 0, minor = 0;
    for (ReviewIssue i : issues) {
      if (i.getSeverity() == IssueSeverity.CRITICAL) critical++;
      else if (i.getSeverity() == IssueSeverity.MINOR) minor++;
    }
    return clamp(100 - critical * 12 - minor * 3);
  }

  private static int severityPenalty(IssueSeverity severity) {
    if (severity == null) return 2;
    return switch (severity) {
      case CRITICAL -> 12;
      case MINOR -> 3;
    };
  }

  /**
   * Derives a 0-100 coverage score — but only from files this run actually touched. Scoring against
   * the whole workspace punished runs that only changed a README in a large Java codebase (0/500
   * tests = score 20 even though the run had no testable code to cover).
   *
   * <p>Returns {@code null} when the run modified no source files (pure doc / config changes) — the
   * UI hides null rows instead of showing a misleading number.
   *
   * <p>v2.5.12 fix for the "Test coverage = 20 by default" bug.
   */
  private static Integer deriveTestCoverage(WorkspaceStats ws, AccuracyReport accuracy) {
    // No source files in the CHANGED set → doc-only change. Hide the bar.
    if (ws.sourceFilesChanged <= 0) {
      return null;
    }
    double ratio = (double) ws.testFilesChanged / Math.max(1, ws.sourceFilesChanged);
    int scoreFromRatio;
    if (ratio >= 0.50) scoreFromRatio = 95;
    else if (ratio >= 0.30) scoreFromRatio = 80;
    else if (ratio >= 0.15) scoreFromRatio = 60;
    else if (ratio >= 0.05) scoreFromRatio = 40;
    else if (ws.testFilesChanged > 0) scoreFromRatio = 25;
    else scoreFromRatio = 10;

    // Pull toward LLM level if it corroborates (reduces noise on tiny diffs).
    int llm = llmCoverageLevel(accuracy);
    return clamp((scoreFromRatio * 2 + llm) / 3);
  }

  private static int llmCoverageLevel(AccuracyReport accuracy) {
    if (accuracy == null || accuracy.getTestCoverageLevel() == null) return 40;
    return switch (accuracy.getTestCoverageLevel().toUpperCase()) {
      case "HIGH", "COMPREHENSIVE" -> 95;
      case "MEDIUM", "ADEQUATE" -> 70;
      case "LOW", "MINIMAL" -> 35;
      case "NONE" -> 10;
      default -> 40;
    };
  }

  /**
   * Workspace scan results. The *Changed counters cover files this run actually touched (mtime
   * after run start); auth hints come from any file in the workspace regardless of modification
   * time.
   */
  private record WorkspaceStats(
      int sourceFilesChanged, int testFilesChanged, boolean hasAuthHints) {}

  private WorkspaceStats scanWorkspace(PipelineRun run) {
    if (run == null || run.getRunId() == null) return new WorkspaceStats(0, 0, false);
    Path root;
    try {
      root = workspaceInitService.runWorkspacePath(run.getRunId());
    } catch (Exception e) {
      return new WorkspaceStats(0, 0, false);
    }
    if (!Files.isDirectory(root)) return new WorkspaceStats(0, 0, false);

    // mtime floor = run start - 10s grace (clock skew / initial clone).
    long mtimeFloorMs =
        run.getStartedAt() != null ? run.getStartedAt().toEpochMilli() - 10_000L : 0L;

    int[] sourceChanged = {0};
    int[] testChanged = {0};
    boolean[] auth = {false};
    int limit = 5000;

    try (Stream<Path> walk = Files.walk(root)) {
      walk.filter(Files::isRegularFile)
          .limit(limit)
          .forEach(
              p -> {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.contains("/node_modules/")
                    || rel.contains("/.git/")
                    || rel.contains("/target/")
                    || rel.contains("/build/")
                    || rel.contains("/__pycache__/")
                    || rel.contains("/.venv/")) {
                  return;
                }
                // Only count files this run touched. Doc/config files are
                // excluded from the "source" denominator so a README-only
                // change doesn't count against test coverage.
                long mtime = 0L;
                try {
                  mtime = Files.getLastModifiedTime(p).toMillis();
                } catch (IOException ignored) {
                }
                boolean changed = mtime >= mtimeFloorMs;

                if (changed && !DOC_OR_CONFIG.matcher(rel).find()) {
                  if (TEST_FILE.matcher(rel).find()) {
                    testChanged[0]++;
                    sourceChanged[0]++; // count test as source too so ratio has denominator
                  } else if (SOURCE_FILE.matcher(rel).find()) {
                    sourceChanged[0]++;
                  }
                }

                if (!auth[0]) {
                  try {
                    String head = readHead(p, 4000);
                    if (head != null && AUTH_HINT.matcher(head).find()) auth[0] = true;
                  } catch (IOException ignored) {
                  }
                }
              });
    } catch (IOException e) {
      log.debug("Workspace scan failed for run {}: {}", run.getRunId(), e.getMessage());
    }
    return new WorkspaceStats(sourceChanged[0], testChanged[0], auth[0]);
  }

  private static String readHead(Path p, int max) throws IOException {
    long size = Files.size(p);
    if (size > 200_000) return null; // skip huge generated files
    byte[] bytes = Files.readAllBytes(p);
    int len = Math.min(bytes.length, max);
    return new String(bytes, 0, len);
  }

  private static int clamp(int v) {
    return Math.max(0, Math.min(100, v));
  }
}
