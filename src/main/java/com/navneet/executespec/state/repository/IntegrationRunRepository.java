package com.navneet.executespec.state.repository;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.state.entity.IntegrationRun;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationRunRepository extends JpaRepository<IntegrationRun, Long> {

  Optional<IntegrationRun> findByRunId(String runId);

  List<IntegrationRun> findAllByOrderByCreatedAtDesc();

  List<IntegrationRun> findBySourceTypeOrderByCreatedAtDesc(String sourceType);
}
