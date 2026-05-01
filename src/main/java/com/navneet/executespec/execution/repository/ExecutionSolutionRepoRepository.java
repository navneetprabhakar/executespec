package com.navneet.executespec.execution.repository;

import java.util.List;

import com.navneet.executespec.execution.entity.ExecutionSolutionRepo;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionSolutionRepoRepository
    extends JpaRepository<ExecutionSolutionRepo, String> {
  List<ExecutionSolutionRepo> findBySolution_Id(String solutionId);
}
