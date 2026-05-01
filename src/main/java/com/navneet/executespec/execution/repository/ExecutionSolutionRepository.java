package com.navneet.executespec.execution.repository;

import java.util.List;

import com.navneet.executespec.execution.entity.ExecutionSolution;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionSolutionRepository extends JpaRepository<ExecutionSolution, String> {

  /**
   * Default order-by lookup. Lazy collections (dependencies, repos) are NOT fetched — callers that
   * walk those must use {@link #findByExecutionRunIdWithDependencies} or run inside an open
   * transaction.
   */
  List<ExecutionSolution> findByExecutionRun_IdOrderBySequenceNumberAsc(String executionRunId);

  /**
   * Eager-fetches the {@code dependencies} set so callers outside a transaction (e.g. {@link
   * com.navneet.executespec.execution.service.SolutionDependencyResolver}) can iterate without a
   * {@code LazyInitializationException}.
   */
  @EntityGraph(attributePaths = {"dependencies"})
  List<ExecutionSolution> findWithDependenciesByExecutionRun_IdOrderBySequenceNumberAsc(
      String executionRunId);

  /**
   * v2.5.12 — eager-fetches the {@code repoMappings} list. Needed by {@link
   * com.navneet.executespec.execution.service.ExecutionOrchestrator#executeApprovedPlan} which runs
   * on an {@code @Async} thread with no open Hibernate session and used to throw {@code
   * LazyInitializationException} when walking {@code solution.getRepoMappings()} after all
   * solutions finished. The exception killed the final "setStatus(COMPLETE)" step, leaving the
   * ExecutionRun stuck at EXECUTING forever (observed on launch 9d5fb7d4).
   *
   * <p>Kept as a separate query from {@link
   * #findWithDependenciesByExecutionRun_IdOrderBySequenceNumberAsc} to avoid
   * MultipleBagFetchException on the mixed Set (dependencies) + List (repoMappings) collections.
   */
  @EntityGraph(attributePaths = {"repoMappings"})
  List<ExecutionSolution> findWithRepoMappingsByExecutionRun_IdOrderBySequenceNumberAsc(
      String executionRunId);
}
