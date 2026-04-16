package com.navneet.executespec.execution.repository;

import com.navneet.executespec.execution.entity.ExecutionSolutionRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExecutionSolutionRepoRepository extends JpaRepository<ExecutionSolutionRepo, String> {
    List<ExecutionSolutionRepo> findBySolution_Id(String solutionId);
}
