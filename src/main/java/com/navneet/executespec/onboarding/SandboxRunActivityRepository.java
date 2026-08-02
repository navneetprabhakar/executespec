package com.navneet.executespec.onboarding;

import java.time.Instant;

import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Narrow run-activity read model used only by disposable onboarding-sandbox cleanup. */
interface SandboxRunActivityRepository extends Repository<PipelineRun, String> {

  long countByProjectIdAndStartedAtGreaterThanEqual(String projectId, Instant since);

  /** Unknown/null statuses fail closed so cleanup never archives a possibly active workspace. */
  @Query(
      "SELECT COUNT(r) FROM PipelineRun r WHERE r.projectId = :projectId AND (r.status IS NULL OR"
          + " r.status NOT IN ('COMPLETE', 'FAILED', 'CANCELLED', 'PARTIAL'))")
  long countNonTerminalByProjectId(@Param("projectId") String projectId);
}
