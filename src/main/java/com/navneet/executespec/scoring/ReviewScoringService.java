package com.navneet.executespec.scoring;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.reviewer.model.AccuracyReport;
import com.navneet.executespec.reviewer.model.IssueCategory;
import com.navneet.executespec.reviewer.model.IssueSeverity;
import com.navneet.executespec.reviewer.model.ReviewIssue;
import com.navneet.executespec.reviewer.model.ReviewReport;
import com.navneet.executespec.state.entity.PipelineRun;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Pilot R6 — produces review dimensions when there's real signal, suppresses them (returns {@code
 * null}) when the only signal would be the anchor passthrough. Closes the b171fa52 follow-up where
 * the dashboard showed Code quality = 0, Architecture = 0, Security = 40, Performance = 40 for a
 * run where: (a) every issue double-counted into codeQuality + its category, (b) we have no actual
 * security or performance measurement so those bars showed the anchor unchanged and looked like
 * authoritative scores.
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
 *       arch-category issue (CROSS_TASK_MISMATCH / LOGIC_ERROR / ERROR_HANDLING / SPEC_MISMATCH /
 *       MISSING_ENDPOINT / MISSING_FEATURE). When there are none, returns {@code null} instead of
 *       showing the anchor with no adjustment.
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
      ReviewReport review,
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
        "Scored run={} anchor={} scores={} codePenalty={} perfPenalty={} srcChanged={}"
            + " testChanged={}",
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
    if (accuracy != null
        && accuracy.hasCitedSpecEvidence()
        && accuracy.getSpecAdherenceScore() > 0) {
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
    // Missing Git evidence is not evidence of a doc-only change. Hide the dimension rather than
    // manufacturing a score from clone mtimes or treating an unavailable measurement as zero.
    if (!ws.changedFilesMeasured) {
      return null;
    }
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
    if (accuracy == null
        || !accuracy.hasCitedTestEvidence()
        || accuracy.getTestCoverageLevel() == null) return 40;
    // A6 (audit 2026-05-29) — AccuracyAssessmentService now grades against the
    // NONE / PARTIAL / GOOD / COMPREHENSIVE bands. GOOD + PARTIAL had no case and fell through to
    // the 40 default, under-scoring the dashboard test-coverage signal for honest runs (a GOOD run
    // read as 40). Map them; keep the legacy HIGH/MEDIUM/LOW/ADEQUATE/MINIMAL aliases for backward
    // compat with already-persisted reports.
    return switch (accuracy.getTestCoverageLevel().toUpperCase()) {
      case "HIGH", "COMPREHENSIVE" -> 95;
      case "GOOD" -> 80;
      case "MEDIUM", "ADEQUATE" -> 70;
      case "PARTIAL" -> 50;
      case "LOW", "MINIMAL" -> 35;
      case "NONE" -> 10;
      default -> 40;
    };
  }

  /**
   * Workspace scan results. The *Changed counters come exclusively from exact Git index/worktree
   * status; auth hints come from any file in the workspace regardless of Git modification state.
   */
  record WorkspaceStats(
      int sourceFilesChanged,
      int testFilesChanged,
      boolean hasAuthHints,
      boolean changedFilesMeasured) {}

  WorkspaceStats scanWorkspace(PipelineRun run) {
    if (run == null || run.getRunId() == null) return new WorkspaceStats(0, 0, false, false);
    Path root;
    try {
      root = workspaceInitService.runWorkspacePath(run.getRunId());
    } catch (Exception e) {
      return new WorkspaceStats(0, 0, false, false);
    }
    if (!Files.isDirectory(root)) return new WorkspaceStats(0, 0, false, false);

    boolean auth = scanAuthorizationHints(root, run.getRunId());
    Set<String> changedPaths;
    try {
      changedPaths = gitChangedPaths(root);
    } catch (Exception e) {
      log.warn(
          "Git changed-file measurement unavailable for run {}: {}",
          run.getRunId(),
          e.getMessage());
      return new WorkspaceStats(0, 0, auth, false);
    }

    int sourceChanged = 0;
    int testChanged = 0;
    for (String rel : changedPaths) {
      if (DOC_OR_CONFIG.matcher(rel).find()) continue;
      if (TEST_FILE.matcher(rel).find()) {
        testChanged++;
        sourceChanged++; // tests remain in the source denominator used by deriveTestCoverage
      } else if (SOURCE_FILE.matcher(rel).find()) {
        sourceChanged++;
      }
    }
    return new WorkspaceStats(sourceChanged, testChanged, auth, true);
  }

  /** Preserves the independent whole-workspace authorization hint scan. */
  private boolean scanAuthorizationHints(Path root, String runId) {
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
                if (!auth[0]) {
                  try {
                    String head = readHead(p, 4000);
                    if (head != null && AUTH_HINT.matcher(head).find()) auth[0] = true;
                  } catch (IOException ignored) {
                  }
                }
              });
    } catch (IOException e) {
      log.debug("Workspace auth scan failed for run {}: {}", runId, e.getMessage());
    }
    return auth[0];
  }

  /**
   * Returns the exact union of modified/indexed/untracked/deleted/conflicting paths. Multi-repo
   * workspaces measure each checkout below {@code repos/}; the outer seed repository is not source
   * evidence. Any unavailable checkout makes the whole measurement unavailable rather than silently
   * undercounting.
   */
  private Set<String> gitChangedPaths(Path workspaceRoot) throws Exception {
    List<Path> repositories = gitRepositories(workspaceRoot);
    Set<String> changed = new LinkedHashSet<>();
    for (Path repository : repositories) {
      Status status;
      try (Git git = Git.open(repository.toFile())) {
        status = git.status().call();
      }
      Set<String> repositoryPaths = new LinkedHashSet<>();
      repositoryPaths.addAll(status.getAdded());
      repositoryPaths.addAll(status.getChanged());
      repositoryPaths.addAll(status.getModified());
      repositoryPaths.addAll(status.getRemoved());
      repositoryPaths.addAll(status.getMissing());
      repositoryPaths.addAll(status.getConflicting());
      repositoryPaths.addAll(status.getUntracked());
      for (String repositoryPath : repositoryPaths) {
        Path absolute = repository.resolve(repositoryPath).normalize();
        if (!absolute.startsWith(repository.normalize())) {
          throw new IOException("Git status returned an escaping path");
        }
        changed.add(workspaceRoot.relativize(absolute).toString().replace('\\', '/'));
      }
    }
    return changed;
  }

  private List<Path> gitRepositories(Path workspaceRoot) throws IOException {
    Path reposRoot = workspaceRoot.resolve("repos");
    if (Files.isDirectory(reposRoot)) {
      List<Path> children;
      try (Stream<Path> stream = Files.list(reposRoot)) {
        children = stream.filter(Files::isDirectory).sorted().toList();
      }
      if (!children.isEmpty()) {
        List<Path> repositories = new ArrayList<>(children.size());
        for (Path child : children) {
          if (Files.isSymbolicLink(child) || !Files.exists(child.resolve(".git"))) {
            throw new IOException("repository checkout unavailable: " + child.getFileName());
          }
          repositories.add(child);
        }
        return repositories;
      }
    }
    if (Files.exists(workspaceRoot.resolve(".git"))) {
      return List.of(workspaceRoot);
    }
    throw new IOException("workspace has no Git checkout");
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
