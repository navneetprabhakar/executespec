package com.navneet.executespec.project.pr;

import java.util.Optional;

import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.data.repository.Repository;

/** Focused durable-run lookup boundary for CI-failure webhook idempotency. */
interface CiFailureRunRepository extends Repository<PipelineRun, String> {

  Optional<PipelineRun> findById(String runId);

  Optional<PipelineRun> findFirstByTriggeredByFindingOrderByStartedAtDesc(String findingKey);
}
