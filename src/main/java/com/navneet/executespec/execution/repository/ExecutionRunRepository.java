package com.navneet.executespec.execution.repository;

import com.navneet.executespec.execution.entity.ExecutionRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionRunRepository extends JpaRepository<ExecutionRun, String> {
    Page<ExecutionRun> findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(String projectId, Pageable pageable);
}
