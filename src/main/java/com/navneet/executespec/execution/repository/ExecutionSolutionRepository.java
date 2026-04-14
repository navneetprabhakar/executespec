package com.navneet.executespec.execution.repository;

import com.navneet.executespec.execution.entity.ExecutionSolution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExecutionSolutionRepository extends JpaRepository<ExecutionSolution, String> {
    List<ExecutionSolution> findByExecutionRun_IdOrderBySequenceNumberAsc(String executionRunId);
}
