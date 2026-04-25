package com.navneet.executespec.contracts.breaking;

import com.navneet.executespec.contracts.ContractRegistry;

/**
 * E2.7 — strategy interface for breaking-change classification. One
 * implementation per {@link com.navneet.executespec.contracts.ContractRegistry.ContractType}.
 *
 * <p>Implementations should be deterministic where possible (CLI tools
 * like {@code oasdiff} / {@code buf breaking} / {@code graphql-inspector}).
 * When the deterministic path can't classify (CLI missing or returns an
 * unparseable result), the implementation should set
 * {@link ContractDiff.Confidence#UNCLASSIFIED} and let the caller decide
 * whether to invoke the LLM fallback.
 *
 * <p>Implementations must NOT throw on classifier failure — return an
 * UNCLASSIFIED diff with {@code summary} explaining the situation.
 */
public interface BreakingChangeClassifier {

    /** Format this classifier handles. */
    ContractRegistry.ContractType type();

    /** Stable name used in {@link ContractDiff#classifierName} + logs. */
    String name();

    /**
     * Classify the diff between two versions of the same contract.
     *
     * @param oldContent previous version content (raw text — YAML / proto / SDL / JSON)
     * @param newContent new version content
     * @return typed {@link ContractDiff}; never null, never throws
     */
    ContractDiff classify(String oldContent, String newContent);
}
