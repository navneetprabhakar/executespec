package com.navneet.executespec.v2.batch;

import java.util.List;

import com.navneet.executespec.router.LlmProvider;

/**
 * Provider-agnostic batch-client contract. Each provider ({@link LlmProvider#ANTHROPIC}, {@link
 * LlmProvider#OPENAI}, {@link LlmProvider#GOOGLE}) gets its own implementation in a subsequent PR.
 *
 * <p>V2 scaffolding — implementations land in Plan 5 P2/P3. See {@code
 * docs/plans/v2-batch-execution-plan.md} §1.
 */
public interface V2BatchClient {

  /**
   * Submit a batch of per-task requests. Returns the job handle that the poll service uses to check
   * state and fetch results.
   */
  V2BatchJob submit(String runId, List<V2BatchRequest> requests);

  /**
   * Poll the current state of an in-flight job. Implementations should NOT long-poll; the scheduler
   * calls this on a 30s cron.
   */
  V2BatchJob.V2BatchJobState poll(V2BatchJob job);

  /**
   * Fetch per-task results once the job reaches a terminal state. May throw if called while the job
   * is still in progress.
   */
  List<V2BatchTaskResult> fetchResults(V2BatchJob job);

  /**
   * Best-effort cancellation. Some providers don't support this; those implementations can no-op.
   */
  void cancel(V2BatchJob job);

  /** Which provider this client talks to (used by the router). */
  LlmProvider provider();
}
