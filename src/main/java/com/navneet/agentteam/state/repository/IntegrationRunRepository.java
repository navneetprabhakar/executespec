package com.navneet.agentteam.state.repository;

import com.navneet.agentteam.state.entity.IntegrationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IntegrationRunRepository extends JpaRepository<IntegrationRun, Long> {

    Optional<IntegrationRun> findByRunId(String runId);

    List<IntegrationRun> findAllByOrderByCreatedAtDesc();

    List<IntegrationRun> findBySourceTypeOrderByCreatedAtDesc(String sourceType);
}
