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
              filePatternEnforcer.evaluate(fpe, repoFilePaths);
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
          AstCheckEnforcer.AstOutcome outcome = astCheckEnforcer.evaluate(ace, sourceFiles);
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
        case Standard.LlmReviewEnforcement ignored -> {
          /* handled in reviewer prompt */
        }
      }
    }
    return new RunReport(all, lintResults, evaluationStatuses);
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
