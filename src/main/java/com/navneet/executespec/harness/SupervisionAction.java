package com.navneet.executespec.harness;

/**
 * Action decided by {@link TaskCompileSupervisor} after classifying compile errors
 * from a post-merge quick check.
 */
public enum SupervisionAction {

    /** All errors are cascading from unmerged sibling tasks — keep merge, pipeline continues. */
    PASS,

    /** Mechanical errors in owned files (syntax, missing import) — targeted LLM patch, no worker re-run. */
    AUTO_FIX,

    /** Structural errors in owned files — re-run WorkerAgent with targeted feedback. */
    RETRY_TASK,

    /** All errors are in non-owned files — not this task's fault, log warning and proceed. */
    CONTINUE_WITH_WARNING
}
