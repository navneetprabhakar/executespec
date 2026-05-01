package com.navneet.executespec.execution.repository;

import java.util.List;

import com.navneet.executespec.execution.entity.ExecutionContextHandover;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionContextHandoverRepository
    extends JpaRepository<ExecutionContextHandover, String> {
  List<ExecutionContextHandover> findByToSolutionId(String toSolutionId);
}
