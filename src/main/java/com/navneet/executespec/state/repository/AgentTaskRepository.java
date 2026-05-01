package com.navneet.executespec.state.repository;

import java.time.Instant;
import java.util.List;

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
}
