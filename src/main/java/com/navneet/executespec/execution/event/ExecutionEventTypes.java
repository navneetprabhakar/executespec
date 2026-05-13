package com.navneet.executespec.execution.event;

/** SSE event type constants for the Execution View channel (sse:channel:exec:{runId}). */
public final class ExecutionEventTypes {
  private ExecutionEventTypes() {}

  // Analysis phase
  public static final String EXEC_REPO_CODEMAP_STARTED = "EXEC_REPO_CODEMAP_STARTED";
  public static final String EXEC_REPO_CODEMAP_DONE = "EXEC_REPO_CODEMAP_DONE";
  public static final String EXEC_REPO_ARCH_STARTED = "EXEC_REPO_ARCH_STARTED";
  public static final String EXEC_REPO_ARCH_DONE = "EXEC_REPO_ARCH_DONE";
  public static final String EXEC_UNIFIED_ARCH_STARTED = "EXEC_UNIFIED_ARCH_STARTED";
  public static final String EXEC_ANALYSIS_COMPLETE = "EXEC_ANALYSIS_COMPLETE";

  // Planning phase
  public static final String EXEC_PLANNING_STARTED = "EXEC_PLANNING_STARTED";
  public static final String EXEC_PLAN_READY = "EXEC_PLAN_READY";
  public static final String EXEC_CONFLICT_DETECTED = "EXEC_CONFLICT_DETECTED";

  // Execution phase
  public static final String EXEC_STARTED = "EXEC_STARTED";
  public static final String EXEC_SOLUTION_QUEUED = "EXEC_SOLUTION_QUEUED";
  public static final String EXEC_SOLUTION_RUNNING = "EXEC_SOLUTION_RUNNING";
  public static final String EXEC_SOLUTION_VALIDATING = "EXEC_SOLUTION_VALIDATING";
  public static final String EXEC_SOLUTION_PR_RAISED = "EXEC_SOLUTION_PR_RAISED";
  public static final String EXEC_SOLUTION_DONE = "EXEC_SOLUTION_DONE";
  public static final String EXEC_SOLUTION_FAILED = "EXEC_SOLUTION_FAILED";
  public static final String EXEC_SOLUTION_SKIPPED = "EXEC_SOLUTION_SKIPPED";
  public static final String EXEC_SOLUTION_LOG = "EXEC_SOLUTION_LOG";
  public static final String EXEC_PAUSED_FOR_DECISION = "EXEC_PAUSED_FOR_DECISION";
  public static final String EXEC_RESUMED = "EXEC_RESUMED";

  // Completion
  public static final String EXEC_FINAL_PR_CREATED = "EXEC_FINAL_PR_CREATED";
  public static final String EXEC_COMPLETE = "EXEC_COMPLETE";
  public static final String EXEC_ABORTED = "EXEC_ABORTED";

  /**
   * V188 (2026-05-13) — terminal-FAILED notification. Used by the unified-dispatch path when the
   * underlying V2 pipeline run terminates with status=FAILED, separate from the user-initiated
   * EXEC_ABORTED. Frontend should treat both as terminal "no-PR" states; FAILED implies the run
   * itself errored, ABORTED implies the user/system cancelled.
   */
  public static final String EXEC_FAILED = "EXEC_FAILED";

  // v2.5.2 (Phase 2.3) — cancel rollback compensations.
  /**
   * Per-step compensation update during cancel cleanup. payload = JSON {type, repo, prNumber?,
   * status: PENDING|RUNNING|COMPLETE|FAILED, message?}.
   */
  public static final String EXEC_COMPENSATION_UPDATE = "EXEC_COMPENSATION_UPDATE";

  /** All compensations have terminated (COMPLETE or FAILED). */
  public static final String EXEC_COMPENSATION_DONE = "EXEC_COMPENSATION_DONE";
}
