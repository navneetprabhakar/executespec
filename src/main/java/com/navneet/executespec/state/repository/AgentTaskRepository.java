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
   * N2 (audit 2026-05-29) — model-comparison feed filtered in the DB, replacing {@code
   * StateStore.computeModelComparison}'s {@code findAll().stream().filter(...)} which loaded the
   * whole agent_task table into heap. Split into all / since-windowed variants (the caller branches
   * on a null window) so no {@code :param IS NULL} predicate is needed. {@code inputTokens IS NOT
   * NULL} preserves the prior filter; the provider/model grouping + null-defaulting stays in Java.
   * {@code startedAt > :since} matches the prior strict {@code isAfter} (also drops null startedAt,
   * exactly as the prior {@code startedAt != null && isAfter}).
   */
  @Query("SELECT t FROM AgentTask t WHERE t.inputTokens IS NOT NULL")
  List<AgentTask> findWithTokens();

  @Query("SELECT t FROM AgentTask t WHERE t.inputTokens IS NOT NULL AND t.startedAt > :since")
  List<AgentTask> findWithTokensSince(@Param("since") Instant since);

  /** N2 — org-scoped variants: restrict to tasks whose run is in the given org's run-id set. */
  @Query("SELECT t FROM AgentTask t WHERE t.inputTokens IS NOT NULL AND t.runId IN :runIds")
  List<AgentTask> findWithTokensByRunIds(@Param("runIds") List<String> runIds);

  @Query(
      "SELECT t FROM AgentTask t WHERE t.inputTokens IS NOT NULL "
          + "AND t.runId IN :runIds AND t.startedAt > :since")
  List<AgentTask> findWithTokensByRunIdsSince(
      @Param("runIds") List<String> runIds, @Param("since") Instant since);
}
