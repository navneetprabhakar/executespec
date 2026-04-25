package com.navneet.executespec.standards;

import com.navneet.executespec.standards.enforcement.ast.AstCheckEnforcer;
import com.navneet.executespec.standards.enforcement.filepattern.FilePatternEnforcer;
import com.navneet.executespec.standards.enforcement.lint.LintAdapter;
import com.navneet.executespec.standards.enforcement.lint.LintResult;
import com.navneet.executespec.standards.enforcement.lint.LintViolation;
import com.navneet.executespec.standards.model.Standard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * E3.10 — orchestrates server-side enforcement of every applicable
 * standard against a workspace. Single entry-point used by the worker
 * self-check loop (after every code edit) AND the reviewer phase
 * (final verdict on the whole diff).
 *
 * <p>Per plan §5.6 ("Rule execution stages"), AST + lint + file-pattern
 * checks run twice for defence-in-depth — worker self-check first,
 * reviewer pass second. This service is stateless; each call evaluates
 * one workspace snapshot.
 *
 * <p>{@code llm-review} standards are NOT executed here — they piggy-back
 * on the reviewer's existing LLM call via {@code LlmReviewEnforcer}'s
 * prompt block. This service handles only the deterministic three.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StandardsRunner {

    private final FilePatternEnforcer filePatternEnforcer;
    private final AstCheckEnforcer astCheckEnforcer;
    private final List<LintAdapter> lintAdapters;

    /**
     * Run every deterministic enforcement type for every applicable
     * standard. {@code llm-review} standards are skipped here —
     * caller integrates them via {@code LlmReviewEnforcer.composePromptBlock}.
     *
     * @param effective    output of {@link EffectiveStandardsResolver#resolve}
     * @param repoRoot     local path of the cloned workspace
     * @param repoFilePaths repo-relative file paths (for file-pattern matching;
     *                     glob globs evaluate against these strings, not disk)
     * @param sourceFiles  Path objects for the AST checker to read content from
     */
    public RunReport run(List<EffectiveStandardsResolver.Effective> effective,
                          Path repoRoot,
                          List<String> repoFilePaths,
                          List<Path> sourceFiles) {
        List<AggregatedViolation> all = new ArrayList<>();
        if (effective == null) return new RunReport(List.of(), Map.of());

        Map<String, LintResult> lintResults = new HashMap<>();
        Map<String, LintAdapter> byTool = new HashMap<>();
        for (LintAdapter la : lintAdapters) byTool.put(la.tool(), la);

        for (EffectiveStandardsResolver.Effective e : effective) {
            Standard s = e.standard();
            switch (s.enforcement()) {
                case Standard.LintEnforcement le -> {
                    LintAdapter adapter = byTool.get(le.tool());
                    if (adapter == null) {
                        log.debug("[StandardsRunner] no adapter for lint tool '{}'; skipping", le.tool());
                        continue;
                    }
                    Path config = le.configPath() == null ? null : Path.of(le.configPath());
                    LintResult lr = adapter.run(repoRoot, config, List.of());
                    lintResults.put(s.id(), lr);
                    for (LintViolation lv : lr.violations()) {
                        all.add(new AggregatedViolation(
                                s.id(), s.severity(), "LINT",
                                lv.file() + ":" + lv.line(),
                                lv.ruleId() + ": " + lv.message()));
                    }
                }
                case Standard.FilePatternEnforcement fpe -> {
                    for (FilePatternEnforcer.Violation v : filePatternEnforcer.enforce(fpe, repoFilePaths)) {
                        all.add(new AggregatedViolation(
                                s.id(), s.severity(), "FILE_PATTERN",
                                v.file() == null ? v.pattern() : v.file(),
                                v.message()));
                    }
                }
                case Standard.AstCheckEnforcement ace -> {
                    for (AstCheckEnforcer.AstViolation v : astCheckEnforcer.enforce(ace, sourceFiles)) {
                        all.add(new AggregatedViolation(
                                s.id(), s.severity(), "AST_CHECK",
                                v.file() + ":" + v.line(),
                                v.ruleKind() + ": " + v.message()));
                    }
                }
                case Standard.LlmReviewEnforcement ignored -> { /* handled in reviewer prompt */ }
            }
        }
        return new RunReport(all, lintResults);
    }

    /**
     * @param standardId       id of the violated standard
     * @param severity         effective severity at time of detection
     * @param enforcementType  LINT | FILE_PATTERN | AST_CHECK | LLM_REVIEW
     * @param location         file:line or pattern key
     * @param message          human-readable message
     */
    public record AggregatedViolation(
            String standardId,
            Standard.Severity severity,
            String enforcementType,
            String location,
            String message) {}

    /**
     * @param violations  flat list across all standards
     * @param lintResults per-standard-id raw LintResult (for telemetry +
     *                    surfacing UNAVAILABLE adapters in the report)
     */
    public record RunReport(List<AggregatedViolation> violations,
                             Map<String, LintResult> lintResults) {

        /** Convenience: true when at least one BLOCKING violation present. */
        public boolean hasBlocking() {
            return violations.stream()
                    .anyMatch(v -> v.severity() == Standard.Severity.BLOCKING);
        }

        public int countBySeverity(Standard.Severity sev) {
            return (int) violations.stream().filter(v -> v.severity() == sev).count();
        }
    }
}
