package com.navneet.executespec.state.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.state.entity.AgentTask;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentTaskRepository extends JpaRepository<AgentTask, String> {

  List<AgentTask> findByRunId(String runId);

  List<AgentTask> findByRunIdAndStatusIn(String runId, List<String> statuses);

  List<AgentTask> findByRunIdOrderByStartedAtAsc(String runId);

  List<AgentTask> findByStatus(String status);

  /**
   * 2026-05-04 — used by {@link
   * com.navneet.executespec.harness.SequentialExecutor#resolveResumeAction} to inspect a specific
   * task's persisted status during boot-time replay. Returns Optional because resume may run for
   * tasks that were never persisted (worker died before the first {@code persistTaskStatus} call).
   */
  Optional<AgentTask> findByRunIdAndTaskId(String runId, String taskId);

  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE AgentTask t SET t.status = 'LOCKED', t.startedAt = :now "
          + "WHERE t.taskId = :taskId AND t.status = 'PENDING'")
  int lockIfPending(@Param("taskId") String taskId, @Param("now") Instant now);

  List<AgentTask> findByParentTaskIdOrderBySubtaskIndexAsc(String parentTaskId);

  /** Atomic lock: PENDING → WORKING in a single UPDATE (no intermediate LOCKED state). */
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE AgentTask t SET t.status = 'WORKING', t.assignedModel = :model, "
          + "t.branchName = :branch, t.startedAt = :now "
          + "WHERE t.taskId = :taskId AND t.status = 'PENDING'")
  int lockAndStart(
      @Param("taskId") String taskId,
      @Param("model") String model,
      @Param("branch") String branch,
      @Param("now") Instant now);

  /**
   * B-19 fix (2026-05-09) — atomic flush of accumulated tokens to the AgentTask row at phase
   * boundaries (between primary worker and retry worker, after retry, etc.) so a pod crash mid-
   * smoke-test doesn't lose the tokens spent up to that point.
   *
   * <p>Pre-fix, AgentTask.input/output_tokens were only written by RunTokenAttributor at run-end.
   * Crashes during the second smoke test or between phases meant the tokens stayed in
   * RunCostTracker's in-memory accumulator and were never persisted; resume started fresh and
   * the under-attributed cost surfaced as a billing discrepancy.
   *
   * <p>Identified by row id (the prefixed form, e.g. "abc12345_T2"), not the bare taskId, to
   * match how SequentialExecutor + RunTokenAttributor refer to the row.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE AgentTask t SET t.inputTokens = :inp, t.outputTokens = :out WHERE t.id = :rowId")
  int writeTokens(
      @Param("rowId") String rowId, @Param("inp") int inp, @Param("out") int out);
}
