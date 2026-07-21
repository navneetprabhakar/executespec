package com.navneet.executespec.state.repository;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.state.entity.IntegrationRun;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationRunRepository extends JpaRepository<IntegrationRun, Long> {

  Optional<IntegrationRun> findByRunId(String runId);

  List<IntegrationRun> findAllByOrderByCreatedAtDesc();

  List<IntegrationRun> findBySourceTypeOrderByCreatedAtDesc(String sourceType);

  /**
   * Org-scoped listing (V320, RR-339). Rows with a NULL {@code org_id} are legacy rows with no
   * recorded owner and are deliberately excluded — an unknown owner must not match any org.
   */
  List<IntegrationRun> findByOrgIdOrderByCreatedAtDesc(String orgId);

  List<IntegrationRun> findByOrgIdAndSourceTypeOrderByCreatedAtDesc(
      String orgId, String sourceType);
}
