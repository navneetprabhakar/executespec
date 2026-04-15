package com.navneet.executespec.execution.repository;

import com.navneet.executespec.execution.entity.ExecutionSolution;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExecutionSolutionRepository extends JpaRepository<ExecutionSolution, String> {

    /**
     * Default order-by lookup. Lazy collections (dependencies, repos) are NOT
     * fetched — callers that walk those must use {@link #findByExecutionRunIdWithDependencies}
     * or run inside an open transaction.
     */
    List<ExecutionSolution> findByExecutionRun_IdOrderBySequenceNumberAsc(String executionRunId);

    /**
     * Eager-fetches the {@code dependencies} set so callers outside a transaction
     * (e.g. {@link com.navneet.executespec.execution.service.SolutionDependencyResolver})
     * can iterate without a {@code LazyInitializationException}.
     */
    @EntityGraph(attributePaths = {"dependencies"})
    List<ExecutionSolution> findWithDependenciesByExecutionRun_IdOrderBySequenceNumberAsc(String executionRunId);
}
