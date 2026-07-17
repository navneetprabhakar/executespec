package com.navneet.executespec.cost;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RunStepUsageRepository extends JpaRepository<RunStepUsage, String> {
  List<RunStepUsage> findByRunIdOrderByCreatedAtAsc(String runId);

  long countByLlmExecutionContextId(String llmExecutionContextId);

  @Query(
      "select coalesce(sum(u.inputTokens), 0) from RunStepUsage u "
          + "where u.llmExecutionContextId = :contextId and u.attemptStatus = 'SUCCESS'")
  Long sumSuccessfulInputTokensByContextId(@Param("contextId") String contextId);

  @Query(
      "select coalesce(sum(u.outputTokens), 0) from RunStepUsage u "
          + "where u.llmExecutionContextId = :contextId and u.attemptStatus = 'SUCCESS'")
  Long sumSuccessfulOutputTokensByContextId(@Param("contextId") String contextId);

  @Query(
      "select coalesce(sum(u.costUsd), 0.0) from RunStepUsage u "
          + "where u.llmExecutionContextId = :contextId and u.attemptStatus = 'SUCCESS'")
  Double sumSuccessfulCostByContextId(@Param("contextId") String contextId);
}
