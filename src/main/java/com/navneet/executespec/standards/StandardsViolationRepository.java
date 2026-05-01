package com.navneet.executespec.standards;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StandardsViolationRepository extends JpaRepository<StandardsViolation, UUID> {

  List<StandardsViolation> findByRunId(UUID runId);

  /**
   * Recent violations for a project — drives the standards compliance Knowledge sub-tab in E3.16.
   */
  @Query(
      "SELECT v FROM StandardsViolation v "
          + "WHERE v.projectId = :projectId AND v.detectedAt >= :since "
          + "ORDER BY v.detectedAt DESC")
  List<StandardsViolation> findRecentForProject(
      @Param("projectId") String projectId, @Param("since") Instant since);

  /**
   * Override-pattern detection (E3.11): same standard overridden N times in the past M days
   * surfaces a "consider permanent exception" recommendation.
   */
  @Query(
      "SELECT v FROM StandardsViolation v "
          + "WHERE v.projectId = :projectId AND v.standardId = :standardId "
          + "AND v.overrideApplied = true AND v.overrideAt >= :since")
  List<StandardsViolation> findRecentOverridesForStandard(
      @Param("projectId") String projectId,
      @Param("standardId") String standardId,
      @Param("since") Instant since);
}
