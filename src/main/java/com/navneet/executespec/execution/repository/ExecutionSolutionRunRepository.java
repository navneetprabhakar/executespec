package com.navneet.executespec.execution.repository;

import com.navneet.executespec.execution.entity.ExecutionSolutionRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExecutionSolutionRunRepository extends JpaRepository<ExecutionSolutionRun, String> {
    List<ExecutionSolutionRun> findBySolutionIdOrderByAttemptNumberAsc(String solutionId);
    int countBySolutionId(String solutionId);
}
