package com.navneet.executespec.contracts.breaking;

import java.util.List;

/**
 * E2.7 — output of every {@link BreakingChangeClassifier}. Holds a typed
 * change list plus a {@link Confidence} marker so callers (E2.12 plan-gate
 * UX) can show "deterministic" vs "LLM-derived" badges separately.
 */
public record ContractDiff(
        String summary,
        List<Change> changes,
        Confidence confidence,
        String classifierName) {

    /** Quick predicate — true when at least one change is BREAKING. */
    public boolean hasBreaking() {
        return changes != null && changes.stream()
                .anyMatch(c -> c.severity() == Change.Severity.BREAKING);
    }

    /** Provenance of the classification — drives the UX badge. */
    public enum Confidence {
        /** Deterministic CLI / structural diff — definitive verdict. */
        DETERMINISTIC,
        /** LLM-derived — fall back when no deterministic tool could classify. */
        LLM_DERIVED,
        /** Could not classify (CLI missing + LLM disabled). Caller decides. */
        UNCLASSIFIED
    }

    /**
     * Single typed change pulled out of the diff.
     *
     * @param severity   {@link Severity#BREAKING} (consumer code breaks),
     *                   {@link Severity#NON_BREAKING} (additive only),
     *                   {@link Severity#COSMETIC} (documentation, formatting)
     * @param description human-readable description
     * @param location    path/field within the contract (e.g. {@code paths./pay.post.responses.200})
     */
    public record Change(Severity severity, String description, String location) {
        public enum Severity { BREAKING, NON_BREAKING, COSMETIC }
    }
}
