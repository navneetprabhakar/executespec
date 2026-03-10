package com.navneet.executespec.harness;

/**
 * Result of merging a single task branch into main.
 */
public enum MergeOutcome {

    /** Merged cleanly with no conflicts. */
    CLEAN,

    /** Had conflicts, auto-resolved by a SpecializedMerger (zero LLM cost). */
    RESOLVED_DETERMINISTIC,

    /** Had conflicts, auto-resolved by the ConflictResolverAgent (Haiku). */
    RESOLVED_LLM,

    /** Had conflicts, resolved after escalation to Sonnet. */
    ESCALATED,

    /** Conflicts could not be auto-resolved — task marked FAILED for manual retry. */
    FAILED
}
