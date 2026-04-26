package com.navneet.executespec.contracts.breaking;

import com.navneet.executespec.contracts.ContractRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * E2.7 — wraps {@code graphql-inspector diff}
 * (https://github.com/kamilkisiela/graphql-inspector) for GraphQL SDL.
 *
 * <p>Invocation: {@code graphql-inspector diff <old> <new>}. Output uses
 * emoji-prefixed lines: ✖ = breaking, ⚠ = dangerous (NON_BREAKING with
 * caveats), ✓ = safe. We map ✖ → BREAKING, ⚠ → NON_BREAKING (with caveat
 * in description), ✓ → COSMETIC.
 */
@Component
@Slf4j
public class GraphQlBreakingChangeClassifier implements BreakingChangeClassifier {

    private final CliBreakingChangeRunner runner;

    public GraphQlBreakingChangeClassifier(CliBreakingChangeRunner runner) {
        this.runner = runner;
    }

    @Override public ContractRegistry.ContractType type() { return ContractRegistry.ContractType.GRAPHQL; }
    @Override public String name() { return "GraphQlBreakingChangeClassifier"; }

    @Override
    public ContractDiff classify(String oldContent, String newContent) {
        CliBreakingChangeRunner.CliResult result = runner.run(
                oldContent, newContent, "graphql-inspector",
                (oldPath, newPath) -> List.of(
                        "graphql-inspector", "diff",
                        oldPath.toString(), newPath.toString()),
                30);
        if (!result.executed()) {
            return new ContractDiff(
                    "graphql-inspector unavailable: " + result.unavailableReason(),
                    List.of(),
                    ContractDiff.Confidence.UNCLASSIFIED,
                    name());
        }
        List<ContractDiff.Change> changes = new ArrayList<>();
        for (String raw : (result.stdout() == null ? "" : result.stdout()).split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            ContractDiff.Change.Severity sev = severityFromLine(line);
            if (sev == null) continue;
            changes.add(new ContractDiff.Change(sev, line, "(see description)"));
        }
        long breaking = changes.stream()
                .filter(c -> c.severity() == ContractDiff.Change.Severity.BREAKING).count();
        String summary = breaking > 0
                ? breaking + " breaking change" + (breaking == 1 ? "" : "s") + " (graphql-inspector)"
                : changes.isEmpty()
                    ? "No structural changes (graphql-inspector)"
                    : changes.size() + " non-breaking change(s) (graphql-inspector)";
        return new ContractDiff(summary, changes, ContractDiff.Confidence.DETERMINISTIC, name());
    }

    static ContractDiff.Change.Severity severityFromLine(String line) {
        // graphql-inspector emits unicode prefixes; also tolerate ASCII fallbacks.
        if (line.startsWith("✖") || line.startsWith("X ") || line.startsWith("BREAKING")) {
            return ContractDiff.Change.Severity.BREAKING;
        }
        if (line.startsWith("⚠") || line.startsWith("! ") || line.startsWith("DANGEROUS")) {
            return ContractDiff.Change.Severity.NON_BREAKING;
        }
        if (line.startsWith("✓") || line.startsWith("OK") || line.startsWith("SAFE")) {
            return ContractDiff.Change.Severity.COSMETIC;
        }
        return null;
    }
}
