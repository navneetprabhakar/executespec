package com.navneet.executespec.harness;

/**
 * Outcome of a merge-approval gate.
 *
 * <ul>
 *   <li>{@link #MERGE} — user approved; proceed to merge branches into main.</li>
 *   <li>{@link #DECLINE} — user declined; mark run PARTIAL and preserve for later.</li>
 *   <li>{@link #IMPLEMENT_SUGGESTIONS} — user requested automated implementation of
 *       post-merge suggestions (MEDIUM/LOW reviewer issues); re-run affected tasks with
 *       suggestion feedback injected, then re-review and re-open the gate.</li>
 *   <li>{@link #REWORK} — user requested rework of tasks flagged by the reviewer;
 *       re-run those tasks with reviewer feedback injected, then re-review and
 *       re-open the gate.</li>
 * </ul>
 */
public enum MergeDecision {
    MERGE,
    DECLINE,
    IMPLEMENT_SUGGESTIONS,
    REWORK
}
