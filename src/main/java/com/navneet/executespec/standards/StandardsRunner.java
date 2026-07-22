package com.navneet.executespec.standards;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.standards.enforcement.EvaluationStatus;
import com.navneet.executespec.standards.enforcement.ast.AstCheckEnforcer;
import com.navneet.executespec.standards.enforcement.filepattern.FilePatternEnforcer;
import com.navneet.executespec.standards.enforcement.lint.LintAdapter;
import com.navneet.executespec.standards.enforcement.lint.LintResult;
import com.navneet.executespec.standards.enforcement.lint.LintViolation;
import com.navneet.executespec.standards.model.Standard;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * E3.10 — orchestrates server-side enforcement of every applicable standard against a workspace.
 * Single entry-point used by the worker self-check loop (after every code edit) AND the reviewer
 * phase (final verdict on the whole diff).
 *
 * <p>Per plan §5.6 ("Rule execution stages"), AST + lint + file-pattern checks run twice for
 * defence-in-depth — worker self-check first, reviewer pass second. This service is stateless; each
 * call evaluates one workspace snapshot.
 *
 * <p>{@code llm-review} standards are NOT executed here — they piggy-back on the reviewer's
 * existing LLM call via {@code LlmReviewEnforcer}'s prompt block. This service handles only the
 * deterministic three.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StandardsRunner {

  private final FilePatternEnforcer filePatternEnforcer;
  private final AstCheckEnforcer astCheckEnforcer;
  private final List<LintAdapter> lintAdapters;

  /**
   * Run every deterministic enforcement type for every applicable standard. {@code llm-review}
   * standards are skipped here — caller integrates them via {@code
   * LlmReviewEnforcer.composePromptBlock}.
   *
   * @param runId the V2 run this enforcement belongs to; threaded into the lint adapters so an
   *     admitted linter can be contained in the run's sandbox rather than run on the host (RR-1051
   *     / RR-1674). Null/blank means no run scope, which — with sandbox routing on — makes lint
   *     UNAVAILABLE by design.
   * @param effective output of {@link EffectiveStandardsResolver#resolve}
   * @param repoRoot local path of the cloned workspace
   * @param repoFilePaths repo-relative file paths (for file-pattern matching; glob globs evaluate
   *     against these strings, not disk)
   * @param sourceFiles Path objects for the AST checker to read content from
   */
  public RunReport run(
      String runId,
      List<EffectiveStandardsResolver.Effective> effective,
      Path repoRoot,
      List<String> repoFilePaths,
      List<Path> sourceFiles) {
    // Back-compat: callers that own a complete inventory get the previous behaviour.
    return run(runId, effective, repoRoot, repoFilePaths, sourceFiles, true);
  }

  /**
   * @param inventoryComplete RR-1612 — false when the workspace file walk hit its cap or pruned a
   *     subtree, so a {@code required_patterns} "missing" assertion is unsound and is surfaced as
   *     verifier-unavailable instead of a false BLOCKING violation.
   */
  public RunReport run(
      String runId,
      List<EffectiveStandardsResolver.Effective> effective,
      Path repoRoot,
      List<String> repoFilePaths,
      List<Path> sourceFiles,
      boolean inventoryComplete) {
    List<AggregatedViolation> all = new ArrayList<>();
    if (effective == null) return new RunReport(List.of(), Map.of());

    Map<String, LintResult> lintResults = new HashMap<>();
    // RR-1611 — per-standard availability for the AST + file-pattern deterministic types, so a
    // degraded check is visible to the gate instead of being an implicit clean pass.
    Map<String, EvaluationStatus> evaluationStatuses = new HashMap<>();
    Map<String, LintAdapter> byTool = new HashMap<>();
    for (LintAdapter la : lintAdapters) byTool.put(la.tool(), la);

    for (EffectiveStandardsResolver.Effective e : effective) {
      Standard s = e.standard();
      switch (s.enforcement()) {
        case Standard.LintEnforcement le -> {
          LintAdapter adapter = byTool.get(le.tool());
          if (adapter == null) {
            log.warn("[StandardsRunner] no adapter for configured lint tool '{}'", le.tool());
            lintResults.put(
                s.id(),
                new LintResult(
                    le.tool(),
                    LintResult.Status.UNAVAILABLE,
                    List.of(),
                    "No server adapter is registered for the configured lint tool"));
            continue;
          }
          Path config = le.configPath() == null ? null : Path.of(le.configPath());
          LintResult lr = adapter.run(runId, repoRoot, config, List.of());
          lintResults.put(s.id(), lr);
          for (LintViolation lv : lr.violations()) {
            all.add(
                new AggregatedViolation(
                    s.id(),
                    s.severity(),
                    "LINT",
                    lv.file() + ":" + lv.line(),
                    lv.ruleId() + ": " + lv.message()));
          }
        }
        case Standard.FilePatternEnforcement fpe -> {
          FilePatternEnforcer.FilePatternOutcome outcome =
              filePatternEnforcer.evaluate(fpe, repoFilePaths, inventoryComplete);
          evaluationStatuses.put(s.id(), outcome.status());
          for (FilePatternEnforcer.Violation v : outcome.violations()) {
            all.add(
                new AggregatedViolation(
                    s.id(),
                    s.severity(),
                    "FILE_PATTERN",
                    v.file() == null ? v.pattern() : v.file(),
                    v.message()));
          }
        }
        case Standard.AstCheckEnforcement ace -> {
          // RR-1052 / RR-1632 — apply the standard's declared scope (languages / paths) before the
          // AST check runs, so a Java-scoped or path-scoped policy no longer gates unrelated files.
          List<Path> scoped = scopeFilter(sourceFiles, s.scope());
          if (!sourceFiles.isEmpty() && scoped.isEmpty()) {
            // The scope legitimately excludes every file in the workspace — nothing in scope can
            // violate the rule. That is a clean, evaluated pass, NOT an unavailable check.
            evaluationStatuses.put(s.id(), EvaluationStatus.EVALUATED);
          } else {
            AstCheckEnforcer.AstOutcome outcome = astCheckEnforcer.evaluate(ace, scoped);
            evaluationStatuses.put(s.id(), outcome.status());
            for (AstCheckEnforcer.AstViolation v : outcome.violations()) {
              all.add(
                  new AggregatedViolation(
                      s.id(),
                      s.severity(),
                      "AST_CHECK",
                      v.file() + ":" + v.line(),
                      v.ruleKind() + ": " + v.message()));
            }
          }
        }
        case Standard.LlmReviewEnforcement ignored -> {
          // RR-860 — llm-review is NOT executed as a structured gate here (it only ever contributed
          // advisory prompt text). A BLOCKING semantic standard that no deterministic stage can
          // verify must not read as a clean pass: surface it as verifier-unavailable so the gate
          // forces PARTIAL/STANDARDS_ENFORCEMENT_UNAVAILABLE rather than silently certifying it.
          // WARNING/ADVISORY llm-review standards stay advisory (they do not gate).
          if (s.severity() == Standard.Severity.BLOCKING) {
            evaluationStatuses.put(s.id(), EvaluationStatus.UNAVAILABLE);
          }
        }
      }
    }
    return new RunReport(all, lintResults, evaluationStatuses);
  }

  /**
   * RR-1052 / RR-1632 — restrict the AST source-file set to the standard's declared scope. A null
   * or empty scope means "applies everywhere" (no filtering). {@code languages} filters by file
   * extension; {@code paths} filters by repo-relative glob. Both act as an AND filter. {@code
   * repos} (monorepo-only) is not applied here — that requires repo-boundary metadata the runner
   * does not receive — and is tracked as residual.
   */
  static List<Path> scopeFilter(List<Path> files, Standard.Scope scope) {
    if (files == null || files.isEmpty() || scope == null || scope.isEmpty()) {
      return files == null ? List.of() : files;
    }
    List<String> languages = scope.languages();
    List<String> paths = scope.paths();
    boolean filterLang = languages != null && !languages.isEmpty();
    boolean filterPaths = paths != null && !paths.isEmpty();
    if (!filterLang && !filterPaths) return files;
    List<Path> out = new ArrayList<>();
    for (Path p : files) {
      String rel = p.toString();
      if (filterLang && !matchesAnyLanguage(rel, languages)) continue;
      if (filterPaths && !matchesAnyGlob(rel, paths)) continue;
      out.add(p);
    }
    return out;
  }

  private static boolean matchesAnyGlob(String path, List<String> globs) {
    for (String g : globs) {
      if (g != null
          && !g.isBlank()
          && com.navneet.executespec.contracts.detection.DetectorUtils.matchesGlob(path, g)) {
        return true;
      }
    }
    return false;
  }

  private static boolean matchesAnyLanguage(String path, List<String> languages) {
    String lang = languageOf(path);
    for (String l : languages) {
      if (l != null && l.trim().equalsIgnoreCase(lang)) return true;
    }
    return false;
  }

  /** Map a file path to a coarse language token matching the {@code scope.languages} vocabulary. */
  private static String languageOf(String path) {
    String p = path.toLowerCase();
    if (p.endsWith(".java")) return "java";
    if (p.endsWith(".ts") || p.endsWith(".tsx")) return "typescript";
    if (p.endsWith(".js") || p.endsWith(".jsx") || p.endsWith(".mjs") || p.endsWith(".cjs"))
      return "javascript";
    if (p.endsWith(".py")) return "python";
    if (p.endsWith(".go")) return "go";
    if (p.endsWith(".rb")) return "ruby";
    if (p.endsWith(".rs")) return "rust";
    if (p.endsWith(".kt") || p.endsWith(".kts")) return "kotlin";
    if (p.endsWith(".cs")) return "csharp";
    if (p.endsWith(".php")) return "php";
    if (p.endsWith(".swift")) return "swift";
    if (p.endsWith(".scala")) return "scala";
    return "other";
  }

  /**
   * @param standardId id of the violated standard
   * @param severity effective severity at time of detection
   * @param enforcementType LINT | FILE_PATTERN | AST_CHECK | LLM_REVIEW
   * @param location file:line or pattern key
   * @param message human-readable message
   */
  public record AggregatedViolation(
      String standardId,
      Standard.Severity severity,
      String enforcementType,
      String location,
      String message) {}

  /**
   * @param violations flat list across all standards
   * @param lintResults per-standard-id raw LintResult (for telemetry + surfacing UNAVAILABLE
   *     adapters in the report)
   * @param evaluationStatuses RR-1611 — per-standard-id availability for the AST + file-pattern
   *     deterministic types (lint carries its own status via {@code lintResults}). A non-{@code
   *     EVALUATED} status is a degraded check the gate must surface as unavailable, not a silent
   *     pass. Never null (empty when nothing deterministic-non-lint ran).
   */
  public record RunReport(
      List<AggregatedViolation> violations,
      Map<String, LintResult> lintResults,
      Map<String, EvaluationStatus> evaluationStatuses) {

    /**
     * Backward-compatible constructor for callers that do not populate the availability channel
     * (empty statuses ⇒ no AST/file-pattern unavailability issues emitted).
     */
    public RunReport(List<AggregatedViolation> violations, Map<String, LintResult> lintResults) {
      this(violations, lintResults, Map.of());
    }

    /** Convenience: true when at least one BLOCKING violation present. */
    public boolean hasBlocking() {
      return violations.stream().anyMatch(v -> v.severity() == Standard.Severity.BLOCKING);
    }

    public int countBySeverity(Standard.Severity sev) {
      return (int) violations.stream().filter(v -> v.severity() == sev).count();
    }
  }
}
