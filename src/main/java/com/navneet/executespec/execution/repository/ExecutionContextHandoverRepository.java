package com.navneet.executespec.execution.repository;

import com.navneet.executespec.execution.entity.ExecutionContextHandover;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExecutionContextHandoverRepository extends JpaRepository<ExecutionContextHandover, String> {
    List<ExecutionContextHandover> findByToSolutionId(String toSolutionId);
}
