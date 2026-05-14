package com.navneet.executespec.execution.repository;

import java.util.List;

import com.navneet.executespec.execution.entity.ExecutionSolutionRun;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionSolutionRunRepository
    extends JpaRepository<ExecutionSolutionRun, String> {
  List<ExecutionSolutionRun> findBySolutionIdOrderByAttemptNumberAsc(String solutionId);

  int countBySolutionId(String solutionId);

  /**
   * 2026-05-13 PR-A — every {@code pipeline_run_id} ever spawned for any solution of an execution.
   * Used by {@link com.navneet.executespec.execution.service.ExecutionCostAggregator} to sum cost +
   * tokens across all attempts (including PARTIAL + FAILED), repairing the legacy accumulator that
   * dropped contributions on CAS conflict + ignored non-COMPLETE outcomes entirely.
   */
  @org.springframework.data.jpa.repository.Query(
      "SELECT esr.pipelineRunId FROM ExecutionSolutionRun esr WHERE esr.solutionId IN (SELECT s.id"
          + " FROM ExecutionSolution s WHERE s.executionRun.id = :execRunId) AND esr.pipelineRunId"
          + " IS NOT NULL")
  List<String> findPipelineRunIdsByExecutionRunId(
      @org.springframework.data.repository.query.Param("execRunId") String execRunId);
}
